package com.lagalarm;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Locale;

/** Rendering only; shared by the actual overlay and the headless preview. */
final class LagAlarmPainter
{
    private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 24);
    private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Color PANEL = new Color(20, 24, 31, 242);
    private static final Color TEXT = new Color(236, 239, 243);

    static void paint(Graphics2D original, int width, int height, Rectangle chatbox, LagDetector.Snapshot snapshot,
        Color color, boolean pulse, long now)
    {
        if (width < 140 || height < 140 || snapshot.mode == LagDetector.Mode.IDLE || !snapshot.hasWarning()) { return; }
        Rectangle bounds = warningBounds(width, height, chatbox);
        if (bounds == null) { return; }
        int x = bounds.x;
        int y = bounds.y;
        int w = bounds.width;
        Graphics2D g = (Graphics2D) original.create();
        try
        {
            // Keep all visual effects within the chat area, even when chat itself is hidden.
            g.clip(chatbox.intersection(new Rectangle(0, 0, width, height)));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (pulse)
            {
                // Keep the pulse local to the warning; do not frame the entire game canvas.
                int alpha = (int) (100 + 80 * (0.5 + 0.5 * Math.sin(now * Math.PI / 750.0)));
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
                g.setStroke(new BasicStroke(4));
                g.drawRoundRect(x - 3, y - 3, w + 6, bounds.height + 6, 18, 18);
            }
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRoundRect(x + 3, y + 4, w, bounds.height, 14, 14);
            g.setColor(PANEL);
            g.fillRoundRect(x, y, w, bounds.height, 14, 14);
            g.setColor(color);
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(x, y, w, bounds.height, 14, 14);
            String title = snapshot.confidence == LagDetector.Confidence.CAUTION ? "PING WARNING"
                : snapshot.reason == LagDetector.Reason.TEST ? "LAG ALARM - TEST"
                : snapshot.reason == LagDetector.Reason.CLIENT_STALL ? "CLIENT DELAY DETECTED"
                : snapshot.reason == LagDetector.Reason.PROBE_DELAY || snapshot.reason == LagDetector.Reason.PROBE_FAILURES
                ? "CONNECTION WARNING" : "LAG DETECTED";
            g.setFont(TITLE);
            if (bounds.height < 90)
            {
                // A collapsed chatbox only has room for a single-line warning.
                g.setFont(TITLE.deriveFont((float) Math.min(20, bounds.height - 4)));
                FontMetrics metrics = g.getFontMetrics();
                centered(g, title, x, y + (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent(), w);
                return;
            }
            centered(g, title, x, y + 33, w);
            g.setFont(BODY);
            g.setColor(TEXT);
            centered(g, detail(snapshot), x, y + 58, w);
            g.setFont(SMALL);
            g.setColor(new Color(172, 182, 196));
            String footer = snapshot.reason == LagDetector.Reason.TEST
                ? "Preview only - connection unchanged"
                : snapshot.confidence == LagDetector.Confidence.CAUTION ? "Game connection trouble is not confirmed"
                : "WORLD " + snapshot.world + "  |  Waiting for stable connection and updates";
            centered(g, footer, x, y + 81, w);
        }
        finally { g.dispose(); }
    }

    static Rectangle warningBounds(int width, int height, Rectangle chatbox)
    {
        Rectangle visibleChat = chatbox == null ? null : chatbox.intersection(new Rectangle(0, 0, width, height));
        if (visibleChat == null || visibleChat.width < 140 || visibleChat.height < 20) { return null; }
        int verticalMargin = visibleChat.height >= 116 ? 8 : 2;
        int w = Math.min(510, visibleChat.width - 16);
        int h = Math.min(100, visibleChat.height - 2 * verticalMargin);
        int x = visibleChat.x + (visibleChat.width - w) / 2;
        int y = visibleChat.y + (visibleChat.height - h) / 2;
        return new Rectangle(x, y, w, h);
    }

    static Rectangle chatboxArea(int width, int height, Rectangle chatbox, Rectangle controls)
    {
        Rectangle canvas = new Rectangle(0, 0, width, height);
        for (Rectangle candidate : new Rectangle[] {chatbox, controls})
        {
            if (candidate == null) { continue; }
            Rectangle area = candidate.intersection(canvas);
            if (area.width < 140 || area.height < 20) { continue; }
            if (area.height < 100)
            {
                // Collapsed chat retains its bottom controls; restore the usual footprint above them.
                int bottom = area.y + area.height;
                area.height = Math.min(165, bottom);
                area.y = bottom - area.height;
            }
            return area;
        }
        // Also works when the plugin starts with chat already hidden and no usable widget bounds.
        int chatHeight = Math.min(165, height);
        return new Rectangle(0, height - chatHeight, Math.min(519, width), chatHeight);
    }

    private static String detail(LagDetector.Snapshot snapshot)
    {
        switch (snapshot.reason)
        {
            case CLIENT_STALL: return "Local client updates have also paused";
            case HIGH_PING: return "Sustained high ping - latest " + snapshot.pingMs + " ms";
            case TCP_LATENCY: return "Game connection latency is high - latest " + snapshot.tcpRttMs + " ms";
            case TCP_RETRANSMISSIONS: return "Game connection is repeatedly retransmitting data";
            case PROBE_DELAY: return "Ping reply delayed - connection may be unstable";
            case PROBE_FAILURES: return "Recent ping probes failed - check connection stability";
            case RECOVERING: return "Updates resumed - confirming recovery";
            case TEST: return "Previewing your selected lag alerts";
            default: return String.format(Locale.ROOT, "No game update for %.1f seconds", snapshot.tickAgeMs / 1000.0);
        }
    }

    private static void centered(Graphics2D g, String text, int x, int y, int width)
    {
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) > width - 24)
        {
            float size = g.getFont().getSize2D() * (width - 24) / metrics.stringWidth(text);
            g.setFont(g.getFont().deriveFont(size));
            metrics = g.getFontMetrics();
        }
        g.drawString(text, x + (width - metrics.stringWidth(text)) / 2, y);
    }
}
