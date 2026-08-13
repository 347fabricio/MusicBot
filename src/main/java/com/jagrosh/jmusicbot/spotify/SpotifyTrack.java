package com.jagrosh.jmusicbot.spotify;

/**
 * Immutable representation of a single Spotify track's metadata.
 */
public record SpotifyTrack(
    String id,
    String title,
    String artist,
    long durationMs
) {}