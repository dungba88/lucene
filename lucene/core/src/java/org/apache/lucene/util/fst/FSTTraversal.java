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

import static org.apache.lucene.util.fst.FST.ARCS_FOR_BINARY_SEARCH;
import static org.apache.lucene.util.fst.FST.ARCS_FOR_DIRECT_ADDRESSING;
import static org.apache.lucene.util.fst.FST.BIT_ARC_HAS_FINAL_OUTPUT;
import static org.apache.lucene.util.fst.FST.BIT_ARC_HAS_OUTPUT;
import static org.apache.lucene.util.fst.FST.BIT_FINAL_ARC;
import static org.apache.lucene.util.fst.FST.BIT_LAST_ARC;
import static org.apache.lucene.util.fst.FST.BIT_STOP_NODE;
import static org.apache.lucene.util.fst.FST.BIT_TARGET_NEXT;
import static org.apache.lucene.util.fst.FST.FINAL_END_NODE;
import static org.apache.lucene.util.fst.FST.NON_FINAL_END_NODE;
import static org.apache.lucene.util.fst.FST.VERSION_LITTLE_ENDIAN;

import java.io.IOException;
import org.apache.lucene.store.DataInput;

/**
 * Utility class for traversing FST
 *
 * @param <T> the FST output type
 */
class FSTTraversal<T> {

  private final int version;
  private final FST.INPUT_TYPE inputType;
  private final Outputs<T> outputs;

  FSTTraversal(int version, FST.INPUT_TYPE inputType, Outputs<T> outputs) {
    this.version = version;
    this.inputType = inputType;
    this.outputs = outputs;
  }

  public FST.Arc<T> readFirstRealTargetArc(
      long nodeAddress, FST.Arc<T> arc, final FST.BytesReader in) throws IOException {
    in.setPosition(nodeAddress);
    // System.out.println("   flags=" + arc.flags);

    byte flags = arc.nodeFlags = in.readByte();
    if (flags == ARCS_FOR_BINARY_SEARCH || flags == ARCS_FOR_DIRECT_ADDRESSING) {
      // System.out.println("  fixed length arc");
      // Special arc which is actually a node header for fixed length arcs.
      arc.numArcs = in.readVInt();
      arc.bytesPerArc = in.readVInt();
      arc.arcIdx = -1;
      if (flags == ARCS_FOR_DIRECT_ADDRESSING) {
        readPresenceBytes(arc, in);
        arc.firstLabel = readLabel(in);
        arc.presenceIndex = -1;
      }
      arc.posArcsStart = in.getPosition();
      // System.out.println("  bytesPer=" + arc.bytesPerArc + " numArcs=" + arc.numArcs + "
      // arcsStart=" + pos);
    } else {
      arc.nextArc = nodeAddress;
      arc.bytesPerArc = 0;
    }

    return readNextRealArc(arc, in);
  }

  /**
   * Reads the presence bits of a direct-addressing node. Actually we don't read them here, we just
   * keep the pointer to the bit-table start and we skip them.
   */
  void readPresenceBytes(FST.Arc<T> arc, FST.BytesReader in) throws IOException {
    assert arc.bytesPerArc() > 0;
    assert arc.nodeFlags() == ARCS_FOR_DIRECT_ADDRESSING;
    arc.bitTableStart = in.getPosition();
    in.skipBytes(getNumPresenceBytes(arc.numArcs()));
  }

  /** Reads one BYTE1/2/4 label from the provided {@link DataInput}. */
  public int readLabel(DataInput in) throws IOException {
    final int v;
    if (inputType == FST.INPUT_TYPE.BYTE1) {
      // Unsigned byte:
      v = in.readByte() & 0xFF;
    } else if (inputType == FST.INPUT_TYPE.BYTE2) {
      // Unsigned short:
      if (version < VERSION_LITTLE_ENDIAN) {
        v = Short.reverseBytes(in.readShort()) & 0xFFFF;
      } else {
        v = in.readShort() & 0xFFFF;
      }
    } else {
      v = in.readVInt();
    }
    return v;
  }

