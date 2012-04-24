package org.lantern;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.netty.channel.Channel;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.maxmind.geoip.LookupService;

/**
 * Class for tracking statistics about Lantern.
 */
public class StatsTracker implements LanternData {
    
    private final static Logger log = 
        LoggerFactory.getLogger(StatsTracker.class);

    private final AtomicLong bytesProxied = new AtomicLong(0L);
    
    private final AtomicLong directBytes = new AtomicLong(0L);
    
    private final AtomicInteger proxiedRequests = new AtomicInteger(0);
    
    private final AtomicInteger directRequests = new AtomicInteger(0);

    private static final ConcurrentHashMap<String, CountryData> countries = 
        new ConcurrentHashMap<String, CountryData>();
    
    /** 
     * getXYZBytesPerSecond calls will be calculated using a moving 
     * window average of size DATA_RATE_SECONDS.
     */ 
    private static final int DATA_RATE_SECONDS = 1;
    private static final long ONE_SECOND = 1000;
    private static final long ONE_MINUTE = 60*ONE_SECOND;
    private static final long ONE_HOUR = 60*ONE_MINUTE;
    private static final long ONE_DAY = 24*ONE_HOUR;

    /** 
     * 1-second time-buckets for i/o bytes - DATA_RATE_SECONDS+1 seconds 
     * prior only looking to track average up/down rates for the moment
     * could be adjusted to track more etc.
     */
    private static final TimeSeries1D upBytesPerSecondViaProxies
        = new TimeSeries1D(ONE_SECOND, ONE_SECOND*(DATA_RATE_SECONDS+1));
    private static final TimeSeries1D downBytesPerSecondViaProxies
        = new TimeSeries1D(ONE_SECOND, ONE_SECOND*(DATA_RATE_SECONDS+1));
    private static final TimeSeries1D upBytesPerSecondForPeers
        = new TimeSeries1D(ONE_SECOND, ONE_SECOND*(DATA_RATE_SECONDS+1));
    private static final TimeSeries1D downBytesPerSecondForPeers
        = new TimeSeries1D(ONE_SECOND, ONE_SECOND*(DATA_RATE_SECONDS+1));
    private static final TimeSeries1D upBytesPerSecondToPeers
        = new TimeSeries1D(ONE_SECOND, ONE_SECOND*(DATA_RATE_SECONDS+1));
    private static final TimeSeries1D downBytesPerSecondFromPeers
        = new TimeSeries1D(ONE_SECOND, ONE_SECOND*(DATA_RATE_SECONDS+1));
    
    
    /* Peer count tracking, just tracks current for now */
    private static final PeerCounter peersPerSecond
            = new PeerCounter(ONE_SECOND, ONE_SECOND*2);
    
    
    private boolean upnp;
    
    private boolean natpmp;
    
    public long getUptime() {
        return System.currentTimeMillis() - LanternConstants.START_TIME;
    }
    
    public void resetUserStats() {
        upBytesPerSecondViaProxies.reset();
        downBytesPerSecondViaProxies.reset();
        upBytesPerSecondForPeers.reset();
        downBytesPerSecondForPeers.reset();
        upBytesPerSecondToPeers.reset();
        downBytesPerSecondFromPeers.reset();
        peersPerSecond.reset();
        // others?
    }
    
    public long getPeerCount() {
        return peersPerSecond.latestValue();
    }
    
    public long getPeerCountThisRun() {
        return peersPerSecond.lifetimeTotal();
    }
    
    public long getUpBytesThisRun() {
        return getUpBytesThisRunForPeers() + // requests uploaded to internet for peers
               getUpBytesThisRunViaProxies() + // requests sent to other proxies
               getUpBytesThisRunToPeers();   // responses to requests we proxied
    }
    
    public long getDownBytesThisRun() {
        return getDownBytesThisRunForPeers() + // downloaded from internet for peers
               getDownBytesThisRunViaProxies() + // replys to requests proxied by others
               getDownBytesThisRunFromPeers(); // requests from peers        
    }
    
    public long getUpBytesThisRunForPeers() {
        return upBytesPerSecondForPeers.lifetimeTotal();
    }
    
    public long getUpBytesThisRunViaProxies() {
        return upBytesPerSecondViaProxies.lifetimeTotal();
    }

    public long getUpBytesThisRunToPeers() {
        return upBytesPerSecondToPeers.lifetimeTotal();
    }
    
    public long getDownBytesThisRunForPeers() {
        return downBytesPerSecondForPeers.lifetimeTotal();
    }

    public long getDownBytesThisRunViaProxies() {
        return downBytesPerSecondViaProxies.lifetimeTotal();
    }

