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

import org.apache.commons.logging.*;

import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Trash;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.permission.*;
import org.apache.hadoop.hdfs.BFTRandom;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.common.UpgradeStatusReport;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem.CompleteFileStatus;
import org.apache.hadoop.hdfs.server.namenode.metrics.NameNodeMetrics;
import org.apache.hadoop.hdfs.server.protocol.BlocksWithLocations;
import org.apache.hadoop.hdfs.server.protocol.DatanodeCommand;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.UpgradeCommand;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.ipc.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.security.UserGroupInformation;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

/**********************************************************
 * NameNode serves as both directory namespace manager and
 * "inode table" for the Hadoop DFS.  There is a single NameNode
 * running in any DFS deployment.  (Well, except when there
 * is a second backup/failover NameNode.)
 *
 * The NameNode controls two critical tables:
 *   1)  filename->blocksequence (namespace)
 *   2)  block->machinelist ("inodes")
 *
 * The first table is stored on disk and is very precious.
 * The second table is rebuilt every time the NameNode comes
 * up.
 *
 * 'NameNode' refers to both this class as well as the 'NameNode server'.
 * The 'FSNamesystem' class actually performs most of the filesystem
 * management.  The majority of the 'NameNode' class itself is concerned
 * with exposing the IPC interface to the outside world, plus some
 * configuration management.
 *
 * NameNode implements the ClientProtocol interface, which allows
 * clients to ask for DFS services.  ClientProtocol is not
 * designed for direct use by authors of DFS client code.  End-users
 * should instead use the org.apache.nutch.hadoop.fs.FileSystem class.
 *
 * NameNode also implements the DatanodeProtocol interface, used by
 * DataNode programs that actually store DFS data blocks.  These
 * methods are invoked repeatedly and automatically by all the
 * DataNodes in a DFS deployment.
 *
 * NameNode also implements the NamenodeProtocol interface, used by
 * secondary namenodes or rebalancing processes to get partial namenode's
 * state, for example partial blocksMap etc.
 **********************************************************/
