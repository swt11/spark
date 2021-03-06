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

package org.apache.spark.util.io

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

import com.google.common.primitives.UnsignedBytes
import io.netty.buffer.{ByteBuf, Unpooled}

import org.apache.spark.network.util.ByteArrayWritableChannel
import org.apache.spark.storage.StorageUtils

/**
 * Read-only byte buffer which is physically stored as multiple chunks rather than a single
 * contiguous array.
 *
 * @param chunks an array of [[ByteBuffer]]s. Each buffer in this array must have position == 0.
 *               Ownership of these buffers is transferred to the ChunkedByteBuffer, so if these
 *               buffers may also be used elsewhere then the caller is responsible for copying
 *               them as needed.
 */
private[spark] class ChunkedByteBuffer(var chunks: Array[ByteBuffer]) {
  require(chunks != null, "chunks must not be null")
  require(chunks.forall(_.position() == 0), "chunks' positions must be 0")

  private[this] var disposed: Boolean = false

  /**
   * This size of this buffer, in bytes.
   */
  val size: Long = chunks.map(_.limit().asInstanceOf[Long]).sum

  def this(byteBuffer: ByteBuffer) = {
    this(Array(byteBuffer))
  }

  /**
   * Write this buffer to a channel.
   */
  def writeFully(channel: WritableByteChannel): Unit = {
    for (bytes <- getChunks()) {
      while (bytes.remaining > 0) {
        channel.write(bytes)
      }
    }
  }

  /**
   * Wrap this buffer to view it as a Netty ByteBuf.
   */
  def toNetty: ByteBuf = {
    Unpooled.wrappedBuffer(getChunks(): _*)
  }

  /**
   * Copy this buffer into a new byte array.
   *
   * @throws UnsupportedOperationException if this buffer's size exceeds the maximum array size.
   */
  def toArray: Array[Byte] = {
    if (size >= Integer.MAX_VALUE) {
      throw new UnsupportedOperationException(
        s"cannot call toArray because buffer size ($size bytes) exceeds maximum array size")
    }
    val byteChannel = new ByteArrayWritableChannel(size.toInt)
    writeFully(byteChannel)
    byteChannel.close()
    byteChannel.getData
  }

  /**
   * Copy this buffer into a new ByteBuffer.
   *
   * @throws UnsupportedOperationException if this buffer's size exceeds the max ByteBuffer size.
   */
  def toByteBuffer: ByteBuffer = {
    if (chunks.length == 1) {
      chunks.head.duplicate()
    } else {
      ByteBuffer.wrap(toArray)
    }
  }

  /**
   * Creates an input stream to read data from this ChunkedByteBuffer.
   *
   * @param dispose if true, [[dispose()]] will be called at the end of the stream
   *                in order to close any memory-mapped files which back this buffer.
   */
  def toInputStream(dispose: Boolean = false): InputStream = {
    new ChunkedByteBufferInputStream(this, dispose)
  }

  /**
   * Get duplicates of the ByteBuffers backing this ChunkedByteBuffer.
   */
  def getChunks(): Array[ByteBuffer] = {
    chunks.map(_.duplicate())
  }

  /**
   * Make a copy of this ChunkedByteBuffer, copying all of the backing data into new buffers.
   * The new buffer will share no resources with the original buffer.
   *
   * @param allocator a method for allocating byte buffers
   */
  def copy(allocator: Int => ByteBuffer): ChunkedByteBuffer = {
    val copiedChunks = getChunks().map { chunk =>
      val newChunk = allocator(chunk.limit())
      newChunk.put(chunk)
      newChunk.flip()
      newChunk
    }
    new ChunkedByteBuffer(copiedChunks)
  }

  /**
   * Attempt to clean up a ByteBuffer if it is memory-mapped. This uses an *unsafe* Sun API that
   * might cause errors if one attempts to read from the unmapped buffer, but it's better than
   * waiting for the GC to find it because that could lead to huge numbers of open files. There's
   * unfortunately no standard API to do this.
   */
  def dispose(): Unit = {
    if (!disposed) {
      chunks.foreach(StorageUtils.dispose)
      disposed = true
    }
  }
}

/**
 * Reads data from a ChunkedByteBuffer.
 *
 * @param dispose if true, `ChunkedByteBuffer.dispose()` will be called at the end of the stream
 *                in order to close any memory-mapped files which back the buffer.
 */
private[spark] class ChunkedByteBufferInputStream(
    var chunkedByteBuffer: ChunkedByteBuffer,
    dispose: Boolean)
  extends InputStream {

  private[this] var chunks = chunkedByteBuffer.getChunks().iterator
  private[this] var currentChunk: ByteBuffer = {
    if (chunks.hasNext) {
      chunks.next()
    } else {
      null
    }
  }

  override def read(): Int = {
    if (currentChunk != null && !currentChunk.hasRemaining && chunks.hasNext) {
      currentChunk = chunks.next()
    }
    if (currentChunk != null && currentChunk.hasRemaining) {
      UnsignedBytes.toInt(currentChunk.get())
    } else {
      close()
      -1
    }
  }

  override def read(dest: Array[Byte], offset: Int, length: Int): Int = {
    if (currentChunk != null && !currentChunk.hasRemaining && chunks.hasNext) {
      currentChunk = chunks.next()
    }
    if (currentChunk != null && currentChunk.hasRemaining) {
      val amountToGet = math.min(currentChunk.remaining(), length)
      currentChunk.get(dest, offset, amountToGet)
      amountToGet
    } else {
      close()
      -1
    }
  }

  override def skip(bytes: Long): Long = {
    if (currentChunk != null) {
      val amountToSkip = math.min(bytes, currentChunk.remaining).toInt
      currentChunk.position(currentChunk.position + amountToSkip)
      if (currentChunk.remaining() == 0) {
        if (chunks.hasNext) {
          currentChunk = chunks.next()
        } else {
          close()
        }
      }
      amountToSkip
    } else {
      0L
    }
  }

  override def close(): Unit = {
    if (chunkedByteBuffer != null && dispose) {
      chunkedByteBuffer.dispose()
    }
    chunkedByteBuffer = null
    chunks = null
    currentChunk = null
  }
}
