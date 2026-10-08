# Lag Alarm

![Hourglass icon](icon.png)

A RuneLite plugin that warns about connection trouble and delayed game updates, aimed at hardcore ironmen and anyone concerned about lag.

## How it works

- Learns your normal ping and watches for sustained increases.
- Checks game updates and, where supported, the game connection's latency and retransmissions.
- Preserves calibration through area loading and resets when you change worlds or reconnect.

Detection is automatic. Passive socket checks send no extra packets; ICMP probes target only your current world and back off after failures. World Hopper does not need to be enabled.

An uncertain **PING WARNING** stays silent. Stronger evidence triggers an alarm with your chosen sound and visuals. Monitoring stops after confirmed connection loss.

The plugin cannot predict abrupt disconnections or guarantee advance warning. False alarms and missed interruptions are possible.

## Alerts

- **Spoken lag alert:** says "lag" during an alarm. On by default.
- **Visual alert:** banner and pulsing border, banner only, or off. Banner and border is the default.

The warning is click-through and stays over the chatbox area in fixed and resizable mode, including when chat is hidden.

## Development

Version **0.2.0**. Requires **JDK 11**.

```powershell
.\gradlew.bat jar
.\gradlew.bat run
```

The launcher enables assertions (`-ea`). While logged in, press **Shift+F10** for a five-second alert preview. Jagex accounts need RuneLite's [development-client login setup](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

## Code layout

- `LagAlarmPlugin`: lifecycle, event subscriptions, scheduling and alerts.
- `GameSocketMonitor`: captures the game socket on the client thread; reads TCP statistics on a worker.
- `IcmpMonitor`: actively probes the current world using RuneLite's ping helper.
- `LagDetector`, `TcpHealth`, `LatencyBaseline`: evaluate measurements and decide when to warn.

## Credits and license

Inspired by [Wilderness Player Alarm](https://github.com/adhansen/plugin-repo). Uses RuneLite's [ping and socket helpers](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/worldhopper/ping/Ping.java) and [example plugin](https://github.com/runelite/example-plugin) structure.

[BSD-2-Clause license](LICENSE).
