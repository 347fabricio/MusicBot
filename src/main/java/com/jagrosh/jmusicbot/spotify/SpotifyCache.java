package com.jagrosh.jmusicbot.spotify;

import com.jagrosh.jmusicbot.spotify.SpotifyBridge.SpotifyResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory JVM cache for Spotify metadata using standard Java concurrent data structures.
 */
public class SpotifyCache
{
    private static final Logger LOG = LoggerFactory.getLogger(SpotifyCache.class);

    public record CacheKey(String type, String id)
    {
        public CacheKey
        {
            Objects.requireNonNull(type, "Type must not be null");
            Objects.requireNonNull(id, "ID must not be null");
            type = type.trim().toLowerCase();
            id = id.trim();
        }
    }

    private record CacheEntry(SpotifyResult result, Instant expiresAt) {}

    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;

    /**
     * Constructs a default SpotifyCache (2,000 entry limit, 24-hour expiration).
     */
    public SpotifyCache()
    {
        this(2000, 24, TimeUnit.HOURS);
    }

    /**
     * Constructs a customized SpotifyCache.
     */
    public SpotifyCache(int maxEntries, long duration, TimeUnit timeUnit)
    {
        this.maxEntries = maxEntries;
        this.ttlMillis = timeUnit.toMillis(duration);
    }

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
        if (result == null || result.tracks == null || !result.success || result.tracks.isEmpty())
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
    public void populateIndividualTrackCache(
            List<String> ids, 
            List<String> tracks, 
            List<String> artists, 
            List<Integer> durations)
    {
        if (ids == null || tracks == null || artists == null || durations == null)
        {
            return;
        }

        int cachedCount = 0;

        for (int i = 0; i < tracks.size(); i++)
        {
            String trackId = (i < ids.size()) ? ids.get(i) : null;

            if (trackId == null || trackId.isBlank())
            {
                continue;
            }

            SpotifyResult singleTrackResult = new SpotifyResult(
                    Collections.singletonList(tracks.get(i)),
                    Collections.singletonList(artists.get(i)),
                    Collections.singletonList(durations.get(i)),
                    true
            );

            put("track", trackId, singleTrackResult);
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