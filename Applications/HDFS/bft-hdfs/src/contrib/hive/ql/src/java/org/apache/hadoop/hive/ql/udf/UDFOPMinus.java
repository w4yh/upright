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

package org.apache.hadoop.hive.ql.udf;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.UDF;


public class UDFOPMinus extends UDF {

  private static Log LOG = LogFactory.getLog("org.apache.hadoop.hive.ql.udf.UDFOPMinus");

  public UDFOPMinus() {
  }

  public Byte evaluate(Byte a, Byte b)  {
    // LOG.info("Get input " + a.getClass() + ":" + a + " " + b.getClass() + ":" + b);
    if ((a == null) || (b == null))
      return null;

    return Byte.valueOf((byte)(a-b));
  }

  public Integer evaluate(Integer a, Integer b)  {
    // LOG.info("Get input " + a.getClass() + ":" + a + " " + b.getClass() + ":" + b);
    if ((a == null) || (b == null))
      return null;

    return Integer.valueOf((int)(a-b));
  }

  public Long evaluate(Long a, Long b)  {
    // LOG.info("Get input " + a.getClass() + ":" + a + " " + b.getClass() + ":" + b);
    if ((a == null) || (b == null))
      return null;

    return Long.valueOf((long)(a-b));
  }

  public Float evaluate(Float a, Float b)  {
    // LOG.info("Get input " + a.getClass() + ":" + a + " " + b.getClass() + ":" + b);
    if ((a == null) || (b == null))
      return null;

    return Float.valueOf((float)(a-b));
  }
  
  public Double evaluate(Double a, Double b)  {
    // LOG.info("Get input " + a.getClass() + ":" + a + " " + b.getClass() + ":" + b);
    if ((a == null) || (b == null))
      return null;

    return Double.valueOf((double)(a-b));
  }

}
