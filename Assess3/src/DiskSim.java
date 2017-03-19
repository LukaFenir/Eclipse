/*
 *	DiskSim.java -- simulator framework.
 *	Copyright (C) 2012-2014 Fred Barnes, University of Kent.
 */

import java.util.*;
import java.io.*;


public class DiskSim
{
	/* global constants */

	/* defines (approximately) a 4 GB disk */
	public static final int NHEADS = 16;
	public static final int NSECTORSPERTRACK = 64;
	public static final int NTRACKS = 8192;

	public static final int NBLOCKS = (NHEADS * NTRACKS * NSECTORSPERTRACK);

	public static final int HEADMAXVEL = 32;

	/* Note: the mapping for logical-block -> physical-chs:
	 *
	 *         22 19    18  6     5    0
	 *	[  head  |  track  |  sector  ]
	 *
	 * wierd time-units mean it'll take time to go from one sector to the next.
	 */

	/* maximum number of requests queues, and the number of concurrent
	 * requests that each queue tries to service */
	public static final int MAXQUEUES = 64;
	public static final int MAXCONCUR = 4;
	public static final int MAXREQUESTS = (MAXQUEUES * MAXCONCUR);

	/*{{{  private static class DiskState*/
	/*
	 *	defines the state of the (virtual) disk.
	 */
	private static class DiskState
	{
		public static final int STATE_IDLE = 0;
		public static final int STATE_SEEKING = 1;
		public static final int STATE_READING = 2;

		public int state;			/* disk state (STATE_...) */
		public int cur_track, cur_sector;	/* current track and sector */
		public int cur_headvel;			/* current head velocity (-HEADMAXVEL .. 0 .. HEADMAXVEL) */

		public int target_track, target_sector;	/* target track and sector */
		public int target_blk;			/* target block number */
		public BRequest target_req;		/* target request */

		public int laststate_time;		/* time of last state change (and commit into counters below) */
		public int idle_time;			/* time-steps in idle state */
		public int seek_time;			/* time-steps in seeking state */
		public int read_time;			/* time-steps in reading state */
		public int tblocks;			/* total number of blocks read */

		public DiskState () /*{{{*/
		{
			state = STATE_IDLE;
			cur_track = 0;
			cur_sector = -1;
			cur_headvel = 0;
			target_track = 0;
			target_sector = 0;
			target_blk = -1;
			target_req = null;

			laststate_time = 0;
			idle_time = 0;
			seek_time = 0;
			read_time = 0;
			tblocks = 0;
		}
		/*}}}*/
		public void commit (int timenow) /*{{{*/
		{
			/* note: this commits a change in disk state to teh various counters herein: gathers stats */
			int toadd = timenow - laststate_time;

			if (toadd < 0) {
				System.err.println ("dstate_commit(): last commit in the future!");
				return;
			}
			laststate_time = timenow;
			switch (state) {
			case STATE_IDLE:	idle_time += toadd;	break;
			case STATE_SEEKING:	seek_time += toadd;	break;
			case STATE_READING:	read_time += toadd;	break;
			}
		}
		/*}}}*/
		public void set_seeking (int blk, BRequest req) /*{{{*/
		{
			target_track = block_to_track (blk);
			target_sector = block_to_sector (blk);
			target_blk = blk;
			target_req = req;
			state = STATE_SEEKING;
		}
		/*}}}*/
	}
	/*}}}*/
	/*{{{  private static class BRQueue*/
	/*
	 *	defines a single read-queue.
	 */
	private static class BRQueue
	{
		public int id;				/* identifier (index into 'brqueues') */
		public int stime;			/* start time */
		public int nblks;			/* number of blocks */
		public int blocks[];			/* array of block numbers (scrubbed as they come in) */
		public int nblk;			/* next block in queue */
		public int left;			/* number of blocks left */

		public int is_dynamic;			/* if non-zero, dynamically constructed */
	}
	/*}}}*/
	/*{{{  private static class TQueue*/
	/*
	 *	timer queue for various simulation events.
	 */
	private static class TQueue
	{
		public static final int TQ_INVALID = 0;
		public static final int TQ_READBLOCK = 1;
		public static final int TQ_READREADY = 2;
		public static final int TQ_BRQSTART = 3;
		public static final int TQ_BRQDONE = 4;
		public static final int TQ_DISKIDLE = 5;
		public static final int TQ_DISKSEEK = 6;
		public static final int TQ_DISKREAD = 7;

		public int type;
		public int time;

		/* for READBLOCK, READREADY */
		public int read_blk;
		public BRequest read_req;

		/* for BRQSTART, BRQDONE */
		public BRQueue queue;

		public TQueue () /*{{{*/
		{
			type = TQ_INVALID;
			time = 0;
		}
		/*}}}*/
		public TQueue (int type, int time) /*{{{*/
		{
			this.type = type;
			this.time = time;
		}
		/*}}}*/
		public TQueue (int type, int time, BRQueue brq) /*{{{*/
		{
			this.type = type;
			this.time = time;
			this.queue = brq;
		}
		/*}}}*/
		public TQueue (int type, int time, int blk, BRequest brr) /*{{{*/
		{
			this.type = type;
			this.time = time;
			this.read_blk = blk;
			this.read_req = brr;
		}
		/*}}}*/
	}
	/*}}}*/
	/*{{{  private static class RStats*/
	/*
	 *	simple structure for stats reporting
	 */
	private static class RStats
	{
		public int inq_min, inq_max;			/* time in request queue (min, max) */
		public int inr_min, inr_max;			/* time in read queue (min, max) */
		public int int_min, int_max;			/* time in total (min, max) */

