package com.lagalarm;

/** Passive socket evidence. Counters may count bytes or segments, so only like-unit ratios are used. */
final class TcpHealth
{
    private final PingBaseline baseline = new PingBaseline();
    private final long[] times = new long[32];
    private final long[] sent = new long[32];
    private final long[] retried = new long[32];
    private int cursor;
    private int count;
    private long lastSent = -1;
    private long lastRetried;
    private long evidenceAt = -1;
    private long trainedAt = -1;
    private long highSince = -1;
    private int highSamples;
    private int healthySamples;
    private boolean high;
    private int rtt = -1;

    void reset()
    {
        baseline.reset();
        suspend();
    }

    void suspend()
    {
        lastSent = evidenceAt = trainedAt = highSince = -1;
        cursor = count = highSamples = healthySamples = 0;
        high = false;
        rtt = -1;
        baseline.pauseLearning();
    }

    void record(int latency, long transmitted, long retransmitted, boolean gameHealthy, long now)
    {
        if (latency <= 0 || transmitted < 0 || retransmitted < 0)
        {
            suspend();
            return;
        }
        if (lastSent < 0 || transmitted < lastSent || retransmitted < lastRetried)
        {
            // A socket replacement or counter wrap must never look like massive packet loss.
            if (lastSent >= 0) { reset(); }
            lastSent = transmitted;
            lastRetried = retransmitted;
            return;
        }
        long deltaSent = transmitted - lastSent;
        long deltaRetry = retransmitted - lastRetried;
        lastSent = transmitted;
        lastRetried = retransmitted;
        if (deltaSent == 0 && deltaRetry == 0) { return; }
        // Identical snapshots are not fresh measurements and cannot keep a healthy verdict alive.
        if (evidenceAt >= 0 && now - evidenceAt > 2500)
        {
            high = false;
            highSince = -1;
            highSamples = healthySamples = 0;
            baseline.pauseLearning();
        }
        evidenceAt = now;
        rtt = latency;
        times[cursor] = now;
        sent[cursor] = deltaSent;
        retried[cursor] = deltaRetry;
        cursor = (cursor + 1) % times.length;
        count = Math.min(times.length, count + 1);
        boolean clean = !hasRetries(now);
        if (!gameHealthy || !clean) { baseline.pauseLearning(); }
        if (deltaSent > 0 && clean && (trainedAt < 0 || now - trainedAt >= 1000))
        {
            baseline.record(latency, gameHealthy, now);
            trainedAt = now;
        }
        if ((baseline.isAvailable() || latency >= 1000) && latency >= baseline.highThreshold())
        {
            healthySamples = 0;
            if (highSince < 0) { highSince = now; }
            highSamples++;
            if (highSamples >= 2 && now - highSince >= 500) { high = true; }
        }
        else
        {
            highSince = -1;
            highSamples = 0;
            if (++healthySamples >= 2) { high = false; }
        }
    }

    boolean fresh(long now) { return evidenceAt >= 0 && now - evidenceAt <= 2500; }
    boolean healthy(long now)
    {
        return fresh(now) && baseline.isAvailable() && rtt < baseline.highThreshold() && !hasRetries(now);
    }
    boolean learningSafe(long now)
    {
        return !fresh(now) || (!hasRetries(now) && (!baseline.isAvailable() || rtt < baseline.highThreshold()));
    }
    boolean highLatency(long now) { return fresh(now) && high; }
    int rtt() { return rtt; }
    int normalPing() { return baseline.normalPing(); }

    boolean repeatedRetries(long now)
    {
        long totalSent = 0;
        long totalRetry = 0;
        int episodes = 0;
        for (int i = 0; i < count; i++)
        {
            if (now - times[i] > 3000) { continue; }
            totalSent += sent[i];
            totalRetry += retried[i];
            if (retried[i] > 0) { episodes++; }
        }
        return fresh(now) && episodes >= 2 && totalRetry > 0
            && (totalSent == 0 || (double) totalRetry / totalSent >= 0.10);
    }

    private boolean hasRetries(long now)
    {
        for (int i = 0; i < count; i++)
        {
            if (now - times[i] <= 3000 && retried[i] > 0) { return true; }
        }
        return false;
    }
}