  /** Never returns null, but you should never call this if arc.isLast() is true. */
  public FST.Arc<T> readNextRealArc(FST.Arc<T> arc, final FST.BytesReader in) throws IOException {

    // TODO: can't assert this because we call from readFirstArc
    // assert !flag(arc.flags, BIT_LAST_ARC);

    switch (arc.nodeFlags()) {
      case ARCS_FOR_BINARY_SEARCH:
        assert arc.bytesPerArc() > 0;
        arc.arcIdx++;
        assert arc.arcIdx() >= 0 && arc.arcIdx() < arc.numArcs();
        in.setPosition(arc.posArcsStart() - arc.arcIdx() * (long) arc.bytesPerArc());
        arc.flags = in.readByte();
        break;

      case ARCS_FOR_DIRECT_ADDRESSING:
        assert FST.Arc.BitTable.assertIsValid(arc, in);
        assert arc.arcIdx() == -1 || FST.Arc.BitTable.isBitSet(arc.arcIdx(), arc, in);
        int nextIndex = FST.Arc.BitTable.nextBitSet(arc.arcIdx(), arc, in);
        return readArcByDirectAddressing(arc, in, nextIndex, arc.presenceIndex + 1);

      default:
        // Variable length arcs - linear search.
        assert arc.bytesPerArc() == 0;
        in.setPosition(arc.nextArc());
        arc.flags = in.readByte();
    }
    return readArc(arc, in);
  }

  /**
   * Reads a present direct addressing node arc, with the provided index in the label range and its
   * corresponding presence index (which is the count of presence bits before it).
   */
  FST.Arc<T> readArcByDirectAddressing(
      FST.Arc<T> arc, final FST.BytesReader in, int rangeIndex, int presenceIndex)
      throws IOException {
    in.setPosition(arc.posArcsStart() - presenceIndex * (long) arc.bytesPerArc());
    arc.arcIdx = rangeIndex;
    arc.presenceIndex = presenceIndex;
    arc.flags = in.readByte();
    return readArc(arc, in);
  }

  /**
   * Reads an arc. <br>
   * Precondition: The arc flags byte has already been read and set; the given BytesReader is
   * positioned just after the arc flags byte.
   */
  FST.Arc<T> readArc(FST.Arc<T> arc, FST.BytesReader in) throws IOException {
    if (arc.nodeFlags() == ARCS_FOR_DIRECT_ADDRESSING) {
      arc.label = arc.firstLabel() + arc.arcIdx();
    } else {
      arc.label = readLabel(in);
    }

    if (arc.flag(BIT_ARC_HAS_OUTPUT)) {
      arc.output = outputs.read(in);
    } else {
      arc.output = outputs.getNoOutput();
    }

    if (arc.flag(BIT_ARC_HAS_FINAL_OUTPUT)) {
      arc.nextFinalOutput = outputs.readFinalOutput(in);
    } else {
      arc.nextFinalOutput = outputs.getNoOutput();
    }

    if (arc.flag(BIT_STOP_NODE)) {
      if (arc.flag(BIT_FINAL_ARC)) {
        arc.target = FINAL_END_NODE;
      } else {
        arc.target = NON_FINAL_END_NODE;
      }
      arc.nextArc = in.getPosition(); // Only useful for list.
    } else if (arc.flag(BIT_TARGET_NEXT)) {
      arc.nextArc = in.getPosition(); // Only useful for list.
      // TODO: would be nice to make this lazy -- maybe
      // caller doesn't need the target and is scanning arcs...
      if (!arc.flag(BIT_LAST_ARC)) {
        if (arc.bytesPerArc() == 0) {
          // must scan
          seekToNextNode(in);
        } else {
          int numArcs =
              arc.nodeFlags == ARCS_FOR_DIRECT_ADDRESSING
                  ? FST.Arc.BitTable.countBits(arc, in)
                  : arc.numArcs();
          in.setPosition(arc.posArcsStart() - arc.bytesPerArc() * (long) numArcs);
        }
      }
      arc.target = in.getPosition();
    } else {
      arc.target = readUnpackedNodeTarget(in);
      arc.nextArc = in.getPosition(); // Only useful for list.
    }
    return arc;
  }

  private void seekToNextNode(FST.BytesReader in) throws IOException {

    while (true) {

      final int flags = in.readByte();
      readLabel(in);

      if (flag(flags, BIT_ARC_HAS_OUTPUT)) {
        outputs.skipOutput(in);
      }

      if (flag(flags, BIT_ARC_HAS_FINAL_OUTPUT)) {
        outputs.skipFinalOutput(in);
      }

      if (!flag(flags, BIT_STOP_NODE) && !flag(flags, BIT_TARGET_NEXT)) {
        readUnpackedNodeTarget(in);
      }

      if (flag(flags, BIT_LAST_ARC)) {
        return;
      }
    }
  }

  long readUnpackedNodeTarget(FST.BytesReader in) throws IOException {
    return in.readVLong();
  }

  static boolean flag(int flags, int bit) {
    return (flags & bit) != 0;
  }

  /**
   * Gets the number of bytes required to flag the presence of each arc in the given label range,
   * one bit per arc.
   */
  static int getNumPresenceBytes(int labelRange) {
    assert labelRange >= 0;
    return (labelRange + 7) >> 3;
  }
}