		public double inq_avg, inq_dev;			/* time in request queue (avg, std-dev) */
		public double inr_avg, inr_dev;			/* time in read queue (avg, std-dev) */
		public double int_avg, int_dev;			/* time in total (avg, std-dev) */
	}
	/*}}}*/
	/*{{{  private static class PStats*/
	/*
	 *	simple structure for profile stats reporting
	 */
	private static class PStats
	{
		int scores[];
		int scount;

		public PStats ()
		{
			scores = new int[DiskSim.PROF_PERPQ];
			scount = 0;
		}
	}
	/*}}}*/


	private static DiskState dstate;

	private static int verbose = 0;				/* non-zero if being verbose */
	private static int visualise = 0;			/* non-zero if visualisation enabled */
	private static int dynamic = 0;				/* if dynamic, number of queues */
	private static int dhistdata = 0;			/* non-zero if dynamic and dumping histogram data (for "feedgnuplot") */
	private static int profile = 0;				/* non-zero if doing profiling across dynamic range */
	private static int prof_period = 0;			/* counts profiling reports */
	private static Random drand = null;			/* if dynamic, a random number generator */
	private static PStats pss[] = null;			/* if profiling, array of pstats data [0, 1..MAXQUEUES]. */

	private static final int PROF_PERPQ = 128;		/* how many profile reports we collect for each dynamic bucket */
	private static final int PROF_PERPQ_SHIFT = 7;		/* shift amount */

	private static int timenow = 0;				/* simulation time now */
	private static ArrayList<TQueue> simqueue;

	private static int errcount = 0;
	private static int time_inqueue[];			/* if in dynamic mode, these arrays are a fixed window */
	private static int time_inread[];
	private static int time_total[];
	private static int blockcounter = 0;

	private static final int DYN_BLOCKHISTORY = 4096;	/* must be power-of-two */
	private static final int DYN_BLOCKREPORT = 1024;	/* must be power-of-two */

	private static ArrayList<BRQueue> brqueues;		/* various request queues (at most MAXQUEUES) */
	private static int brqueue_active = 0;			/* number of currently active queues */

	private static final int READ_TIMEUNITS = 3;
	private static final int READ_FASTTIMEUNITS = 1;
	private static DSched dsched;

	private static final int RMAX = 72;
	private static final int RFACTOR = (NTRACKS / RMAX) + 1;

	/*{{{  public static int block_to_track (int blk)*/
	/*
	 *	returns the track associated with particular block.
	 */
	public static int block_to_track (int blk)
	{
		/* XXX: this relies on the layout described above */
		return (blk >> 6) & 0x1fff;			/* bits 6 to 18 */

		/* equivalent calculation is: ((blk / NSECTORSPERTRACK) % NTRACKS) */
	}
	/*}}}*/
	/*{{{  public static int block_to_sector (int blk)*/
	/*
	 *	returns the sector associated with a particular block.
	 */
	public static int block_to_sector (int blk)
	{
		/* XXX: this relies on the layout described above */
		return (blk & 0x3f);				/* bits 0-5 */

		/* equivalent calculation is: ((blk / NHEADS) % NSECTORSPERTRACK) */
	}
	/*}}}*/
	/*{{{  public static int block_to_head (int blk)*/
	/*
	 *	returns the head associated with a particular block.
	 */
	public static int block_to_head (int blk)
	{
		/* XXX: this relies on the layout described above */
		return (blk >> 19) & 0x0f;			/* bits 19-22 */

		/* equivalent calculation is: (blk / (NTRACKS * NSECTORSPERTRACK)) */
	}
	/*}}}*/
	/*{{{  private static void tqueue_insert (TQueue tq)*/
	/*
	 *	inserts something into the timer queue
	 */
	private static void tqueue_insert (TQueue tq)
	{
		int idx;

		for (idx = 0; (idx < simqueue.size ()) && (simqueue.get(idx).time <= tq.time); idx++);
		simqueue.add (idx, tq);
	}
	/*}}}*/
	/*{{{  private static tqueue_removeentries (int type)*/
	/*
	 *	removes matching entries from the timer queue.
	 */
	private static void tqueue_removeentries (int type)
	{
		for (int i=0; i<simqueue.size(); i++) {
			if (simqueue.get(i).type == type) {
				simqueue.remove (i);
			}
		}
	}
	/*}}}*/

