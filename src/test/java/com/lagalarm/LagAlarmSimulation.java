package com.lagalarm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Deterministic simulated time and inputs; never changes the real network. */
public class LagAlarmSimulation
{
    public static void main(String[] args) throws Exception
    {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        StringBuilder report = new StringBuilder("SIMULATED detection latency; not a measurement of your network.\n");
        report.append("1000 randomized outage phases per profile; seed 20261006; watchdog 25 ms.\n");
        report.append("Baseline inputs: 0 ms RTT / 0% probe loss. Complete outage begins after calibration.\n");
        report.append("Measures audible-alarm eligibility, not the earlier silent caution. TCP stats unavailable in this simulation.\n");
        report.append("profile,mean_ms,p50_ms,p95_ms,p99_ms,min_ms,max_ms\n");
        for (int mode = 0; mode < 4; mode++)
        {
            int[] values = new int[1000];
            Random random = new Random(20261006);
            for (int i = 0; i < values.length; i++)
            {
                values[i] = trial(mode, 6000 + random.nextInt(3000));
            }
            Arrays.sort(values);
            double mean = Arrays.stream(values).average().orElseThrow();
            String profile = new String[]{"passive-1500", "fixed-pause500-deadline600", "fixed-pause250-deadline200", "automatic-current-default"}[mode];
            report.append(String.format(Locale.ROOT, "%s,%.2f,%d,%d,%d,%d,%d%n", profile, mean,
                values[499], values[949], values[989], values[0], values[999]));
        }
        report.append("Automatic probe-only cautions escalate when game updates are also overdue. Not confirmed game-packet loss.\n");
        report.append("Zero RTT is a synthetic lower bound, not a normal internet latency claim.\n");
        Files.writeString(output.resolve("simulation.csv"), report, StandardCharsets.UTF_8);
        System.out.print(report);
    }

    private static int trial(int mode, int outage)
    {
        boolean active = mode != 0;
        int interval = mode == 2 ? 250 : 500;
        int deadline = mode == 2 ? 200 : 600;
        LagDetector detector = new LagDetector();
        detector.configure(new LagDetector.Settings(1500, 0, 3000, active, 400, 2, true, deadline, 20, 20));
        if (mode == 3) { detector.configure(LagDetector.Settings.automatic()); }
        detector.loggedIn(301, 0);
        int nextProbe = 0;
        LagDetector.Probe pending = null;
        int completeAt = 0;
        for (int now = 0; now <= outage + 5000; now++)
        {
            if (now < outage && now % 600 == 0) { detector.tick(now); }
            if (active)
            {
                if (pending != null && now >= completeAt)
                {
                    detector.ping(pending, -1, now);
                    pending = null;
                    nextProbe = now + (mode == 3 ? detector.probeDelay() : interval);
                }
                if (pending == null && now >= nextProbe)
                {
                    LagDetector.Probe request = detector.probe();
                    detector.beginProbe(request, now);
                    if (now < outage)
                    {
                        detector.ping(request, 0, now);
                        nextProbe = now + (mode == 3 ? detector.probeDelay() : interval);
                    }
                    else { pending = request; completeAt = now + 2000; }
                }
            }
            if (now % 25 == 0 && detector.evaluate(now).isAlarm())
            {
                if (now < outage) { throw new AssertionError("False alarm during zero-lag baseline"); }
                return now - outage;
            }
        }
        throw new AssertionError("Outage went undetected");
    }
}
