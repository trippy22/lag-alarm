package com.lagalarm;

/** Thread-safe, constant-space detector. All times are monotonic milliseconds. No RuneLite or I/O dependency. */
final class LagDetector
{
    private static final int GAME_TICK_LATE_MS = 900;
    private static final int CLIENT_TICK_STALE_MS = 500;
    private static final int MIN_HEALTHY_TICK_INTERVAL_MS = 300;
    private static final int MAX_HEALTHY_TICK_INTERVAL_MS = 1000;
    private static final int ICMP_SAMPLE_MAX_AGE_MS = 5000;
    private static final int ICMP_COMPLETION_MAX_AGE_MS = 10000;
    private static final int ICMP_HISTORY_MAX_AGE_MS = 30000;
    private static final int TEST_ALERT_DURATION_MS = 5000;

    enum Mode
    {
        IDLE,
        MONITORING,
        LOADING
    }

    enum Reason
    {
        NONE,
        TICK_STALL,
        CLIENT_STALL,
        HIGH_PING,
        TCP_LATENCY,
        TCP_RETRANSMISSIONS,
        PROBE_DELAY,
        PROBE_FAILURES,
        RECOVERING,
        TEST
    }

    enum Confidence
    {
        NONE,
        CAUTION,
        ALARM
    }

    static final class Settings
    {
        final int stallMs;
        final int loginGraceMs;
        final int loadingGraceMs;
        final boolean pingEnabled;
        final int pingThresholdMs;
        final int highPingSamples;
        final boolean earlyWarning;
        final int probeDeadlineMs;
        final int failurePercent;
        final int failureWindow;
        final boolean adaptive;

        Settings(int stallMs, int loginGraceMs, int loadingGraceMs, boolean pingEnabled, int pingThresholdMs)
        {
            this(stallMs, loginGraceMs, loadingGraceMs, pingEnabled, pingThresholdMs, 2, false, 600, 20, 20);
        }

        Settings(int stallMs, int loginGraceMs, int loadingGraceMs, boolean pingEnabled, int pingThresholdMs,
            int highPingSamples, boolean earlyWarning, int probeDeadlineMs, int failurePercent, int failureWindow)
        {
            this(stallMs, loginGraceMs, loadingGraceMs, pingEnabled, pingThresholdMs, highPingSamples,
                earlyWarning, probeDeadlineMs, failurePercent, failureWindow, false);
        }

        private Settings(int stallMs, int loginGraceMs, int loadingGraceMs, boolean pingEnabled, int pingThresholdMs,
            int highPingSamples, boolean earlyWarning, int probeDeadlineMs, int failurePercent, int failureWindow, boolean adaptive)
        {
            this.adaptive = adaptive;
            this.stallMs = Math.max(900, Math.min(10000, stallMs));
            this.loginGraceMs = Math.max(0, Math.min(15000, loginGraceMs));
            this.loadingGraceMs = Math.max(0, Math.min(10000, loadingGraceMs));
            this.pingEnabled = pingEnabled;
            this.pingThresholdMs = Math.max(100, Math.min(2000, pingThresholdMs));
            this.highPingSamples = Math.max(1, Math.min(10, highPingSamples));
            this.earlyWarning = earlyWarning;
            this.probeDeadlineMs = Math.max(100, Math.min(2000, probeDeadlineMs));
            this.failurePercent = Math.max(1, Math.min(100, failurePercent));
            this.failureWindow = Math.max(5, Math.min(100, failureWindow));
        }

        static Settings automatic()
        {
            return new Settings(1500, 3000, 3000, true, 400, 2, true, 600, 20, 10, true);
        }
    }

    /** Identifies a measurement's world and generation; creating a token sends no network request. */
    static final class ConnectionToken
    {
        final long generation;
        final int world;

        ConnectionToken(long generation, int world)
        {
            this.generation = generation;
            this.world = world;
        }
    }

    static final class Snapshot
    {
        final Mode mode;
        final Reason reason;
        final int world;
        final long tickAgeMs;
        final int pingMs;
        final boolean warmingUp;
        final Confidence confidence;
        int tcpRttMs = -1;
        String pingStatus = "";
        int normalPingMs = -1;
        int deadlineMs;

        Snapshot(Mode mode, Reason reason, int world, long tickAgeMs, int pingMs, boolean warmingUp)
        {
            this(mode, reason, world, tickAgeMs, pingMs, warmingUp,
                reason == Reason.NONE ? Confidence.NONE : Confidence.ALARM);
        }

