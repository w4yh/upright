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
package org.apache.hadoop.hdfs.server.namenode;


import java.io.File;
import java.util.ArrayList;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.DF;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.namenode.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import junit.framework.TestCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;



/**
 * This tests InterDataNodeProtocol for block handling. 
 */
public class TestNamenodeCapacityReport extends TestCase {
  private static final Log LOG = LogFactory.getLog(TestNamenodeCapacityReport.class);

  /**
   * The following test first creates a file.
   * It verifies the block information from a datanode.
   * Then, it updates the block with new information and verifies again. 
   */
  public void testVolumeSize() throws Exception {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;

    // Set aside fifth of the total capacity as reserved
    long reserved = 10000;
    conf.setLong("dfs.datanode.du.reserved", reserved);
    
    try {
      cluster = new MiniDFSCluster(conf, 1, true, null);
      cluster.waitActive();
      
      FSNamesystem namesystem = cluster.getNameNode().namesystem;
      
      // Ensure the data reported for each data node is right
      ArrayList<DatanodeDescriptor> live = new ArrayList<DatanodeDescriptor>();
      ArrayList<DatanodeDescriptor> dead = new ArrayList<DatanodeDescriptor>();
      namesystem.DFSNodesStatus(live, dead);
      
      assertTrue(live.size() == 1);
      
      long used, remaining, totalCapacity, presentCapacity;
      float percentUsed;
      
      for (final DatanodeDescriptor datanode : live) {
        used = datanode.getDfsUsed();
        remaining = datanode.getRemaining();
        totalCapacity = datanode.getCapacity();
        presentCapacity = datanode.getPresentCapacity();
        percentUsed = datanode.getDfsUsedPercent();
        
        LOG.info("Datanode totalCapacity " + totalCapacity
            + " presentCapacity " + presentCapacity + " used " + used
            + " remaining " + remaining + " perenceUsed " + percentUsed);
        
        assertTrue(presentCapacity == (used + remaining));
        assertTrue(percentUsed == ((100.0f * (float)used)/(float)presentCapacity));
      }   
      
      DF df = new DF(new File(cluster.getDataDirectory()), conf);
     
      //
      // Currently two data directories are created by the data node
      // in the MiniDFSCluster. This results in each data directory having
      // capacity equals to the disk capacity of the data directory.
      // Hence the capacity reported by the data node is twice the disk space
      // the disk capacity
      //
      // So multiply the disk capacity and reserved space by two 
      // for accommodating it
      //
      int numOfDataDirs = 2;
      
      long diskCapacity = numOfDataDirs * df.getCapacity();
      reserved *= numOfDataDirs;
      
      totalCapacity = namesystem.getCapacityTotal();
      presentCapacity = namesystem.getPresentCapacity();
      used = namesystem.getCapacityUsed();
      remaining = namesystem.getCapacityRemaining();
      percentUsed = namesystem.getCapacityUsedPercent();
      
      LOG.info("Data node directory " + cluster.getDataDirectory());
           
      LOG.info("Name node diskCapacity " + diskCapacity + " totalCapacity "
          + totalCapacity + " reserved " + reserved + " presentCapacity "
          + presentCapacity + " used " + used + " remaining " + remaining
          + " percentUsed " + percentUsed);
      
      // Ensure new total capacity reported excludes the reserved space
      assertTrue(totalCapacity == diskCapacity - reserved);
      
      // Ensure present capacity is sum of used and remaining
      assertTrue(presentCapacity == (used + remaining));
      
      // Ensure percent used is calculated based on used and present capacity
      assertTrue(percentUsed == ((float)used * 100.0f)/(float)presentCapacity);
    }
    finally {
      if (cluster != null) {cluster.shutdown();}
    }
  }
}
