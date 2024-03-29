// $Id$

package BFT.order.messages;

import BFT.util.UnsignedTypes;
import BFT.messages.MacArrayMessage;
import BFT.messages.NonDeterminism;
import BFT.messages.Digest;
import BFT.messages.CommandBatch;
import BFT.messages.Entry;
import BFT.order.messages.MessageTags;
import BFT.messages.HistoryDigest;

import BFT.messages.VerifiedMessageBase;

import BFT.Parameters;

/**
 **/
public class ViewChange extends MacArrayMessage{


    /**
       phashes and pphashes are the hashes of the commmandBatch +
       nondeterminism for the respective sequence numbers
     **/
    public  ViewChange(long view, long ccpseq, long scpseq, long pseq, 
		       long ppseq,
		       Digest cphash, Digest scphash, 
		       Digest[] entryhashes, HistoryDigest[] hist,
		       int sendingReplica){
	super(MessageTags.ViewChange,
	      computeSize(cphash,scphash, entryhashes, hist),
	      sendingReplica,
	      Parameters.getOrderCount());

	if (cpSeqNo > pSeqNo || pSeqNo > ppSeqNo)
	    throw new RuntimeException("invalid view change message");
	
	viewNo = view;
	cpSeqNo = ccpseq;
	stableCPSeqNo = scpseq;
	pSeqNo = pseq;
	ppSeqNo = ppseq;
	cpHash = cphash;
	stableCPHash = scphash;
	batchHashes = entryhashes;
	histories = hist;
	if (histories.length != batchHashes.length)
	    throw new RuntimeException("Invalid histories array");
	if (batchHashes.length != ppSeqNo - cpSeqNo)
	    throw new RuntimeException("invalid pphashes array");
	if (scpseq < ccpseq || scpseq > pseq)
	    throw new RuntimeException("invalid stable checkpoint");
	if (pSeqNo < ccpseq - 1)
	    throw new RuntimeException("invalid prepare seqno");
	if (ppSeqNo < pSeqNo)
	    throw new RuntimeException("invalid pp seqno");
	
	int offset = getOffset();
	byte[] bytes = getBytes();
	// place the view number
	byte[] tmp = UnsignedTypes.longToBytes(viewNo);
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];
	
