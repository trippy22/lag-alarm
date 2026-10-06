package com.lagalarm;

import java.util.Arrays;

/** Robust per-world latency baseline and bounded retry policy. Called under the detector lock. */
final class PingBaseline
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

    void reset()
    {
        count = cursor = consecutiveSuccesses = consecutiveFailures = median = deviation = 0;
        available = false;
        burstRemaining = 0;
        nextBurstAllowed = Long.MIN_VALUE;
    }

    void record(int rtt, boolean gameUpdatesHealthy, long now)
    {
        if (burstRemaining > 0) { burstRemaining--; }
        if (rtt < 0)
        {
            consecutiveSuccesses = 0;
            burstRemaining = 0;
            consecutiveFailures = Math.min(8, consecutiveFailures + 1);
            if (consecutiveFailures >= 3)
            {
                // ICMP may have become blocked. Stop treating it as evidence about the game.
                available = false;
                count = cursor = 0;
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
        if (gameUpdatesHealthy && (!available || rtt < highThreshold()))
        {
            values[cursor] = rtt;
            cursor = (cursor + 1) % values.length;
            count = Math.min(values.length, count + 1);
            System.arraycopy(values, 0, scratch, 0, count);
            Arrays.sort(scratch, 0, count);
            median = scratch[count / 2];
            for (int i = 0; i < count; i++) { scratch[i] = Math.abs(values[i] - median); }
            Arrays.sort(scratch, 0, count);
            deviation = scratch[count / 2];
        }
        if (count >= 5 && consecutiveSuccesses >= 5) { available = true; }
    }

    boolean isAvailable() { return available; }
    int normalPing() { return count == 0 ? -1 : median; }

    int highThreshold()
    {
        // Require a large relative increase AND material absolute latency; always flag >= 1 second.
        return Math.min(1000, Math.max(200, median + Math.max(100, Math.max(median, deviation * 6))));
    }

    int deadline()
    {
        return Math.min(2000, Math.max(400, median * 3 + deviation * 6));
    }

    int retryDelay()
    {
        if (consecutiveFailures == 0) { return burstRemaining > 0 ? 250 : 1000; }
        return Math.min(30000, 1000 << Math.min(5, consecutiveFailures - 1));
    }

    String status()
    {
        if (available) { return "Active"; }
        if (consecutiveFailures > 0) { return "Ping unavailable; retrying"; }
        return "Learning ping " + Math.min(count, consecutiveSuccesses) + "/5";
    }
}
