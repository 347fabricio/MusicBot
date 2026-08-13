package com.jagrosh.jmusicbot.spotify;

public enum SpotifyType
{
    TRACK("track"),
    EPISODE("episode"),
    PLAYLIST("playlist"),
    ALBUM("album");

    private final String value;

    SpotifyType(String value)
    {
        this.value = value;
    }

    public String getValue()
    {
        return value;
    }

    public static SpotifyType fromString(String type)
    {
        if (type == null)
            return null;

        for (SpotifyType t : values())
        {
            if (t.value.equalsIgnoreCase(type.trim()))
                return t;
        }
        return null;
    }
}