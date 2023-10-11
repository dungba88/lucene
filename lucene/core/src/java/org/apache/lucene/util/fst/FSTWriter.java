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

/** Abstract class which provides low-level functionality to write to a FST */
public interface FSTWriter extends Accountable {

  /**
   * Write a single byte to the end of this FSTWriter
   *
   * @param b the byte to write
   */
  void writeByte(byte b) throws IOException;

  /**
   * Write a byte array to the end of this FSTWriter
   *
   * @param b the byte array to write
   * @param offset the offset of the array
   * @param length the number of bytes to write
   */
  void writeBytes(byte[] b, int offset, int length) throws IOException;

  /**
   * Get the current position of this FSTWriter
   *
   * @return the current position of the writer
   */
  long getPosition();

  /**
   * Called when the FST construction is finished and no more node can be added to it. Freezing,
   * optimizing the datastructure can be done here
   *
   * @throws IOException if exception occurred during the operation
   */
  default void finish() throws IOException {
    // do nothing by default
  }

  /**
   * Get the BytesReader of the FST
   *
   * @return the BytesReader
   */
  FST.BytesReader getReverseReader();

  /**
   * Get the BytesReader of the FST used for suffix sharing
   *
   * @return the BytesReader
   */
  FST.BytesReader getReverseReaderForSuffixSharing();

  /**
   * Write this FST data to a DataOutput. Must be called after {@link #finish()}
   *
   * @param out the DataOutput
   * @throws IOException if exception occurred during the operation
   */
  void writeTo(DataOutput out) throws IOException;
}
