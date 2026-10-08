package com.lagalarm;

import com.google.inject.Provides;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
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
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(name = "Lag Alarm", internalName = "lag-alarm",
    description = "Distinct visual and audio warnings for delayed game updates and sustained high ping",
    tags = {"lag", "latency", "hardcore", "ironman", "alarm", "ping"})
public class LagAlarmPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(LagAlarmPlugin.class);
    private static final int WATCHDOG_INTERVAL_MS = 25;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private LagAlarmConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private LagAlarmOverlay overlay;

    @Inject
    private WorldService worldService;

    @Inject
    private AudioPlayer audioPlayer;

    @Inject
    private KeyManager keyManager;

    private final LagDetector detector = new LagDetector();
    private volatile LagDetector.Snapshot snapshot = detector.evaluate(0);
    private volatile boolean running;
    private volatile long runGeneration;
    private volatile SoundSettings soundSettings = new SoundSettings(true, 60, 4);
    private ScheduledExecutorService workers;
    private GameSocketMonitor socketMonitor;
    private IcmpMonitor icmpMonitor;
    private final AtomicBoolean soundPending = new AtomicBoolean();
    private long lastSound;
    private boolean previouslyAlarmed;

    private final HotkeyListener testKey = new HotkeyListener(() -> new Keybind(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK))
    {
        @Override
        public void hotkeyPressed()
        {
            if (running)
            {
                detector.test(now());
            }
        }
    };

    @Provides
    LagAlarmConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(LagAlarmConfig.class);
    }

    @Override
    protected void startUp()
    {
        detector.stop();
        migrateAlertPreference();
        refreshSettings();
        previouslyAlarmed = false;
        soundPending.set(false);
        long generation = ++runGeneration;
        // Scheduled tasks retain these instances, so an old run cannot poll a replacement monitor.
        GameSocketMonitor sockets = new GameSocketMonitor(client, detector);
        IcmpMonitor icmp = new IcmpMonitor(worldService, detector);
        socketMonitor = sockets;
        icmpMonitor = icmp;
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
            if (isCurrent(generation))
            {
                updateState(client.getGameState());
            }
        });
        executor.scheduleWithFixedDelay(() -> checkForLag(generation, executor),
            0, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // Fixed delay avoids backlogs or overlapping probes when ICMP times out.
        executor.scheduleWithFixedDelay(icmp::probeCurrentWorld, IcmpMonitor.SCHEDULE_CHECK_INTERVAL_MS,
            IcmpMonitor.SCHEDULE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(sockets::pollSocketStatistics, GameSocketMonitor.POLL_INTERVAL_MS,
            GameSocketMonitor.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void shutDown()
    {
        running = false;
        runGeneration++;
        if (socketMonitor != null)
        {
            socketMonitor.stop();
            socketMonitor = null;
        }
        if (icmpMonitor != null)
        {
            icmpMonitor.stop();
            icmpMonitor = null;
        }
        // This executor belongs only to this plugin run; stop all scheduled tasks together.
        if (workers != null)
        {
            workers.shutdownNow();
            workers = null;
        }
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
        GameSocketMonitor sockets = socketMonitor;
        if (!running || sockets == null)
        {
            return;
        }
        long time = now();
        detector.clientTick(time);
        sockets.captureSocket(time);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (running)
        {
            updateState(event.getGameState());
        }
    }

    private void updateState(GameState state)
    {
        GameSocketMonitor sockets = socketMonitor;
        if (!running || sockets == null)
        {
            return;
        }
        sockets.onGameStateChanged(state);
        switch (state)
        {
            case LOGGED_IN:
                detector.loggedIn(client.getWorld(), now());
                break;
            case LOADING:
                detector.loading(now());
                break;
            case CONNECTION_LOST:
                detector.connectionLost();
                break;
            // Includes intentional hops, logout, and fresh login attempts.
            default:
                detector.stop();
                break;
        }
        snapshot = detector.evaluate(now());
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (LagAlarmConfig.GROUP.equals(event.getGroup()))
        {
            refreshSettings();
        }
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

    private void checkForLag(long generation, ScheduledExecutorService executor)
    {
        try
        {
            if (!isCurrent(generation))
            {
                return;
            }
            long time = now();
            LagDetector.Snapshot next = detector.evaluate(time);
            if (!isCurrent(generation))
            {
                return;
            }
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
                    catch (Exception ex)
                    {
                        log.debug("Unable to play spoken lag alert", ex);
                    }
                    finally
                    {
                        soundPending.set(false);
                    }
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

    private boolean isCurrent(long generation)
    {
        return running && generation == runGeneration;
    }

    LagDetector.Snapshot getSnapshot()
    {
        return snapshot;
    }

    static long now()
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
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
