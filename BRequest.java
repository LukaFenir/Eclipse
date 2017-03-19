/*
 *	BRequest.java -- defines a block request type
 *	Copyright (C) 2012 Fred Barnes, University of Kent.
 */


public class BRequest
{
	public int blk;					/* block number */
	public int time_in, time_read, time_out;	/* time the request went in, time it was sent to the disk and time returned to the system */
	public Object queue;				/* associated block request queue */
	public int hash;				/* data hash */

	public BRequest () /*{{{*/
	{
		blk = -1;
		time_in = 0;
		time_read = 0;
		time_out = 0;
		queue = null;
		hash = 0;
	}
	/*}}}*/
	public BRequest (int blk, Object queue) /*{{{*/
	{
		this.blk = blk;
		this.queue = queue;

		time_in = 0;
		time_read = 0;
		time_out = 0;
		hash = 0;
	}
	/*}}}*/
	public static int make_request_hash (BRequest req) /*{{{*/
	{
		int hash = 0xdeadbeef;

		hash ^= req.queue.hashCode ();
		hash = (hash << 4) ^ (hash >> 4);
		hash ^= req.blk;

		return hash;
	}
	/*}}}*/
}

