package com.lagalarm;

import org.junit.Test;
import static org.junit.Assert.*;

public class LagReliabilityTest
{
    private final Object socket = new Object();

    private LagDetector trained(boolean withTcp)
    {
        LagDetector detector = new LagDetector();
        detector.configure(LagDetector.Settings.automatic());
        detector.loggedIn(301, 0);
        LagDetector.Probe request = withTcp ? detector.observeSocket(socket) : null;
        for (int now = 0; now <= 6000; now += 50)
        {
            detector.clientTick(now);
            if (now % 600 == 0) { detector.tick(now); }
            if (withTcp && now % 250 == 0) { detector.tcpSample(request, socket, 40, now / 250, 0, now); }
            if (now % 1000 == 0) { detector.ping(detector.probe(), 40, now); }
        }
        assertEquals(40, detector.evaluate(6000).normalPingMs);
        return detector;
    }

    @Test public void areaLoadingKeepsBaselineAndRejectsOldProbe()
    {
        LagDetector detector = trained(false);
        LagDetector.Probe stale = detector.probe();
        detector.beginProbe(stale, 6100);
        detector.loading(6200);
        detector.loggedIn(301, 6300);
        detector.tick(6300);
        detector.clientTick(6900);
        detector.ping(stale, 1200, 6900);
        assertEquals(40, detector.evaluate(6900).normalPingMs);
        assertEquals(-1, detector.evaluate(6900).pingMs);
        detector.beginProbe(detector.probe(), 6900);
        detector.tick(6900);
        detector.clientTick(7300);
        assertEquals(LagDetector.Reason.PROBE_DELAY, detector.evaluate(7300).reason);
        assertEquals(LagDetector.Confidence.CAUTION, detector.evaluate(7300).confidence);
    }

    @Test public void icmpOnlyDelayIsVisibleButSilentUntilGameUpdatesAreLate()
    {
        LagDetector detector = trained(false);
        detector.beginProbe(detector.probe(), 6100);
        detector.clientTick(6500);
        assertTrue(detector.evaluate(6500).hasWarning());
        assertFalse(detector.evaluate(6500).isAlarm());
        detector.clientTick(6900);
        assertEquals(LagDetector.Reason.PROBE_DELAY, detector.evaluate(6900).reason);
        assertTrue(detector.evaluate(6900).isAlarm());
    }

    @Test public void healthyGameSocketDowngradesIcmpHighPing()
    {
        LagDetector detector = trained(true);
        detector.clientTick(6500);
        detector.ping(detector.probe(), 500, 6250);
        detector.ping(detector.probe(), 500, 6500);
        assertEquals(LagDetector.Reason.HIGH_PING, detector.evaluate(6500).reason);
        assertEquals(LagDetector.Confidence.CAUTION, detector.evaluate(6500).confidence);
    }

    @Test public void tcpLatencyAlarmsIndependentlyOfHealthyIcmpAndTicks()
    {
        LagDetector detector = trained(true);
        LagDetector.Probe request = detector.observeSocket(socket);
        for (int i = 25; i <= 27; i++)
        {
            detector.clientTick(i * 250L);
            detector.tcpSample(request, socket, 500, i, 0, i * 250L);
        }
        detector.tick(6600);
        detector.ping(detector.probe(), 40, 6750);
        assertEquals(LagDetector.Reason.TCP_LATENCY, detector.evaluate(6750).reason);
        assertTrue(detector.evaluate(6750).isAlarm());
    }

    @Test public void repeatedGameSocketRetriesAlarmWithoutIcmpFailure()
    {
        LagDetector detector = trained(true);
        LagDetector.Probe request = detector.observeSocket(socket);
        detector.tcpSample(request, socket, 40, 25, 1, 6250);
        detector.tcpSample(request, socket, 40, 26, 2, 6500);
        assertEquals(LagDetector.Reason.TCP_RETRANSMISSIONS, detector.evaluate(6500).reason);
        assertTrue(detector.evaluate(6500).isAlarm());
    }

    @Test public void staleTcpHealthCannotSuppressHighPing()
    {
        LagDetector detector = trained(true);
        detector.tick(9000);
        detector.clientTick(9000);
        detector.ping(detector.probe(), 500, 8800);
        detector.ping(detector.probe(), 500, 9000);
        assertEquals(LagDetector.Reason.HIGH_PING, detector.evaluate(9000).reason);
        assertTrue(detector.evaluate(9000).isAlarm());
    }

    @Test public void socketReplacementClearsBaselineAndRejectsLateResults()
    {
        LagDetector detector = trained(true);
        LagDetector.Probe old = detector.observeSocket(socket);
        Object replacement = new Object();
        detector.observeSocket(replacement);
        detector.tcpSample(old, socket, 1500, 30, 20, 6250);
        detector.ping(old, 1500, 6250);
        assertEquals(-1, detector.evaluate(6250).normalPingMs);
        assertFalse(detector.evaluate(6250).isAlarm());
        assertEquals(-1, detector.evaluate(6250).tcpRttMs);
    }

