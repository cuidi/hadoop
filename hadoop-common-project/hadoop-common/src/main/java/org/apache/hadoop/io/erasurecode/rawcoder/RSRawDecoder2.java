/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.io.erasurecode.rawcoder;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.io.erasurecode.rawcoder.util.CoderUtil;
import org.apache.hadoop.io.erasurecode.rawcoder.util.DumpUtil;
import org.apache.hadoop.io.erasurecode.rawcoder.util.GF256;
import org.apache.hadoop.io.erasurecode.rawcoder.util.RSUtil;
import org.apache.hadoop.io.erasurecode.rawcoder.util.RSUtil2;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A raw erasure decoder in RS code scheme in pure Java in case native one
 * isn't available in some environment. Please always use native implementations
 * when possible. This new Java coder is about 5X faster than the one originated
 * from HDFS-RAID, and also compatible with the native/ISA-L coder.
 */
@InterfaceAudience.Private
public class RSRawDecoder2 extends AbstractRawErasureDecoder {
  //relevant to schema and won't change during decode calls
  private byte[] encodeMatrix;

  /**
   * Below are relevant to schema and erased indexes, thus may change during
   * decode calls.
   */
  private byte[] decodeMatrix;
  private byte[] invertMatrix;
  /**
   * Array of input tables generated from coding coefficients previously.
   * Must be of size 32*k*rows
   */
  private byte[] gfTables;
  private int[] cachedErasedIndexes;
  private int[] validIndexes;
  private int numErasedDataUnits;
  private boolean[] erasureFlags;

  public RSRawDecoder2(int numDataUnits, int numParityUnits) {
    super(numDataUnits, numParityUnits);
    if (numDataUnits + numParityUnits >= RSUtil.GF.getFieldSize()) {
      throw new HadoopIllegalArgumentException(
              "Invalid getNumDataUnits() and numParityUnits");
    }

    int numAllUnits = getNumDataUnits() + numParityUnits;
    encodeMatrix = new byte[numAllUnits * getNumDataUnits()];
    RSUtil2.genCauchyMatrix(encodeMatrix, numAllUnits, getNumDataUnits());
    if (isAllowingVerboseDump()) {
      DumpUtil.dumpMatrix(encodeMatrix, numDataUnits, numAllUnits);
    }
  }

  @Override
  protected void doDecode(ByteBuffer[] inputs, int[] erasedIndexes,
                          ByteBuffer[] outputs) {
    prepareDecoding(inputs, erasedIndexes);

    ByteBuffer[] realInputs = new ByteBuffer[getNumDataUnits()];
    for (int i = 0; i < getNumDataUnits(); i++) {
      realInputs[i] = inputs[validIndexes[i]];
    }
    RSUtil2.encodeData(gfTables, realInputs, outputs);
  }

  @Override
  protected void doDecode(byte[][] inputs, int[] inputOffsets,
                          int dataLen, int[] erasedIndexes,
                          byte[][] outputs, int[] outputOffsets) {
    prepareDecoding(inputs, erasedIndexes);

    byte[][] realInputs = new byte[getNumDataUnits()][];
    int[] realInputOffsets = new int[getNumDataUnits()];
    for (int i = 0; i < getNumDataUnits(); i++) {
      realInputs[i] = inputs[validIndexes[i]];
      realInputOffsets[i] = inputOffsets[validIndexes[i]];
    }
    RSUtil2.encodeData(gfTables, dataLen, realInputs, realInputOffsets,
            outputs, outputOffsets);
  }

  private <T> void prepareDecoding(T[] inputs, int[] erasedIndexes) {
    int[] tmpValidIndexes = new int[getNumDataUnits()];
    CoderUtil.makeValidIndexes(inputs, tmpValidIndexes);
    if (Arrays.equals(this.cachedErasedIndexes, erasedIndexes) &&
        Arrays.equals(this.validIndexes, tmpValidIndexes)) {
      return; // Optimization. Nothing to do
    }
    this.cachedErasedIndexes =
            Arrays.copyOf(erasedIndexes, erasedIndexes.length);
    this.validIndexes =
            Arrays.copyOf(tmpValidIndexes, tmpValidIndexes.length);

    processErasures(erasedIndexes);
  }

  private void processErasures(int[] erasedIndexes) {
    this.decodeMatrix = new byte[getNumAllUnits() * getNumDataUnits()];
    this.invertMatrix = new byte[getNumAllUnits() * getNumDataUnits()];
    this.gfTables = new byte[getNumAllUnits() * getNumDataUnits() * 32];

    this.erasureFlags = new boolean[getNumAllUnits()];
    this.numErasedDataUnits = 0;

    for (int i = 0; i < erasedIndexes.length; i++) {
      int index = erasedIndexes[i];
      erasureFlags[index] = true;
      if (index < getNumDataUnits()) {
        numErasedDataUnits++;
      }
    }

    generateDecodeMatrix(erasedIndexes);

    RSUtil2.initTables(getNumDataUnits(), erasedIndexes.length,
        decodeMatrix, 0, gfTables);
    if (isAllowingVerboseDump()) {
      System.out.println(DumpUtil.bytesToHex(gfTables, -1));
    }
  }

  // Generate decode matrix from encode matrix
  private void generateDecodeMatrix(int[] erasedIndexes) {
    int i, j, r, p;
    byte s;
    byte[] tmpMatrix = new byte[getNumAllUnits() * getNumDataUnits()];

    // Construct matrix tmpMatrix by removing error rows
    for (i = 0; i < getNumDataUnits(); i++) {
      r = validIndexes[i];
      for (j = 0; j < getNumDataUnits(); j++) {
        tmpMatrix[getNumDataUnits() * i + j] =
                encodeMatrix[getNumDataUnits() * r + j];
      }
    }

    GF256.gfInvertMatrix(tmpMatrix, invertMatrix, getNumDataUnits());

    for (i = 0; i < numErasedDataUnits; i++) {
      for (j = 0; j < getNumDataUnits(); j++) {
        decodeMatrix[getNumDataUnits() * i + j] =
                invertMatrix[getNumDataUnits() * erasedIndexes[i] + j];
      }
    }

    for (p = numErasedDataUnits; p < erasedIndexes.length; p++) {
      for (i = 0; i < getNumDataUnits(); i++) {
        s = 0;
        for (j = 0; j < getNumDataUnits(); j++) {
          s ^= GF256.gfMul(invertMatrix[j * getNumDataUnits() + i],
                  encodeMatrix[getNumDataUnits() * erasedIndexes[p] + j]);
        }
        decodeMatrix[getNumDataUnits() * p + i] = s;
      }
    }
  }
}