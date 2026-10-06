package com.lagalarm;

import com.google.inject.Provides;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.FileDescriptor;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ClientTick;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.WorldService;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.worldhopper.ping.Ping;
import net.runelite.client.plugins.worldhopper.ping.TCPInfo;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(name = "Lag Alarm", internalName = "lag-alarm",
    description = "Distinct visual and audio warnings for delayed game updates and sustained high ping",
    tags = {"lag", "latency", "hardcore", "ironman", "alarm", "ping"})
public class LagAlarmPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(LagAlarmPlugin.class);
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private LagAlarmConfig config;
    @Inject private ConfigManager configManager;
    @Inject private OverlayManager overlayManager;
    @Inject private LagAlarmOverlay overlay;
    @Inject private WorldService worldService;
    @Inject private AudioPlayer audioPlayer;
    @Inject private KeyManager keyManager;

    private final LagDetector detector = new LagDetector();
    private volatile LagDetector.Snapshot snapshot = detector.evaluate(0);
    private volatile boolean running;
    private volatile long runGeneration;
    private volatile SoundSettings soundSettings = new SoundSettings(true, 60, 4);
    private ScheduledExecutorService workers;
    private ScheduledFuture<?> watchdogFuture;
    private ScheduledFuture<?> pingFuture;
    private ScheduledFuture<?> tcpFuture;
    private volatile SocketTarget socketTarget;
    private long nextSocketCapture;
    private int socketCaptureFailures;
    private final AtomicBoolean soundPending = new AtomicBoolean();
    private long lastSound;
    private boolean previouslyAlarmed;
    private final ProbePacer probePacer = new ProbePacer();

    private final HotkeyListener testKey = new HotkeyListener(() -> new Keybind(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK))
    {
        @Override public void hotkeyPressed()
        {
            if (running) { detector.test(now()); }
        }
    };

    @Provides
    LagAlarmConfig provideConfig(ConfigManager manager) { return manager.getConfig(LagAlarmConfig.class); }

    @Override
    protected void startUp()
    {
        detector.stop();
        migrateAlertPreference();
        refreshSettings();
        previouslyAlarmed = false;
        probePacer.reset();
        soundPending.set(false);
        socketTarget = null;
        nextSocketCapture = 0;
        socketCaptureFailures = 0;
        long generation = ++runGeneration;
        running = true;
        // Keep native socket queries, ICMP and audio off both the client thread and the watchdog.
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(4, runnable ->
        {
            Thread thread = new Thread(runnable, "lag-alarm-worker");
            thread.setDaemon(true);
            return thread;
        });
        workers = executor;
        overlayManager.add(overlay);
        keyManager.registerKeyListener(testKey);
        clientThread.invokeLater(() ->
        {
            if (isCurrent(generation)) { updateState(client.getGameState()); }
        });
        watchdogFuture = executor.scheduleWithFixedDelay(() -> watch(generation, executor), 0, 25, TimeUnit.MILLISECONDS);
        // Fixed delay avoids backlogs or overlapping probes when ICMP times out.
        pingFuture = executor.scheduleWithFixedDelay(() -> probe(generation), 25, 25, TimeUnit.MILLISECONDS);
        TcpPoller tcpPoller = new TcpPoller();
        tcpFuture = executor.scheduleWithFixedDelay(() -> tcpPoller.poll(generation), 250, 250, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void shutDown()
    {
        running = false;
        runGeneration++;
        if (watchdogFuture != null) { watchdogFuture.cancel(true); watchdogFuture = null; }
        if (pingFuture != null) { pingFuture.cancel(true); pingFuture = null; }
        if (tcpFuture != null) { tcpFuture.cancel(true); tcpFuture = null; }
        socketTarget = null;
        if (workers != null) { workers.shutdownNow(); workers = null; }
        keyManager.unregisterKeyListener(testKey);
        overlayManager.remove(overlay);
        detector.stop();
        snapshot = detector.evaluate(now());
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (running && client.getGameState() == GameState.LOGGED_IN)
        {
            detector.loggedIn(client.getWorld(), now());
            detector.tick(now());
        }
    }

    @Subscribe
    public void onClientTick(ClientTick event)
    {
        if (!running) { return; }
        long time = now();
        detector.clientTick(time);
        if (time >= nextSocketCapture)
        {
            nextSocketCapture = time + 250;
            // Client APIs stay on the client thread; only the immutable target crosses threads.
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                try
                {
                    FileDescriptor fd = client.getSocketFD();
                    LagDetector.Probe request = detector.observeSocket(fd);
                    socketTarget = fd == null || request == null ? null : new SocketTarget(fd, request);
                    socketCaptureFailures = 0;
                }
                catch (RuntimeException | LinkageError ex)
                {
                    SocketTarget previous = socketTarget;
                    socketTarget = null;
                    if (previous != null) { detector.tcpUnavailable(previous.request, previous.fd); }
                    socketCaptureFailures = Math.min(6, socketCaptureFailures + 1);
                    nextSocketCapture = time + Math.min(30000, 1000L << (socketCaptureFailures - 1));
                    log.debug("Game socket unavailable; tick and ICMP checks remain active", ex);
                }
            }
            else { socketTarget = null; }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (running) { updateState(event.getGameState()); }
    }

    private void updateState(GameState state)
    {
        if (state != GameState.LOGGED_IN) { socketTarget = null; }
        nextSocketCapture = 0;
        switch (state)
        {
            case LOGGED_IN: detector.loggedIn(client.getWorld(), now()); break;
            case LOADING: detector.loading(now()); break;
            case CONNECTION_LOST: detector.connectionLost(); break;
            // Includes intentional hops, logout, and fresh login attempts.
            default: detector.stop(); break;
        }
        snapshot = detector.evaluate(now());
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (LagAlarmConfig.GROUP.equals(event.getGroup())) { refreshSettings(); }
    }

    private void refreshSettings()
    {
        detector.configure(LagDetector.Settings.automatic());
        soundSettings = new SoundSettings(config.soundEnabled(), 60, 4);
    }

    private void migrateAlertPreference()
    {
        // Preserve the earlier preference for a steady banner when introducing the visual-mode selector.
        if (configManager.getConfiguration(LagAlarmConfig.GROUP, "visualAlert") == null
            && "false".equals(configManager.getConfiguration(LagAlarmConfig.GROUP, "pulseBorder")))
        {
            configManager.setConfiguration(LagAlarmConfig.GROUP, "visualAlert", VisualAlert.BANNER);
        }
    }

    private void watch(long generation, ScheduledExecutorService executor)
    {
        try
        {
            if (!isCurrent(generation)) { return; }
            long time = now();
            LagDetector.Snapshot next = detector.evaluate(time);
            if (!isCurrent(generation)) { return; }
            snapshot = next;
            SoundSettings sound = soundSettings;
            if (next.isAlarm() && sound.enabled
                && (!previouslyAlarmed || time - lastSound >= sound.repeatMs)
                && soundPending.compareAndSet(false, true))
            {
                lastSound = time;
                executor.execute(() ->
                {
                    try
                    {
                        if (isCurrent(generation) && detector.evaluate(now()).isAlarm() && soundSettings.enabled)
                        {
                            audioPlayer.play(LagAlarmPlugin.class, "lag-voice.wav", sound.gain);
                        }
                    }
                    catch (Exception ex) { log.debug("Unable to play spoken lag alert", ex); }
                    finally { soundPending.set(false); }
                });
            }
            previouslyAlarmed = next.isAlarm();
        }
        catch (RuntimeException ex)
        {
            // A thrown exception must not silently cancel all future scheduled checks.
            log.debug("Lag watchdog check failed", ex);
        }
    }

    private void probe(long generation)
    {
        LagDetector.Probe request = null;
        boolean started = false;
        try
        {
            if (!isCurrent(generation)) { return; }
            request = detector.probe();
            if (request == null) { return; }
            if (!probePacer.ready(request, now())) { return; }
            WorldResult worlds = worldService.getWorlds();
            World world = worlds == null ? null : worlds.findWorld(request.world);
            if (world == null) { return; }
            if (!detector.beginProbe(request, now())) { return; }
            started = true;
            // Reuse the World Hopper helper, without depending on World Hopper being enabled.
            // No TCP fallback on Windows/macOS/Linux: failed ICMP is an unknown measurement.
            int result = Ping.ping(world, false);
            if (isCurrent(generation)) { detector.ping(request, result, now()); }
        }
        catch (RuntimeException | LinkageError ex)
        {
            if (started && isCurrent(generation)) { detector.ping(request, -1, now()); }
            log.debug("Ping unavailable; passive tick monitoring remains active", ex);
        }
        finally
        {
            if (started && isCurrent(generation))
            {
                probePacer.finished(request, now(), detector.probeDelay());
            }
        }
    }

    private boolean isCurrent(long generation) { return running && generation == runGeneration; }
    LagDetector.Snapshot getSnapshot() { return snapshot; }
    static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }

    private static final class SocketTarget
    {
        final FileDescriptor fd;
        final LagDetector.Probe request;
        SocketTarget(FileDescriptor fd, LagDetector.Probe request) { this.fd = fd; this.request = request; }
    }

    private final class TcpPoller
    {
        private SocketTarget previous;
        private long retryAt;
        private int failures;

        void poll(long generation)
        {
            if (!isCurrent(generation)) { return; }
            SocketTarget target = socketTarget;
            if (target == null) { return; }
            if (previous == null || previous.fd != target.fd || previous.request.generation != target.request.generation)
            {
                previous = target;
                failures = 0;
                retryAt = 0;
            }
            if (now() < retryAt) { return; }
            try
            {
                TCPInfo info = target.fd.valid() ? Ping.getTCPInfo(target.fd) : null;
                if (info != null && info.getRTT() > 0 && info.getTransmitted() >= 0 && info.getRetransmitted() >= 0)
                {
                    // Native RTT is microseconds. Round up so a valid sub-millisecond sample stays valid.
                    int rtt = (int) Math.min(Integer.MAX_VALUE, (info.getRTT() + 999) / 1000);
                    if (isCurrent(generation))
                    {
                        detector.tcpSample(target.request, target.fd, rtt, info.getTransmitted(), info.getRetransmitted(), now());
                    }
                    failures = 0;
                    return;
                }
            }
            catch (RuntimeException | LinkageError ex)
            {
                log.debug("Game socket statistics unavailable; tick and ICMP checks remain active", ex);
            }
            if (isCurrent(generation)) { detector.tcpUnavailable(target.request, target.fd); }
            failures = Math.min(6, failures + 1);
            retryAt = now() + Math.min(30000, 1000L << (failures - 1));
        }
    }

    private static final class SoundSettings
    {
        final boolean enabled;
        final float gain;
        final long repeatMs;
        SoundSettings(boolean enabled, int volume, int repeatSeconds)
        {
            this.enabled = enabled;
            gain = (float) (20 * Math.log10(Math.max(1, Math.min(100, volume)) / 100.0));
            repeatMs = Math.max(1, Math.min(30, repeatSeconds)) * 1000L;
        }
    }
}
