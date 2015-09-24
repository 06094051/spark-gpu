/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import scala.reflect.ClassTag
import scala.language.existentials

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.{ObjectInputStream, ObjectOutputStream}

import org.apache.spark.annotation.{DeveloperApi, Experimental}
import org.apache.spark.unsafe.memory.MemoryBlock
import org.apache.spark.util.IteratorFunctions._
import org.apache.spark.util.Utils

import jcuda.Pointer

import org.slf4j.Logger
import org.slf4j.LoggerFactory

case object ColumnFormat extends PartitionFormat

// scalastyle:off no.finalize
@DeveloperApi
@Experimental
class ColumnPartitionData[T](
    private var _schema: ColumnPartitionSchema,
    private var _size: Long
  ) extends PartitionData[T] with Serializable {

  def schema: ColumnPartitionSchema = _schema

  def size: Long = _size

  private[spark] var pointers: Array[Pointer] = null

  private var freed = false

  /**
   * Columns kept as ByteBuffers. May be read directly. The inherent limitation of 2GB - 1B for
   * the partition size is present in other places too (e.g. BlockManager's serialized data).
   */
  lazy val buffers: Array[ByteBuffer] =
    (pointers zip schema.columns).map { case (ptr, col) =>
      val columnSize = col.columnType.bytes * size
      assert(columnSize <= Int.MaxValue)
      ptr.getByteBuffer(0, columnSize).order(ByteOrder.LITTLE_ENDIAN)
    }

  // Extracted to a function for use in deserialization
  private def initialize {
    pointers = schema.columns.map { col =>
      SparkEnv.get.executorMemoryManager.allocatePinnedMemory(col.columnType.bytes * size)
    }

    freed = false
  }
  initialize

  /**
   * Total amount of memory allocated in columns. Does not take into account Java objects aggregated
   * in this PartitionData.
   */
  def memoryUsage: Long = schema.memoryUsage(size)

  /**
   * Deallocate internal memory. The buffers may not be used after this call.
   */
  def free() {
    assert(!freed)
    pointers.foreach(SparkEnv.get.executorMemoryManager.freePinnedMemory(_))
    freed = true
  }

  /**
   * Finalizer method to free the memory if it was not freed yet for some reason. Prints a warning
   * in such cases.
   */
  override def finalize() {
    if (!freed) {
      free()
      if (ColumnPartitionData.logger.isWarnEnabled()) {
        ColumnPartitionData.logger.warn("{}B of memory still not freed in finalizer.", memoryUsage);
      }
    }
  }

  /**
   * Rewinds all column buffers, so that they may be read from the beginning.
   */
  def rewind {
    buffers.foreach(_.rewind)
  }

  /**
   * Serializes an iterator of objects into columns. Amount of objects written must not exceed the
   * size of this ColumnPartitionData. Note that it does not handle any null pointers inside
   * objects. Memory footprint is that of one object at a time.
   */
  // TODO allow for dropping specific columns if some kind of optimizer detected that they are not
  // needed
  def serialize(iter: Iterator[T]) {
    val getters = schema.getters
    rewind

    iter.take(size).foreach { obj =>
      for (((col, getter), buf) <- ((schema.columns zip getters) zip buffers)) {
        // TODO what should we do if sub-object is null?
        // TODO bulk put/get might be faster

        col.columnType match {
          case BYTE_COLUMN => buf.put(getter(obj).asInstanceOf[Byte])
          case SHORT_COLUMN => buf.putShort(getter(obj).asInstanceOf[Short])
          case INT_COLUMN => buf.putInt(getter(obj).asInstanceOf[Int])
          case LONG_COLUMN => buf.putLong(getter(obj).asInstanceOf[Long])
          case FLOAT_COLUMN => buf.putFloat(getter(obj).asInstanceOf[Float])
          case DOUBLE_COLUMN => buf.putDouble(getter(obj).asInstanceOf[Double])
        }
      }
    }
  }

  /**
   * Deserializes columns into Java objects. Memory footprint is that of one object at a time.
   */
  def deserialize(): Iterator[T] = {
    rewind

    if (schema.isPrimitive) {
      Iterator.continually {
        deserializeColumnValue(schema.columns(0).columnType, buffers(0)).asInstanceOf[T]
      } take size
    } else {
      // version of setters that creates objects that do not exist yet
      val setters: Array[(AnyRef, Any) => Unit] = {
        val mirror = ColumnPartitionSchema.mirror
        schema.columns.map { col =>
          val get: AnyRef => AnyRef = col.terms.dropRight(1).foldLeft(identity[AnyRef] _)
            { (r, term) => { (obj: AnyRef) =>
              val rf = mirror.reflect(obj).reflectField(term)
              rf.get match {
                case inner if inner != null => inner.asInstanceOf[AnyRef]
                case _ =>
                  val propCls = mirror.runtimeClass(term.typeSignature.typeSymbol.asClass)
                  // we assume we don't instantiate inner class instances, so $outer field is not
                  // needed
                  val propVal = instantiateClass(propCls, null)
                  rf.set(propVal)
                  propVal
              } } compose r
            }

          (obj: Any, value: Any) => mirror.reflect(get(obj.asInstanceOf[AnyRef]))
            .reflectField(col.terms.last).set(value)
        }
      }

      Iterator.continually {
        val obj = instantiateClass(schema.cls, null)

        for (((col, setter), buf) <- ((schema.columns zip setters) zip buffers)) {
          setter(obj, deserializeColumnValue(col.columnType, buf))
        }

        obj.asInstanceOf[T]
      } take size
    }
  }

  /**
   * Reads the buffer in a way specified by its ColumnType.
   */
  def deserializeColumnValue(columnType: ColumnType, buf: ByteBuffer): Any = {
    columnType match {
      case BYTE_COLUMN => buf.get()
      case SHORT_COLUMN => buf.getShort()
      case INT_COLUMN => buf.getInt()
      case LONG_COLUMN => buf.getLong()
      case FLOAT_COLUMN => buf.getFloat()
      case DOUBLE_COLUMN => buf.getDouble()
    }
  }

  /**
   * Instantiates a class. Also handles inner classes by passing enclosingObject parameter.
   */
  private[spark] def instantiateClass(
      cls: Class[_],
      enclosingObject: AnyRef): AnyRef = {
    // Use reflection to instantiate object without calling constructor
    val rf = sun.reflect.ReflectionFactory.getReflectionFactory()
    val parentCtor = classOf[java.lang.Object].getDeclaredConstructor()
    val newCtor = rf.newConstructorForSerialization(cls, parentCtor)
    val obj = newCtor.newInstance().asInstanceOf[AnyRef]
    if (enclosingObject != null) {
      val field = cls.getDeclaredField("$outer")
      field.setAccessible(true)
      field.set(obj, enclosingObject)
    }
    obj
  }

  /**
   * Iterator for objects inside this PartitionData. Causes deserialization of the data and may be
   * costly.
   */
  override def iterator: Iterator[T] = deserialize

  override def convert(format: PartitionFormat)(implicit ct: ClassTag[T]): PartitionData[T] = {
    format match {
      // Converting from column-based format to iterator-based format.
      case IteratorFormat => IteratedPartitionData(deserialize)

      // We already have column format.
      case ColumnFormat => this
    }
  }

  /**
   * Special serialization, since we use off-heap memory.
   */
  private def writeObject(out: ObjectOutputStream): Unit = Utils.tryOrIOException {
    out.writeObject(_schema)
    out.writeLong(_size)
    rewind
    val bytes = new Array[Byte](buffers.map(_.capacity).max)
    for (buf <- buffers) {
      val sizeToWrite = buf.capacity
      buf.get(bytes, 0, sizeToWrite)
      out.write(bytes, 0, sizeToWrite)
    }
  }

  /**
   * Special deserialization, since we use off-heap memory.
   */
  private def readObject(in: ObjectInputStream): Unit = Utils.tryOrIOException {
    _schema = in.readObject().asInstanceOf[ColumnPartitionSchema]
    _size = in.readLong()
    initialize
    val bytes = new Array[Byte](buffers.map(_.capacity).max)
    for (buf <- buffers) {
      val sizeToRead = buf.capacity
      var position = 0
      while (position < sizeToRead) {
        val readBytes = in.read(bytes, position, sizeToRead - position)
        assert(readBytes >= 0)
        position += readBytes
      }
      buf.put(bytes, 0, sizeToRead)
    }
  }

}
// scalastyle:on no.finalize

object ColumnPartitionData {

  private final val logger: Logger = LoggerFactory.getLogger(classOf[ColumnPartitionData[_]])

}
