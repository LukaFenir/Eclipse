/*
 *	DSched.java -- disk I/O scheduler code.
 *	<<<INSERT YOUR NAME AND EMAIL HERE>>>
 */

/*
 *	FIXME: this implements a FIFO (first in, first out) request queue.
 *	requests are collected in the "readqueue" array, which acts as a circular
 *	buffer, with "readqueue_head" pointing at the next empty slot, "readqueue_tail"
 *	pointing at the oldest request slot, and "readqueue_size" indicating how many
 *	entries are in the buffer.
 *
 *	This is *not* an efficient way of doing disk scheduling because the time taken
 *	to move the read heads between tracks is significant.
 *
 *	Three methods are defined in the disk simulator code which you can call in addition
 *	to the ones already used:
 *
 *		int DiskSim.block_to_head (int blk)
 *		int DiskSim.block_to_track (int blk)
 *		int DiskSim.block_to_sector (int blk)
 *
 *	Which return respectively the head, track and sector for a particular block.
 */

/*
 *	NOTE: for the Java version, you may *not* use collection classes such as ArrayList here.
 *	Only primitive arrays are allowed.  If you want some sorting, you'll have to do that
 *	yourself.  The reasoning is that code inside an OS does not normally have access to a
 *	rich API of things that Java might normally offer.
 *
 *	If you have queries about what you can/cannot use, please ask on the anonymous Q+A forum,
 *	linked from the module's Moodle page.
 */

public class DSched
{
	/*{{{  private class ReadReq*/
	/*
	 *	local class/structure to hold read requests
	 */
	private class ReadReq
	{
		int blk;		/* block number */
		BRequest req;		/* request info */

		/* constructor: just clear fields */
		public ReadReq ()
		{
			blk = -1;
			req = null;
		}
	}
	/*}}}*/
	
	/*}}}*/

	/*{{{  private class LastNext*/
	/*
	 *	structure to hold last or next requests
	 */
	private class LastNext
	{
		int index;		/* index of request */
		ReadReq lnReq;		/* request info */
		//int fun = 10;

		/* constructor: just clear fields */
		public LastNext ()
		{
			index = 0;
			lnReq = null;
		}
	}
	/*}}}*/

	/* Private state */
	private ReadReq readqueue[];
	private int readqueue_head;
	private int readqueue_tail;
	private int readqueue_size;
	//private ReadReq last;
	//private ReadReq next; Replace with next1
	private LastNext next;
	private LastNext last1;
	int head;
	int track;
	int sector;
	int h;
	int t;
	int s;

	/*{{{  public DSched ()*/
	/*
	 *	Constructor, initialises various local state.
	 */
	public DSched ()
	{
		readqueue = new ReadReq[DiskSim.MAXREQUESTS];
		readqueue_head = 0;
		readqueue_tail = 0;
		readqueue_size = 0;
		//last = null;
		//next = null; replace next with next1
		next = new LastNext();
		last1 = null;

		/* allocate individual ReadReq entries */
		for (int i=0; i<readqueue.length; i++) {
			readqueue[i] = new ReadReq ();
		}
	}
	/*}}}*/

	/*{{{  public void blockread (int blk, BRequest req)*/
	/*
	 *	Called by higher-level code to request that a block is read.
	 */
	public void blockread (int blk, BRequest req)
	{
		/* add the request to the head of the queue */
		readqueue[readqueue_head].blk = blk; ///////
		readqueue[readqueue_head].req = req;

		/* increment head pointer (modulo buffer size) */
		readqueue_head = (readqueue_head + 1) % DiskSim.MAXREQUESTS;
		readqueue_size++;
	}
	/*}}}*/
	/*{{{  public void readcomplete (int blk, BRequest req)*/
	/*
	 *	Called by lower-level (disk) code when a read has completed and the next
	 *	read can be dispatched.  If "blk" < 0 or "req" == null, then this is telling
	 *	us that the disk is idle and ready to accept a new read request.
	 */
	public void readcomplete (int blk, BRequest req)
	{
		if (blk >= 0) {
			/* give the block read back to the high-level system */
			DiskSim.highlevel_didread (blk, req);
		}
		
		if (readqueue_size > 0) {
			/* still got requests to service, dispatch the next block request (at tail) */	
			if(last1 == null){
				next.lnReq = readqueue[readqueue_tail];
				//head = DiskSim.block_to_head(last1.lnReq.blk);
				//track = DiskSim.block_to_track(last1.lnReq.blk);
				//sector = DiskSim.block_to_sector(last1.lnReq.blk);
				System.out.println(head + " " + track + " " + sector);
				for(int a=0; a < readqueue.length; a++) {
					h = DiskSim.block_to_head(readqueue[a].blk);
					t = DiskSim.block_to_track(readqueue[a].blk);
					s = DiskSim.block_to_sector(readqueue[a].blk);	
					if(true) {
						
					}
				}
				System.out.println(readqueue[readqueue_head].blk);
				//next.lnReq = readqueue[readqueue_head];
			} else {			
				//next = readqueue[readqueue_tail]; Revert line 147 to DiskSim.disk_readblock (next.blk, next.req);
				next.lnReq = readqueue[readqueue_tail];
			}
			
			DiskSim.disk_readblock (next.lnReq.blk, next.lnReq.req);
			
			/* increment tail pointer, modulo buffer size */
			readqueue_tail = (readqueue_tail + 1) % DiskSim.MAXREQUESTS;
			readqueue_size--;
			//last1 = next;
		}
	}
	/*}}}*/
}