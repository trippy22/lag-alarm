package com.lagalarm;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LagAlarmLauncher
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(LagAlarmPlugin.class);
        RuneLite.main(args);
    }
}
