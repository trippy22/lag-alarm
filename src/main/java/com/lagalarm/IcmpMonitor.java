package com.lagalarm;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
    private static final int RESOLVE_RETRY_MS = 5000;
    private static final int REFRESH_AFTER_FAILURES = 5;

    private static final Logger log = LoggerFactory.getLogger(IcmpMonitor.class);

    private final WorldService worldService;
    private final LagDetector detector;
    private final IcmpProbePacer pacer = new IcmpProbePacer();
    private volatile boolean active = true;

    // Worker state. The world's address is resolved once so DNS is never part of a timed probe.
    private World target;
    private int targetWorld = -1;
    private long resolveRetryAt;
    private int failedProbes;

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
            World world = resolve(connection.world);
            if (!active || world == null || !detector.beginIcmpProbe(connection, now()))
            {
                return;
            }
            started = true;
            // Reuse RuneLite's helper without requiring World Hopper to be enabled.
            // On Windows/macOS/Linux, false disables the TCP-connect fallback.
            // Failed ICMP is an unknown measurement, not proof of game-connection loss.
            int result = Ping.ping(world, false);
            // A long run of failures may mean the address changed; look it up again before the next probe.
            failedProbes = result < 0 ? failedProbes + 1 : 0;
            if (failedProbes >= REFRESH_AFTER_FAILURES)
            {
                targetWorld = -1;
                failedProbes = 0;
            }
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

    // A slow or failed lookup delays probing; it is never reported to the detector as a slow or lost ping.
    private World resolve(int worldId)
    {
        if (targetWorld == worldId)
        {
            return target;
        }
        if (now() < resolveRetryAt)
        {
            return null;
        }
        WorldResult worlds = worldService.getWorlds();
        World world = worlds == null ? null : worlds.findWorld(worldId);
        if (world == null)
        {
            return null;
        }
        try
        {
            String address = InetAddress.getByName(world.getAddress()).getHostAddress();
            // An IP literal makes RuneLite's helper skip name resolution.
            target = World.builder().id(worldId).address(address).build();
            targetWorld = worldId;
            failedProbes = 0;
            return target;
        }
        catch (UnknownHostException ex)
        {
            resolveRetryAt = now() + RESOLVE_RETRY_MS;
            log.debug("Unable to resolve world {}; ping checks will retry", worldId, ex);
            return null;
        }
    }

    void stop()
    {
        // A stopped instance is never reused, including its pacing state.
        active = false;
    }
}
