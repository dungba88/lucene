/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util.fst;

import java.io.IOException;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.IntsRef;

/** Abstract class which provides low-level functionality to write to a FST */
public abstract class FSTWriter extends DataOutput implements Accountable {

  /**
   * Truncate the FST data to a new length
   *
   * @param newLen the new length of the FST data
   * @throws IOException
   */
  public abstract void truncate(long newLen) throws IOException;

  /**
   * Reverse the data from srcPos to destPos, inclusively
   *
   * @param srcPos the src position
   * @param destPos the dest position
   * @throws IOException
   */
  public abstract void reverse(long srcPos, long destPos) throws IOException;

  /**
   * Skip a number of bytes from the current position
   *
   * @param len the bytes to skip
   * @throws IOException
   */
  public abstract void skipBytes(int len) throws IOException;

  /**
   * Copy <code>len</code> bytes from <code>src</code> to <code>dest</code>. This can only be called
   * on parts already written.
   *
   * @param src the position to copy from
   * @param dest the position to copy to
   * @param len the number of bytes to copy
   * @throws IOException
   */
  public abstract void copyBytes(long src, long dest, int len) throws IOException;

  /**
   * Copy <code>len</code> bytes from <code>src</code> position to <code>dest</code> buffer.
   *
   * @param src the position to copy from
   * @param dest the buffer to copy to
   * @param offset the offset of the buffer to copy to
   * @param len the number of bytes to copy
   * @throws IOException
   */
  public abstract void copyBytes(long src, byte[] dest, int offset, int len) throws IOException;

  /**
   * Write <code>len</code> bytes to <code>dest</code> position
   *
   * @param dest the position to write to
   * @param b the data to write
   * @param offset the offset of the byte array to write
   * @param len the number of bytes to write
   * @throws IOException
   */
  public abstract void writeBytes(long dest, byte[] b, int offset, int len) throws IOException;

  /**
   * Write a single byte at the specified position
   *
   * @param dest the position to write to
   * @param b the byte to write
   * @throws IOException
   */
  public abstract void writeByte(long dest, byte b) throws IOException;

  /**
   * Get the current position (or pointer) of the writer
   *
   * @return the current position of the writer
   */
  public abstract long getPosition();

  /**
   * Called when the FST construction is finished and no more node can be added to it. Freezing,
   * optimizing the datastructure can be done here
   *
   * @throws IOException
   */
  public void finish() throws IOException {
    // do nothing by default
  }

  /**
   * Called before adding an input
   *
   * @param input the input to be added
   * @throws IOException
   */
  public void beforeAdded(IntsRef input) throws IOException {
    // do nothing by default
  }

  /**
   * Called after an input is added to the FST Flushing, cleaning up, etc. can be done here
   *
   * @param input the added input
   * @throws IOException
   */
  public void afterAdded(IntsRef input) throws IOException {
    // do nothing by default
  }

  /**
   * Get the BytesReader of the FST
   *
   * @return the BytesReader
   */
  public abstract FST.BytesReader getReverseReader();

  /**
   * Get the BytesReader of the FST used for suffix sharing
   *
   * @return the BytesReader
   */
  public abstract FST.BytesReader getReverseReaderForSuffixSharing();

  /**
   * Write this FST data to a DataOutput. Must be called after {@link #finish()}
   *
   * @param out the DataOutput
   * @throws IOException
   */
  public abstract void writeTo(DataOutput out) throws IOException;
}
