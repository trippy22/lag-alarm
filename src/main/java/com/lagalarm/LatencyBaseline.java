package com.lagalarm;

import java.util.Arrays;

/** Robust per-world latency baseline and bounded retry policy. Called under the detector lock. */
final class LatencyBaseline
{
    private final int[] values = new int[61];
    private final int[] scratch = new int[61];
    private int count;
    private int cursor;
    private int consecutiveSuccesses;
    private int consecutiveFailures;
    private int median;
    private int deviation;
    private boolean available;
    private int burstRemaining;
    private long nextBurstAllowed;
    private int reference = -1;
    private int referenceDeviation;
    private long candidateSince = -1;
    private int candidatePing;
    private int candidateSamples;
    private long lastSample = -1;

    void reset()
    {
        count = cursor = consecutiveSuccesses = consecutiveFailures = median = deviation = 0;
        available = false;
        burstRemaining = 0;
        nextBurstAllowed = Long.MIN_VALUE;
        reference = -1;
        referenceDeviation = 0;
        pauseLearning();
        lastSample = -1;
    }

    void record(int rtt, boolean gameUpdatesHealthy, long now)
    {
        if (burstRemaining > 0)
        {
            burstRemaining--;
        }
        if (rtt < 0)
        {
            pauseLearning();
            consecutiveSuccesses = 0;
            burstRemaining = 0;
            consecutiveFailures = Math.min(8, consecutiveFailures + 1);
            if (consecutiveFailures >= 3)
            {
                // ICMP may have become blocked. Stop treating it as evidence about the game.
                available = false;
                count = cursor = 0;
                reference = -1;
            }
            return;
        }
        consecutiveFailures = 0;
        consecutiveSuccesses = Math.min(5, consecutiveSuccesses + 1);
        if (available && rtt >= highThreshold() && now >= nextBurstAllowed)
        {
            burstRemaining = 8;
            nextBurstAllowed = now + 30000;
        }
        if (!gameUpdatesHealthy || (lastSample >= 0 && now - lastSample > 2500))
        {
            pauseLearning();
        }
        lastSample = now;
        if (gameUpdatesHealthy)
        {
            values[cursor] = rtt;
            cursor = (cursor + 1) % values.length;
            count = Math.min(values.length, count + 1);
            System.arraycopy(values, 0, scratch, 0, count);
            Arrays.sort(scratch, 0, count);
            median = scratch[count / 2];
            for (int i = 0; i < count; i++)
            {
                scratch[i] = Math.abs(values[i] - median);
            }
            Arrays.sort(scratch, 0, count);
            deviation = scratch[count / 2];
        }
        if (!available && count >= 5 && consecutiveSuccesses >= 5)
        {
            available = true;
            reference = median;
            referenceDeviation = deviation;
        }
        else if (available && gameUpdatesHealthy)
        {
            // Keep a slow reference: gradual degradation must not raise its own alarm threshold.
            if (median < reference)
            {
                reference = median;
                referenceDeviation = deviation;
            }
            considerStableRoute(now);
        }
    }

    boolean isAvailable()
    {
        return available;
    }

    int normalPing()
    {
        return count == 0 ? -1 : reference < 0 ? median : reference;
    }

    int highThreshold()
    {
        // Require a large relative increase AND material absolute latency; always flag >= 1 second.
        int normal = Math.max(0, normalPing());
        int jitter = reference < 0 ? deviation : referenceDeviation;
        return Math.min(1000, Math.max(200, normal + Math.max(100, Math.max(normal, jitter * 6))));
    }

    int deadline()
    {
        return Math.min(2000, Math.max(400, Math.max(0, normalPing()) * 3
            + (reference < 0 ? deviation : referenceDeviation) * 6));
    }

    void pauseLearning()
    {
        candidateSince = -1;
        candidateSamples = 0;
    }

    private void considerStableRoute(long now)
    {
        if (count < 15)
        {
            return;
        }
        // A short window tests stability; its level must also stay near one fixed candidate
        // for two minutes. A moving window alone would quietly accept a slow upward ramp.
        for (int i = 0; i < 15; i++)
        {
            scratch[i] = values[(cursor - 1 - i + values.length) % values.length];
        }
        Arrays.sort(scratch, 0, 15);
        int recent = scratch[7];
        int tolerance = Math.max(20, recent / 10);
        if (recent > 500 || recent <= reference || scratch[14] - scratch[0] > tolerance)
        {
            pauseLearning();
            return;
        }
        if (candidateSince < 0 || Math.abs(recent - candidatePing) > Math.max(20, candidatePing / 10))
        {
            candidateSince = now;
            candidatePing = recent;
            candidateSamples = 0;
        }
        if (++candidateSamples >= 30 && now - candidateSince >= 120000)
        {
            reference = recent;
            referenceDeviation = deviation;
            pauseLearning();
        }
    }

    int retryDelay()
    {
        if (consecutiveFailures == 0)
        {
            return burstRemaining > 0 ? 250 : 1000;
        }
        return Math.min(30000, 1000 << Math.min(5, consecutiveFailures - 1));
    }

    String status()
    {
        if (available)
        {
            return "Active";
        }
        if (consecutiveFailures > 0)
        {
            return "Ping unavailable; retrying";
        }
        return "Learning ping " + Math.min(count, consecutiveSuccesses) + "/5";
    }
}
