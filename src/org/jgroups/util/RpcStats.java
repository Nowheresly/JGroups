package org.jgroups.util;

import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.protocols.RTTHeader;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Keeps track of stats for sync and async unicasts and multicasts
 * @author Bela Ban
 * @since  3.6.8
 */
public class RpcStats {
    protected final AtomicInteger           sync_unicasts=new AtomicInteger(0);
    protected final AtomicInteger           async_unicasts=new AtomicInteger(0);
    protected final AtomicInteger           sync_multicasts=new AtomicInteger(0);
    protected final AtomicInteger           async_multicasts=new AtomicInteger(0);
    protected final AtomicInteger           sync_anycasts=new AtomicInteger(0);
    protected final AtomicInteger           async_anycasts=new AtomicInteger(0);
    protected volatile Map<Address,Result>  stats;
    protected volatile Map<Address,RTTStat> rtt_stats;

    public enum Type {MULTICAST, UNICAST, ANYCAST}

    public RpcStats(boolean extended_stats) {
        extendedStats(extended_stats);
    }

    public int unicasts(boolean sync)    {return sync? sync_unicasts.get()   : async_unicasts.get();}
    public int multicasts(boolean sync)  {return sync? sync_multicasts.get() : async_multicasts.get();}
    public int anycasts(boolean sync)    {return sync? sync_anycasts.get()   : async_anycasts.get();}

    public boolean  extendedStats()          {return stats != null;}
    public RpcStats extendedStats(boolean f) {
        if(f) {
            if(stats == null)
                stats=new ConcurrentHashMap<>();
        }
        else
            stats=null;
        return this;
    }

    public void reset() {
        if(stats != null)
            stats.clear();
        if(rtt_stats != null)
            rtt_stats.clear();
        for(AtomicInteger ai: Arrays.asList(sync_unicasts, async_unicasts, sync_multicasts, async_multicasts, sync_anycasts, async_anycasts))
            ai.set(0);
    }

    public void add(Type type, Address dest, boolean sync, long time) {
        update(type, sync);
        addToResults(dest, sync, time);
    }

    public void addAnycast(boolean sync, long time, Collection<Address> dests) {
        update(Type.ANYCAST, sync);
        if(dests != null)
            for(Address dest: dests)
                addToResults(dest, sync, time);
    }

    public void addRTTStats(Address sender, RTTHeader hdr) {
        if(hdr == null)
            return;
        if(this.rtt_stats == null)
            this.rtt_stats=new ConcurrentHashMap<>();
        Address key=sender == null? Global.NULL_ADDRESS : sender;
        RTTStat rtt_stat=rtt_stats.computeIfAbsent(key, k -> new RTTStat());
        rtt_stat.add(hdr);
    }

    public void retainAll(Collection<Address> members) {
        Map<Address,Result> map;
        if(members == null || (map=stats) == null)
            return;
        map.keySet().retainAll(members);
        if(rtt_stats != null)
            rtt_stats.keySet().retainAll(members);
    }


    public String printStatsByDest() {
        if(stats == null) return "(no stats)";
        StringBuilder sb=new StringBuilder("\n");
        for(Map.Entry<Address,Result> entry: stats.entrySet()) {
            Address dst=entry.getKey();
            sb.append(String.format("%s: %s\n", dst == Global.NULL_ADDRESS? "<all>" : dst, entry.getValue()));
        }
        return sb.toString();
    }


    public String printRTTStatsByDest() {
        if(rtt_stats == null) return "(no RTT stats)";
        StringBuilder sb=new StringBuilder("\n");
        for(Map.Entry<Address,RTTStat> entry: rtt_stats.entrySet()) {
            Address dst=entry.getKey();
            sb.append(String.format("%s:\n%s\n", dst == Global.NULL_ADDRESS? "<all>" : dst, entry.getValue()));
        }
        return sb.toString();
    }

    public String toString() {
        return String.format("sync mcasts: %d, async mcasts: %d, sync ucasts: %d, async ucasts: %d, sync acasts: %d, async acasts: %d",
                             sync_multicasts.get(), async_multicasts.get(), sync_unicasts.get(), async_unicasts.get(),
                             sync_anycasts.get(), async_anycasts.get());
    }

    protected void update(Type type, boolean sync) {
        switch(type) {
            case MULTICAST:
                if(sync)
                    sync_multicasts.incrementAndGet();
                else
                    async_multicasts.incrementAndGet();
                break;
            case UNICAST:
                if(sync)
                    sync_unicasts.incrementAndGet();
                else
                    async_unicasts.incrementAndGet();
                break;
            case ANYCAST:
                if(sync)
                    sync_anycasts.incrementAndGet();
                else
                    async_anycasts.incrementAndGet();
                break;
        }
    }

    protected void addToResults(Address dest, boolean sync, long time) {
        Map<Address,Result> map=stats;
        if(map == null)
            return;
        if(dest == null)
            dest=Global.NULL_ADDRESS;
        Result res=map.computeIfAbsent(dest, k -> new Result());
        res.add(sync, time);
    }


    protected static class Result {
        protected long                sync, async;
        protected final AverageMinMax avg=new AverageMinMax();
        protected long                sync()  {return sync;}
        protected long                async() {return async;}
        protected long                min()   {return avg.min();}
        protected long                max()   {return avg.max();}
        protected synchronized double avg()   {return avg.average();}

        protected synchronized void add(boolean sync, long time) {
            if(sync)
                this.sync++;
            else
                this.async++;
            if(time > 0)
                avg.add(time);
        }

        public String toString() {
            double avg_us=avg()/1000.0;     // convert nanos to microsecs
            double min_us=avg.min()/1000.0; // us
            double max_us=avg.max()/1000.0; // us
            return String.format("async: %d, sync: %d, round-trip min/avg/max (us): %.2f / %.2f / %.2f", async, sync, min_us, avg_us, max_us);
        }
    }

    protected static class RTTStat {
        protected final AverageMinMax total_time=new AverageMinMax();
        protected final AverageMinMax down_req_time=new AverageMinMax();

        protected void add(RTTHeader hdr) {
            if(hdr == null)
                return;
            if(hdr.totalTime() > 0)
                total_time.add(hdr.totalTime());
            if(hdr.downRequest() > 0)
                down_req_time.add(hdr.downRequest());
        }

        public String toString() {
            return String.format("  total: %s\n  down-req: %s",
                                 total_time.toString(NANOSECONDS),
                                 down_req_time.toString(NANOSECONDS));
        }
    }
}