	/*{{{  private static RStats make_request_stats (int count)*/
	/*
	 *	turns stuff in history buffer into RStats.
	 */
	private static RStats make_request_stats (int count)
	{
		RStats r = new RStats ();

		double inq_tot = 0.0;
		double inr_tot = 0.0;
		double int_tot = 0.0;
		
		r.inq_min = time_inqueue[0];
		r.inq_max = time_inqueue[0];
		r.inr_min = time_inread[0];
		r.inr_max = time_inread[0];
		r.int_min = time_total[0];
		r.int_max = time_total[0];

		for (int i=0; i<count; i++) {
			inq_tot += (double)time_inqueue[i];
			inr_tot += (double)time_inread[i];
			int_tot += (double)time_total[i];

			if (time_inqueue[i] < r.inq_min) {
				r.inq_min = time_inqueue[i];
			} else if (time_inqueue[i] > r.inq_max) {
				r.inq_max = time_inqueue[i];
			}
			if (time_inread[i] < r.inr_min) {
				r.inr_min = time_inread[i];
			} else if (time_inread[i] > r.inr_max) {
				r.inr_max = time_inread[i];
			}
			if (time_total[i] < r.int_min) {
				r.int_min = time_total[i];
			} else if (time_total[i] > r.int_max) {
				r.int_max = time_total[i];
			}
		}

		r.inq_avg = inq_tot / (double)count;
		r.inr_avg = inr_tot / (double)count;
		r.int_avg = int_tot / (double)count;

		r.inq_dev = 0.0;
		r.inr_dev = 0.0;
		r.int_dev = 0.0;

		for (int i=0; i<count; i++) {
			r.inq_dev += ((double)time_inqueue[i] - r.inq_avg) * ((double)time_inqueue[i] - r.inq_avg);
			r.inr_dev += ((double)time_inread[i] - r.inr_avg) * ((double)time_inread[i] - r.inr_avg);
			r.int_dev += ((double)time_total[i] - r.int_avg) * ((double)time_total[i] - r.int_avg);
		}
		r.inq_dev /= (double)(count - 1);
		r.inr_dev /= (double)(count - 1);
		r.int_dev /= (double)(count - 1);

		r.inq_dev = Math.sqrt (r.inq_dev);
		r.inr_dev = Math.sqrt (r.inr_dev);
		r.int_dev = Math.sqrt (r.int_dev);

		return r;
	}
	/*}}}*/
	/*{{{  private static int retire_request (BRequest req)*/
	/*
	 *	retires a block request, returns 0 on success, non-zero on failure.
	 */
	private static int retire_request (BRequest req)
	{
		time_inqueue[blockcounter] = (req.time_read - req.time_in);
		time_inread[blockcounter] = (req.time_out - req.time_read);
		time_total[blockcounter] = (req.time_out - req.time_in);
		blockcounter++;

		if (dynamic > 0) {
			blockcounter &= (DYN_BLOCKHISTORY - 1);
			if ((blockcounter & (DYN_BLOCKREPORT - 1)) == 0) {
				int wtime = 100;

				if (dhistdata > 0) {
					/* dump out the raw buffer data so "feedgnuplot" can generate a histogram of service (total) times :) */
					int i;

					System.out.println ("clear");
					for (i=0; i<DYN_BLOCKHISTORY; i++) {
						System.out.println ("" + time_total[i]);
					}
					System.out.println ("replot");
					wtime = 1000;
				} else if (profile > 0) {
					/* doing this in profile mode */
					RStats st = make_request_stats (DYN_BLOCKHISTORY);
					int ptime;

					wtime = 0;
					prof_period++;
					ptime = prof_period >> PROF_PERPQ_SHIFT;

					if (ptime == 0) {
						/* warmup! */
					} else if (ptime > MAXQUEUES) {
						/* all done! -- force exit else we never terminate.. */
						System.exit (0);
					} else {
						/* fill up particular stats bucket */
						if (pss[ptime].scount >= PROF_PERPQ) {
							System.err.println ("error: confused when profiling in " + ptime + ", scount=" +
									pss[ptime].scount + " but PROF_PERPQ=" + PROF_PERPQ);
							System.exit (1);
						}

						pss[ptime].scores[pss[ptime].scount++] = (int)(st.int_avg + st.int_dev);

						if (pss[ptime].scount == PROF_PERPQ) {
							/* last one in this bucket, process! */
							int i, total = 0;
							int min = pss[ptime].scores[0];
							int max = min;

							for (i=0; i<PROF_PERPQ; i++) {
								int s = pss[ptime].scores[i];

								total += s;
								if (s < min) {
									min = s;
								} else if (s > max) {
									max = s;
								}
							}
							total >>= PROF_PERPQ_SHIFT;

							System.out.format ("%d %d %d %d\n", ptime, total, min, max);

							/* and here: setup the next one (if we need it) */
							if (ptime < MAXQUEUES) {
								int nextbrq = ptime;
								BRQueue brq = new BRQueue ();
								TQueue tq;

								brq.id = nextbrq;
								brq.is_dynamic = 1;
								brq.stime = timenow + 1;
								brqueues.add (brq.id, brq);

								init_dynamic_bqueue (brq);
								tq = new TQueue (TQueue.TQ_BRQSTART, brq.stime, brq);

								tqueue_insert (tq);
							}
						}
					}
				} else {
					/* do dynamic report */
					RStats st = make_request_stats (DYN_BLOCKHISTORY);

					System.out.format ("bc: %-10d  dt(I: %-10d  S: %-10d  R: %-10d)  st(M: %-5d  X: %-5d  A: %-5.1f  sd: %-5.1f) score: %-6d   \r",
							dstate.tblocks, dstate.idle_time, dstate.seek_time, dstate.read_time,
							st.int_min, st.int_max, st.int_avg, st.int_dev, (int)(st.int_avg + st.int_dev));
				}
				/* deliberately slow things down..! */
				if ((visualise == 0) && (wtime > 0)) {
					try {
						synchronized (drand) {
							drand.wait (wtime);
						}
					} catch (Exception e) {
					}
				}
			}
		}

		return 0;
	}
	/*}}}*/


