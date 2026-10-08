package com.lagalarm;

import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.worldhopper.ping.Ping;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.lagalarm.LagAlarmPlugin.now;

/** Active probes of the current world. Separate from passive game-socket statistics. */
final class IcmpMonitor
{
    static final int SCHEDULE_CHECK_INTERVAL_MS = 25;

    private static final Logger log = LoggerFactory.getLogger(IcmpMonitor.class);

    private final WorldService worldService;
    private final LagDetector detector;
    private final IcmpProbePacer pacer = new IcmpProbePacer();
    private volatile boolean active = true;

    IcmpMonitor(WorldService worldService, LagDetector detector)
    {
        this.worldService = worldService;
        this.detector = detector;
    }

    // Called by one scheduled worker. The pacer determines the actual probe interval.
    void probeCurrentWorld()
    {
        LagDetector.ConnectionToken connection = null;
        boolean started = false;
        try
        {
            if (!active)
            {
                return;
            }
            connection = detector.currentConnection();
            if (connection == null || !pacer.ready(connection, now()))
            {
                return;
            }
            WorldResult worlds = worldService.getWorlds();
            World world = worlds == null ? null : worlds.findWorld(connection.world);
            if (!active || world == null || !detector.beginIcmpProbe(connection, now()))
            {
                return;
            }
            started = true;
            // Reuse RuneLite's helper without requiring World Hopper to be enabled.
            // On Windows/macOS/Linux, false disables the TCP-connect fallback.
            // Failed ICMP is an unknown measurement, not proof of game-connection loss.
            int result = Ping.ping(world, false);
            if (active)
            {
                detector.recordIcmpSample(connection, result, now());
            }
        }
        catch (RuntimeException | LinkageError ex)
        {
            if (started && active)
            {
                detector.recordIcmpSample(connection, -1, now());
            }
            log.debug("Ping unavailable; passive tick monitoring remains active", ex);
        }
        finally
        {
            if (started && active)
            {
                pacer.finished(connection, now(), detector.icmpProbeDelay());
            }
        }
    }

    void stop()
    {
        // A stopped instance is never reused, including its pacing state.
        active = false;
    }
}