        Snapshot(Mode mode, Reason reason, int world, long tickAgeMs, int pingMs, boolean warmingUp, Confidence confidence)
        {
            this.mode = mode;
            this.reason = reason;
            this.world = world;
            this.tickAgeMs = tickAgeMs;
            this.pingMs = pingMs;
            this.warmingUp = warmingUp;
            this.confidence = confidence;
        }

        boolean isAlarm()
        {
            return confidence == Confidence.ALARM;
        }
        boolean hasWarning()
        {
            return reason != Reason.NONE;
        }
    }

    private Settings settings = new Settings(1500, 3000, 3000, false, 400);
    private Mode mode = Mode.IDLE;
    private int world;
    private long generation;
    private long lastTick;
    private long suppressUntil;
    private long loadingUntil;
    private boolean haveTick;
    private long lastPing;
    private int pingMs = -1;
    private int highPings;
    private int healthyPings;
    private boolean pingAlarm;
    private boolean alarm;
    private int healthyTicks;
    private long testUntil;
    private boolean testing;
    private ConnectionToken pendingProbe;
    private long probeStarted;
    private int successes;
    private final boolean[] failedProbes = new boolean[100];
    private final long[] probeTimes = new long[100];
    private int sampleCount;
    private int sampleCursor;
    private int failures;
    private long lastProbeCompleted;
    private long lastClientTick;
    private boolean haveClientTick;
    private final LatencyBaseline baseline = new LatencyBaseline();
    private final TcpHealth tcp = new TcpHealth();
    private Object socketIdentity;

    synchronized void configure(Settings next)
    {
        if (settings.stallMs == next.stallMs && settings.loginGraceMs == next.loginGraceMs
            && settings.loadingGraceMs == next.loadingGraceMs && settings.pingEnabled == next.pingEnabled
            && settings.pingThresholdMs == next.pingThresholdMs && settings.highPingSamples == next.highPingSamples
            && settings.earlyWarning == next.earlyWarning && settings.probeDeadlineMs == next.probeDeadlineMs
            && settings.failurePercent == next.failurePercent && settings.failureWindow == next.failureWindow
            && settings.adaptive == next.adaptive)
        {
            return; // Sound/color changes must not reset network calibration.
        }
        settings = next;
        // Invalidate in-flight samples taken with the old settings.
        generation++;
        clearPing();
        resetProbes();
        tcp.reset();
    }

    synchronized void loggedIn(int currentWorld, long now)
    {
        if (mode == Mode.IDLE || world != currentWorld)
        {
            stop();
            world = currentWorld;
            mode = Mode.MONITORING;
            lastTick = now;
            suppressUntil = now + settings.loginGraceMs;
        }
        else if (mode == Mode.LOADING)
        {
            mode = Mode.MONITORING;
            // Permit one normal tick interval after loading, without an unbounded new grace.
            lastTick = now;
            haveTick = false;
        }
    }

    synchronized void loading(long now)
    {
        if (mode == Mode.MONITORING)
        {
            mode = Mode.LOADING;
            loadingUntil = now + settings.loadingGraceMs;
            generation++;
            clearPing();
            clearProbeHistory();
            baseline.pauseLearning();
            tcp.suspend();
        }
    }

    synchronized void connectionLost()
    {
        // This alarm is for early warning. A confirmed disconnect is already visible in the client.
        stop();
    }

    synchronized void stop()
    {
        generation++;
        mode = Mode.IDLE;
        world = 0;
        haveTick = false;
        haveClientTick = false;
        alarm = false;
        healthyTicks = 0;
        testing = false;
        clearPing();
        resetProbes();
        tcp.reset();
        socketIdentity = null;
    }

    synchronized void tick(long now)
    {
        if (mode == Mode.IDLE)
        {
            return;
        }
        long gap = now - lastTick;
        // Catch a long gap even if the watchdog itself was paused by a JVM stall.
        if (haveTick && mode == Mode.MONITORING && now >= suppressUntil && gap >= settings.stallMs)
        {
            alarm = true;
            healthyTicks = 0;
        }
        // Bursts of queued ticks are not evidence that live updates have stabilized.
        boolean normalInterval = haveTick && gap >= MIN_HEALTHY_TICK_INTERVAL_MS
            && gap < Math.min(MAX_HEALTHY_TICK_INTERVAL_MS, settings.stallMs);
        healthyTicks = normalInterval ? Math.min(2, healthyTicks + 1) : 0;
        haveTick = true;
        lastTick = now;
        mode = Mode.MONITORING;
    }

