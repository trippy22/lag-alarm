package com.lagalarm;

import java.util.concurrent.ThreadLocalRandom;

/** Completion-based pacing shared by the plugin and live benchmark. */
final class IcmpProbePacer
{
    private boolean scheduled;
    private long generation;
    private long nextProbeAt;

    synchronized boolean ready(LagDetector.ConnectionToken connection, long now)
    {
        return !scheduled || connection.generation != generation || now >= nextProbeAt;
    }

    synchronized void finished(LagDetector.ConnectionToken connection, long now, int delayMs)
    {
        int variation = Math.max(1, delayMs / 10);
        nextProbeAt = now + delayMs + ThreadLocalRandom.current().nextInt(-variation, variation + 1);
        generation = connection.generation;
        scheduled = true;
    }
}
