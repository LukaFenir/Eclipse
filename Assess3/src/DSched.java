/*
 *  DSched.java -- disk I/O scheduler code.
 *  <<<INSERT YOUR NAME AND EMAIL HERE>>>
 */

/*
 *  FIXME: this implements a FIFO (first in, first out) request queue.
 *  requests are collected in the "readqueue" array, which acts as a circular
 *  buffer, with "readqueue_head" pointing at the next empty slot, "readqueue_tail"
 *  pointing at the oldest request slot, and "readqueue_size" indicating how many
 *  entries are in the buffer.
 *
 *  This is *not* an efficient way of doing disk scheduling because the time taken
 *  to move the read heads between tracks is significant.
 *
 *  Three methods are defined in the disk simulator code which you can call in addition
 *  to the ones already used:
 *
 *      int DiskSim.block_to_head (int blk)
 *      int DiskSim.block_to_track (int blk)
 *      int DiskSim.block_to_sector (int blk)
 *
 *  Which return respectively the head, track and sector for a particular block.
 */

/*
 *  NOTE: for the Java version, you may *not* use collection classes such as ArrayList here.
 *  Only primitive arrays are allowed.  If you want some sorting, you'll have to do that
 *  yourself.  The reasoning is that code inside an OS does not normally have access to a
 *  rich API of things that Java might normally offer.
 *
 *  If you have queries about what you can/cannot use, please ask on the anonymous Q+A forum,
 *  linked from the module's Moodle page.
 */

public class DSched
{
    /*{{{  private class ReadReq*/
    /*
     *  local class/structure to hold read requests
     */
    private class ReadReq
    {
        int blk;        /* block number */
        BRequest req;       /* request info */
        int index;

        /* constructor: just clear fields */
        public ReadReq ()
        {
            blk = -1;
            req = null;
            index = 0;
        }
    }
    /*}}}*/

    /* Private state */
    private ReadReq readqueue[];
    private int readqueue_head;
    private int readqueue_tail;
    private int readqueue_size;
    private ReadReq last;
    private ReadReq next;
    private int index;
    private int head;
    private int track;
    private int sector;
    private int h;
    private int t;
    private int s;
    private int direction; // 0 = null, 1 = up blocks, -1 = down blocks
    private int dist; //distance between block and current array block
    private int distPrev; //last dist


    /*{{{  public DSched ()*/
    /*
     *  Constructor, initialises various local state.
     */
    public DSched ()
    {
        readqueue = new ReadReq[DiskSim.MAXREQUESTS];
        readqueue_head = 0;
        readqueue_tail = 0;
        readqueue_size = 0;
        last = null;
        next = null;
        head = 0;
        track = 0;
        sector = 0;
        direction = 0;
        dist = 0;

        /* allocate individual ReadReq entries */
        for (int i=0; i<readqueue.length; i++) {
            readqueue[i] = new ReadReq ();
            readqueue[i].index = i;
        }
    }
    /*}}}*/

    /*{{{  public void blockread (int blk, BRequest req)*/
    /*
     *  Called by higher-level code to request that a block is read.
     */
    public void blockread (int blk, BRequest req)
    {
        /* add the request to the head of the queue */
        readqueue[readqueue_head].blk = blk;
        int add = readqueue[readqueue_head].blk;
        readqueue[readqueue_head].req = req;
        System.out.println("Block added "+ add + " to array "+readqueue_head+" Track "+DiskSim.block_to_track(add)+" Sector "+DiskSim.block_to_sector(add));
        /* increment head pointer (modulo buffer size) */
        readqueue_head = (readqueue_head + 1) % DiskSim.MAXREQUESTS;
        readqueue_size++;
    }
    /*}}}*/
    /*{{{  public void readcomplete (int blk, BRequest req)*/
    /*
     *  Called by lower-level (disk) code when a read has completed and the next
     *  read can be dispatched.  If "blk" < 0 or "req" == null, then this is telling
     *  us that the disk is idle and ready to accept a new read request.
     */
    public void readcomplete (int blk, BRequest req)
    {
        if (blk >= 0) {
            /* give the block read back to the high-level system */
            DiskSim.highlevel_didread (blk, req);
            System.out.println("Read block "+blk+ " track "+DiskSim.block_to_track(blk)+" sector "+DiskSim.block_to_sector(blk));
        }
        
        if (readqueue_size > 0) {
            /* still got requests to service, dispatch the next block request (at tail) */
            t = DiskSim.block_to_track(blk); //last-read's track
            s = DiskSim.block_to_sector(blk);//last-read's sector
            if(direction==0){
                next = readqueue[readqueue_tail];
                if(readqueue[readqueue_tail+1].blk != -1){ //if there is something in the next one
                    t = DiskSim.block_to_track(readqueue[readqueue_tail].blk);       //first's track
                    track = DiskSim.block_to_track(readqueue[readqueue_tail+1].blk); //second's track
                    dist = t - track;
                    System.out.println("Original block "+readqueue[readqueue_tail].blk+" block2 "+readqueue[readqueue_tail+1].blk+" original track "+t+" new track "+track+" distance "+dist);
                    if(dist!=0){
                        for(int a=readqueue_tail+2; a < readqueue_size; a++) {
                            track = DiskSim.block_to_track(readqueue[a].blk);
                            if(dist>Math.abs(t-track)){
                                dist = t - track;
                                System.out.println("Change direction, dist "+dist);
                            }
                        } 
                    }else{
                        s = DiskSim.block_to_sector(readqueue[readqueue_tail].blk);         //first's sector                        
                        sector = DiskSim.block_to_sector(readqueue[readqueue_tail+1].blk);  //second's sector
                        dist = s - sector;
                        for(int a=readqueue_tail+1; a < readqueue_size; a++) {
                            if(t==DiskSim.block_to_track(readqueue[a].blk)){
                                sector = DiskSim.block_to_sector(readqueue[a].blk);                                
                                if(dist>Math.abs(s-sector)){
                                    dist = s - sector;
                                    System.out.println("Change direction, dist "+dist);
                                }
                            }
                        }     
                    }                   
                    if(dist > 0){ //dist is positive, direction is down//what about for 0?
                        direction = -1;
                    }
                    else if(dist < 0) { //dist is negative, direction is up
                        direction = 1;
                    }
                }
            }
            else {
                for(int a=readqueue_tail; a < readqueue.length; a++) {
                    dist = t - DiskSim.block_to_track(readqueue[a].blk);//added this
                    switch (direction) {
                    case 1:
                        if(dist > distPrev) {
                        //System.out.println(dist+" dist | Previous dist "+distPrev);
                        next = readqueue[readqueue_tail];
                        
                        }
                        break;
                    case -1:
                        if(dist < distPrev) {
                        //System.out.println(dist+" dist | Previous dist "+distPrev);
                        next = readqueue[readqueue_tail];
                        //System.out.println("Allocated next, case -1");
                        
                        }
                        break;
                    }
                }
            }
            distPrev = dist;

            //for(int a=readqueue_tail; a < readqueue.length; a++) {

            //}
            /////readqueue[readqueue_tail] needs changing 
            DiskSim.disk_readblock (next.blk, next.req);
            
            /* increment tail pointer, modulo buffer size */
            readqueue_tail = (readqueue_tail + 1) % DiskSim.MAXREQUESTS;
            readqueue_size--;
            last = next;
        }
    }
    /*}}}*/
}