	// place the committed cp sequence number
	tmp = UnsignedTypes.longToBytes(cpSeqNo);
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];

	// place the stable cp sequence number
	tmp = UnsignedTypes.longToBytes(stableCPSeqNo);
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];

	// place the p sequence number
	tmp = UnsignedTypes.longToBytes(pSeqNo);
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];

	// place the pp sequence number
	tmp = UnsignedTypes.longToBytes(ppSeqNo);
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];

	// place the committed cphash
	tmp = cpHash.getBytes();
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];

	// place the stable cphash
	tmp = stableCPHash.getBytes();
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];


	// place the batch hashes
	for (int j = 0; j < batchHashes.length; j++){
	    tmp = batchHashes[j].getBytes();
	    for (int i = 0; i < tmp.length; i++, offset++)
		bytes[offset] = tmp[i];
	}

	// place the histories
	for (int j = 0; j < histories.length; j++){
	    tmp = histories[j].getBytes();
	    for (int i = 0; i < tmp.length; i++, offset++)
		bytes[offset] = tmp[i];
	}


    }

    public ViewChange(byte[] bits){
	super(bits);
	if (getTag() != MessageTags.ViewChange)
	    throw new RuntimeException("invalid message Tag: "+getTag());

	int offset = getOffset();
	byte[] tmp;

	// read the view number;
	tmp = new byte[4];
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bits[offset];
	viewNo = UnsignedTypes.bytesToLong(tmp);

	// read the committedcp sequence number
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bits[offset];
	cpSeqNo = UnsignedTypes.bytesToLong(tmp);

	// read the stable cp sequence number
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bits[offset];
	stableCPSeqNo = UnsignedTypes.bytesToLong(tmp);

	// read the p sequence number
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bits[offset];
	pSeqNo = UnsignedTypes.bytesToLong(tmp);

	// read the pp sequence number
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bits[offset];
	ppSeqNo = UnsignedTypes.bytesToLong(tmp);

	// read the committed cpHash
	tmp = new byte[Digest.size()];
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bits[offset];
	cpHash = Digest.fromBytes(tmp);

	// read the stable cphash
	tmp = new byte[Digest.size()];
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bits[offset];
	stableCPHash = Digest.fromBytes(tmp);


	// read the Batch Hashes
	batchHashes = new Digest[(int)(ppSeqNo-cpSeqNo)];
	for (int j = 0; j < batchHashes.length; j++){
	    tmp = new byte[Digest.size()];
	    for(int i = 0; i < tmp.length; i++, offset++)
		tmp[i] = bits[offset];
	    batchHashes[j] = Digest.fromBytes(tmp);
	}
	
	// read the histories hashes
	histories = new HistoryDigest[batchHashes.length];
	for (int j = 0; j < histories.length; j++){
	    tmp = new byte[Digest.size()];
	    for(int i = 0; i < tmp.length; i++, offset++)
		tmp[i] = bits[offset];
	    histories[j] = HistoryDigest.fromBytes(tmp);
	}
	
	// need error checking to assert that cp <= p <= pp
	// offset is at the end of the message
	
	if (offset != getBytes().length - getAuthenticationSize())
	    throw new RuntimeException("Invalid byte input");
	if (cpSeqNo > pSeqNo || pSeqNo > ppSeqNo)
	    throw new RuntimeException("Invalid view change");
	
    }

    protected long viewNo;
    public long getView(){
	return viewNo;
    }

    protected long cpSeqNo;
    public long getCommittedCPSeqNo(){
	return cpSeqNo;
    }
    
    protected long stableCPSeqNo;
    public long getStableCPSeqNo(){
	return stableCPSeqNo;
    }
    
    
    protected long pSeqNo;
    public long getPSeqNo(){
	return pSeqNo;
    }

    protected long ppSeqNo;
    public long getPPSeqNo(){
	return ppSeqNo;
    }

    protected Digest cpHash;
    public Digest getCommittedCPHash(){
	return cpHash;
    }
    
    protected Digest stableCPHash;
    public Digest getStableCPHash(){
	return stableCPHash;
    }

    protected Digest[] batchHashes;
    public Digest[] getBatchHashes(){
	return batchHashes;
    }
    
    protected HistoryDigest[] histories;
    public HistoryDigest[] getHistories(){
	return histories;
    }


    public long getSendingReplica(){
	return getSender();
    }

    public boolean equals(ViewChange nb){
	boolean res = super.equals(nb) && viewNo == nb.viewNo && 
	    cpSeqNo == nb.cpSeqNo && pSeqNo == nb.pSeqNo &&
	    ppSeqNo == nb.ppSeqNo && cpHash.equals(nb.cpHash);

	for(int i = 0; i < batchHashes.length; i++)
	    res = res && batchHashes[i].equals(nb.batchHashes[i]);
	
	return res;
    }

    /**
       returns the index of the largest sequence number such that vc
       and this are compatible with each other
     **/
    public long maxCompatibleSequenceNumber(ViewChange vc){
	long base = vc.getCommittedCPSeqNo();
	long myBase = this.getCommittedCPSeqNo();
	// if base > mybase, then flip sides
	if (base > myBase)
	    return vc.maxCompatibleSequenceNumber(this);
	// if vc.pp < mybase, then mybase-1 is max compatible
	if (vc.getPPSeqNo() < getCommittedCPSeqNo())
	    return myBase -1;

	// invariant:  mybase >= base
	// invariant:  mybase <= vc.pp
	int offset = (int) (myBase - base);
	long result = myBase-1;
	for (int i = 0;
	     i < batchHashes.length &&
		 offset < vc.getBatchHashes().length ;
	     i++, offset++){
	    if (batchHashes[i].equals(vc.getBatchHashes()[offset]) &&
		histories[i].equals(vc.getHistories()[offset]))
		result++;
	}
	return result;
    }
	
    /**
     * returns true if vc contains a cp that is consistent with the
     * local committed cp
     **/
    public boolean matchesCommittedCP(ViewChange vc){
	return (vc.getCommittedCPSeqNo() == getCommittedCPSeqNo()
		&& vc.getCommittedCPHash().equals(getCommittedCPHash()))
	    || (vc.getStableCPSeqNo() == getCommittedCPSeqNo()
		&& vc.getStableCPHash().equals(getCommittedCPHash()));
    }
    
    /** computes the size of the bits specific to ViewChange **/
    private static int computeSize(Digest d1, Digest d4, 
				   Digest[] d3,
				   HistoryDigest[] d5){
	int size =  MessageTags.uint32Size + MessageTags.uint32Size +
	    MessageTags.uint32Size + MessageTags.uint32Size + 
	    MessageTags.uint32Size +Digest.size() + Digest.size();
	for(int i = 0; i < d3.length; i++)
	    size += Digest.size();
	for (int i = 0; i< d5.length; i++)
	    size+= HistoryDigest.size();
	return size;
    }


    public String toString(){
	return "<VC, "+super.toString()+", v:"+viewNo+", cpseq:"+cpSeqNo+
	    ", pSeq:"+pSeqNo+", ppSeq:"+ppSeqNo+
	    ", \n\t\tcpHash    : "+cpHash+
	    ", \n\t\tstableHash: "+stableCPHash+", \n\t\tbatchlength:"+
	    batchHashes.length+">";
    }

    public static void main(String args[]){
	String stmp = "what are we doing today";
	HistoryDigest d = new HistoryDigest(stmp.getBytes());
	Entry[] entries = new Entry[1];
	entries[0] = new Entry(234, 22623, d.getBytes());
	CommandBatch cb = new CommandBatch(entries);
	NonDeterminism nondet = new NonDeterminism(43,42);
	Digest[] pHash = new Digest[2];
	for (int i = 0; i < pHash.length; i++)
	    pHash[i] = d;
	HistoryDigest[] ppHash = new HistoryDigest[4];
	ppHash[0] = d;
	for (int i = 1; i < ppHash.length; i++)
	    ppHash[i] = new HistoryDigest(ppHash[i-1], cb, nondet);

	ViewChange vmb = 
	    new ViewChange(1, 2, 2, 4, 8,
			   d, d, ppHash, new HistoryDigest[0],
			   234);
	//System.out.println("initial: "+vmb.toString());
	UnsignedTypes.printBytes(vmb.getBytes());
	ViewChange vmb2 = 
	    new ViewChange(vmb.getBytes());
	//System.out.println("\nsecondary: "+vmb2.toString());
	UnsignedTypes.printBytes(vmb2.getBytes());
	
	vmb = new ViewChange(134,22,22,24,28, d,d, ppHash,new HistoryDigest[0], 235);
	//System.out.println("\ninitial: "+vmb.toString());
	UnsignedTypes.printBytes(vmb.getBytes());
	vmb2 = new ViewChange(vmb.getBytes());
	//System.out.println("\nsecondary: "+vmb2.toString());
	UnsignedTypes.printBytes(vmb2.getBytes());
	
	//System.out.println("\nold = new: "+vmb.equals(vmb2));
    }
    
}