	/*{{{  private static int readfile (String fname)*/
	/*
	 *	reads the request file, parses and adds BRQSTART events to the timer queue.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int readfile (String fname)
	{
		try {
			BufferedReader reader = new BufferedReader (new FileReader (fname));
			String s;
			int lineno = 1;
			int nrqs = 0;

			for (s = reader.readLine (); s != null; s = reader.readLine (), lineno++) {
				String bits[] = s.split ("\\s");

				if (bits.length == 0) {
					continue;
				}
				bits[0] = bits[0].trim ();
				if (bits[0].length () == 0) {
					/* empty line */
					continue;
				}
				if (bits[0].charAt (0) == '#') {
					/* comment line */
					continue;
				}

				try {
					BRQueue brq = new BRQueue ();
					TQueue tq;
					
					brq.stime = new Integer (bits[0]);
					brq.nblks = new Integer (bits[1]);
					brq.nblk = 0;
					brq.blocks = new int[brq.nblks];
					brq.is_dynamic = 0;
					
					int boff = 2;
					for (int i=0; i<brq.nblks; i++) {
						if ((i + boff) == bits.length) {
							/* assume continued on next line.. */
							s = reader.readLine ();
							if (s != null) {
								bits = s.split ("\\s");
								boff = -i + 1;
							}
						}
						brq.blocks[i] = new Integer (bits[i+boff]);
					}

					brq.left = brq.nblks;
					brq.id = brqueues.size ();

					if (nrqs < MAXQUEUES) {
						brqueues.add (brq.id, brq);
						tq = new TQueue (TQueue.TQ_BRQSTART, brq.stime, brq);
						tqueue_insert (tq);

						nrqs++;
					} else {
						System.err.println ("warning: discarding queue at line " + lineno +
							" (MAXQUEUES = " + MAXQUEUES + ")");
					}
				} catch (Exception e) {
					System.err.println ("error in " + fname + " line " + lineno + ": " + e);
					return -1;
				}
			}
			reader.close ();
		} catch (IOException e) {
			System.err.println ("failed to open " + fname + ": " + e.toString ());
			return -1;
		}

		return 0;
	}
	/*}}}*/
	/*{{{  private static int init_dynamic ()*/
	/*
	 *	initialises dynamic request queues.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int init_dynamic ()
	{
		int i;

		drand = new Random ();

		for (i=0; i<dynamic; i++) {
			BRQueue brq = new BRQueue ();
			TQueue tq;

			brq.id = i;
			brq.is_dynamic = 1;
			brqueues.add (brq.id, brq);

			init_dynamic_bqueue (brq);
			tq = new TQueue (TQueue.TQ_BRQSTART, brq.stime, brq);

			tqueue_insert (tq);
		}

		if (profile > 0) {
			/* create some profile structures */
			pss = new PStats[MAXQUEUES + 1];
			pss[0] = null;
			for (i=1; i<=MAXQUEUES; i++) {
				pss[i] = new PStats ();
			}
		}

		return 0;
	}
	/*}}}*/
	/*{{{  private static int init_dynamic_bqueue (BRQueue brq)*/
	/*
	 *	initialises dynamic request queue (single one).
	 *	uses current time as a basis for population and random behaviour.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int init_dynamic_bqueue (BRQueue brq)
	{
		// System.err.println ("init_dynamic_bqueue(): for queue " + brq.id + "");

		int tkey = drand.nextInt (32);		/* 0-31 */

		/* generate a random request queue behaviour based on 'tkey' */
		switch (tkey) {
		case 0: /*{{{  disk FS task: regular access pattern across all heads*/
		{
			int i;

			brq.nblks = NHEADS * 8;
			brq.blocks = new int[brq.nblks];
			brq.nblk = 0;

			for (i=0; i<8; i++) {
				int rtrk = drand.nextInt (NTRACKS);
				int rsec = drand.nextInt (NSECTORSPERTRACK);
				int j;

				for (j=0; j<NHEADS; j++) {
					brq.blocks[brq.nblk] = (j * (NTRACKS * NSECTORSPERTRACK)) + (rtrk * NSECTORSPERTRACK) + rsec;
					brq.nblk++;
				}
			}

			if (brq.nblk != brq.nblks) {
				System.err.println ("init_dynamic_bqueue(0): argh!");
				System.exit (1);
			}
			/* start at a random point up to 400 units in the future */
			brq.stime = timenow + (drand.nextInt (300) + 100);
			brq.nblk = 0;
			brq.left = brq.nblks;

			break;
		}	/*}}}*/
		case 1: case 2: /*{{{  small sequential reads*/
		{
			int i, start;

			brq.nblks = drand.nextInt (32) + 16;
			brq.blocks = new int[brq.nblks];

			start = drand.nextInt (NBLOCKS - (brq.nblks + 1));

			for (i=0; i<brq.nblks; i++) {
				brq.blocks[i] = start + i;
			}

			/* start at a random point up to 150 units in the future */
			brq.stime = timenow + (drand.nextInt (100) + 50);
			brq.nblk = 0;
			brq.left = brq.nblks;

			break;
		}	/*}}}*/
		case 3: /*{{{  large chunks of disk*/
		{
			int i, start;
			int frags;

			brq.nblks = drand.nextInt (128) + 1024;
			brq.blocks = new int[brq.nblks];

			start = drand.nextInt (NBLOCKS - ((brq.nblks + 1) * 4));

			for (i=0; i<brq.nblks; i++) {
				brq.blocks[i] = start + (i*4) + drand.nextInt (4);
			}

			/* schedule for some distance in the future */
			brq.stime = timenow + (drand.nextInt (200) + 400);
			brq.nblk = 0;
			brq.left = brq.nblks;

			break;
		}	/*}}}*/
		case 4: /*{{{  scatter-gun random*/
		{
			int i;

			brq.nblks = drand.nextInt (16) + 8;
			brq.blocks = new int[brq.nblks];

			for (i=0; i<brq.nblks; i++) {
				brq.blocks[i] = drand.nextInt (NBLOCKS);
			}

			/* start almost immediately */
			brq.stime = timenow + 5;
			brq.nblk = 0;
			brq.left = brq.nblks;

			break;
		}	/*}}}*/
		default: /*{{{  everything else: assorted randomly bunched accesses*/
		{	int spread = drand.nextInt (8192) + 1024;
			int start = drand.nextInt (NBLOCKS - spread);
			int ngrps = drand.nextInt (16) + 8;
			int gsize[] = new int[ngrps];
			int i;

			brq.nblks = 0;
			for (i=0; i<ngrps; i++) {
				gsize[i] = drand.nextInt (30) + 2;
				brq.nblks += gsize[i];
			}
			brq.blocks = new int[brq.nblks];
			brq.nblk = 0;
			for (i=0; i<ngrps; i++) {
				int j;

				for (j=0; j<gsize[i]; j++) {
					brq.blocks[brq.nblk] = start + drand.nextInt (spread);
					brq.nblk++;
				}
			}

			if (brq.nblk != brq.nblks) {
				System.err.println ("init_dynamic_bqueue(0): argh!");
				System.exit (1);
			}
			/* start at a random point up to 200 units in the future */
			brq.stime = timenow + (drand.nextInt (200) + 1);
			brq.nblk = 0;
			brq.left = brq.nblks;

			break;
		}	/*}}}*/
		}
		return 0;
	}
	/*}}}*/


	/*{{{  public static void highlevel_didread (int blk, BRequest req)*/
	/*
	 *	called by student code when a read request has completed.
	 */
	public static void highlevel_didread (int blk, BRequest req)
	{
		BRQueue brq;
		int i;

		if ((blk < 0) || (req == null)) {
			System.err.println ("highlevel_didread(): called with bad block (" + blk + ") at time " + timenow);
			errcount++;
			return;
		}
		brq = (BRQueue)req.queue;
		if ((brq == null) || (brqueues.get(brq.id) != brq)) {
			System.err.println ("highlevel_didread(): invalid queue!");
			errcount++;
			return;
		}
		if (brq.left == 0) {
			System.err.println ("highlevel_didread(): read queue " + brq.id + " already done at time " + timenow + " (block " + blk + ")");
			errcount++;
			return;
		}

		/* expect this to be a valid block between the start and nblk */
		for (i=0; i<brq.nblk; i++) {
			if (brq.blocks[i] == blk) {
				/* this one */
				brq.blocks[i] = -1;
				break;
			}
		}
		if (i == brq.nblk) {
			System.err.println ("highlevel_didread(): read queue " + brq.id + " got unexpected block " + blk);
			errcount++;
			return;
		}
		if (req.hash != BRequest.make_request_hash (req)) {
			System.err.println ("highlevel_didread(): bad hash for block " + blk + ", expected " +
					BRequest.make_request_hash (req) + " got " + req.hash);
			errcount++;
			return;
		}

		/* successfully read the expected block */
		req.time_out = timenow;
		retire_request (req);
		brq.left--;

		if ((brq.left > 0) && (brq.nblk < brq.nblks)) {
			/* more to go */
			BRequest brr = new BRequest (brq.blocks[brq.nblk], (Object)brq);
			TQueue tq = new TQueue (TQueue.TQ_READBLOCK, timenow + 1, brr.blk, brr);

			brq.nblk++;
			tqueue_insert (tq);
		} else if (brq.left == 0) {
			TQueue tq = new TQueue (TQueue.TQ_BRQDONE, timenow + 1, brq);

			tqueue_insert (tq);
		}
	}
	/*}}}*/
	/*{{{  public static void disk_readblock (int blk, BRequest req)*/
	/*
	 *	called by student scheduler code to read a specific block.
	 */
	public static void disk_readblock (int blk, BRequest req)
	{
		if (dstate.state == dstate.STATE_IDLE) {
			TQueue rdy = new TQueue (TQueue.TQ_DISKSEEK, timenow);

			dstate.commit (timenow);
			dstate.set_seeking (blk, req);
			req.time_read = timenow;
			tqueue_insert (rdy);

			dstate.tblocks++;
		} else {
			System.err.println ("drive busy, cannot read block " + blk + " at this time!");
			errcount++;
		}
	}
	/*}}}*/
	

	/*{{{  private static int brequest_start (BRQueue brq)*/
	/*
	 *	starts a block read request queue.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int brequest_start (BRQueue brq)
	{
		int i;

		/* note: this is only called once for each queue */
		for (i = 0; (i < MAXCONCUR) && (i < brq.nblks); i++) {
			BRequest brr = new BRequest (brq.blocks[i], brq);
			TQueue tq = new TQueue (TQueue.TQ_READBLOCK, timenow + 1, brr.blk, brr);

			tqueue_insert (tq);
		}
		brq.nblk = i;
		brqueue_active++;

		return 0;
	}
	/*}}}*/
	/*{{{  private static int brequest_done (BRQueue brq)*/
	/*
	 *	called when a read request queue has finished.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int brequest_done (BRQueue brq)
	{
		if (brqueue_active == 0) {
			System.err.println ("brequest_done(): no queues active..");
			return -1;
		}
		brqueue_active--;

		if (dynamic > 0) {
			TQueue tq;

			// System.err.println ("brequest_done(): fixme, regenerate dynamic queue..");
			init_dynamic_bqueue (brq);
			tq = new TQueue (TQueue.TQ_BRQSTART, brq.stime, brq);

			tqueue_insert (tq);
		}
		return 0;
	}
	/*}}}*/
	/*{{{  private static int readblock_dispatch (int blk, BRequest brq)*/
	/*
	 *	dispatches a block read request to the scheduler.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int readblock_dispatch (int blk, BRequest brq)
	{
		brq.time_in = timenow;
		dsched.blockread (blk, brq);

		if (dstate.state == dstate.STATE_IDLE) {
			/* drive currently idle, so schedule a new notification */
			TQueue tq = new TQueue (TQueue.TQ_DISKIDLE, timenow);

			tqueue_removeentries (TQueue.TQ_DISKIDLE);
			tqueue_insert (tq);
		}
		return 0;
	}
	/*}}}*/
	/*{{{  private static int readready_dispatch (int blk, BRequest brq)*/
	/*
	 *	dispatches a read ready/complete request to the scheduler.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int readready_dispatch (int blk, BRequest brq)
	{
		if (dstate.state == dstate.STATE_READING) {
			dstate.commit (timenow);
			dstate.state = dstate.STATE_IDLE;

			if (blk != dstate.target_blk) {
				System.err.println ("readready_dispatch(): confused about block " + blk + " ready, but target is " + dstate.target_blk);
				errcount++;
			}
			dstate.target_blk = -1;
			dstate.target_req = null;

			brq.hash = BRequest.make_request_hash (brq);

			/* NOTE: if the scheduler has more blocks, it may initiate a new read as a result of calling this */
			dsched.readcomplete (blk, brq);
		} else {
			System.err.println ("readready_dispatch(): state not READING, was (" + dstate.state + ")");
			errcount++;
		}
		return 0;
	}
	/*}}}*/
	/*{{{  private static int diskidle_dispatch ()*/
	/*
	 *	dispatches a disk-idle notification to the scheduler.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int diskidle_dispatch ()
	{
		dsched.readcomplete (-1, null);
		return 0;
	}
	/*}}}*/
	/*{{{  private static int diskseek_process ()*/
	/*
	 *	processes an ongoing seek-to-location.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int diskseek_process ()
	{
		if (dstate.state == dstate.STATE_SEEKING) {
			if (dstate.cur_track == dstate.target_track) {
				/* got there! --- right sector yet? */
				TQueue tq = new TQueue ();

				dstate.commit (timenow);
				tq.type = TQueue.TQ_DISKREAD;
				if (dstate.cur_sector == dstate.target_sector) {
					tq.time = timenow;		/* already at the right place */
				} else if ((dstate.cur_sector >= 0) && (((dstate.cur_sector + 1) & 0x3f) == dstate.target_sector)) {
					/* sector is next, so read can happen quickly */
					tq.time = timenow;		/* almost at the right place */
				} else {
					/* between 1-4 time units to get to the right place */
					int diff = dstate.target_sector - dstate.cur_sector;
					int secseektime;

					if (diff < 0) {
						diff = NSECTORSPERTRACK - diff;
					}
					secseektime = ((diff - 1) / (NSECTORSPERTRACK / 4)) + 1;

					tq.time = timenow + secseektime;
				}
				dstate.cur_headvel = 0;
				dstate.state = dstate.STATE_READING;
				tqueue_insert (tq);
			} else {
				/* else not there yet.. */
				TQueue tq = new TQueue (TQueue.TQ_DISKSEEK, timenow + 1);

				/* hacky bit: if |head-velocity| > 16 then we forget sector */
				if (Math.abs (dstate.cur_headvel) > 16) {
					dstate.cur_sector = -1;
				}
				if (dstate.cur_track < dstate.target_track) {
					/* moving up to target track */
					if (dstate.cur_headvel <= 0) {
						dstate.cur_headvel = 1;
					} else if (dstate.cur_headvel < HEADMAXVEL) {
						dstate.cur_headvel += (HEADMAXVEL / 8);
					} /* else head already at maximum velocity! */

					dstate.cur_track += dstate.cur_headvel;
					if (dstate.cur_track > dstate.target_track) {
						/* over-shot, allow settle for one cycle, unless hit end */
						dstate.cur_track = dstate.target_track + 1;
						if (dstate.cur_track >= NTRACKS) {
							dstate.cur_track = NTRACKS - 1;
						}
					}
				} else {
					/* moving down to target track */
					if (dstate.cur_headvel >= 0) {
						dstate.cur_headvel = -1;
					} else if (dstate.cur_headvel > (-HEADMAXVEL)) {
						dstate.cur_headvel -= (HEADMAXVEL / 8);
					} /* else head already at maximum velocity (backwards) */

					dstate.cur_track += dstate.cur_headvel;
					if (dstate.cur_track < dstate.target_track) {
						/* over-shot, allow settle for one cycle, unless hit start */
						dstate.cur_track = dstate.target_track - 1;
						if (dstate.cur_track < 0) {
							dstate.cur_track = 0;
						}
					}
				}
				tqueue_insert (tq);
			}
		} else {
			System.err.println ("diskseek_process(): state not SEEKING, was (" + dstate.state + ")");
			errcount++;
		}
		return 0;
	}
	/*}}}*/
	/*{{{  private static int diskread_process ()*/
	/*
	 *	called when the disk has seeked to the right point.
	 *	returns 0 on success, non-zero on failure.
	 */
	private static int diskread_process ()
	{
		if (dstate.state == dstate.STATE_READING) {
			/* takes READ_TIMEUNITS time unit to read the block, unless we were *already* at the right sector */
			TQueue tq;

			if (dstate.cur_sector == dstate.target_sector) {
				/* already here, so assume the data is ready right now */
				tq = new TQueue (TQueue.TQ_READREADY, timenow, dstate.target_blk, dstate.target_req);
			} else if ((dstate.cur_sector >= 0) && (((dstate.cur_sector + 1) & 0x3f) == dstate.target_sector)) {
				/* almost here, so assume data read is quick (READ_FASTTIMEUNITS) */
				dstate.cur_sector = dstate.target_sector;
				tq = new TQueue (TQueue.TQ_READREADY, timenow + READ_FASTTIMEUNITS, dstate.target_blk, dstate.target_req);
			} else {
				/* first read at this sector, so takes time */
				dstate.cur_sector = dstate.target_sector;
				tq = new TQueue (TQueue.TQ_READREADY, timenow + READ_TIMEUNITS, dstate.target_blk, dstate.target_req);
			}

			tqueue_insert (tq);
		} else {
			System.err.println ("diskread_process(): state not READING, was (" + dstate.state + ")");
			errcount++;
		}
		return 0;
	}
	/*}}}*/

	/* some private state for the disk visualisation */
	static int rd_lastrtime = 0;
	static int rd_spin = 0;

	/*{{{  private static void render_disk_state (PrintStream out)*/
	/*
	 *	renders the disk state for visualisation.
	 */
	private static void render_disk_state (PrintStream out)
	{
		String spinchars = ",<`^'>._";
		int i, hoff = dstate.cur_track / RFACTOR;

		out.print ("\r$");
		for (i=0; i<hoff; i++) {
			out.print ("=");
		}
		switch (dstate.state) {
		case DiskState.STATE_IDLE:	out.print ("I");	break;
		case DiskState.STATE_SEEKING:	out.print ("S");	break;
		case DiskState.STATE_READING:	out.print ("R");	break;
		}
		i++;
		for (; i<RMAX; i++) {
			out.print ("-");
		}
		out.print ("|" + spinchars.charAt(rd_spin >> 3) + "|  ");
		rd_spin = (rd_spin + 1) & 0x3f;
		out.flush ();

		if (timenow > rd_lastrtime) {
			/* wait for this long */
			try {
				int delay = (timenow - rd_lastrtime);
				Object o = new Object ();

				synchronized (o) {
					o.wait (delay);
				}
			} catch (Exception e) {}
		}
		rd_lastrtime = timenow;

		return;
	}
	/*}}}*/
	/*{{{  private static void do_sim ()*/
	/*
	 *	runs the simulation proper
	 */
	private static void do_sim ()
	{
		/* some specific things to control visualisation */
		int vskipcount = 0;
		int lasttqtype = TQueue.TQ_INVALID;

		if ((visualise > 0) && (verbose == 0)) {
			render_disk_state (System.out);
		}

		while (simqueue.size () > 0) {
			TQueue tq = simqueue.remove (0);

			timenow = tq.time;

			if ((visualise > 0) && (verbose == 0)) {
				/* only render if state change, or sufficient steps since last time */
				if ((vskipcount > 6) || (tq.type != lasttqtype)) {
					render_disk_state (System.out);
					vskipcount = 0;
					lasttqtype = tq.type;
				} else {
					vskipcount++;
				}
			}

			switch (tq.type) {
			case TQueue.TQ_INVALID:
				System.err.println ("invalid entry at time " + tq.time + " (ignoring)");
				break;
			case TQueue.TQ_BRQSTART:
				if (verbose > 0) {
					System.err.println (tq.time + ": block request queue start (" + tq.queue.id + ")");
				}
				brequest_start (tq.queue);
				break;
			case TQueue.TQ_BRQDONE:
				if (verbose > 0) {
					System.err.println (tq.time + ": block request queue done (" + tq.queue.id + ")");
				}
				brequest_done (tq.queue);
				break;
			case TQueue.TQ_READBLOCK:
				if (verbose > 0) {
					System.err.println (tq.time + ": read request for block " + tq.read_blk);
				}
				readblock_dispatch (tq.read_blk, tq.read_req);
				break;
			case TQueue.TQ_READREADY:
				if (verbose > 0) {
					System.err.println (tq.time + ": read complete for block " + tq.read_blk);
				}
				readready_dispatch (tq.read_blk, tq.read_req);
				break;
			case TQueue.TQ_DISKIDLE:
				if (verbose > 0) {
					System.err.println (tq.time + ": disk idle");
				}
				diskidle_dispatch ();
				break;
			case TQueue.TQ_DISKSEEK:
				if (verbose > 0) {
					System.err.println (tq.time + ": disk seeking");
				}
				diskseek_process ();
				break;
			case TQueue.TQ_DISKREAD:
				diskread_process ();
				break;
			default:
				System.err.println ("unhandled entry type " + tq.type + " at time " + tq.time);
				break;
			}
		}

		if ((visualise > 0) && (verbose == 0)) {
			System.out.println ("\n");
		}
	}
	/*}}}*/


	/*{{{  public static void main (String args[])*/
	/*
	 *	start here!
	 */
	public static void main (String args[])
	{
		String infname = "requests.txt";

		/*{{{  initialise*/
		dstate = new DiskState ();
		simqueue = new ArrayList<TQueue> ();
		brqueues = new ArrayList<BRQueue> ();

		/*}}}*/
		/*{{{  process command-line arguments*/
		for (int i=0; i<args.length; i++) {
			if (args[i].equals ("-f") || args[i].equals ("--file")) {
				i++;
				if (i == args.length) {
					System.err.println ("option " + args[i-1] + " requires argument");
					System.exit (1);
				}
				infname = args[i];
			} else if (args[i].equals ("-d") || args[i].equals ("--dynamic")) {
				i++;
				if (i == args.length) {
					System.err.println ("option " + args[i-1] + " requires argument");
					System.exit (1);
				}
				try {
					dynamic = Integer.parseInt (args[i]);
					if (dynamic > MAXQUEUES) {
						dynamic = MAXQUEUES;
						System.err.println ("warning: limiting number of dynamic queues to " + dynamic);
					}
				} catch (Exception e) {
					dynamic = 0;
					System.err.println ("invalid integer for option " + args[i-1] + ": " + e);
					System.exit (1);
				}
			} else if (args[i].equals ("-h") || args[i].equals ("--help")) {
				System.out.println ("Usage: java DiskSim [options]");
				System.out.println ("where options are:");
				System.out.println ("  -f | --file  <path>      specify request file");
				System.out.println ("  -v | --verbose           verbose operation");
				System.out.println ("       --vis               visualise (crude)");
				System.out.println ("  -d | --dynamic <n>       dynamic queues (continuous operation)");
				System.out.println ("       --histdata          dump histogram data (for feedgnuplot) in dynamic mode");
				System.out.println ("       --profile           generate performance profile across workload");
				System.out.println ("  -h | --help              show this help");
				System.exit (1);
			} else if (args[i].equals ("-v") || args[i].equals ("--verbose")) {
				verbose++;
			} else if (args[i].equals ("--histdata")) {
				dhistdata = 1;
			} else if (args[i].equals ("--profile")) {
				profile = 1;
			} else if (args[i].equals ("--vis")) {
				visualise++;
			} else {
				System.err.println ("unknown option " + args[i] + ", use --help for usage");
				System.exit (1);
			}
		}

		if (profile > 0) {
			if (dhistdata > 0) {
				System.err.println ("error: --histdata and --profile incompatible");
				System.exit (1);
			} else if (dynamic > 0) {
				System.err.println ("error: --profile implies --dynamic, cannot specify both");
				System.exit (1);
			}
			dynamic = 1;		/* kickstart */
		}

		/*}}}*/
		/*{{{  more initialisation*/
		dsched = new DSched ();

		if (dynamic > 0) {
			/* will be running in continuous dynamic operation, just have a big enough window :) */
			int trq = DYN_BLOCKHISTORY;

			/* populate initial queue with some dynamic request queues */
			init_dynamic ();

			time_inqueue = new int[trq];
			time_inread = new int[trq];
			time_total = new int[trq];

			for (int i=0; i<trq; i++) {
				time_inqueue[i] = 9999;
				time_inread[i] = 9999;
				time_total[i] = 9999;
			}
		} else {
			int trq = 0;

			if (readfile (infname) != 0) {
				System.exit (1);
			}

			/* create stats things because we know exactly how many blocks are being requested */
			for (int i=0; i<brqueues.size (); trq += brqueues.get(i).nblks, i++);
			time_inqueue = new int[trq];
			time_inread = new int[trq];
			time_total = new int[trq];

			for (int i=0; i<trq; i++) {
				time_inqueue[i] = 0;
				time_inread[i] = 0;
				time_total[i] = 0;
			}
		}

		/*}}}*/
		/*{{{  run simulation and report*/
		do_sim ();

		/* bit numb, since if dynamic != 0, never get here.. :) */
		if (dynamic == 0) {
			System.out.println ("finished at time " + timenow + " with " + brqueue_active + " pending queues");
			System.out.println ("disk spent " + dstate.idle_time + " idle, " + dstate.seek_time + " seeking to track, " +
					dstate.read_time + " reading " + dstate.tblocks + " blocks");
			if (errcount > 0) {
				System.out.println ("*** had " + errcount + " logical errors!");
			}

			if (blockcounter > 1) {
				RStats st = make_request_stats (blockcounter);

				System.out.format ("time for request in queue (min, max, avg): %-5d, %-5d, %.1f  +/- %.1f\n",
						st.inq_min, st.inq_max, st.inq_avg, st.inq_dev);
				System.out.format ("time for request in read (min, max, avg):  %-5d, %-5d, %.1f  +/- %.1f\n",
						st.inr_min, st.inr_max, st.inr_avg, st.inr_dev);
				System.out.format ("time for request total (min, max, avg):    %-5d, %-5d, %.1f  +/- %.1f\n",
						st.int_min, st.int_max, st.int_avg, st.int_dev);
				System.out.format ("\nScore value: %d\n", (int)(st.int_avg + st.int_dev));
			}
		}
		/*}}}*/

		System.exit (0);
	}
	/*}}}*/
}