    synchronized void clientTick(long now)
    {
        if (mode != Mode.IDLE)
        {
            lastClientTick = now;
            haveClientTick = true;
        }
    }

    synchronized ConnectionToken trackSocketIdentity(Object identity)
    {
        if (mode != Mode.MONITORING)
        {
            return null;
        }
        if (socketIdentity != identity)
        {
            boolean replacing = socketIdentity != null;
            socketIdentity = identity;
            tcp.reset();
            if (replacing)
            {
                generation++;
                clearPing();
                resetProbes();
            }
        }
        return currentConnection();
    }

    synchronized void recordTcpSample(ConnectionToken connection, Object identity, int rtt,
        long transmitted, long retransmitted, long now)
    {
        if (!currentSocket(connection, identity))
        {
            return;
        }
        tcp.record(rtt, transmitted, retransmitted, gameUpdatesHealthy(now), now);
    }

    synchronized void markTcpUnavailable(ConnectionToken connection, Object identity)
    {
        if (currentSocket(connection, identity))
        {
            tcp.suspend();
        }
    }

    private boolean currentSocket(ConnectionToken connection, Object identity)
    {
        // State resets cannot cancel a native query already in progress. Discard its late result here.
        return mode == Mode.MONITORING && connection != null && connection.generation == generation
            && connection.world == world && identity != null && identity == socketIdentity;
    }

    private boolean gameUpdatesHealthy(long now)
    {
        return haveTick && now - lastTick < GAME_TICK_LATE_MS
            && (!haveClientTick || now - lastClientTick < CLIENT_TICK_STALE_MS);
    }

    synchronized ConnectionToken currentConnection()
    {
        return settings.pingEnabled && mode == Mode.MONITORING ? new ConnectionToken(generation, world) : null;
    }

    synchronized boolean beginIcmpProbe(ConnectionToken connection, long now)
    {
        if (connection == null || connection.generation != generation || mode != Mode.MONITORING || pendingProbe != null)
        {
            return false;
        }
        pendingProbe = connection;
        probeStarted = now;
        return true;
    }

    synchronized void recordIcmpSample(ConnectionToken connection, int result, long now)
    {
        if (connection == null || connection.generation != generation || connection.world != world
            || !settings.pingEnabled || mode != Mode.MONITORING)
        {
            return;
        }
        if (pendingProbe != null && pendingProbe != connection)
        {
            return;
        }
        pendingProbe = null;
        lastProbeCompleted = now;
        if (settings.adaptive)
        {
            boolean wasAvailable = baseline.isAvailable();
            baseline.record(result, gameUpdatesHealthy(now) && tcp.learningSafe(now), now);
            if (!wasAvailable && baseline.isAvailable())
            {
                // Do not carry old unavailable-period failures into a newly calibrated connection.
                sampleCount = sampleCursor = failures = 0;
            }
        }
        if (result >= 0)
        {
            successes = Math.min(3, successes + 1);
        }
        recordProbe(result < 0, now);
        // Old evidence must not combine with a sample that arrived much later.
        if (now - lastPing > ICMP_SAMPLE_MAX_AGE_MS)
        {
            clearPing();
        }
        lastPing = now;
        pingMs = result;
        if (result < 0)
        {
            clearPing(); // ICMP can be blocked; this is unknown, not proof of lag.
        }
        else if ((!settings.adaptive || baseline.isAvailable() || result >= 1000)
            && result >= (settings.adaptive ? baseline.highThreshold() : settings.pingThresholdMs))
        {
            healthyPings = 0;
            highPings = Math.min(settings.highPingSamples, highPings + 1);
            if (highPings >= settings.highPingSamples)
            {
                pingAlarm = true;
            }
        }
        else
        {
            highPings = 0;
            healthyPings = Math.min(2, healthyPings + 1);
            if (healthyPings >= 2)
            {
                pingAlarm = false;
            }
        }
    }

    synchronized void test(long now)
    {
        if (mode != Mode.IDLE)
        {
            testing = true;
            testUntil = now + TEST_ALERT_DURATION_MS;
        }
    }