    @Test public void loadingDiscardsLiveTcpEvidenceAndKeepsTickWatchdog()
    {
        LagDetector detector = trained(true);
        LagDetector.Probe old = detector.observeSocket(socket);
        detector.loading(6100);
        detector.tcpSample(old, socket, 1500, 30, 20, 6250);
        assertFalse(detector.evaluate(6250).isAlarm());
        assertEquals(40, detector.evaluate(6250).normalPingMs);
        assertEquals(LagDetector.Reason.CLIENT_STALL, detector.evaluate(9200).reason);
    }

    @Test public void missingNativeStatsLeavesTickAndPingFallbacksActive()
    {
        LagDetector detector = trained(true);
        detector.tcpUnavailable(detector.observeSocket(socket), socket);
        detector.ping(detector.probe(), 500, 6250);
        detector.ping(detector.probe(), 500, 6500);
        assertEquals(LagDetector.Reason.HIGH_PING, detector.evaluate(6500).reason);
        assertTrue(detector.evaluate(6500).isAlarm());
    }

    @Test public void confirmedDisconnectCannotBeRevivedByTcpResults()
    {
        LagDetector detector = trained(true);
        LagDetector.Probe old = detector.observeSocket(socket);
        detector.connectionLost();
        detector.tcpSample(old, socket, 1500, 30, 20, 6250);
        assertEquals(LagDetector.Mode.IDLE, detector.evaluate(6250).mode);
        assertFalse(detector.evaluate(6250).hasWarning());
    }

    @Test public void steadyModerateRouteChangeEventuallyStopsTheAudibleAlarm()
    {
        LagDetector detector = trained(true);
        LagDetector.Probe request = detector.observeSocket(socket);
        boolean sounded = false;
        for (int now = 6050; now <= 186000; now += 50)
        {
            detector.clientTick(now);
            if (now % 600 == 0) { detector.tick(now); }
            if (now % 250 == 0) { detector.tcpSample(request, socket, 250, now / 250, 0, now); }
            if (now % 1000 == 0) { detector.ping(detector.probe(), 250, now); }
            sounded |= detector.evaluate(now).isAlarm();
        }
        assertTrue("The initial significant change must be noticed", sounded);
        assertFalse("Three minutes of healthy, steady traffic must not leave audio stuck on", detector.evaluate(186000).isAlarm());
    }

    @Test public void gradualDegradationAlarmsBeforeItCanBeLearnedAway()
    {
        LagDetector detector = trained(true);
        LagDetector.Probe request = detector.observeSocket(socket);
        int firstAlarmRtt = -1;
        for (int now = 6050; now <= 246000; now += 50)
        {
            int latency = 40 + (now - 6000) / 500;
            detector.clientTick(now);
            if (now % 600 == 0) { detector.tick(now); }
            if (now % 250 == 0) { detector.tcpSample(request, socket, latency, now / 250, 0, now); }
            if (now % 1000 == 0) { detector.ping(detector.probe(), latency, now); }
            if (detector.evaluate(now).isAlarm() && firstAlarmRtt < 0) { firstAlarmRtt = latency; }
        }
        assertTrue(firstAlarmRtt >= 200 && firstAlarmRtt < 250);
        assertTrue(detector.evaluate(246000).isAlarm());
        assertEquals(40, detector.evaluate(246000).normalPingMs);
    }

    @Test public void silentCautionDoesNotPreventRecoveryFromAnEarlierAlarm()
    {
        LagDetector detector = trained(false);
        detector.clientTick(7600);
        assertTrue(detector.evaluate(7600).isAlarm());
        detector.tick(7800);
        detector.beginProbe(detector.probe(), 7800);
        detector.clientTick(8400);
        detector.tick(8400);
        assertTrue(detector.evaluate(8400).isAlarm());
        detector.clientTick(9000);
        detector.tick(9000);
        assertFalse(detector.evaluate(9000).isAlarm());
        assertEquals(LagDetector.Confidence.CAUTION, detector.evaluate(9000).confidence);
    }

    @Test public void failureWindowCannotReuseOldFailuresAfterASamplingGap()
    {
        LagDetector detector = trained(false);
        for (int i = 7; i <= 16; i++)
        {
            detector.tick(i * 1000L);
            detector.clientTick(i * 1000L);
            detector.ping(detector.probe(), i == 10 || i == 12 ? -1 : 40, i * 1000L);
        }
        // No evaluate calls before the gap: inspect the evidence window without a recovery latch.
        detector.loggedIn(301, 50000);
        detector.tick(50000);
        detector.tick(50600);
        detector.tick(51200);
        detector.clientTick(51200);
        detector.ping(detector.probe(), 40, 51200);
        assertEquals(LagDetector.Reason.NONE, detector.evaluate(51200).reason);
    }
}
