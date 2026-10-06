package com.lagalarm;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class LagAlarmOverlay extends Overlay
{
    private final Client client;
    private final LagAlarmPlugin plugin;
    private final LagAlarmConfig config;

    @Inject
    LagAlarmOverlay(Client client, LagAlarmPlugin plugin, LagAlarmConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(PRIORITY_HIGH);
        setMovable(false);
        setSnappable(false);
        setDragTargetable(false);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        LagDetector.Snapshot snapshot = plugin.getSnapshot();
        if (snapshot.mode == LagDetector.Mode.IDLE || !snapshot.isAlarm()) { return null; }
        VisualAlert visual = config.visualAlert();
        if (visual == VisualAlert.OFF) { return null; }
        Color color = new Color(255, 176, 64);
        LagAlarmPainter.paint(graphics, client.getCanvasWidth(), client.getCanvasHeight(), chatboxBounds(),
            snapshot, color, visual == VisualAlert.BANNER_AND_BORDER, LagAlarmPlugin.now());
        return null;
    }

    private Rectangle chatboxBounds()
    {
        Widget chatbox = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
        Widget controls = client.getWidget(InterfaceID.Chatbox.CONTROLS);
        // Hiding chat does not hide our independent overlay or invalidate its layout bounds.
        return LagAlarmPainter.chatboxArea(client.getCanvasWidth(), client.getCanvasHeight(),
            chatbox == null ? null : chatbox.getBounds(), controls == null ? null : controls.getBounds());
    }
}
