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
import org.apache.lucene.store.ByteArrayDataOutput;

/**
 * Implementation of {@link FSTWriter} which use similar behavior as {@link BytesStore}, but upon
 * calling {@link #finish()}, if the size is not too large, it will merge all blocks into a single
 * byte array.
 *
 * <p>For reading with the {@link #getReverseReader()}, it will be more efficient than {@link
 * BytesStore} as it has less overhead. However, during the call to {@link #finish()} it will
 * temporarily need ~2x more heap than {@link BytesStore}, thus it is recommended for small (less
 * than 1GB), write-once-read-many FST.
 */
public class SingleBlockBytesStore extends BytesStore {

  final int maxBits;

  byte[] bytes;

  /**
   * constructor
   *
   * @param blockBits the size of each byte block used during constructing the FST
   * @param maxBits the maximum size of the byte array to hold the final FST. if the FST is larger
   *     than this threshold, it will use the BytesStore's byte blocks instead
   */
  public SingleBlockBytesStore(int blockBits, int maxBits) {
    super(blockBits);
    if (maxBits < 1 || maxBits > 30) {
      throw new IllegalArgumentException("maxBits should be 1 .. 30; got " + maxBits);
    }

    this.maxBits = maxBits;
  }

  @Override
  public void finish() throws IOException {
    super.finish();

    long pos = super.getPosition();

    if (pos > 1 << this.maxBits) {
      // the buffer is too large, keep with the BytesStore implementation
      return;
    }

    // merge all blocks into a single byte array
    bytes = new byte[(int) pos];
    writeTo(new ByteArrayDataOutput(bytes));

    blocks.clear();
    blocks.add(bytes);
  }

  @Override
  public long getPosition() {
    if (bytes == null) { // before calling finish()
      return super.getPosition();
    }
    return bytes.length;
  }
}
