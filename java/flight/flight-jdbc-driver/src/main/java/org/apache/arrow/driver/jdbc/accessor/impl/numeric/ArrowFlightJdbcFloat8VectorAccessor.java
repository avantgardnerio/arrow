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


import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.function.IntSupplier;

import org.apache.arrow.driver.jdbc.accessor.ArrowFlightJdbcAccessor;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.holders.NullableFloat8Holder;

/**
 * Accessor for the Float8Vector.
 */
public class ArrowFlightJdbcFloat8VectorAccessor extends ArrowFlightJdbcAccessor {

  private Float8Vector vector;
  private NullableFloat8Holder holder;

  public ArrowFlightJdbcFloat8VectorAccessor(Float8Vector vector,
                                      IntSupplier currentRowSupplier) {
    super(currentRowSupplier);
    this.holder = new NullableFloat8Holder();
    this.vector = vector;
  }

  @Override
  public double getDouble() {
    vector.get(getCurrentRow(), holder);

    this.wasNull = holder.isSet == 0;
    return this.wasNull ? 0 : holder.value;
  }

  @Override
  public Object getObject() {
    return this.getDouble();
  }

  @Override
  public String getString() {
    return Double.toString(getDouble());
  }

  @Override
  public boolean getBoolean() {
    return this.getDouble() != 0.0;
  }

  @Override
  public byte getByte() {
    return (byte) this.getDouble();
  }

  @Override
  public short getShort() {
    return (short) this.getDouble();
  }

  @Override
  public int getInt() {
    return (int) this.getDouble();
  }

  @Override
  public long getLong() {
    return (long) this.getDouble();
  }

  @Override
  public float getFloat() {
    return (float) this.getDouble();
  }

  @Override
  public BigDecimal getBigDecimal() {
    return BigDecimal.valueOf(this.getDouble());
  }

  @Override
  public BigDecimal getBigDecimal(int scale) {
    if (scale != 0) {
      throw new UnsupportedOperationException("Can not use getBigDecimal(int scale) on a decimal accessor.");
    }
    return BigDecimal.valueOf(this.getDouble());
  }

  @Override
  public byte[] getBytes() {
    return ByteBuffer.allocate(Float8Vector.TYPE_WIDTH)
        .putDouble(getDouble()).array();
  }
}
