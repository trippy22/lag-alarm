package com.lagalarm;

import org.junit.Test;
import static org.junit.Assert.*;

public class TcpHealthTest
{
    private TcpHealth trained(long scale)
    {
        TcpHealth health = new TcpHealth();
        health.reset();
        for (int i = 0; i <= 24; i++) { health.record(40, i * scale, 0, true, i * 250L); }
        assertEquals(40, health.normalPing());
        assertTrue(health.healthy(6000));
        return health;
    }

    @Test public void repeatedHighLatencyRequiresProgressAndHalfASecond()
    {
        TcpHealth health = trained(1);
        health.record(500, 25, 0, true, 6250);
        health.record(500, 26, 0, true, 6500);
        assertFalse(health.highLatency(6500));
        health.record(500, 27, 0, true, 6750);
        assertTrue(health.highLatency(6750));
        health.record(40, 28, 0, true, 7000);
        assertTrue(health.highLatency(7000));
        health.record(40, 29, 0, true, 7250);
        assertFalse(health.highLatency(7250));
    }

    @Test public void rereadingAnIdleSocketCannotConfirmHighLatencyOrStayFresh()
    {
        TcpHealth health = trained(1);
        for (int i = 25; i <= 48; i++) { health.record(1500, 24, 0, true, i * 250L); }
        assertFalse(health.fresh(12000));
        assertFalse(health.highLatency(12000));
        assertFalse(health.healthy(12000));
    }

    @Test public void isolatedRetransmissionDoesNotAlarm()
    {
        TcpHealth health = trained(1);
        health.record(40, 25, 1, true, 6250);
        assertFalse(health.repeatedRetries(6250));
        assertFalse(health.healthy(6250));
        health.record(40, 26, 2, true, 9500);
        assertFalse(health.repeatedRetries(9500));
    }

    @Test public void repeatedRetransmissionsWorkForByteAndSegmentCounters()
    {
        for (long scale : new long[]{1, 1024})
        {
            TcpHealth health = trained(scale);
            health.record(40, 25 * scale, scale, true, 6250);
            health.record(40, 26 * scale, 2 * scale, true, 6500);
            assertTrue(health.repeatedRetries(6500));
            assertFalse(health.repeatedRetries(10000));
        }
    }

    @Test public void tinyRetransmissionFractionDoesNotAlarm()
    {
        TcpHealth health = trained(1000);
        health.record(40, 25000, 1, true, 6250);
        health.record(40, 26000, 2, true, 6500);
        assertFalse(health.repeatedRetries(6500));
    }

    @Test public void counterResetOrWrapClearsOldEvidence()
    {
        TcpHealth health = trained(1);
        health.record(40, 25, 1, true, 6250);
        health.record(40, 26, 2, true, 6500);
        health.record(40, 1, 0, true, 6750);
        assertFalse(health.repeatedRetries(6750));
        assertFalse(health.healthy(6750));
        assertEquals(-1, health.normalPing());
    }

    @Test public void suspensionDropsEvidenceButPreservesBaseline()
    {
        TcpHealth health = trained(1);
        health.suspend();
        assertEquals(40, health.normalPing());
        assertFalse(health.healthy(6250));
        health.record(40, 25, 0, true, 6250);
        assertFalse(health.fresh(6250));
        health.record(40, 26, 0, true, 6500);
        assertTrue(health.healthy(6500));
    }

    @Test public void invalidNativeValuesCannotBecomeHealthyEvidence()
    {
        TcpHealth health = trained(1);
        health.record(0, 25, 0, true, 6250);
        assertFalse(health.fresh(6250));
        health.record(40, -1, 0, true, 6500);
        assertFalse(health.fresh(6500));
    }

    @Test public void extremeInitialLatencyCanAlarmWithoutCalibration()
    {
        TcpHealth health = new TcpHealth();
        health.reset();
        health.record(1200, 1, 0, true, 0);
        health.record(1200, 2, 0, true, 250);
        health.record(1200, 3, 0, true, 750);
        assertTrue(health.highLatency(750));
    }
}
