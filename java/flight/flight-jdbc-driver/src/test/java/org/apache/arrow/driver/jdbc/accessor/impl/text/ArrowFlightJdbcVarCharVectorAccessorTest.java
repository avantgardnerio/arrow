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

package org.apache.arrow.driver.jdbc.accessor.impl.text;

import static org.apache.calcite.avatica.util.Cursor.Accessor;
import static org.apache.commons.io.IOUtils.toByteArray;
import static org.apache.commons.io.IOUtils.toCharArray;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.function.IntSupplier;

import org.apache.arrow.vector.util.Text;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;


@RunWith(MockitoJUnitRunner.class)
public class ArrowFlightJdbcVarCharVectorAccessorTest {

  private Accessor accessor;
  private final SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
  private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSSXXX");

  @Mock
  private ArrowFlightJdbcVarCharVectorAccessor.Getter getter;

  @Rule
  public ErrorCollector collector = new ErrorCollector();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    IntSupplier currentRowSupplier = () -> 0;
    accessor = new ArrowFlightJdbcVarCharVectorAccessor(getter, currentRowSupplier);
  }

  @Test
  public void testShouldGetStringFromNullReturnNull() throws Exception {
    when(getter.get(0)).thenReturn(null);
    final String result = accessor.getString();

    collector.checkThat(result, equalTo(null));
  }

  @Test
  public void testShouldGetStringReturnValidString() throws Exception {
    Text value = new Text("Value for Test.");
    when(getter.get(0)).thenReturn(value);

    final String result = accessor.getString();

    collector.checkThat(result, instanceOf(String.class));
    collector.checkThat(result, equalTo(value.toString()));
  }

  @Test
  public void testShouldGetByteThrowsExceptionForNonNumericValue() throws Exception {
    Text value = new Text("Invalid value for byte.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getByte();
  }

  @Test
  public void testShouldGetByteThrowsExceptionForOutOfRangePositiveValue() throws Exception {
    Text value = new Text("128");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getByte();
  }

  @Test
  public void testShouldGetByteThrowsExceptionForOutOfRangeNegativeValue() throws Exception {
    Text value = new Text("-129");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getByte();
  }

  @Test
  public void testShouldGetByteReturnValidPositiveByte() throws Exception {
    Text value = new Text("127");
    when(getter.get(0)).thenReturn(value);

    byte result = accessor.getByte();

    collector.checkThat(result, instanceOf(Byte.class));
    collector.checkThat(result, equalTo((byte) 127));
  }

  @Test
  public void testShouldGetByteReturnValidNegativeByte() throws Exception {
    Text value = new Text("-128");
    when(getter.get(0)).thenReturn(value);

    byte result = accessor.getByte();

    collector.checkThat(result, instanceOf(Byte.class));
    collector.checkThat(result, equalTo((byte) -128));
  }

  @Test
  public void testShouldGetShortThrowsExceptionForNonNumericValue() throws Exception {
    Text value = new Text("Invalid value for short.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getShort();
  }

  @Test
  public void testShouldGetShortThrowsExceptionForOutOfRangePositiveValue() throws Exception {
    Text value = new Text("32768");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getShort();
  }

  @Test
  public void testShouldGetShortThrowsExceptionForOutOfRangeNegativeValue() throws Exception {
    Text value = new Text("-32769");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getShort();
  }

  @Test
  public void testShouldGetShortReturnValidPositiveShort() throws Exception {
    Text value = new Text("32767");
    when(getter.get(0)).thenReturn(value);

    short result = accessor.getShort();

    collector.checkThat(result, instanceOf(Short.class));
    collector.checkThat(result, equalTo((short) 32767));
  }

  @Test
  public void testShouldGetShortReturnValidNegativeShort() throws Exception {
    Text value = new Text("-32768");
    when(getter.get(0)).thenReturn(value);

    short result = accessor.getShort();

    collector.checkThat(result, instanceOf(Short.class));
    collector.checkThat(result, equalTo((short) -32768));
  }

  @Test
  public void testShouldGetIntThrowsExceptionForNonNumericValue() throws Exception {
    Text value = new Text("Invalid value for int.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getInt();
  }

  @Test
  public void testShouldGetIntThrowsExceptionForOutOfRangePositiveValue() throws Exception {
    Text value = new Text("2147483648");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getInt();
  }

  @Test
  public void testShouldGetIntThrowsExceptionForOutOfRangeNegativeValue() throws Exception {
    Text value = new Text("-2147483649");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getInt();
  }

  @Test
  public void testShouldGetIntReturnValidPositiveInteger() throws Exception {
    Text value = new Text("2147483647");
    when(getter.get(0)).thenReturn(value);

    int result = accessor.getInt();

    collector.checkThat(result, instanceOf(Integer.class));
    collector.checkThat(result, equalTo(2147483647));
  }

  @Test
  public void testShouldGetIntReturnValidNegativeInteger() throws Exception {
    Text value = new Text("-2147483648");
    when(getter.get(0)).thenReturn(value);

    int result = accessor.getInt();

    collector.checkThat(result, instanceOf(Integer.class));
    collector.checkThat(result, equalTo(-2147483648));
  }

  @Test
  public void testShouldGetLongThrowsExceptionForNonNumericValue() throws Exception {
    Text value = new Text("Invalid value for long.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getLong();
  }

  @Test
  public void testShouldGetLongThrowsExceptionForOutOfRangePositiveValue() throws Exception {
    Text value = new Text("9223372036854775808");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getLong();
  }

  @Test
  public void testShouldGetLongThrowsExceptionForOutOfRangeNegativeValue() throws Exception {
    Text value = new Text("-9223372036854775809");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getLong();
  }

  @Test
  public void testShouldGetLongReturnValidPositiveLong() throws Exception {
    Text value = new Text("9223372036854775807");
    when(getter.get(0)).thenReturn(value);

    long result = accessor.getLong();

    collector.checkThat(result, instanceOf(Long.class));
    collector.checkThat(result, equalTo(9223372036854775807L));
  }

  @Test
  public void testShouldGetLongReturnValidNegativeLong() throws Exception {
    Text value = new Text("-9223372036854775808");
    when(getter.get(0)).thenReturn(value);

    long result = accessor.getLong();

    collector.checkThat(result, instanceOf(Long.class));
    collector.checkThat(result, equalTo(-9223372036854775808L));
  }

  @Test
  public void testShouldGetBigDecimalThrowsExceptionForNonNumericValue() throws Exception {
    Text value = new Text("Invalid value for BigDecimal.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getBigDecimal();
  }

  @Test
  public void testShouldGetBigDecimalReturnValidPositiveBigDecimal() throws Exception {
    Text value = new Text("9223372036854775807000.999");
    when(getter.get(0)).thenReturn(value);

    BigDecimal result = accessor.getBigDecimal();

    collector.checkThat(result, instanceOf(BigDecimal.class));
    collector.checkThat(result, equalTo(new BigDecimal("9223372036854775807000.999")));
  }

  @Test
  public void testShouldGetBigDecimalReturnValidNegativeBigDecimal() throws Exception {
    Text value = new Text("-9223372036854775807000.999");
    when(getter.get(0)).thenReturn(value);

    BigDecimal result = accessor.getBigDecimal();

    collector.checkThat(result, instanceOf(BigDecimal.class));
    collector.checkThat(result, equalTo(new BigDecimal("-9223372036854775807000.999")));
  }

  @Test
  public void testShouldGetDoubleThrowsExceptionForNonNumericValue() throws Exception {
    Text value = new Text("Invalid value for double.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getDouble();
  }

  @Test
  public void testShouldGetDoubleReturnValidPositiveDouble() throws Exception {
    Text value = new Text("1.7976931348623157E308D");
    when(getter.get(0)).thenReturn(value);

    double result = accessor.getDouble();

    collector.checkThat(result, instanceOf(Double.class));
    collector.checkThat(result, equalTo(1.7976931348623157E308D));
  }

  @Test
  public void testShouldGetDoubleReturnValidNegativeDouble() throws Exception {
    Text value = new Text("-1.7976931348623157E308D");
    when(getter.get(0)).thenReturn(value);

    double result = accessor.getDouble();

    collector.checkThat(result, instanceOf(Double.class));
    collector.checkThat(result, equalTo(-1.7976931348623157E308D));
  }

  @Test
  public void testShouldGetDoubleWorkWithPositiveInfinity() throws Exception {
    Text value = new Text("Infinity");
    when(getter.get(0)).thenReturn(value);

    double result = accessor.getDouble();

    collector.checkThat(result, instanceOf(Double.class));
    collector.checkThat(result, equalTo(Double.POSITIVE_INFINITY));
  }

  @Test
  public void testShouldGetDoubleWorkWithNegativeInfinity() throws Exception {
    Text value = new Text("-Infinity");
    when(getter.get(0)).thenReturn(value);

    double result = accessor.getDouble();

    collector.checkThat(result, instanceOf(Double.class));
    collector.checkThat(result, equalTo(Double.NEGATIVE_INFINITY));
  }

  @Test
  public void testShouldGetDoubleWorkWithNaN() throws Exception {
    Text value = new Text("NaN");
    when(getter.get(0)).thenReturn(value);

    double result = accessor.getDouble();

    collector.checkThat(result, instanceOf(Double.class));
    collector.checkThat(result, equalTo(Double.NaN));
  }

  @Test
  public void testShouldGetFloatThrowsExceptionForNonNumericValue() throws Exception {
    Text value = new Text("Invalid value for float.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getFloat();
  }

  @Test
  public void testShouldGetFloatReturnValidPositiveFloat() throws Exception {
    Text value = new Text("3.4028235E38F");
    when(getter.get(0)).thenReturn(value);

    float result = accessor.getFloat();

    collector.checkThat(result, instanceOf(Float.class));
    collector.checkThat(result, equalTo(3.4028235E38F));
  }

  @Test
  public void testShouldGetFloatReturnValidNegativeFloat() throws Exception {
    Text value = new Text("-3.4028235E38F");
    when(getter.get(0)).thenReturn(value);

    float result = accessor.getFloat();

    collector.checkThat(result, instanceOf(Float.class));
    collector.checkThat(result, equalTo(-3.4028235E38F));
  }

  @Test
  public void testShouldGetFloatWorkWithPositiveInfinity() throws Exception {
    Text value = new Text("Infinity");
    when(getter.get(0)).thenReturn(value);

    float result = accessor.getFloat();

    collector.checkThat(result, instanceOf(Float.class));
    collector.checkThat(result, equalTo(Float.POSITIVE_INFINITY));
  }

  @Test
  public void testShouldGetFloatWorkWithNegativeInfinity() throws Exception {
    Text value = new Text("-Infinity");
    when(getter.get(0)).thenReturn(value);

    float result = accessor.getFloat();

    collector.checkThat(result, instanceOf(Float.class));
    collector.checkThat(result, equalTo(Float.NEGATIVE_INFINITY));
  }

  @Test
  public void testShouldGetFloatWorkWithNaN() throws Exception {
    Text value = new Text("NaN");
    when(getter.get(0)).thenReturn(value);

    float result = accessor.getFloat();

    collector.checkThat(result, instanceOf(Float.class));
    collector.checkThat(result, equalTo(Float.NaN));
  }

  @Test
  public void testShouldGetDateThrowsExceptionForNonDateValue() throws Exception {
    Text value = new Text("Invalid value for date.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getDate(null);
  }

  @Test
  public void testShouldGetDateReturnValidDateWithoutCalendar() throws Exception {
    Text value = new Text("2021-07-02");
    when(getter.get(0)).thenReturn(value);

    Date result = accessor.getDate(null);

    collector.checkThat(result, instanceOf(Date.class));

    Calendar calendar = Calendar.getInstance();
    calendar.setTime(result);

    collector.checkThat(dateTimeFormat.format(calendar.getTime()), equalTo("2021-07-02T00:00:00.000Z"));
  }

  @Test
  public void testShouldGetDateReturnValidDateWithCalendar() throws Exception {
    Text value = new Text("2021-07-02");
    when(getter.get(0)).thenReturn(value);

    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo"));
    Date result = accessor.getDate(calendar);

    calendar = Calendar.getInstance(TimeZone.getTimeZone("Etc/UTC"));
    calendar.setTime(result);

    collector.checkThat(dateTimeFormat.format(calendar.getTime()), equalTo("2021-07-02T03:00:00.000Z"));
  }

  @Test
  public void testShouldGetTimeThrowsExceptionForNonTimeValue() throws Exception {
    Text value = new Text("Invalid value for time.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getTime(null);
  }

  @Test
  public void testShouldGetTimeReturnValidDateWithoutCalendar() throws Exception {
    Text value = new Text("02:30:00");
    when(getter.get(0)).thenReturn(value);

    Time result = accessor.getTime(null);

    Calendar calendar = Calendar.getInstance();
    calendar.setTime(result);

    collector.checkThat(timeFormat.format(calendar.getTime()), equalTo("02:30:00.000Z"));
  }

  @Test
  public void testShouldGetTimeReturnValidDateWithCalendar() throws Exception {
    Text value = new Text("02:30:00");
    when(getter.get(0)).thenReturn(value);

    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo"));
    Time result = accessor.getTime(calendar);

    calendar = Calendar.getInstance(TimeZone.getTimeZone("Etc/UTC"));
    calendar.setTime(result);

    collector.checkThat(timeFormat.format(calendar.getTime()), equalTo("05:30:00.000Z"));
  }

  @Test
  public void testShouldGetTimestampThrowsExceptionForNonTimeValue() throws Exception {
    Text value = new Text("Invalid value for timestamp.");
    when(getter.get(0)).thenReturn(value);

    thrown.expect(IllegalArgumentException.class);
    accessor.getTimestamp(null);
  }

  @Test
  public void testShouldGetTimestampReturnValidDateWithoutCalendar() throws Exception {
    Text value = new Text("2021-07-02 02:30:00.000");
    when(getter.get(0)).thenReturn(value);

    Timestamp result = accessor.getTimestamp(null);

    Calendar calendar = Calendar.getInstance();
    calendar.setTime(result);

    collector.checkThat(dateTimeFormat.format(calendar.getTime()), equalTo("2021-07-02T02:30:00.000Z"));
  }

  @Test
  public void testShouldGetTimestampReturnValidDateWithCalendar() throws Exception {
    Text value = new Text("2021-07-02 02:30:00.000");
    when(getter.get(0)).thenReturn(value);

    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo"));
    Timestamp result = accessor.getTimestamp(calendar);

    calendar = Calendar.getInstance(TimeZone.getTimeZone("Etc/UTC"));
    calendar.setTime(result);

    collector.checkThat(dateTimeFormat.format(calendar.getTime()), equalTo("2021-07-02T05:30:00.000Z"));
  }

  @Test
  public void testShouldGetBooleanReturnTrueForNonEmpty() throws Exception {
    Text value = new Text("anything");
    when(getter.get(0)).thenReturn(value);

    boolean result = accessor.getBoolean();
    collector.checkThat(result, equalTo(true));
  }

  @Test
  public void testShouldGetBooleanReturnFalseForEmpty() throws Exception {
    Text value = new Text("");
    when(getter.get(0)).thenReturn(value);

    boolean result = accessor.getBoolean();
    collector.checkThat(result, equalTo(false));
  }

  @Test
  public void testShouldGetBytesReturnValidByteArray() throws Exception {
    Text value = new Text("Value for Test.");
    when(getter.get(0)).thenReturn(value);

    final byte[] result = accessor.getBytes();

    collector.checkThat(result, instanceOf(byte[].class));
    collector.checkThat(result, equalTo(value.toString().getBytes()));
  }

  @Test
  public void testShouldGetAsciiStreamReturnValidInputStream() throws Exception {
    Text value = new Text("Value for Test.");
    when(getter.get(0)).thenReturn(value);

    try (InputStream result = accessor.getAsciiStream()) {
      byte[] resultBytes = toByteArray(result);

      collector.checkThat(new String(resultBytes), equalTo(value.toString()));
    }
  }

  @Test
  public void testShouldGetCharacterStreamReturnValidReader() throws Exception {
    Text value = new Text("Value for Test.");
    when(getter.get(0)).thenReturn(value);

    try (Reader result = accessor.getCharacterStream()) {
      char[] resultChars = toCharArray(result);

      collector.checkThat(new String(resultChars), equalTo(value.toString()));
    }
  }
}
