package com.lagalarm;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class LagDetectorTest
{
    private LagDetector detector;

    @Before public void setUp()
    {
        detector = new LagDetector();
        detector.configure(new LagDetector.Settings(1500, 0, 3000, true, 400));
        detector.loggedIn(301, 0);
        detector.tick(0);
    }

    @Test public void detectsStallWithoutAnyFurtherGameEvents()
    {
        assertFalse(detector.evaluate(1499).isAlarm());
        assertEquals(LagDetector.Reason.TICK_STALL, detector.evaluate(1500).reason);
        assertEquals(1500, detector.evaluate(1500).tickAgeMs);
    }

    @Test public void normalTicksAndModestJitterDoNotAlarm()
    {
        for (long time : new long[]{600, 1210, 1830, 2800, 3400, 4000})
        {
            detector.tick(time);
            assertFalse(detector.evaluate(time + 100).isAlarm());
        }
    }

    @Test public void recoversOnlyAfterTwoNormallySpacedIntervals()
    {
        assertTrue(detector.evaluate(1600).isAlarm());
        detector.tick(2000);
        assertEquals(LagDetector.Reason.RECOVERING, detector.evaluate(2000).reason);
        detector.tick(2600);
        assertTrue(detector.evaluate(2600).isAlarm());
        detector.tick(3200);
        assertFalse(detector.evaluate(3200).isAlarm());
    }

    @Test public void queuedTickBurstDoesNotClearAlarm()
    {
        detector.evaluate(1600);
        detector.tick(2000);
        detector.tick(2001);
        detector.tick(2002);
        assertTrue(detector.evaluate(2002).isAlarm());
        detector.tick(2602);
        detector.tick(3202);
        assertFalse(detector.evaluate(3202).isAlarm());
    }

    @Test public void noticesLongTickGapEvenIfWatchdogWasPaused()
    {
        detector.tick(3000);
        assertEquals(LagDetector.Reason.RECOVERING, detector.evaluate(3000).reason);
    }

    @Test public void loginGraceIsBoundedEvenIfNoFirstTickArrives()
    {
        detector.stop();
        detector.configure(new LagDetector.Settings(1500, 3000, 3000, false, 400));
        detector.loggedIn(301, 500);
        assertFalse(detector.evaluate(3499).isAlarm());
        assertTrue(detector.evaluate(3499).warmingUp);
        assertEquals(LagDetector.Reason.TICK_STALL, detector.evaluate(3500).reason);
    }

    @Test public void loadingHasBoundedGrace()
    {
        detector.loading(600);
        detector.loading(1000); // Repeated state events must not extend the deadline.
        assertFalse(detector.evaluate(3599).isAlarm());
        assertEquals(LagDetector.Reason.TICK_STALL, detector.evaluate(3600).reason);
    }

    @Test public void normalLoadingThenReturnDoesNotImmediatelyAlarm()
    {
        detector.loading(600);
        detector.loggedIn(301, 2500);
        assertFalse(detector.evaluate(2500).isAlarm());
        detector.tick(2900);
        assertFalse(detector.evaluate(3000).isAlarm());
    }

    @Test public void loadingDoesNotHideAnExistingAlarm()
    {
        detector.evaluate(1600);
        detector.loading(1700);
        assertTrue(detector.evaluate(1800).isAlarm());
    }

    @Test public void connectionLostSuppressesAlarmAndReconnectGetsFreshGrace()
    {
        detector.configure(new LagDetector.Settings(1500, 3000, 3000, false, 400));
        detector.stop();
        detector.loggedIn(301, 0);
        assertTrue(detector.evaluate(3100).isAlarm());
        detector.connectionLost();
        assertFalse(detector.evaluate(3200).isAlarm());
        assertNull(detector.probe());
        detector.loggedIn(301, 5000);
        assertFalse(detector.evaluate(7999).isAlarm());
        assertTrue(detector.evaluate(7999).warmingUp);
        detector.tick(8000);
        assertFalse(detector.evaluate(8000).isAlarm());
    }

    @Test public void logoutClearsAllAlarmsAndStopsProbes()
    {
        detector.evaluate(1500);
        detector.test(1500);
        detector.stop();
        assertFalse(detector.evaluate(10000).isAlarm());
        assertEquals(LagDetector.Mode.IDLE, detector.evaluate(10000).mode);
        assertNull(detector.probe());
    }

    @Test public void delayedProbeFromOldWorldCannotPolluteNewWorld()
    {
        LagDetector.Probe old = detector.probe();
        detector.stop();
        detector.loggedIn(302, 1000);
        detector.ping(old, 800, 1100);
        detector.ping(old, 800, 1200);
        assertEquals(-1, detector.evaluate(1200).pingMs);
        assertFalse(detector.evaluate(1200).isAlarm());
    }

    @Test public void sameWorldRelogAlsoInvalidatesInFlightPing()
    {
        LagDetector.Probe old = detector.probe();
        detector.stop();
        detector.loggedIn(301, 1000);
        detector.ping(old, 800, 1100);
        assertEquals(-1, detector.evaluate(1100).pingMs);
    }

    @Test public void oneHighPingDoesNotAlarmButTwoDo()
    {
        detector.ping(detector.probe(), 400, 100);
        assertFalse(detector.evaluate(100).isAlarm());
        detector.tick(600);
        detector.ping(detector.probe(), 450, 1100);
        assertEquals(LagDetector.Reason.HIGH_PING, detector.evaluate(1100).reason);
    }

    @Test public void healthyPingResetsConsecutiveHighSamples()
    {
        detector.ping(detector.probe(), 500, 100);
        detector.ping(detector.probe(), 50, 200);
        detector.ping(detector.probe(), 500, 300);
        assertFalse(detector.evaluate(300).isAlarm());
    }

    @Test public void blockedPingAloneNeverAlarms()
    {
        for (int i = 1; i <= 10; i++)
        {
            detector.tick(i * 600);
            detector.ping(detector.probe(), -1, i * 600);
            assertFalse(detector.evaluate(i * 600).isAlarm());
        }
        assertEquals(-1, detector.evaluate(6000).pingMs);
    }

    @Test public void failedPingBreaksTheHighPingSequence()
    {
        detector.ping(detector.probe(), 700, 100);
        detector.ping(detector.probe(), -1, 200);
        detector.ping(detector.probe(), 700, 300);
        assertFalse(detector.evaluate(300).isAlarm());
    }

    @Test public void disablingPingsInvalidatesPendingResult()
    {
        LagDetector.Probe pending = detector.probe();
        detector.configure(new LagDetector.Settings(1500, 0, 3000, false, 400));
        detector.ping(pending, 900, 100);
        assertNull(detector.probe());
        assertEquals(-1, detector.evaluate(100).pingMs);
    }

    @Test public void staleHighPingExpiresInsteadOfAlarmingForever()
    {
        detector.ping(detector.probe(), 800, 100);
        detector.ping(detector.probe(), 800, 200);
        assertTrue(detector.evaluate(200).isAlarm());
        for (int i = 1; i <= 12; i++)
        {
            detector.tick(i * 600);
            detector.evaluate(i * 600);
        }
        assertFalse(detector.evaluate(7200).isAlarm());
        assertEquals(-1, detector.evaluate(7200).pingMs);
    }

    @Test public void widelySeparatedSamplesDoNotCountAsConsecutive()
    {
        detector.ping(detector.probe(), 800, 100);
        for (int i = 1; i <= 10; i++) { detector.tick(i * 600); }
        detector.ping(detector.probe(), 800, 6100);
        assertFalse(detector.evaluate(6100).isAlarm());
    }

    @Test public void tickStallTakesPriorityOverPing()
    {
        detector.ping(detector.probe(), 800, 100);
        detector.ping(detector.probe(), 800, 200);
        assertEquals(LagDetector.Reason.TICK_STALL, detector.evaluate(1500).reason);
    }

    @Test public void testAlarmExpiresAndDoesNotMaskRealLag()
    {
        detector.test(100);
        assertEquals(LagDetector.Reason.TEST, detector.evaluate(100).reason);
        assertEquals(LagDetector.Reason.TICK_STALL, detector.evaluate(1600).reason);
        for (int i = 3; i <= 9; i++) { detector.tick(i * 600); }
        assertFalse(detector.evaluate(5400).isAlarm());
    }

    @Test public void defaultPassiveConfigurationDoesNotRequestNetworkIo()
    {
        LagDetector passive = new LagDetector();
        passive.loggedIn(301, 0);
        assertNull(passive.probe());
    }

    @Test public void recoveryOfPingNeedsTwoHealthyProbesAndStableTicks()
    {
        detector.ping(detector.probe(), 800, 100);
        detector.ping(detector.probe(), 800, 200);
        detector.evaluate(200);
        detector.tick(600);
        detector.ping(detector.probe(), 50, 700);
        assertEquals(LagDetector.Reason.HIGH_PING, detector.evaluate(700).reason);
        detector.tick(1200);
        detector.ping(detector.probe(), 50, 1300);
        assertTrue(detector.evaluate(1300).isAlarm());
        detector.tick(1800);
        assertFalse(detector.evaluate(1800).isAlarm());
    }

    private void fastSettings(int deadline, int confirmations, int window, int failures)
    {
        detector.configure(new LagDetector.Settings(1500, 0, 3000, true, 400,
            confirmations, true, deadline, failures, window));
    }

    private void calibrate()
    {
        detector.ping(detector.probe(), 25, 100);
        detector.ping(detector.probe(), 25, 200);
        detector.ping(detector.probe(), 25, 300);
    }

    @Test public void earlyWarningDoesNotWaitForBlockingPingToReturn()
    {
        fastSettings(200, 2, 20, 20);
        calibrate();
        assertTrue(detector.beginProbe(detector.probe(), 400));
        assertFalse(detector.evaluate(599).isAlarm());
        assertEquals(LagDetector.Reason.PROBE_DELAY, detector.evaluate(600).reason);
    }

    @Test public void unsupportedPingCannotArmEarlyWarningsOrFailureRate()
    {
        fastSettings(200, 2, 5, 1);
        for (int i = 1; i < 20; i++)
        {
            long time = i * 600;
            detector.tick(time);
            LagDetector.Probe request = detector.probe();
            detector.beginProbe(request, time);
            assertFalse(detector.evaluate(time + 300).isAlarm());
            detector.ping(request, -1, time + 400);
            assertFalse(detector.evaluate(time + 400).isAlarm());
        }
    }

    @Test public void onlyOneProbeCanBeOutstanding()
    {
        assertTrue(detector.beginProbe(detector.probe(), 100));
        assertFalse(detector.beginProbe(detector.probe(), 200));
    }

    @Test public void hopInvalidatesOutstandingDeadline()
    {
        fastSettings(200, 2, 20, 20);
        calibrate();
        detector.beginProbe(detector.probe(), 400);
        detector.stop();
        detector.loggedIn(302, 500);
        assertFalse(detector.evaluate(1000).isAlarm());
    }

    @Test public void failureRateRequiresFullWindowAndWorkingIcmp()
    {
        fastSettings(200, 2, 5, 20);
        calibrate();
        detector.ping(detector.probe(), -1, 400);
        assertFalse(detector.evaluate(400).isAlarm());
        detector.ping(detector.probe(), 30, 500);
        assertEquals(LagDetector.Reason.PROBE_FAILURES, detector.evaluate(500).reason);
    }

    @Test public void failuresLeaveRollingWindow()
    {
        fastSettings(200, 2, 5, 20);
        calibrate();
        detector.ping(detector.probe(), -1, 400);
        detector.ping(detector.probe(), 30, 500);
        detector.evaluate(500);
        for (int i = 1; i <= 8; i++)
        {
            detector.tick(i * 600);
            detector.ping(detector.probe(), 30, i * 600);
            detector.evaluate(i * 600);
        }
        assertFalse(detector.evaluate(4800).isAlarm());
    }

    @Test public void singleConfirmationModeAlertsOnFirstHighPing()
    {
        fastSettings(200, 1, 20, 20);
        detector.ping(detector.probe(), 450, 100);
        assertEquals(LagDetector.Reason.HIGH_PING, detector.evaluate(100).reason);
    }

    @Test public void liveSettingsEnforceMinimums()
    {
        LagDetector.Settings settings = new LagDetector.Settings(0, 0, 0, true, 0, 0, true, 0, 0, 0);
        assertEquals(900, settings.stallMs);
        assertEquals(100, settings.pingThresholdMs);
        assertEquals(100, settings.probeDeadlineMs);
        assertEquals(1, settings.highPingSamples);
        assertEquals(1, settings.failurePercent);
        assertEquals(5, settings.failureWindow);
    }

    @Test public void distinguishesLocalClientPauseFromMissingServerTicks()
    {
        detector.clientTick(100);
        assertEquals(LagDetector.Reason.CLIENT_STALL, detector.evaluate(1600).reason);
        detector.clientTick(1610);
        assertEquals(LagDetector.Reason.TICK_STALL, detector.evaluate(1620).reason);
    }

    @Test public void confirmedDisconnectInvalidatesDelayedProbe()
    {
        fastSettings(200, 2, 20, 20);
        calibrate();
        LagDetector.Probe pending = detector.probe();
        detector.beginProbe(pending, 400);
        detector.evaluate(600);
        detector.connectionLost();
        detector.ping(pending, -1, 2400);
        assertEquals(LagDetector.Mode.IDLE, detector.evaluate(2500).mode);
        assertFalse(detector.evaluate(2500).isAlarm());
    }

    @Test public void soundOrColorChangesDoNotResetCalibration()
    {
        fastSettings(200, 2, 20, 20);
        calibrate();
        fastSettings(200, 2, 20, 20);
        detector.beginProbe(detector.probe(), 400);
        assertEquals(LagDetector.Reason.PROBE_DELAY, detector.evaluate(600).reason);
    }

    private void trainAutomatic(int ping)
    {
        detector.configure(LagDetector.Settings.automatic());
        detector.stop();
        detector.loggedIn(301, 0);
        for (int i = 1; i <= 6; i++)
        {
            detector.tick(i * 600);
            detector.ping(detector.probe(), ping, i * 600);
        }
    }

    @Test public void automaticModeAcceptsStable250msAndAdaptsDeadline()
    {
        trainAutomatic(250);
        assertFalse(detector.evaluate(3600).isAlarm());
        assertEquals(250, detector.evaluate(3600).normalPingMs);
        assertEquals(750, detector.evaluate(3600).deadlineMs);
        detector.beginProbe(detector.probe(), 3700);
        assertFalse(detector.evaluate(4449).isAlarm());
        assertEquals(LagDetector.Reason.PROBE_DELAY, detector.evaluate(4450).reason);
    }

    @Test public void automaticModeDetectsLargeRelativeJump()
    {
        trainAutomatic(250);
        detector.ping(detector.probe(), 600, 3700);
        assertFalse(detector.evaluate(3700).isAlarm());
        detector.ping(detector.probe(), 600, 4000);
        assertEquals(LagDetector.Reason.HIGH_PING, detector.evaluate(4000).reason);
        assertEquals(250, detector.evaluate(4000).normalPingMs);
    }

    @Test public void automaticModeStillWarnsAboutExtremeInitialLatency()
    {
        trainAutomatic(1500);
        assertEquals(LagDetector.Reason.HIGH_PING, detector.evaluate(3600).reason);
    }

    @Test public void automaticModeFallsBackAndRearmsAfterBlockedIcmp()
    {
        trainAutomatic(30);
        for (int i = 7; i <= 12; i++)
        {
            detector.tick(i * 600);
            detector.ping(detector.probe(), -1, i * 600);
            detector.evaluate(i * 600);
        }
        assertFalse(detector.evaluate(7200).isAlarm());
        assertEquals("Ping unavailable; retrying", detector.evaluate(7200).pingStatus);
        assertEquals(30000, detector.probeDelay());
        for (int i = 13; i <= 18; i++)
        {
            detector.tick(i * 600);
            detector.ping(detector.probe(), 80, i * 600);
            detector.evaluate(i * 600);
        }
        assertFalse(detector.evaluate(10800).isAlarm());
        assertEquals("Active", detector.evaluate(10800).pingStatus);
        assertEquals(80, detector.evaluate(10800).normalPingMs);
    }
}
