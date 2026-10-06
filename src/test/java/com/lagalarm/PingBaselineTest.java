package com.lagalarm;

import org.junit.Test;
import static org.junit.Assert.*;

public class PingBaselineTest
{
    private PingBaseline trained(int rtt)
    {
        PingBaseline baseline = new PingBaseline();
        baseline.reset();
        for (int i = 0; i < 5; i++) { baseline.record(rtt, true, i * 1000); }
        return baseline;
    }

    @Test public void stableHighPingGetsItsOwnBaseline()
    {
        PingBaseline baseline = trained(250);
        assertTrue(baseline.isAvailable());
        assertEquals(250, baseline.normalPing());
        assertEquals(500, baseline.highThreshold());
        assertEquals(750, baseline.deadline());
    }

    @Test public void smallAbsoluteIncreaseIsNotAlarmWorthy()
    {
        PingBaseline baseline = trained(10);
        assertEquals(200, baseline.highThreshold());
        assertEquals(400, baseline.deadline());
    }

    @Test public void egregiousLatencyIsNotNormalizedAway()
    {
        PingBaseline baseline = trained(1500);
        assertEquals(1000, baseline.highThreshold());
    }

    @Test public void sustainedSpikeDoesNotBecomeTheNewNormal()
    {
        PingBaseline baseline = trained(30);
        for (int i = 5; i < 100; i++) { baseline.record(800, true, i * 1000); }
        assertEquals(30, baseline.normalPing());
        assertEquals(200, baseline.highThreshold());
    }

    @Test public void initialOutlierDoesNotDominateMedian()
    {
        PingBaseline baseline = new PingBaseline();
        baseline.reset();
        for (int value : new int[]{1200, 30, 35, 25, 30}) { baseline.record(value, true, 0); }
        assertEquals(30, baseline.normalPing());
    }

    @Test public void failuresBackOffButDoNotPermanentlyDisable()
    {
        PingBaseline baseline = trained(30);
        int[] waits = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
        for (int i = 0; i < waits.length; i++)
        {
            baseline.record(-1, true, 5000 + i * 1000);
            assertEquals(waits[i], baseline.retryDelay());
        }
        assertFalse(baseline.isAvailable());
        for (int i = 0; i < 5; i++) { baseline.record(70, true, 20000 + i * 1000); }
        assertTrue(baseline.isAvailable());
        assertEquals(70, baseline.normalPing());
        assertEquals(1000, baseline.retryDelay());
    }

    @Test public void neverWorkingIcmpCannotCalibrate()
    {
        PingBaseline baseline = new PingBaseline();
        baseline.reset();
        for (int i = 0; i < 100; i++) { baseline.record(-1, true, i * 1000); }
        assertFalse(baseline.isAvailable());
        assertEquals(-1, baseline.normalPing());
        assertEquals(30000, baseline.retryDelay());
    }

    @Test public void failingGameUpdatesDoNotTrainBaseline()
    {
        PingBaseline baseline = trained(30);
        for (int i = 0; i < 100; i++) { baseline.record(150, false, i * 1000); }
        assertEquals(30, baseline.normalPing());
    }

    @Test public void fastConfirmationBurstIsBounded()
    {
        PingBaseline baseline = trained(30);
        baseline.record(800, true, 5000);
        assertEquals(250, baseline.retryDelay());
        for (int i = 1; i <= 8; i++) { baseline.record(800, true, 5000 + i * 250); }
        assertEquals(1000, baseline.retryDelay());
        baseline.record(800, true, 34000);
        assertEquals(1000, baseline.retryDelay());
        baseline.record(800, true, 35000);
        assertEquals(250, baseline.retryDelay());
    }
}
