package com.lagalarm;

import java.io.FileDescriptor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.worldhopper.ping.Ping;
import net.runelite.client.plugins.worldhopper.ping.TCPInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.lagalarm.LagAlarmPlugin.now;

/** Borrows the game socket to read OS TCP statistics. Does not send probes or read game packets. */
final class GameSocketMonitor
{
    static final int POLL_INTERVAL_MS = 250;

    private static final Logger log = LoggerFactory.getLogger(GameSocketMonitor.class);
    private static final int MAX_FAILURES = 6;
    private static final int MAX_RETRY_DELAY_MS = 30000;

    private final Client client;
    private final LagDetector detector;
    private volatile boolean active = true;
    private volatile CapturedSocket socket;

    // Client-thread state.
    private long nextCaptureAt;
    private int captureFailures;

    // Worker state. Fixed-delay scheduling prevents overlapping polls.
    private CapturedSocket previous;
    private long retryAt;
    private int queryFailures;

    GameSocketMonitor(Client client, LagDetector detector)
    {
        this.client = client;
        this.detector = detector;
    }

    // Called on the client thread; the worker never calls Client.getSocketFD().
    void captureSocket(long time)
    {
        if (!active || time < nextCaptureAt)
        {
            return;
        }
        nextCaptureAt = time + POLL_INTERVAL_MS;
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            socket = null;
            return;
        }
        try
        {
            FileDescriptor gameSocket = client.getSocketFD();
            LagDetector.ConnectionToken connection = detector.trackSocketIdentity(gameSocket);
            socket = gameSocket == null || connection == null ? null : new CapturedSocket(gameSocket, connection);
            captureFailures = 0;
        }
        catch (RuntimeException | LinkageError ex)
        {
            CapturedSocket captured = socket;
            socket = null;
            if (captured != null)
            {
                detector.markTcpUnavailable(captured.connection, captured.gameSocket);
            }
            captureFailures = Math.min(MAX_FAILURES, captureFailures + 1);
            nextCaptureAt = time + retryDelay(captureFailures);
            log.debug("Game socket unavailable; tick and ICMP checks remain active", ex);
        }
    }

    // Called on the client thread before updating the detector's connection state.
    void onGameStateChanged(GameState state)
    {
        if (state != GameState.LOGGED_IN)
        {
            socket = null;
        }
        nextCaptureAt = 0;
    }

    // Called by the scheduled worker; native queries stay off the client thread.
    void pollSocketStatistics()
    {
        if (!active)
        {
            return;
        }
        CapturedSocket captured = socket;
        if (captured == null)
        {
            return;
        }
        if (previous == null || previous.gameSocket != captured.gameSocket
            || previous.connection.generation != captured.connection.generation)
        {
            previous = captured;
            queryFailures = 0;
            retryAt = 0;
        }
        if (now() < retryAt)
        {
            return;
        }
        try
        {
            // Same helper as World Hopper. This queries existing OS counters without sending packets.
            TCPInfo info = Ping.getTCPInfo(captured.gameSocket);
            if (info != null && info.getRTT() > 0 && info.getTransmitted() >= 0 && info.getRetransmitted() >= 0)
            {
                // Native RTT is microseconds. Preserve valid sub-millisecond measurements.
                int rtt = (int) Math.min(Integer.MAX_VALUE, (info.getRTT() + 999) / 1000);
                if (active)
                {
                    // The detector rejects results from an old world, generation or socket.
                    detector.recordTcpSample(captured.connection, captured.gameSocket,
                        rtt, info.getTransmitted(), info.getRetransmitted(), now());
                }
                queryFailures = 0;
                return;
            }
        }
        catch (RuntimeException | LinkageError ex)
        {
            log.debug("Game socket statistics unavailable; tick and ICMP checks remain active", ex);
        }
        if (active)
        {
            detector.markTcpUnavailable(captured.connection, captured.gameSocket);
        }
        queryFailures = Math.min(MAX_FAILURES, queryFailures + 1);
        retryAt = now() + retryDelay(queryFailures);
    }

    void stop()
    {
        // Each plugin start creates a new monitor; an old worker can never become active again.
        active = false;
        socket = null;
    }

    private static long retryDelay(int failures)
    {
        return Math.min(MAX_RETRY_DELAY_MS, 1000L << (failures - 1));
    }

    // The handle is borrowed. RuneLite owns the socket's lifetime; this plugin never closes it.
    private static final class CapturedSocket
    {
        final FileDescriptor gameSocket;
        final LagDetector.ConnectionToken connection;

        CapturedSocket(FileDescriptor gameSocket, LagDetector.ConnectionToken connection)
        {
            this.gameSocket = gameSocket;
            this.connection = connection;
        }
    }
}