    synchronized Snapshot evaluate(long now)
    {
        if (pingMs >= 0 && now - lastPing > ICMP_SAMPLE_MAX_AGE_MS)
        {
            clearPing();
        }
        long age = mode == Mode.IDLE ? 0 : Math.max(0, now - lastTick);
        boolean grace = mode != Mode.IDLE && now < suppressUntil;
        Reason reason = Reason.NONE;
        Confidence confidence = Confidence.NONE;
        if (mode != Mode.IDLE && !grace)
        {
            if (age >= settings.stallMs && (mode != Mode.LOADING || now >= loadingUntil))
            {
                reason = haveClientTick && now - lastClientTick >= CLIENT_TICK_STALE_MS
                    ? Reason.CLIENT_STALL : Reason.TICK_STALL;
            }
            else if (mode == Mode.MONITORING && settings.adaptive && tcp.repeatedRetries(now))
            {
                reason = Reason.TCP_RETRANSMISSIONS;
            }
            else if (mode == Mode.MONITORING && settings.adaptive && tcp.highLatency(now))
            {
                reason = Reason.TCP_LATENCY;
            }
            else if (mode == Mode.MONITORING && pingAlarm
                && (!settings.adaptive || baseline.isAvailable() || pingMs >= 1000))
            {
                reason = Reason.HIGH_PING;
            }
            else if (settings.pingEnabled && (settings.adaptive ? baseline.isAvailable() : successes >= 3))
            {
                int deadline = settings.adaptive ? baseline.deadline() : settings.probeDeadlineMs;
                if (settings.earlyWarning && pendingProbe != null && now - probeStarted >= deadline)
                {
                    reason = Reason.PROBE_DELAY;
                }
                else if (sampleCount == settings.failureWindow && now - lastProbeCompleted <= ICMP_COMPLETION_MAX_AGE_MS
                    && now - probeTimes[sampleCursor] <= ICMP_HISTORY_MAX_AGE_MS
                    && failures * 100 >= settings.failurePercent * sampleCount)
                {
                    reason = Reason.PROBE_FAILURES;
                }
            }
        }
        if (reason != Reason.NONE)
        {
            confidence = Confidence.ALARM;
            if (settings.adaptive)
            {
                boolean probeOnly = reason == Reason.PROBE_DELAY || reason == Reason.PROBE_FAILURES;
                boolean gameLate = haveTick && age >= GAME_TICK_LATE_MS;
                // ICMP alone is weaker evidence: require late game updates for failed/delayed probes,
                // and keep high ICMP latency silent while fresh TCP evidence and game updates are healthy.
                if ((probeOnly && !gameLate)
                    || (reason == Reason.HIGH_PING && tcp.healthy(now) && gameUpdatesHealthy(now)))
                {
                    confidence = Confidence.CAUTION;
                }
            }
        }
        if (confidence == Confidence.ALARM)
        {
            alarm = true;
            healthyTicks = 0;
        }
        else if (alarm)
        {
            if (healthyTicks >= 2)
            {
                alarm = false;
            }
            else
            {
                reason = Reason.RECOVERING;
                confidence = Confidence.ALARM;
            }
        }
        if (testing && now >= testUntil)
        {
            testing = false;
        }
        if (confidence != Confidence.ALARM && testing)
        {
            reason = Reason.TEST;
            confidence = Confidence.ALARM;
        }
        Snapshot snapshot = new Snapshot(mode, reason, world, age, pingMs, grace, confidence);
        snapshot.tcpRttMs = tcp.rtt();
        snapshot.pingStatus = settings.adaptive ? baseline.status() : "Fixed thresholds";
        snapshot.normalPingMs = settings.adaptive ? baseline.normalPing() : -1;
        snapshot.deadlineMs = settings.adaptive ? baseline.deadline() : settings.probeDeadlineMs;
        return snapshot;
    }

    synchronized int icmpProbeDelay()
    {
        return settings.adaptive ? baseline.retryDelay() : 500;
    }

    private void clearPing()
    {
        pingMs = -1;
        highPings = 0;
        healthyPings = 0;
        pingAlarm = false;
    }

    private void resetProbes()
    {
        baseline.reset();
        clearProbeHistory();
    }

    private void clearProbeHistory()
    {
        pendingProbe = null;
        successes = 0;
        sampleCount = 0;
        sampleCursor = 0;
        failures = 0;
    }

    private void recordProbe(boolean failed, long now)
    {
        if (sampleCount == settings.failureWindow)
        {
            if (failedProbes[sampleCursor])
            {
                failures--;
            }
        }
        else
        {
            sampleCount++;
        }
        failedProbes[sampleCursor] = failed;
        probeTimes[sampleCursor] = now;
        if (failed)
        {
            failures++;
        }
        sampleCursor = (sampleCursor + 1) % settings.failureWindow;
    }
}
