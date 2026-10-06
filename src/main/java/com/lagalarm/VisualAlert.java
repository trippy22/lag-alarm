package com.lagalarm;

public enum VisualAlert
{
    BANNER_AND_BORDER("Banner and border"),
    BANNER("Banner only"),
    OFF("Off");

    private final String label;

    VisualAlert(String label) { this.label = label; }

    @Override
    public String toString() { return label; }
}
