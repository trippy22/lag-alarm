package com.lagalarm;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(LagAlarmConfig.GROUP)
public interface LagAlarmConfig extends Config
{
    String GROUP = "lag-alarm";

    @ConfigItem(keyName = "soundEnabled", name = "Spoken lag alert", position = 0,
        description = "Say 'lag' during an alarm, repeating every four seconds. Unconfirmed ping cautions remain silent. Works independently of the visual alert.")
    default boolean soundEnabled()
    {
        return true;
    }

    @ConfigItem(keyName = "visualAlert", name = "Visual alert", position = 1,
        description = "Show a click-through warning in the chatbox area, even with chat hidden: banner with pulsing border, banner alone, or off. Sound is controlled separately. Shift+F10 previews the alerts.")
    default VisualAlert visualAlert()
    {
        return VisualAlert.BANNER_AND_BORDER;
    }
}