    public long getDownBytesThisRunFromPeers() {
        return downBytesPerSecondFromPeers.lifetimeTotal();
    }
    
    
    public long getUpBytesPerSecond() {
        return getUpBytesPerSecondForPeers() + // requests uploaded to internet for peers
               getUpBytesPerSecondViaProxies() + // requests sent to other proxies
               getUpBytesPerSecondToPeers();   // responses to requests we proxied
    }

    public long getDownBytesPerSecond() {
        return getDownBytesPerSecondForPeers() + // downloaded from internet for peers
               getDownBytesPerSecondViaProxies() + // replys to requests proxied by others
               getDownBytesPerSecondFromPeers(); // requests from peers
    }
    
    public long getUpBytesPerSecondForPeers() {
        return getBytesPerSecond(upBytesPerSecondForPeers);
    }

    public long getUpBytesPerSecondViaProxies() {
        return getBytesPerSecond(upBytesPerSecondViaProxies);
    }

    public long getDownBytesPerSecondForPeers() {
        return getBytesPerSecond(downBytesPerSecondForPeers);
    }
    
    public long getDownBytesPerSecondViaProxies() {
        return getBytesPerSecond(downBytesPerSecondViaProxies);
    }
    
    public long getDownBytesPerSecondFromPeers() {
        return getBytesPerSecond(downBytesPerSecondFromPeers);
    }
    
    public long getUpBytesPerSecondToPeers() {
        return getBytesPerSecond(upBytesPerSecondToPeers);
    }
    
    private long getBytesPerSecond(TimeSeries1D ts) {
        long now = System.currentTimeMillis();
        // prior second to the one we're still accumulating 
        long windowEnd = ((now / ONE_SECOND) * ONE_SECOND) - 1;
        // second DATA_RATE_SECONDS before that
        long windowStart = windowEnd - (ONE_SECOND*DATA_RATE_SECONDS);
        // take the average
        return (long) (ts.windowAverage(windowStart, windowEnd) + 0.5);
    }
    
    /**
     * request bytes this lantern proxy sent to other lanterns for proxying
     */
    public void addUpBytesViaProxies(final long bp, final Channel channel) {
        upBytesPerSecondViaProxies.addData(bp);
        log.debug("upBytesPerSecondViaProxies += {} up-rate {}", bp, getUpBytesPerSecond());
    }

    /**
     * request bytes this lantern proxy sent to other lanterns for proxying
     */
    public void addUpBytesViaProxies(final long bp, final Socket sock) {
        upBytesPerSecondViaProxies.addData(bp);
        log.debug("upBytesPerSecondViaProxies += {} up-rate {}", bp, getUpBytesPerSecond());
    }

    /**
     * bytes sent upstream on behalf of another lantern by this
     * lantern
     */
    public void addUpBytesForPeers(final long bp, final Channel channel) {
        upBytesPerSecondForPeers.addData(bp);
        log.debug("upBytesPerSecondForPeers += {} up-rate {}", bp, getUpBytesPerSecond());
    }

    /**
     * bytes sent upstream on behalf of another lantern by this
     * lantern
     */
    public void addUpBytesForPeers(final long bp, final Socket sock) {
        upBytesPerSecondForPeers.addData(bp);
        log.debug("upBytesPerSecondForPeers += {} up-rate {}", bp, getUpBytesPerSecond());
    }

    /**
     * response bytes downloaded by Peers for this lantern
     */
    public void addDownBytesViaProxies(final long bp, final Channel channel) {
        downBytesPerSecondViaProxies.addData(bp);
        log.debug("downBytesPerSecondViaProxies += {} down-rate {}", bp, getDownBytesPerSecond());
    }

    /**
     * response bytes downloaded by Peers for this lantern
     */
    public void addDownBytesViaProxies(final long bp, final Socket sock) {
        downBytesPerSecondViaProxies.addData(bp);
        log.debug("downBytesPerSecondViaProxies += {} down-rate {}", bp, getDownBytesPerSecond());
    }

    /**
     * bytes downloaded on behalf of another lantern by this
     * lantern
     */
    public void addDownBytesForPeers(final long bp, final Channel channel) {
        downBytesPerSecondForPeers.addData(bp);
        log.debug("downBytesPerSecondForPeers += {} down-rate {}", bp, getDownBytesPerSecond());
    }
    /**
     * bytes downloaded on behalf of another lantern by this
     * lantern
     */
    public void addDownBytesForPeers(final long bp, final Socket sock) {
        downBytesPerSecondForPeers.addData(bp);
        log.debug("downBytesPerSecondForPeers += {} down-rate {}", bp, getDownBytesPerSecond());
    }
    
