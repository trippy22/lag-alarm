package com.lagalarm;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class LagAlarmLayoutTest
{
    @Test public void staysInsideChatInFixedResizableAndMovedLayouts()
    {
        Rectangle[] canvases = {new Rectangle(765, 503), new Rectangle(1200, 800), new Rectangle(1200, 800)};
        Rectangle[] chats = {new Rectangle(0, 338, 519, 165), new Rectangle(0, 635, 519, 165), new Rectangle(240, 570, 600, 190)};
        for (int i = 0; i < chats.length; i++)
        {
            Rectangle warning = LagAlarmPainter.warningBounds(canvases[i].width, canvases[i].height, chats[i]);
            assertTrue(canvases[i].contains(warning));
            assertTrue("Keep the game view unobstructed", chats[i].contains(warning));
            assertTrue("Follow the chatbox, not the canvas center", Math.abs(warning.getCenterX() - chats[i].getCenterX()) <= 1);
            assertTrue(Math.abs(warning.getCenterY() - chats[i].getCenterY()) <= 1);
        }
    }

    @Test public void painterRejectsInvalidAreas()
    {
        assertNull(LagAlarmPainter.warningBounds(1200, 800, null));
        assertNull(LagAlarmPainter.warningBounds(1200, 800, new Rectangle(1800, 1200, 519, 165)));
        assertNull(LagAlarmPainter.warningBounds(1200, 800, new Rectangle(0, 0, 0, 0)));
    }

    @Test public void absentOrOffscreenWidgetsUseTheUsualChatAreaOnTheCurrentCanvas()
    {
        Rectangle[] missing = {null, new Rectangle(1800, 1200, 519, 165), new Rectangle(0, 0, 0, 0)};
        Rectangle[] canvases = {new Rectangle(765, 503), new Rectangle(1200, 800)};
        for (Rectangle canvas : canvases)
        {
            for (Rectangle bounds : missing)
            {
                Rectangle area = LagAlarmPainter.chatboxArea(canvas.width, canvas.height, bounds, null);
                assertEquals(new Rectangle(0, canvas.height - 165, 519, 165), area);
                assertTrue(area.contains(LagAlarmPainter.warningBounds(canvas.width, canvas.height, area)));
            }
        }
    }

    @Test public void hiddenChatRetainsItsGeometryAndCollapsedControlsRestoreTheFootprint()
    {
        Rectangle full = new Rectangle(240, 570, 600, 190);
        Rectangle controls = new Rectangle(240, 735, 600, 25);
        // Geometry is deliberately independent of widget visibility, including hidden-at-startup.
        assertEquals(full, LagAlarmPainter.chatboxArea(1200, 800, full, controls));
        Rectangle expected = new Rectangle(240, 595, 600, 165);
        assertEquals(expected, LagAlarmPainter.chatboxArea(1200, 800, null, controls));
        assertEquals(expected, LagAlarmPainter.chatboxArea(1200, 800, controls, null));
        assertEquals(100, LagAlarmPainter.warningBounds(1200, 800, expected).height);
    }

    @Test public void collapsedAndPartlyOffscreenChatKeepsWarningInsideVisibleChat()
    {
        Rectangle[] chats = {new Rectangle(0, 775, 519, 25), new Rectangle(-200, 300, 519, 165),
            new Rectangle(900, 700, 519, 165)};
        Rectangle canvas = new Rectangle(1200, 800);
        for (Rectangle chat : chats)
        {
            Rectangle warning = LagAlarmPainter.warningBounds(canvas.width, canvas.height, chat);
            assertNotNull(warning);
            assertTrue(chat.intersection(canvas).contains(warning));
        }
    }

    @Test public void paintingIncludingPulseAndShadowNeverSpillsOutsideChat()
    {
        Rectangle[] chats = {new Rectangle(0, 338, 519, 165), new Rectangle(0, 775, 519, 25), null};
        LagDetector.Snapshot snapshot = new LagDetector.Snapshot(LagDetector.Mode.MONITORING,
            LagDetector.Reason.TICK_STALL, 301, 1800, -1, false);
        for (Rectangle chat : chats)
        {
            BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try
            {
                LagAlarmPainter.paint(g, 1200, 800, chat, snapshot, Color.ORANGE, true, 400);
            }
            finally { g.dispose(); }
            int painted = 0;
            for (int y = 0; y < image.getHeight(); y++)
            {
                for (int x = 0; x < image.getWidth(); x++)
                {
                    if (image.getRGB(x, y) != 0)
                    {
                        assertTrue("All visual effects must remain within chat", chat != null && chat.contains(x, y));
                        painted++;
                    }
                }
            }
            if (chat != null) { assertTrue("Visible chat should receive an alert", painted > 0); }
        }
    }
}
