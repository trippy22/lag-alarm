package com.lagalarm;

import java.util.concurrent.ThreadLocalRandom;

/** Completion-based pacing shared by the plugin and live benchmark. */
final class ProbePacer
{
    private boolean scheduled;
    private long generation;
    private long nextProbeAt;

    synchronized void reset() { scheduled = false; }

    synchronized boolean ready(LagDetector.Probe probe, long now)
    {
        return !scheduled || probe.generation != generation || now >= nextProbeAt;
    }

    synchronized void finished(LagDetector.Probe probe, long now, int delayMs)
    {
        int variation = Math.max(1, delayMs / 10);
        nextProbeAt = now + delayMs + ThreadLocalRandom.current().nextInt(-variation, variation + 1);
        generation = probe.generation;
        scheduled = true;
    }
}
