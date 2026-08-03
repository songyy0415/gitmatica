package me.arnavpmr.lvc.overlay;

public enum LvcTrackingOverlayRevision
{
    CURRENT("current"),
    PARENT("parent");

    private final String serializedName;

    LvcTrackingOverlayRevision(String serializedName)
    {
        this.serializedName = serializedName;
    }

    String serializedName()
    {
        return this.serializedName;
    }
}
