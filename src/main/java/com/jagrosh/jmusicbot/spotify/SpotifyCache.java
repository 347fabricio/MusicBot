package com.jagrosh.jmusicbot.spotify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory JVM cache for Spotify metadata using standard Java concurrent data structures.
 */
public class SpotifyCache
{
	private static final Logger LOG = LoggerFactory.getLogger(SpotifyCache.class);

    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final int maxEntries = 2000;
    private final long ttlMillis = 24 * 60 * 60 * 1000L; // 24 Hours

    private record CacheKey(String type, String id) {}
    private record CacheEntry(SpotifyResult result, Instant expiresAt) {}

    /**
     * Retrieves a cached SpotifyResult if present and not expired.
     */
    public Optional<SpotifyResult> get(String type, String id)
    {
        CacheKey key = new CacheKey(type, id);
        CacheEntry entry = cache.get(key);

        if (entry == null)
        {
            LOG.debug("Spotify JVM cache MISS for [{}:{}]", key.type(), key.id());
            return Optional.empty();
        }

        if (Instant.now().isAfter(entry.expiresAt()))
        {
            cache.remove(key, entry);
            LOG.debug("Spotify JVM cache EXPIRED for [{}:{}]", key.type(), key.id());
            return Optional.empty();
        }

        LOG.info("Spotify JVM cache HIT for [{}:{}]", key.type(), key.id());
        return Optional.of(entry.result());
    }

    /**
     * Stores a successful SpotifyResult in the cache.
     */
    public void put(String type, String id, SpotifyResult result)
    {
    	if (result == null || !result.success() || result.tracks() == null || result.tracks().isEmpty())
        {
            return;
        }

        if (cache.size() >= maxEntries)
        {
            cache.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue().expiresAt()));
        }

        CacheKey key = new CacheKey(type, id);
        Instant expiresAt = Instant.now().plusMillis(ttlMillis);
        cache.put(key, new CacheEntry(result, expiresAt));
        LOG.debug("Cached Spotify metadata for [{}:{}]", key.type(), key.id());
    }

    /**
     * Loops through playlist/album tracks and caches each track under its individual track ID.
     */
    public void populateIndividualTrackCache(List<SpotifyTrack> tracks)
    {
        if (tracks == null || tracks.isEmpty())
            return;

        int cachedCount = 0;
        for (SpotifyTrack track : tracks)
        {
            if (track == null || track.id() == null || track.id().isBlank())
                continue;

            SpotifyResult singleResult = SpotifyResult.success(List.of(track));
            put(SpotifyType.TRACK.getValue(), track.id(), singleResult);
            cachedCount++;
        }

        LOG.info("Pre-populated JVM cache with {} individual tracks from playlist/album.", cachedCount);
    }
    public void clear()
    {
        cache.clear();
        LOG.info("Spotify JVM cache cleared.");
    }

    public long size()
    {
        return cache.size();
    }
}