public class NameNode implements ClientProtocol, DatanodeProtocol,
                                 NamenodeProtocol, FSConstants, 
                                 BFTGlueNamenodeProtocol {
  public long getProtocolVersion(String protocol, 
                                 long clientVersion) throws IOException { 
    if (protocol.equals(ClientProtocol.class.getName())) {
      return ClientProtocol.versionID; 
    } else if (protocol.equals(DatanodeProtocol.class.getName())){
      return DatanodeProtocol.versionID;
    } else if (protocol.equals(NamenodeProtocol.class.getName())){
      return NamenodeProtocol.versionID;
    } else if (protocol.equals(BFTGlueNamenodeProtocol.class.getName())){
    	return BFTGlueNamenodeProtocol.versionID;
    } else {
      throw new IOException("Unknown protocol to name node: " + protocol);
    }
  }
    
  public static int DEFAULT_PORT = 8020;
  
  public static boolean isHelper = false;

  public static final Log LOG = LogFactory.getLog(NameNode.class.getName());
  public static final Log stateChangeLog = LogFactory.getLog("org.apache.hadoop.hdfs.StateChange");
  public FSNamesystem namesystem;
  private Server server;
  private Thread emptier;
  private int handlerCount = 2;
  private boolean supportAppends = true; // allow appending to hdfs files
    
  private InetSocketAddress nameNodeAddress = null;
    
  /** only used for testing purposes  */
  private boolean stopRequested = false;

  /** Format a new filesystem.  Destroys any filesystem that may already
   * exist at this location.  **/
  public static void format(Configuration conf) throws IOException {
    format(conf, false);
  }

  static NameNodeMetrics myMetrics;

  public static NameNodeMetrics getNameNodeMetrics() {
    return myMetrics;
  }
  
  public static InetSocketAddress getAddress(String address) {
  	if(isHelper){
  		DEFAULT_PORT += 1;
  		if(address.indexOf(":") >= 0){
  			String ip = address.split(":")[0];
  			String port = address.split(":")[1];
  			address = ip + ":" + (Integer.parseInt(port)+1);			
  		}
  	}
  	  	
    return NetUtils.createSocketAddr(address, DEFAULT_PORT);
  }

  public static InetSocketAddress getAddress(Configuration conf) {
    return getAddress(FileSystem.getDefaultUri(conf).getAuthority());
  }

  public static URI getUri(InetSocketAddress namenode) {
    int port = namenode.getPort();
    String portString = port == DEFAULT_PORT ? "" : (":"+port);
    return URI.create("hdfs://"+ namenode.getHostName()+portString);
  }
  
  /**
   * Initialize the server
   * 
   * @param address hostname:port to bind to
   * @param conf the configuration
   */
  private void initialize(String address, Configuration conf) throws IOException {
    InetSocketAddress socAddr = NameNode.getAddress(address);
    this.supportAppends = conf.getBoolean("dfs.support.append", true);
    this.handlerCount = conf.getInt("dfs.namenode.handler.count", 10);
    this.server = RPC.getServer(this, socAddr.getHostName(), socAddr.getPort(),
                                handlerCount, false, conf);

    // The rpc-server port can be ephemeral... ensure we have the correct info
    this.nameNodeAddress = this.server.getListenerAddress(); 
    FileSystem.setDefaultUri(conf, getUri(nameNodeAddress));
    LOG.info("Namenode up at: " + this.nameNodeAddress);

    myMetrics = new NameNodeMetrics(conf, this);

    this.namesystem = new FSNamesystem(this, conf);
    this.server.start();  //start RPC server   

    // emptier below created a client and have it connect to this namenode using rpc
    // so wrapper should be created before emptier 
    if(conf.getBoolean("dfs.bft", false)){
      //BftNamenodeGlue bng = new BftNamenodeGlue(conf, this);
      //bng.initialize();
      //BftNameNodeSmallGlue bnsg = new BftNameNodeSmallGlue(conf, this);
      //bnsg.initialize();
	//BftNamenodeWrapper wrapper = new BftNamenodeWrapper(conf);
	//wrapper.initialize();
    	
    	// NEW General CP
    	if(!isHelper){
    		BftPrimaryGlue bpg = new BftPrimaryGlue(conf, this);
    		bpg.initialize();
    	}
    }
    
    /*
    this.emptier = new Thread(new Trash(conf).getEmptier(), "Trash Emptier");
    this.emptier.setDaemon(true);
    this.emptier.start();
    */
  }
    
  /**
   * Start NameNode.
   * <p>
   * The name-node can be started with one of the following startup options:
   * <ul> 
   * <li>{@link org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption#REGULAR REGULAR} - normal startup</li>
   * <li>{@link org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption#FORMAT FORMAT} - format name node</li>
   * <li>{@link org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption#UPGRADE UPGRADE} - start the cluster  
   * upgrade and create a snapshot of the current file system state</li> 
   * <li>{@link org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption#ROLLBACK ROLLBACK} - roll the  
   *            cluster back to the previous state</li>
   * </ul>
   * The option is passed via configuration field: 
   * <tt>dfs.namenode.startup</tt>
   * 
   * The conf will be modified to reflect the actual ports on which 
   * the NameNode is up and running if the user passes the port as
   * <code>zero</code> in the conf.
   * 
   * @param conf  confirguration
   * @throws IOException
   */
  public NameNode(Configuration conf) throws IOException {
    this(FileSystem.getDefaultUri(conf).getAuthority(), conf);
  }

  /**
   * Create a NameNode at the specified location and start it.
   * 
   * The conf will be modified to reflect the actual ports on which 
   * the NameNode is up and running if the user passes the port as
   * <code>zero</code>.  
   */
  public NameNode(String bindAddress,
                  Configuration conf
                  ) throws IOException {
    try {
    	boolean bft = conf.getBoolean("dfs.bft", false);
    	if(bft){
    		if(bindAddress.indexOf(":") >= 0){
    			bindAddress = DNS.getDefaultIP("default")
    			+ ":" + bindAddress.split(":")[1];
    		} else {
    			bindAddress = DNS.getDefaultIP("default");
    		}
    		
    	}
    	
      initialize(bindAddress, conf);
    } catch (IOException e) {
      this.stop();
      throw e;
    }
  }

  /**
   * Wait for service to finish.
   * (Normally, it runs forever.)
   */
  public void join() {
    try {
      this.server.join();
    } catch (InterruptedException ie) {
    }
  }

  /**
   * Stop all NameNode threads and wait for all to finish.
   */
  public void stop() {
    if (stopRequested)
      return;
    stopRequested = true;
    if(namesystem != null) namesystem.close();
    if(emptier != null) emptier.interrupt();
    if(server != null) server.stop();
    if (myMetrics != null) {
      myMetrics.shutdown();
    }
    if (namesystem != null) {
      namesystem.shutdown();
    }
  }
  
  /////////////////////////////////////////////////////
  // NamenodeProtocol
  /////////////////////////////////////////////////////
  /**
   * return a list of blocks & their locations on <code>datanode</code> whose
   * total size is <code>size</code>
   * 
   * @param datanode on which blocks are located
   * @param size total size of blocks
   */
  public BlocksWithLocations getBlocks(DatanodeInfo datanode, long size)
  throws IOException {
    if(size <= 0) {
      throw new IllegalArgumentException(
        "Unexpected not positive size: "+size);
    }

    return namesystem.getBlocks(datanode, size); 
  }
  
  /////////////////////////////////////////////////////
  // ClientProtocol
  /////////////////////////////////////////////////////
  /** {@inheritDoc} */
  public LocatedBlocks   getBlockLocations(String src, 
                                          long offset, 
                                          long length) throws IOException {
    myMetrics.numGetBlockLocations.inc();
    return namesystem.getBlockLocations(getClientMachine(), 
                                        src, offset, length);
  }
  
  private static String getClientMachine() {
  	if(FSNamesystem.bft == true){
  		return "";
  	}
    String clientMachine = Server.getRemoteAddress();
    if (clientMachine == null) {
      clientMachine = "";
    }
    return clientMachine;
  }

  /** {@inheritDoc} */
  public void create(String src, 
                     FsPermission masked,
                             String clientName, 
                             boolean overwrite,
                             short replication,
                             long blockSize
                             ) throws IOException {
    String clientMachine = getClientMachine();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.create: file "
                         +src+" for "+clientName+" at "+clientMachine);
    }
    if (!checkPathLength(src)) {
      throw new IOException("create: Pathname too long.  Limit " 
                            + MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    namesystem.startFile(src,
        new PermissionStatus(UserGroupInformation.getCurrentUGI().getUserName(),
            "", masked),
        clientName, clientMachine, overwrite, replication, blockSize);
    myMetrics.numFilesCreated.inc();
    myMetrics.numCreateFileOps.inc();
  }

  /** {@inheritDoc} */
  public LocatedBlock append(String src, String clientName) throws IOException {
    String clientMachine = getClientMachine();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.append: file "
          +src+" for "+clientName+" at "+clientMachine);
    }
    if (supportAppends == false) {
      throw new IOException("Append to hdfs not supported." +
                            " Please refer to dfs.support.append configuration parameter.");
    }

    LocatedBlock info = namesystem.appendFile(src, clientName, clientMachine);
    myMetrics.numFilesAppended.inc();
    return info;
  }

  /** {@inheritDoc} */
  public boolean setReplication(String src, 
                                short replication
                                ) throws IOException {
    return namesystem.setReplication(src, replication);
  }
    
  /** {@inheritDoc} */
  public void setPermission(String src, FsPermission permissions
      ) throws IOException {
    namesystem.setPermission(src, permissions);
  }

  /** {@inheritDoc} */
  public void setOwner(String src, String username, String groupname
      ) throws IOException {
    namesystem.setOwner(src, username, groupname);
  }

  /**
   */
  public LocatedBlock addBlock(String src, 
                               String clientName) throws IOException {
    stateChangeLog.debug("*BLOCK* NameNode.addBlock: file "
                         +src+" for "+clientName);
    LocatedBlock locatedBlock = namesystem.getAdditionalBlock(src, clientName);
    if (locatedBlock != null)
      myMetrics.numAddBlockOps.inc();
    
    return locatedBlock;
  }
  
  public LocatedBlock addBlock(String src,
  		String clientName,
  		byte[] hashOfPreviousBlock) throws IOException {

  	stateChangeLog.debug("*BLOCK* NameNode.addBlock [bftdatanode]: file "
  			+src+" for "+clientName);
  	LOG.debug("[bft] addblock Hash Val : " + ((hashOfPreviousBlock!=null)?
  			new MD5Hash(hashOfPreviousBlock).toString():""));
  	LocatedBlock locatedBlock = namesystem.getAdditionalBlock(src, clientName, hashOfPreviousBlock);
  	if (locatedBlock != null)
  		myMetrics.numAddBlockOps.inc();

  	return locatedBlock;
  }

  /**
   * The client needs to give up on the block.
   */
  public void abandonBlock(Block b, String src, String holder
      ) throws IOException {
    stateChangeLog.debug("*BLOCK* NameNode.abandonBlock: "
                         +b+" of file "+src);
    if (!namesystem.abandonBlock(b, src, holder)) {
      throw new IOException("Cannot abandon block during write to " + src);
    }
  }
  
  public boolean complete(String src,
  																String clientName,
  																byte[] hashOfLastBlock) throws IOException {
    stateChangeLog.debug("*DIR* NameNode.complete [bftdatanode] : " + src + " for " + clientName);
    if(hashOfLastBlock == null){
    	LOG.debug("[bft] complete Hash Val : NULL ");
    } else {
    	LOG.debug("[bft] complete Hash Val : " +	
    			new MD5Hash(hashOfLastBlock).toString());
    }
    
    CompleteFileStatus returnCode = namesystem.completeFile(src, clientName, hashOfLastBlock);
    if (returnCode == CompleteFileStatus.STILL_WAITING) {
      return false;
    } else if (returnCode == CompleteFileStatus.COMPLETE_SUCCESS) {
      return true;
    } else {
      throw new IOException("Could not complete write to file " + src + " by " + clientName);
    }
  }

  /** {@inheritDoc} */
  public boolean complete(String src, String clientName) throws IOException {
    stateChangeLog.debug("*DIR* NameNode.complete: " + src + " for " + clientName);
    CompleteFileStatus returnCode = namesystem.completeFile(src, clientName);
    if (returnCode == CompleteFileStatus.STILL_WAITING) {
      return false;
    } else if (returnCode == CompleteFileStatus.COMPLETE_SUCCESS) {
      return true;
    } else {
      throw new IOException("Could not complete write to file " + src + " by " + clientName);
    }
  }

  /**
   * The client has detected an error on the specified located blocks 
   * and is reporting them to the server.  For now, the namenode will 
   * mark the block as corrupt.  In the future we might 
   * check the blocks are actually corrupt. 
   */
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    stateChangeLog.info("*DIR* NameNode.reportBadBlocks");
    for (int i = 0; i < blocks.length; i++) {
      Block blk = blocks[i].getBlock();
      DatanodeInfo[] nodes = blocks[i].getLocations();
      for (int j = 0; j < nodes.length; j++) {
        DatanodeInfo dn = nodes[j];
        namesystem.markBlockAsCorrupt(blk, dn);
      }
    }
    
    if(FSNamesystem.bft & !namesystem.bftReplaying){
      ((BftFSEditLog)namesystem.getEditLog()).bftLogReportBadBlocks(blocks);
    }
    
  }

  /** {@inheritDoc} */
  public long nextGenerationStamp(Block block) throws IOException{
    return namesystem.nextGenerationStampForBlock(block);
  }

  /** {@inheritDoc} */
  public void commitBlockSynchronization(Block block,
      long newgenerationstamp, long newlength,
      boolean closeFile, boolean deleteblock, DatanodeID[] newtargets
      ) throws IOException {
    namesystem.commitBlockSynchronization(block,
        newgenerationstamp, newlength, closeFile, deleteblock, newtargets);
  }
  
  public long getPreferredBlockSize(String filename) throws IOException {
    return namesystem.getPreferredBlockSize(filename);
  }
    
  /**
   */
  public boolean rename(String src, String dst) throws IOException {
    stateChangeLog.debug("*DIR* NameNode.rename: " + src + " to " + dst);
    if (!checkPathLength(dst)) {
      throw new IOException("rename: Pathname too long.  Limit " 
                            + MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    boolean ret = namesystem.renameTo(src, dst);
    if (ret) {
      myMetrics.numFilesRenamed.inc();
    }
    return ret;
  }

  /**
   */
  @Deprecated
  public boolean delete(String src) throws IOException {
    return delete(src, true);
  }

  /** {@inheritDoc} */
  public boolean delete(String src, boolean recursive) throws IOException {
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* Namenode.delete: src=" + src
          + ", recursive=" + recursive);
    }
    boolean ret = namesystem.delete(src, recursive);
    if (ret) 
      myMetrics.numDeleteFileOps.inc();
    return ret;
  }

  /**
   * Check path length does not exceed maximum.  Returns true if
   * length and depth are okay.  Returns false if length is too long 
   * or depth is too great.
   * 
   */
  private boolean checkPathLength(String src) {
    Path srcPath = new Path(src);
    return (src.length() <= MAX_PATH_LENGTH &&
            srcPath.depth() <= MAX_PATH_DEPTH);
  }
    
  /** {@inheritDoc} */
  public boolean mkdirs(String src, FsPermission masked) throws IOException {
    stateChangeLog.debug("*DIR* NameNode.mkdirs: " + src);
    if (!checkPathLength(src)) {
      throw new IOException("mkdirs: Pathname too long.  Limit " 
                            + MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    return namesystem.mkdirs(src,
        new PermissionStatus(UserGroupInformation.getCurrentUGI().getUserName(),
            "", masked));
  }

  /**
   */
  public void renewLease(String clientName) throws IOException {
    namesystem.renewLease(clientName);        
  }

  /**
   */
  public FileStatus[] getListing(String src) throws IOException {
    FileStatus[] files = namesystem.getListing(src);
    if (files != null) {
      myMetrics.numGetListingOps.inc();
    }
    return files;
  }

  /**
   * Get the file info for a specific file.
   * @param src The string representation of the path to the file
   * @throws IOException if permission to access file is denied by the system
   * @return object containing information regarding the file
   *         or null if file not found
   */
  public FileStatus getFileInfo(String src)  throws IOException {
    return namesystem.getFileInfo(src);
  }

  /** @inheritDoc */
  public long[] getStats() throws IOException {
    return namesystem.getStats();
  }

  /**
   */
  public DatanodeInfo[] getDatanodeReport(DatanodeReportType type)
  throws IOException {
    DatanodeInfo results[] = namesystem.datanodeReport(type);
    if (results == null ) {
      throw new IOException("Cannot find datanode report");
    }
    return results;
  }
    
  /**
   * @inheritDoc
   */
  public boolean setSafeMode(SafeModeAction action) throws IOException {
    return namesystem.setSafeMode(action);
  }

  /**
   * Is the cluster currently in safe mode?
   */
  public boolean isInSafeMode() {
    return namesystem.isInSafeMode();
  }

  /*
   * Refresh the list of datanodes that the namenode should allow to  
   * connect.  Re-reads conf by creating new Configuration object and 
   * uses the files list in the configuration to update the list. 
   */
  public void refreshNodes() throws IOException {
    namesystem.refreshNodes(new Configuration());
  }

  /**
   * Returns the size of the current edit log.
   */
  public long getEditLogSize() throws IOException {
    return namesystem.getEditLogSize();
  }

  /**
   * Roll the edit log.
   */
  public CheckpointSignature rollEditLog() throws IOException {
    return namesystem.rollEditLog();
  }

  /**
   * Roll the image 
   */
  public void rollFsImage() throws IOException {
    namesystem.rollFSImage();
  }
    
  public void finalizeUpgrade() throws IOException {
    namesystem.finalizeUpgrade();
  }

  public UpgradeStatusReport distributedUpgradeProgress(UpgradeAction action
                                                        ) throws IOException {
    return namesystem.distributedUpgradeProgress(action);
  }

  /**
   * Dumps namenode state into specified file
   */
  public void metaSave(String filename) throws IOException {
    namesystem.metaSave(filename);
  }

  /** {@inheritDoc} */
  public ContentSummary getContentSummary(String path) throws IOException {
    return namesystem.getContentSummary(path);
  }

  /** {@inheritDoc} */
  public void setQuota(String path, long namespaceQuota, long diskspaceQuota) 
                       throws IOException {
    namesystem.setQuota(path, namespaceQuota, diskspaceQuota);
  }
  
  /** {@inheritDoc} */
  public void fsync(String src, String clientName) throws IOException {
    namesystem.fsync(src, clientName);
  }

  /** @inheritDoc */
  public void setTimes(String src, long mtime, long atime) throws IOException {
    namesystem.setTimes(src, mtime, atime);
  }

  ////////////////////////////////////////////////////////////////
  // DatanodeProtocol
  ////////////////////////////////////////////////////////////////
  /** 
   */
  public DatanodeRegistration register(DatanodeRegistration nodeReg
                                       ) throws IOException {
    verifyVersion(nodeReg.getVersion());
    namesystem.registerDatanode(nodeReg);
      
    return nodeReg;
  }

  /**
   * Data node notify the name node that it is alive 
   * Return a block-oriented command for the datanode to execute.
   * This will be either a transfer or a delete operation.
   */
  public DatanodeCommand sendHeartbeat(DatanodeRegistration nodeReg,
                                       long capacity,
                                       long dfsUsed,
                                       long remaining,
                                       int xmitsInProgress,
                                       int xceiverCount) throws IOException {
    verifyRequest(nodeReg);
    return namesystem.handleHeartbeat(nodeReg, capacity, dfsUsed, remaining,
        xceiverCount, xmitsInProgress);
  }

  public DatanodeCommand blockReport(DatanodeRegistration nodeReg,
                                     long[] blocks) throws IOException {
    verifyRequest(nodeReg);
    BlockListAsLongs blist = new BlockListAsLongs(blocks);
    stateChangeLog.debug("*BLOCK* NameNode.blockReport: "
           +"from "+nodeReg.getName()+" "+blist.getNumberOfBlocks() +" blocks");

    namesystem.processReport(nodeReg, blist);
  
    if(FSNamesystem.bft & !namesystem.bftReplaying){
      ((BftFSEditLog)namesystem.getEditLog()).bftLogBlockReport(nodeReg, blocks);
    }
    
    if (getFSImage().isUpgradeFinalized())
      return DatanodeCommand.FINALIZE;
    return null;
  }

  public DatanodeCommand blockReport(DatanodeRegistration nodeReg,
  		Block[] blocks) throws IOException {
  	verifyRequest(nodeReg);
  	//BlockListAsLongs blist = new BlockListAsLongs(blocks);
  	stateChangeLog.debug("*BLOCK* NameNode.blockReport: "
  			+"from "+nodeReg.getName()+" "+blocks.length +" blocks");

  	namesystem.processReport(nodeReg, blocks);

  	if(FSNamesystem.bft & !namesystem.bftReplaying){
  		((BftFSEditLog)namesystem.getEditLog()).bftLogBlockReport(nodeReg, blocks);  		  		
  	}

  	if (getFSImage().isUpgradeFinalized())
  		return DatanodeCommand.FINALIZE;
  	return null;
  }
  
  

  public void blockReceived(DatanodeRegistration nodeReg, 
                            Block blocks[],
                            String delHints[]) throws IOException {
    verifyRequest(nodeReg);
    stateChangeLog.debug("*BLOCK* NameNode.blockReceived: "
                         +"from "+nodeReg.getName()+" "+blocks.length+" blocks.");
    for (int i = 0; i < blocks.length; i++) {
      namesystem.blockReceived(nodeReg, blocks[i], delHints[i]);
    }
    if(FSNamesystem.bft & !namesystem.bftReplaying){
      ((BftFSEditLog)namesystem.getEditLog()).bftLogBlockReceived(nodeReg, blocks, delHints);
    }
    
  }

  /**
   */
  public void errorReport(DatanodeRegistration nodeReg,
                          int errorCode, 
                          String msg) throws IOException {
    // Log error message from datanode
    String dnName = (nodeReg == null ? "unknown DataNode" : nodeReg.getName());
    LOG.info("Error report from " + dnName + ": " + msg);
    if (errorCode == DatanodeProtocol.NOTIFY) {
      return;
    }
    verifyRequest(nodeReg);
    if (errorCode == DatanodeProtocol.DISK_ERROR) {
      namesystem.removeDatanode(nodeReg);            
    }
  }
    
  public NamespaceInfo versionRequest() throws IOException {
    return namesystem.getNamespaceInfo();
  }

  public UpgradeCommand processUpgradeCommand(UpgradeCommand comm) throws IOException {
    return namesystem.processDistributedUpgradeCommand(comm);
  }

  /** 
   * Verify request.
   * 
   * Verifies correctness of the datanode version, registration ID, and 
   * if the datanode does not need to be shutdown.
   * 
   * @param nodeReg data node registration
   * @throws IOException
   */
  public void verifyRequest(DatanodeRegistration nodeReg) throws IOException {
    verifyVersion(nodeReg.getVersion());
    if (!namesystem.getRegistrationID().equals(nodeReg.getRegistrationID()))
      throw new UnregisteredDatanodeException(nodeReg);
  }
    
  /**
   * Verify version.
   * 
   * @param version
   * @throws IOException
   */
  public void verifyVersion(int version) throws IOException {
    if (version != LAYOUT_VERSION)
      throw new IncorrectVersionException(version, "data node");
  }

  /**
   * Returns the name of the fsImage file
   */
  public File getFsImageName() throws IOException {
    return getFSImage().getFsImageName();
  }
    
  public FSImage getFSImage() {
    return namesystem.dir.fsImage;
  }

  /**
   * Returns the name of the fsImage file uploaded by periodic
   * checkpointing
   */
  public File[] getFsImageNameCheckpoint() throws IOException {
    return getFSImage().getFsImageNameCheckpoint();
  }

  /**
   * Returns the address on which the NameNodes is listening to.
   * @return the address on which the NameNodes is listening to.
   */
  public InetSocketAddress getNameNodeAddress() {
    return nameNodeAddress;
  }

  NetworkTopology getNetworkTopology() {
    return this.namesystem.clusterMap;
  }

  /////////////////////////////////////////////////////
  // BftWrapperNamenodeProtocol
  /////////////////////////////////////////////////////

  public String getEditLogName() throws IOException {
	  return getFSImage().getEditLog().getFsEditName().getAbsolutePath();
  }
  public String getNewCPName () throws IOException {
	  return getFSImage().getFsImageNameCheckpoint()[0].getAbsolutePath();
  }
  public File getImageName() throws IOException{
	  return getFSImage().getFsImageName();
  }


  public void executeThreadFunctions() {
	  namesystem.executeThreadFunctions();
  }

  public void setBftTime(long time) {
	  BFTRandom.setBftTime(time);
  }

  public void reloadImage() throws IOException{

	  if(namesystem !=null){
		  namesystem.close();
		  namesystem.shutdown();
	  }
	  namesystem = new FSNamesystem(this, new Configuration());
  }


  /**
   * Verify that configured directories exist, then
   * Interactively confirm that formatting is desired 
   * for each existing directory and format them.
   * 
   * @param conf
   * @param isConfirmationNeeded
   * @return true if formatting was aborted, false otherwise
   * @throws IOException
   */
  private static boolean format(Configuration conf,
                                boolean isConfirmationNeeded
                                ) throws IOException {
    Collection<File> dirsToFormat = FSNamesystem.getNamespaceDirs(conf);
    Collection<File> editDirsToFormat = 
                 FSNamesystem.getNamespaceEditsDirs(conf);
    for(Iterator<File> it = dirsToFormat.iterator(); it.hasNext();) {
      File curDir = it.next();
      if (!curDir.exists())
        continue;
      if (isConfirmationNeeded) {
        System.err.print("Re-format filesystem in " + curDir +" ? (Y or N) ");
        if (!(System.in.read() == 'Y')) {
          System.err.println("Format aborted in "+ curDir);
          return true;
        }
        while(System.in.read() != '\n'); // discard the enter-key
      }
    }

    FSNamesystem nsys = new FSNamesystem(new FSImage(dirsToFormat,
                                         editDirsToFormat), conf);
    
    nsys.dir.fsImage.format();
    return false;
  }

  private static boolean finalize(Configuration conf,
                               boolean isConfirmationNeeded
                               ) throws IOException {
    Collection<File> dirsToFormat = FSNamesystem.getNamespaceDirs(conf);
    Collection<File> editDirsToFormat = 
                               FSNamesystem.getNamespaceEditsDirs(conf);
    FSNamesystem nsys = new FSNamesystem(new FSImage(dirsToFormat,
                                         editDirsToFormat), conf);
    System.err.print(
        "\"finalize\" will remove the previous state of the files system.\n"
        + "Recent upgrade will become permanent.\n"
        + "Rollback option will not be available anymore.\n");
    if (isConfirmationNeeded) {
      System.err.print("Finalize filesystem state ? (Y or N) ");
      if (!(System.in.read() == 'Y')) {
        System.err.println("Finalize aborted.");
        return true;
      }
      while(System.in.read() != '\n'); // discard the enter-key
    }
    nsys.dir.fsImage.finalizeUpgrade();
    return false;
  }

  private static void printUsage() {
    System.err.println(
      "Usage: java NameNode [" +
      StartupOption.FORMAT.getName() + "] | [" +
      StartupOption.UPGRADE.getName() + "] | [" +
      StartupOption.ROLLBACK.getName() + "] | [" +
      StartupOption.FINALIZE.getName() + "] | [" +
      StartupOption.IMPORT.getName() + "]");
  }

  private static StartupOption parseArguments(String args[], 
                                              Configuration conf) {
    int argsLen = (args == null) ? 0 : args.length;
    StartupOption startOpt = StartupOption.REGULAR;
    for(int i=0; i < argsLen; i++) {
      String cmd = args[i];
      if (StartupOption.FORMAT.getName().equalsIgnoreCase(cmd)) {
        startOpt = StartupOption.FORMAT;
      } else if (StartupOption.REGULAR.getName().equalsIgnoreCase(cmd)) {
        startOpt = StartupOption.REGULAR;
      } else if (StartupOption.UPGRADE.getName().equalsIgnoreCase(cmd)) {
        startOpt = StartupOption.UPGRADE;
      } else if (StartupOption.ROLLBACK.getName().equalsIgnoreCase(cmd)) {
        startOpt = StartupOption.ROLLBACK;
      } else if (StartupOption.FINALIZE.getName().equalsIgnoreCase(cmd)) {
        startOpt = StartupOption.FINALIZE;
      } else if (StartupOption.IMPORT.getName().equalsIgnoreCase(cmd)) {
        startOpt = StartupOption.IMPORT;
      } else
        return null;
    }
    setStartupOption(conf, startOpt);
    return startOpt;
  }

  private static void setStartupOption(Configuration conf, StartupOption opt) {
    conf.set("dfs.namenode.startup", opt.toString());
  }

  static StartupOption getStartupOption(Configuration conf) {
    return StartupOption.valueOf(conf.get("dfs.namenode.startup",
                                          StartupOption.REGULAR.toString()));
  }

  public static NameNode createNameNode(String argv[], 
                                 Configuration conf) throws IOException {
    if (conf == null)
      conf = new Configuration();
    StartupOption startOpt = parseArguments(argv, conf);
    if (startOpt == null) {
      printUsage();
      return null;
    }

    switch (startOpt) {
      case FORMAT:
        boolean aborted = format(conf, true);
        System.exit(aborted ? 1 : 0);
      case FINALIZE:
        aborted = finalize(conf, true);
        System.exit(aborted ? 1 : 0);
      default:
    }

    NameNode namenode = new NameNode(conf);
    return namenode;
  }
    
  /**
   */
  public static void main(String argv[]) throws Exception {
    try {
    	String shimid="";
    	String upRightConfigFile="";
    	if(argv.length>0){
    		LinkedList<String> ll = new LinkedList<String>();
    		for(int i=0; i < argv.length; i++){
    			String arg = argv[i];
    			if(arg.equalsIgnoreCase("-helper")){
    				System.out.println("Helper NameNode");
    				isHelper = true;
    			} else if (arg.equalsIgnoreCase("-shimid")){
    				shimid = argv[i+1];
    				i++;
    			} else if (arg.equalsIgnoreCase("-UpRightConfig")){
    				upRightConfigFile = argv[i+1];
    				i++;
    			} else {
    				if(arg!=null)
    					ll.add(arg);
    			}
    		}
    		argv = ll.toArray(new String[0]);
    	}
    	System.setProperty("UpRightShimID", shimid);
    	System.setProperty("UpRightConfigFile", upRightConfigFile);

      StringUtils.startupShutdownMessage(NameNode.class, argv, LOG);
      NameNode namenode = createNameNode(argv, null);
      if (namenode != null)
        namenode.join();
    } catch (Throwable e) {
      LOG.error(StringUtils.stringifyException(e));
      System.exit(-1);
    }
  }

	@Override
	public boolean confirmBlockUpdate(String clientAddr, Block block)
			throws IOException {
		return namesystem.confirmBlockUpdate(clientAddr, block);
	}

	
}
