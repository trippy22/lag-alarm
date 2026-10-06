# Lag Alarm

A RuneLite plugin that warns about unstable connections and delayed game updates **before** the client reports a disconnect. Useful for hardcore ironmen, and works on other accounts too.

The warning sits directly in the chatbox area with explicit **LAG DETECTED** text, an optional amber border, and an optional voice saying **"lag"**. It follows the chatbox in fixed and resizable modes and remains visible there when chat is hidden. It works with World Hopper and Wilderness Player Alarm disabled. No game actions are automated.

## Build and try it

Use **JDK 11** and the included Gradle wrapper. The dependency is pinned to RuneLite **1.13.1**; `-PruneliteVersion=...` can override it for development.

```powershell
.\gradlew.bat test jar
.\gradlew.bat run
```

Enable **Lag Alarm** in the development client's plugin list. While logged in somewhere safe, press **Shift+F10** for a five-second preview. For Jagex accounts, follow RuneLite's [development-client login instructions](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

This is a local development build, not a published Plugin Hub release. The small JAR is a plugin artifact, not a standalone RuneLite installer; normal RuneLite does not automatically load arbitrary local plugin JARs.

## Two alert settings

| Setting | Default | Effect |
|---|---|---|
| Spoken lag alert | On | A short voice saying "lag", repeating every four seconds during a warning |
| Visual alert | Banner and pulsing border | Choose banner with border, banner only, or off |

These are the only user-facing settings, and all control presentation. ICMP monitoring, baseline learning, retry timing, thresholds, and recovery are automatic. The normal user interface contains no networking sliders. Numeric overrides remain available only in the development benchmark.

The plugin displays a warning banner and plays the spoken cue when lag is detected; it draws nothing during normal monitoring. For audio only, select **Visual alert: Off**. Sound can likewise be disabled independently. Shift+F10 previews the selected alert channels. Changes to these settings do not reset the learned baseline.

The banner is centered in the chatbox area. Its shadow and optional border pulse are clipped to that area. Fixed, resizable and moved chat layouts are supported. Hidden widgets still supply their position; collapsed chat uses the usual chatbox footprint above its bottom controls. If no usable chatbox or control bounds exist, the banner uses a standard 519-by-165-pixel chat area at the lower left, clipped to the canvas. This also works when enabling Lag Alarm with chat already hidden. The warning is click-through, with a dark background approximately 95% opaque for readability.

The supplied Fixed Mode Hide Chat reference hides `CHATBOX_FRAME` while retaining the surrounding chatbox layout. Lag Alarm draws independently and does not reopen chat or change that plugin's settings. When hidden chat allows the game view to expand into the former chat area, an active warning temporarily covers that portion of the scene. Detection and enabled sound alerts do not depend on chat visibility. Source inspection and headless layout checks cover this behavior; live coexistence testing is still needed.

The spoken cue is a bundled, approximately 0.6-second mono PCM WAV generated locally with the installed Windows Microsoft Zira voice. It is trimmed to reduce leading/trailing silence. Playback requires no text-to-speech installation, online speech service, or additional network calls on the user's machine.

## How the automatic policy works

- **Learn the user's normal connection.** Five consecutive successful probes with healthy game updates establish an initial baseline. A rolling median and median absolute deviation over up to 61 accepted samples describe normal latency and variation for this world/session.
- **Require a meaningful change.** A high-ping sample must exceed the baseline by at least 100 ms, approximately double normal latency, and exceed a jitter allowance; the threshold also has a 200 ms floor. Two consecutive high samples warn. A stable 250 ms route is normal; repeated 600 ms readings against that baseline warn. A 10 → 20 ms change does not.
- **Do not normalize extreme latency.** Repeated readings of at least 1,000 ms warn even during initial learning, after the login grace. A large spike after calibration is excluded from the baseline, so sustained bad readings do not simply become the new normal. Initial outliers are limited by the median.
- **Warn on an overdue reply.** Once calibrated, a separate watchdog can warn while a ping call is still waiting. Its deadline is at least 400 ms and otherwise uses roughly three times normal ping plus a jitter margin, capped at the helper's 2,000 ms timeout. A stable 250 ms route therefore has a 750 ms early-warning deadline.
- **Track repeated probe failures.** After calibration, two failures within a full window of ten completed probes can warn. This measures unanswered/failed ICMP probes, not proven loss of game TCP packets.
- **Handle blocked ICMP automatically.** Three consecutive failed attempts mark fast network detection unavailable. Game-tick monitoring continues, while probes retry with pauses of 1, 2, 4, 8, 16 and then 30 seconds, with slight jitter. Five successful probes with healthy game updates recalibrate and re-enable fast detection. It does not permanently give up.
- **Monitor game updates independently.** More than 1,500 ms without a `GameTick` raises a warning. If local `ClientTick` events also stop, the banner identifies a local client delay instead of asserting a network failure.

The early-warning deadline is more conservative than the repeated-high-ping threshold: one late response needs more headroom than two confirmed slow responses. Steady-state probes use a one-second pause after completion, with ±10% random timing variation. A confirmed successful high-RTT sample may trigger up to eight faster confirmation probes with 250 ms base pauses, at most once per 30 seconds. Failed attempts use backoff instead of fast retries. Only one probe is in flight at a time.

An initially slow route and an initially slow world can produce identical observations. The plugin cannot conclusively distinguish them from RTT alone. Initial readings remain a best estimate; an absolute extreme-latency guard and the independent game-update watchdog provide additional checks. A permanent route change may require a new session or toggling the plugin to relearn; the detector intentionally avoids rapidly learning away a large degradation.

## Login, hopping, disconnects and recovery

Intentional world hops, logout and new login attempts clear the alarm and discard old probe results. A fresh logged-in session gets a three-second grace period and its own baseline. Area loading has a bounded three-second grace and pauses new probes. A loading screen that never finishes can still warn; entering loading does not clear an already active warning.

**A confirmed `CONNECTION_LOST` state stops monitoring and clears the warning.** The plugin aims to detect the lead-up rather than duplicate the disconnected message. Reconnection starts fresh. A voice clip already playing may finish; no further clips are queued for the cleared warning.

Recovery requires two normally spaced game-update intervals. Queued catch-up ticks do not immediately clear an alarm. High ping also requires two healthy replies (or unavailable/stale evidence) before recovery. ICMP-unavailable mode suppresses unsupported network conclusions while retaining the game-update detector.

## What the warning means

- **LAG DETECTED / No game update:** updates are overdue. Normal ping alongside this can suggest world/server or local processing trouble, but does not prove the cause.
- **LAG DETECTED / Sustained high ping:** repeated round trips are much worse than baseline, or extremely high in absolute terms.
- **CONNECTION WARNING / Ping reply delayed:** a response is overdue; this is an early warning, not proof of dropped game packets.
- **CONNECTION WARNING / Recent ping probes failed:** enough recent ICMP attempts failed while the path was known to support ICMP.
- **CLIENT DELAY DETECTED:** local client updates have paused as well.

ICMP can be deprioritized or blocked independently of the game's TCP connection. Very brief interruptions between probes can be missed. No design guarantees zero false positives/negatives or predicts abrupt outages without a measurable precursor. If the entire JVM stops, the watchdog and sound cannot run; if rendering stops, a new overlay cannot appear until rendering resumes.

## Network cost and RuneLite integration

The plugin directly imports RuneLite's bundled `net.runelite.client.plugins.worldhopper.ping.Ping`. This static helper does **not** require the World Hopper plugin to be enabled, and its settings are not used. Its native implementation is reused through the RuneLite dependency rather than copied into this plugin.

Only the current world, obtained from RuneLite's cached world list, is probed. There is no telemetry, external backend, all-world scan or scene scan. Windows/macOS/Linux use ICMP with the `RuneLitePing` payload and approximately two-second native timeout; TCP fallback is disabled on those platforms. DNS time is outside that native timeout. Other platforms have not been validated.

At one successful probe per second, minimum IPv4 traffic is about **78 B/s per client**: 39 bytes each way (20 IP + 8 ICMP + 11 payload), before link-layer, DNS and VPN overhead. Real RTT and scheduling affect the rate. The latest 20-second live run completed 20/20 probes and calculates to 77.93 B/s at that layer. This is an **estimate from observed probe counts**, not packet-capture-measured bandwidth.

Aggregate traffic still grows with adoption: 10,000 simultaneously monitoring clients would produce roughly 10,000 requests/second across their selected worlds, before bounded bursts. Jitter avoids deliberate synchronization but is not a server-capacity guarantee. Probe frequency and upstream acceptance need review before wide Plugin Hub distribution; this plugin is not approved or published.

The watchdog checks monotonic time on a requested 25 ms cadence, separate from blocking ping and audio jobs. Actual Windows intervals measured around 31 ms on this PC. No busy loops or per-frame network requests are used. Runtime histories have fixed bounds and no per-probe files or telemetry are written.

## Validation and benchmarks

```powershell
.\gradlew.bat test renderPreview simulate
.\gradlew.bat benchmark '-PbenchmarkHost=127.0.0.1' '-PbenchmarkSeconds=30'
.\gradlew.bat benchmark '-PbenchmarkHost=oldschool1.runescape.com' '-PbenchmarkSeconds=30'
# Developer-only fixed-policy comparison, not user-facing plugin settings:
.\gradlew.bat benchmark '-PbenchmarkPolicy=fixed' '-PbenchmarkHost=oldschool1.runescape.com' '-PprobeIntervalMs=250' '-PprobeDeadlineMs=200'
```

`simulate` supplies zero RTT/zero loss before synthetic outages. `benchmark` uses the real RuneLite helper, actual scheduler and automatic pacing, with healthy synthetic game ticks. Neither logs in, automates game input, nor disrupts your connection. Both write their output under the ignored `build/` directory. Captured bandwidth is a separate measurement; the harness labels its calculated byte estimates explicitly.

Reference checkouts, local capture utilities, machine-specific benchmark reports, IDE files, and generated artifacts are excluded by `.gitignore`. Plugin sources, regression tests, the Gradle wrapper (including its JAR), and the bundled voice WAV remain eligible for version control.

The code builds and deterministic tests pass. Manual in-game checks are still needed: preview with sound on/off, login/logout/hop flows, normal area loading, fixed/resized layouts, and coexistence with the wilderness alarm. Use a low-risk account for any deliberate fault testing.

## References

- [Wilderness Player Alarm release](https://github.com/adhansen/plugin-repo/tree/cbddd45029e84197c9d485b140d2a16af77cdef2): inspiration for an in-game warning. The detector and renderer are original; the short spoken cue was synthesized locally.
- [RuneLite ping helper](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/worldhopper/ping/Ping.java): reused via RuneLite's dependency.
- [Official example plugin](https://github.com/runelite/example-plugin): Gradle wrapper and launcher structure. Wrapper notices are retained.

BSD-2-Clause; see [LICENSE](LICENSE).
