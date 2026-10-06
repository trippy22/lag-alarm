package com.lagalarm;

import com.sun.management.OperatingSystemMXBean;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.client.plugins.worldhopper.ping.Ping;
import net.runelite.http.api.worlds.World;

/** Development-only harness. Does not log in, touch game inputs, or change network settings. */
public class LagAlarmBenchmark
{
    public static void main(String[] args) throws Exception
    {
        if (args.length < 5) { throw new IllegalArgumentException("host seconds interval-ms deadline-ms output-directory"); }
        String host = args[0];
        int seconds = Math.max(5, Math.min(300, Integer.parseInt(args[1])));
        int interval = Math.max(100, Integer.parseInt(args[2]));
        int deadline = Math.max(100, Integer.parseInt(args[3]));
        boolean automatic = args.length > 5 && "automatic".equals(args[5]);
        Path output = Path.of(args[4]);
        Files.createDirectories(output);
        InetAddress address = InetAddress.getByName(host);
        if (!(address instanceof Inet4Address)) { throw new IllegalArgumentException("Select an IPv4 endpoint"); }
        // Resolve once before timing: separate DNS/bootstrap cost from steady-state probes.
        World world = World.builder().address(address.getHostAddress()).id(301).build();
        Ping.ping(world, false); // Warm native libraries and ICMP path outside the measurement.
        LagDetector detector = new LagDetector();
        detector.configure(automatic ? LagDetector.Settings.automatic()
            : new LagDetector.Settings(1500, 0, 3000, true, 400, 2, true, deadline, 20, 20));
        ProbePacer pacer = new ProbePacer();
        long origin = LagAlarmPlugin.now();
        detector.loggedIn(301, origin);
        detector.tick(origin);
        OperatingSystemMXBean operatingSystem = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = operatingSystem.getProcessCpuTime();
        long wallStart = System.nanoTime();
        AtomicInteger checks = new AtomicInteger();
        AtomicLong maxCheck = new AtomicLong();
        AtomicInteger alarms = new AtomicInteger();
        List<Integer> roundTrips = Collections.synchronizedList(new ArrayList<>());
        List<Integer> watchdogGaps = Collections.synchronizedList(new ArrayList<>());
        AtomicLong previousCheck = new AtomicLong();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output.resolve("probes.csv"), StandardCharsets.UTF_8)))
        {
            csv.println("start_epoch_ms,start_elapsed_ms,end_elapsed_ms,call_duration_ms,rtt_ms,success");
            ScheduledFuture<?> ticks = executor.scheduleAtFixedRate(() -> detector.tick(LagAlarmPlugin.now()), 600, 600, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> watch = executor.scheduleWithFixedDelay(() ->
            {
                long started = System.nanoTime();
                long previous = previousCheck.getAndSet(started);
                if (previous != 0) { watchdogGaps.add((int) ((started - previous) / 1000)); }
                if (detector.evaluate(LagAlarmPlugin.now()).isAlarm()) { alarms.incrementAndGet(); }
                maxCheck.accumulateAndGet(System.nanoTime() - started, Math::max);
                checks.incrementAndGet();
            }, 0, 25, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> probes = executor.scheduleWithFixedDelay(() ->
            {
                long start = LagAlarmPlugin.now();
                long epoch = System.currentTimeMillis();
                LagDetector.Probe request = detector.probe();
                if (automatic && !pacer.ready(request, start)) { return; }
                if (!detector.beginProbe(request, start)) { throw new IllegalStateException("Overlapping probe"); }
                int result = Ping.ping(world, false);
                long end = LagAlarmPlugin.now();
                detector.ping(request, result, end);
                if (automatic) { pacer.finished(request, end, detector.probeDelay()); }
                roundTrips.add(result);
                csv.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%s%n", epoch, start - origin, end - origin, end - start, result, result >= 0);
                csv.flush();
            }, 0, automatic ? 25 : interval, TimeUnit.MILLISECONDS);
            // The harness has an explicit bounded duration; the plugin never blocks this way.
            executor.schedule(() -> {}, seconds, TimeUnit.SECONDS).get(seconds + 5L, TimeUnit.SECONDS);
            probes.cancel(false);
            ticks.cancel(false);
            watch.cancel(false);
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) { executor.shutdownNow(); }
            double elapsed = (System.nanoTime() - wallStart) / 1e9;
            double cpuMs = (operatingSystem.getProcessCpuTime() - cpuStart) / 1e6;
            List<Integer> successful = new ArrayList<>();
            synchronized (roundTrips) { for (int value : roundTrips) { if (value >= 0) { successful.add(value); } } }
            Collections.sort(successful);
            Collections.sort(watchdogGaps);
            int count = roundTrips.size();
            int replies = successful.size();
            // Each minimum IPv4 echo packet: 20-byte IP header + 8-byte ICMP header + 11-byte payload.
            int estimatedIpBytes = 39 * (count + replies);
            String report = String.format(Locale.ROOT,
                "Host: %s (%s)%nElapsed: %.3f s%nPolicy: %s%nCurrent base pause: %d ms%nCurrent deadline: %d ms%n"
                + "Completed attempts: %d%nSuccessful replies: %d%nFailed attempts: %d%n"
                + "RTT p50/p95/p99: %d / %d / %d ms%n"
                + "Watchdog evaluations: %d%nMaximum evaluation cost: %.3f ms%n"
                + "Actual watchdog gap p50/p95/p99: %.3f / %.3f / %.3f ms (requested 25 ms)%n"
                + "Alarmed evaluations: %d (synthetic healthy game ticks)%n"
                + "Process CPU: %.3f ms (%.4f%% of one logical core over this interval)%n"
                + "Estimated minimum IPv4 bytes: %d (%.2f B/s)%n"
                + "Traffic above is calculated from attempts/replies, NOT packet-capture measured bytes.%n"
                + "Warmup, DNS, Ethernet/Wi-Fi headers, tunneling, driver overhead, and failed sends are excluded.%n"
                + "CPU includes this harness, CSV writes, JIT/GC and native calls; this is not a full-client overhead measurement.%n",
                host, address.getHostAddress(), elapsed, automatic ? "automatic with pacing jitter" : "fixed benchmark override",
                automatic ? detector.probeDelay() : interval, automatic ? detector.evaluate(LagAlarmPlugin.now()).deadlineMs : deadline,
                count, replies, count - replies,
                percentile(successful, 0.50), percentile(successful, 0.95), percentile(successful, 0.99),
                checks.get(), maxCheck.get() / 1e6,
                percentile(watchdogGaps, .50) / 1000.0, percentile(watchdogGaps, .95) / 1000.0,
                percentile(watchdogGaps, .99) / 1000.0, alarms.get(), cpuMs, cpuMs / (elapsed * 10),
                estimatedIpBytes, estimatedIpBytes / elapsed);
            Files.writeString(output.resolve("live-summary.txt"), report, StandardCharsets.UTF_8);
            System.out.print(report);
        }
        finally { executor.shutdownNow(); }
    }

    private static int percentile(List<Integer> values, double percentile)
    {
        return values.isEmpty() ? -1 : values.get(Math.max(0, (int) Math.ceil(values.size() * percentile) - 1));
    }
}
