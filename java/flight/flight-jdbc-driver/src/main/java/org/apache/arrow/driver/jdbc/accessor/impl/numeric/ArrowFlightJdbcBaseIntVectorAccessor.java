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

package org.apache.arrow.driver.jdbc.accessor.impl.numeric;

import static org.apache.arrow.driver.jdbc.accessor.impl.numeric.ArrowFlightJdbcNumericGetter.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.function.IntSupplier;

import org.apache.arrow.driver.jdbc.accessor.ArrowFlightJdbcAccessor;
import org.apache.arrow.driver.jdbc.accessor.impl.numeric.ArrowFlightJdbcNumericGetter.NumericHolder;
import org.apache.arrow.vector.BaseIntVector;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;

/**
 * Accessor for the arrow types: TinyIntVector, SmallIntVector, IntVector, BigIntVector,
 * UInt1Vector, UInt2Vector, UInt4Vector and UInt8Vector.
 */
public class ArrowFlightJdbcBaseIntVectorAccessor extends ArrowFlightJdbcAccessor {

  private final boolean isUnsigned;
  private final int bytesToAllocate;
  private final Getter getter;
  private final NumericHolder holder;

  public ArrowFlightJdbcBaseIntVectorAccessor(UInt1Vector vector,
                                              IntSupplier currentRowSupplier) {
    this(vector, currentRowSupplier, true, UInt1Vector.TYPE_WIDTH);
  }

  public ArrowFlightJdbcBaseIntVectorAccessor(UInt2Vector vector,
                                              IntSupplier currentRowSupplier) {
    this(vector, currentRowSupplier, true, UInt2Vector.TYPE_WIDTH);
  }

  public ArrowFlightJdbcBaseIntVectorAccessor(UInt4Vector vector,
                                              IntSupplier currentRowSupplier) {
    this(vector, currentRowSupplier, true, UInt4Vector.TYPE_WIDTH);
  }

  public ArrowFlightJdbcBaseIntVectorAccessor(UInt8Vector vector,
                                              IntSupplier currentRowSupplier) {
    this(vector, currentRowSupplier, true, UInt8Vector.TYPE_WIDTH);
  }

  public ArrowFlightJdbcBaseIntVectorAccessor(TinyIntVector vector,
                                              IntSupplier currentRowSupplier) {
    this(vector, currentRowSupplier, false, TinyIntVector.TYPE_WIDTH);
  }

  public ArrowFlightJdbcBaseIntVectorAccessor(SmallIntVector vector,
                                              IntSupplier currentRowSupplier) {
    this(vector, currentRowSupplier, false, SmallIntVector.TYPE_WIDTH);
  }

  public ArrowFlightJdbcBaseIntVectorAccessor(IntVector vector,
                                              IntSupplier currentRowSupplier) {
    this(vector, currentRowSupplier, false, IntVector.TYPE_WIDTH);
  }

  public ArrowFlightJdbcBaseIntVectorAccessor(BigIntVector vector,
                                              IntSupplier currentRowSupplier) {
    this(vector, currentRowSupplier, false, BigIntVector.TYPE_WIDTH);
  }

  private ArrowFlightJdbcBaseIntVectorAccessor(BaseIntVector vector,
                                               IntSupplier currentRowSupplier,
                                               boolean isUnsigned,
                                               int bytesToAllocate) {
    super(currentRowSupplier);
    this.holder = new NumericHolder();
    this.getter = createGetter(vector);
    this.isUnsigned = isUnsigned;
    this.bytesToAllocate = bytesToAllocate;
  }

  @Override
  public long getLong() {
    getter.get(getCurrentRow(), holder);
    this.wasNull = holder.isSet == 0;

    return this.wasNull ? 0L : holder.value;
  }

  @Override
  public Class<?> getObjectClass() {
    return Long.class;
  }

  @Override
  public String getString() {
    final long number = getLong();

    if (this.wasNull) {
      return null;
    } else {
      return isUnsigned ? Long.toUnsignedString(number) : Long.toString(number);
    }
  }

  @Override
  public byte getByte() {
    return (byte) getLong();
  }

  @Override
  public short getShort() {
    return (short) getLong();
  }

  @Override
  public int getInt() {
    return (int) getLong();
  }

  @Override
  public float getFloat() {
    return (float) getLong();
  }

  @Override
  public double getDouble() {
    return (double) getLong();
  }

  @Override
  public byte[] getBytes() {
    final ByteBuffer buffer = ByteBuffer.allocate(bytesToAllocate);
    final long value = getLong();

    if (this.wasNull) {
      return null;
    } else if (bytesToAllocate == Byte.BYTES) {
      return buffer.put((byte) value).array();
    } else if (bytesToAllocate == Short.BYTES) {
      return buffer.putShort((short) value).array();
    } else if (bytesToAllocate == Integer.BYTES) {
      return buffer.putInt((int) value).array();
    } else if (bytesToAllocate == Long.BYTES) {
      return buffer.putLong(value).array();
    }

    throw new UnsupportedOperationException();
  }

  @Override
  public BigDecimal getBigDecimal() {
    final BigDecimal value = BigDecimal.valueOf(getLong());
    return this.wasNull ? null : value;
  }

  @Override
  public BigDecimal getBigDecimal(int scale) {
    final BigDecimal value = BigDecimal.valueOf(this.getDouble()).setScale(scale, RoundingMode.UNNECESSARY);
    return this.wasNull ? null : value;
  }

  @Override
  public Object getObject() {
    long value = getLong();
    return this.wasNull ? null : value;
  }

  @Override
  public boolean getBoolean() {
    final long value = getLong();

    return value != 0;
  }
}
