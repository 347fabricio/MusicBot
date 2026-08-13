package com.jagrosh.jmusicbot.spotify;

import java.util.List;

/**
 * Encapsulates the outcome of a Spotify scrape or cache lookup.
 */
public record SpotifyResult(
    List<SpotifyTrack> tracks,
    boolean success,
    String errorMessage
)
{
    public static SpotifyResult success(List<SpotifyTrack> tracks)
    {
        return new SpotifyResult(tracks != null ? tracks : List.of(), true, null);
    }

    public static SpotifyResult failure(String errorMessage)
    {
        return new SpotifyResult(List.of(), false, errorMessage);
    }
}