package com.lagalarm;

/** Passive socket evidence. Counters may count bytes or segments, so only like-unit ratios are used. */
final class TcpHealth
{
    private static final int EVIDENCE_MAX_AGE_MS = 2500;
    private static final int BASELINE_SAMPLE_INTERVAL_MS = 1000;
    private static final int HIGH_LATENCY_DURATION_MS = 500;
    private static final int RETRANSMISSION_WINDOW_MS = 3000;
    private static final double MIN_RETRANSMISSION_RATIO = 0.10;
    private static final int ACK_POLL_MAX_AGE_MS = 1000;
    private static final int MIN_ACK_STALL_MS = 500;
    private static final int MAX_ACK_STALL_MS = 1500;

    private final LatencyBaseline baseline = new LatencyBaseline();
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
    // Optional acknowledgement progress. Not every platform or RuneLite version provides it.
    private long lastAcked = -1;
    private long ackProgressAt = -1;
    private long ackPolledAt = -1;
    private boolean awaitingAck;

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
        lastAcked = ackProgressAt = ackPolledAt = -1;
        awaitingAck = false;
        baseline.pauseLearning();
    }

    void record(int latency, long transmitted, long retransmitted, boolean gameHealthy, long now)
    {
        record(latency, transmitted, retransmitted, -1, -1, gameHealthy, now);
    }

    /** acked and unacked are -1 when the platform cannot report them. */
    void record(int latency, long transmitted, long retransmitted, long acked, long unacked, boolean gameHealthy, long now)
    {
        if (latency <= 0 || transmitted < 0 || retransmitted < 0)
        {
            suspend();
            return;
        }
        boolean replaced = lastSent >= 0 && (transmitted < lastSent || retransmitted < lastRetried);
        trackAcks(replaced ? -1 : acked, unacked, now);
        if (lastSent < 0 || transmitted < lastSent || retransmitted < lastRetried)
        {
            // A socket replacement or counter wrap must never look like massive packet loss.
            if (lastSent >= 0)
            {
                reset();
            }
            lastSent = transmitted;
            lastRetried = retransmitted;
            return;
        }
        long deltaSent = transmitted - lastSent;
        long deltaRetry = retransmitted - lastRetried;
        lastSent = transmitted;
        lastRetried = retransmitted;
        if (deltaSent == 0 && deltaRetry == 0)
        {
            return;
        }
        // Identical snapshots are not fresh measurements and cannot keep a healthy verdict alive.
        if (evidenceAt >= 0 && now - evidenceAt > EVIDENCE_MAX_AGE_MS)
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
        if (!gameHealthy || !clean)
        {
            baseline.pauseLearning();
        }
        if (deltaSent > 0 && clean && (trainedAt < 0 || now - trainedAt >= BASELINE_SAMPLE_INTERVAL_MS))
        {
            baseline.record(latency, gameHealthy, now);
            trainedAt = now;
        }
        if ((baseline.isAvailable() || latency >= 1000) && latency >= baseline.highThreshold())
        {
            healthySamples = 0;
            if (highSince < 0)
            {
                highSince = now;
            }
            highSamples++;
            if (highSamples >= 2 && now - highSince >= HIGH_LATENCY_DURATION_MS)
            {
                high = true;
            }
        }
        else
        {
            highSince = -1;
            highSamples = 0;
            if (++healthySamples >= 2)
            {
                high = false;
            }
        }
    }

    boolean fresh(long now)
    {
        return evidenceAt >= 0 && now - evidenceAt <= EVIDENCE_MAX_AGE_MS;
    }

    boolean healthy(long now)
    {
        return fresh(now) && baseline.isAvailable() && rtt < baseline.highThreshold() && !hasRetries(now)
            && !ackStalled(now);
    }

    boolean learningSafe(long now)
    {
        return !ackStalled(now)
            && (!fresh(now) || (!hasRetries(now) && (!baseline.isAvailable() || rtt < baseline.highThreshold())));
    }

    // Unlike the counters, "data is waiting for an acknowledgement" is a state: every poll is evidence.
    private void trackAcks(long acked, long unacked, long now)
    {
        if (acked < 0 || unacked < 0)
        {
            lastAcked = ackProgressAt = ackPolledAt = -1;
            awaitingAck = false;
            return;
        }
        // The wait is timed from the first poll that saw unacknowledged data, never from an earlier idle
        // poll: the data may have been sent just before this poll, and a late start cannot overstate a stall.
        // Only an increase is progress; a platform may account for retransmitted bytes differently.
        if (unacked > 0 && (!awaitingAck || lastAcked < 0 || acked > lastAcked))
        {
            ackProgressAt = now;
        }
        lastAcked = acked;
        awaitingAck = unacked > 0;
        ackPolledAt = now;
    }

    boolean ackTracking(long now)
    {
        return ackPolledAt >= 0 && now - ackPolledAt <= ACK_POLL_MAX_AGE_MS;
    }

    /** Sent data has gone unacknowledged for longer than one lost packet normally takes to recover. */
    boolean ackStalled(long now)
    {
        if (!ackTracking(now) || !awaitingAck)
        {
            return false;
        }
        int normal = baseline.isAvailable() ? baseline.normalPing() : Math.max(0, rtt);
        int limit = Math.min(MAX_ACK_STALL_MS, Math.max(MIN_ACK_STALL_MS, normal * 3 + 300));
        return ackPolledAt - ackProgressAt >= limit;
    }

    boolean highLatency(long now)
    {
        return fresh(now) && high;
    }

    int rtt()
    {
        return rtt;
    }

    int normalPing()
    {
        return baseline.normalPing();
    }

    boolean repeatedRetries(long now)
    {
        long totalSent = 0;
        long totalRetry = 0;
        int episodes = 0;
        for (int i = 0; i < count; i++)
        {
            if (now - times[i] > RETRANSMISSION_WINDOW_MS)
            {
                continue;
            }
            totalSent += sent[i];
            totalRetry += retried[i];
            if (retried[i] > 0)
            {
                episodes++;
            }
        }
        return fresh(now) && episodes >= 2 && totalRetry > 0
            && (totalSent == 0 || (double) totalRetry / totalSent >= MIN_RETRANSMISSION_RATIO);
    }

    private boolean hasRetries(long now)
    {
        for (int i = 0; i < count; i++)
        {
            if (now - times[i] <= RETRANSMISSION_WINDOW_MS && retried[i] > 0)
            {
                return true;
            }
        }
        return false;
    }
}
