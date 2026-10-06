package com.lagalarm;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Uses the production painter with illustrative chat bounds, without interacting with RuneScape. */
public class LagAlarmPreview
{
    public static void main(String[] args) throws Exception
    {
        File directory = new File(args[0]);
        if (!directory.isDirectory() && !directory.mkdirs()) { throw new IllegalStateException("Cannot create preview directory"); }
        render(directory, "fixed", 765, 503, new Rectangle(0, 338, 519, 165), VisualAlert.BANNER_AND_BORDER);
        render(directory, "resizable", 1200, 800, new Rectangle(0, 635, 519, 165), VisualAlert.BANNER_AND_BORDER);
        render(directory, "moved-chat", 1200, 800, new Rectangle(240, 570, 600, 190), VisualAlert.BANNER);
        render(directory, "hidden-chat", 1200, 800, null, VisualAlert.BANNER_AND_BORDER);
        render(directory, "fixed-hide-chat", 765, 503, new Rectangle(0, 338, 519, 165), VisualAlert.BANNER_AND_BORDER, false);
        render(directory, "collapsed-chat", 1200, 800, new Rectangle(0, 775, 519, 25), VisualAlert.BANNER);
        render(directory, "visual-off", 765, 503, new Rectangle(0, 338, 519, 165), VisualAlert.OFF);
        render(directory, "icmp-caution", 765, 503, new Rectangle(0, 338, 519, 165), VisualAlert.BANNER_AND_BORDER);
        render(directory, "tcp-alarm", 765, 503, new Rectangle(0, 338, 519, 165), VisualAlert.BANNER_AND_BORDER);
    }

    private static void render(File directory, String name, int width, int height, Rectangle chatbox, VisualAlert visual) throws Exception
    {
        render(directory, name, width, height, chatbox, visual, true);
    }

    private static void render(File directory, String name, int width, int height, Rectangle chatbox, VisualAlert visual,
        boolean chatVisible) throws Exception
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(37, 43, 49));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(57, 64, 69));
        for (int x = 0; x < width; x += 32) { g.drawLine(x, 0, x, height); }
        for (int y = 0; y < height; y += 32) { g.drawLine(0, y, width, y); }
        if (chatbox != null && chatVisible)
        {
            g.setColor(new Color(62, 57, 48));
            g.fill(chatbox);
            g.setColor(new Color(141, 130, 112));
            g.draw(chatbox);
            g.drawString("CHATBOX - illustrative bounds", chatbox.x + 12, chatbox.y + 18);
        }
        if (visual != VisualAlert.OFF)
        {
            LagDetector.Snapshot snapshot = new LagDetector.Snapshot(LagDetector.Mode.MONITORING,
                LagDetector.Reason.TICK_STALL, 301, 1800, -1, false);
            if (name.equals("icmp-caution"))
            {
                snapshot = new LagDetector.Snapshot(LagDetector.Mode.MONITORING,
                    LagDetector.Reason.PROBE_DELAY, 301, 500, -1, false, LagDetector.Confidence.CAUTION);
            }
            if (name.equals("tcp-alarm"))
            {
                snapshot = new LagDetector.Snapshot(LagDetector.Mode.MONITORING,
                    LagDetector.Reason.TCP_LATENCY, 301, 200, 40, false);
                snapshot.tcpRttMs = 500;
            }
            Rectangle area = LagAlarmPainter.chatboxArea(width, height, chatbox, null);
            LagAlarmPainter.paint(g, width, height, area, snapshot, new Color(255, 176, 64),
                visual == VisualAlert.BANNER_AND_BORDER && snapshot.isAlarm(), 400);
        }
        g.setColor(new Color(165, 174, 180));
        g.drawString("Lag Alarm / " + name + " / layout preview, not a game screenshot", 22, 24);
        g.dispose();
        ImageIO.write(image, "png", new File(directory, "chatbox-" + name + ".png"));
    }
}