    /**
     * request bytes sent by peers to this lantern
     */
    public void addDownBytesFromPeers(final long bp, final Channel channel) {
        downBytesPerSecondFromPeers.addData(bp);
        log.debug("downBytesPerSecondFromPeers += {} down-rate {}", bp, getDownBytesPerSecond());
    }
    /**
     * request bytes sent by peers to this lantern
     */
    public void addDownBytesFromPeers(final long bp, final Socket sock) {
        downBytesPerSecondFromPeers.addData(bp);
        log.debug("downBytesPerSecondFromPeers += {} down-rate {}", bp, getDownBytesPerSecond());
    }
    
    /** 
     * reply bytes send to peers by this lantern
     */
    public void addUpBytesToPeers(final long bp, final Channel channel) {
        upBytesPerSecondToPeers.addData(bp);
        log.debug("upBytesPerSecondToPeers += {} up-rate {}", bp, getUpBytesPerSecond());
    }
    /** 
     * reply bytes send to peers by this lantern
     */
    public void addUpBytesToPeers(final long bp, final Socket sock) {
        upBytesPerSecondToPeers.addData(bp);
        log.debug("upBytesPerSecondToPeers += {} up-rate {}", bp, getUpBytesPerSecond());
    }

    @Override
    public long getTotalBytesProxied() {
        return bytesProxied.get();
    }

    public void addDirectBytes(final int db) {
        directBytes.addAndGet(db);
    }

    @Override
    public long getDirectBytes() {
        return directBytes.get();
    }

    public void incrementDirectRequests() {
        this.directRequests.incrementAndGet();
    }

    public void incrementProxiedRequests() {
        this.proxiedRequests.incrementAndGet();
    }

    @Override
    public int getTotalProxiedRequests() {
        return proxiedRequests.get();
    }

    @Override
    public int getDirectRequests() {
        return directRequests.get();
    }
    

    public void addBytesProxied(final long bp, final Channel channel) {
        bytesProxied.addAndGet(bp);
        final CountryData cd = toCountryData(channel);
        if (cd != null) {
            cd.bytes += bp;
        }
        else {
            log.warn("No CountryData for {} Not adding bytes proxied.", channel);
        }
    }

    public void addBytesProxied(final long bp, final Socket sock) {
        bytesProxied.addAndGet(bp);
        final CountryData cd = toCountryData(sock);
        if (cd != null) {
            cd.bytes += bp;
        }
        else {
            log.warn("No CountryData for {} Not adding bytes proxied.", sock);
        }
    }

    public void setUpnp(final boolean upnp) {
        this.upnp = upnp;
    }

    public boolean isUpnp() {
        return upnp;
    }

    public void setNatpmp(final boolean natpmp) {
        this.natpmp = natpmp;
    }

    public boolean isNatpmp() {
        return natpmp;
    }

    private CountryData toCountryData(final Channel channel) {
        final InetSocketAddress isa = 
            (InetSocketAddress) channel.getRemoteAddress();
        return toCountryData(isa);
    }
    
    
    private CountryData toCountryData(final Socket sock) {
        final InetSocketAddress isa = 
            (InetSocketAddress)sock.getRemoteSocketAddress();
        return toCountryData(isa);
    }
    
    private CountryData toCountryData(final InetSocketAddress isa) {
        if (isa == null) {
            return null;
        }
        
        final LookupService ls = LanternHub.getGeoIpLookup();
        final InetAddress addr = isa.getAddress();
        final Country country = new Country(ls.getCountry(addr));
        final CountryData cd;
        final CountryData temp = new CountryData(country);
        final CountryData existing = 
            countries.putIfAbsent(country.getCode(), temp);
        if (existing == null) {
            cd = temp;
        } else {
            cd = existing;
        }
        
        cd.addresses.add(addr);
        return cd;
    }
    

    public static CountryData newCountryData(final String cc, 
        final String name) {
        if (countries.containsKey(cc)) {
            return countries.get(cc);
        } 
        final Country co = new Country(cc, name);
        final CountryData cd = new CountryData(co);
        countries.put(cc, cd);
        return cd;
    }

    public String getCountryCode() {
        return LanternHub.censored().countryCode();
    }
    
    public String version() {
        return LanternConstants.VERSION;
    }

    public static final class CountryData {
        private final Set<InetAddress> addresses = new HashSet<InetAddress>();
        private volatile long bytes;
        
        private final JSONObject lanternData = new JSONObject();
        final JSONObject data = new JSONObject();
        
        private CountryData(final Country country) {
            data.put("censored", LanternHub.censored().isCensored(country));
            data.put("name", country.getName());
            data.put("code", country.getCode());
            data.put("lantern", lanternData);
        }
    }
}
