package com.jagrosh.jmusicbot.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge utility for invoking the local Python scraper script to extract Spotify metadata.
 */
public class SpotifyBridge
{
    private static final Logger LOG = LoggerFactory.getLogger(SpotifyBridge.class);

    private static final SpotifyCache CACHE = new SpotifyCache();

    private static final int MAX_CONCURRENT_SCRAPERS = 4;
    private static final Semaphore SCRAPER_SEMAPHORE = new Semaphore(MAX_CONCURRENT_SCRAPERS, true);

    /**
     * Container holding extracted Spotify track metadata and execution status.
     */
    public static class SpotifyResult
    {
        public List<String> tracks;
        public List<String> artists;
        public List<Integer> durationMs;
        public boolean success;

        public SpotifyResult(List<String> tracks, List<String> artists, List<Integer> durationMs, boolean success)
        {
            this.tracks = tracks;
            this.artists = artists;
            this.durationMs = durationMs;
            this.success = success;
        }
    }

    /**
     * Executes the Python scraping process to retrieve metadata, checking JVM cache first.
     */
    public static SpotifyResult getTrackInfo(String type, String id)
    {
        Optional<SpotifyResult> cachedResult = CACHE.get(type, id);
        if (cachedResult.isPresent())
        {
            return cachedResult.get();
        }

        SpotifyResult result = executeScript(type, id);

        if (result != null && result.success)
        {
            CACHE.put(type, id, result);
        }

        return result;
    }

    /**
	 * Spawns an external Python process to scrape Spotify metadata via {@code scrapper.py}.
	 * 
	 * <p>This method executes the following workflow:
	 * <ul>
	 *   <li>Acquires a permit from {@link #SCRAPER_SEMAPHORE} (max 4 parallel executions, 5s acquire timeout)
	 *       to bound system resource usage and mitigate Spotify rate limiting.</li>
	 *   <li>Executes {@code scrapper.py} inside the local virtual environment ({@code .venv})
	 *       enforcing a 20-second hard execution timeout.</li>
	 *   <li>Redirects stderr to stdout via {@link ProcessBuilder#redirectErrorStream(boolean)} to prevent stream 
	 *       deadlocks and capture full Python tracebacks upon failure.</li>
	 *   <li>Parses the index-aligned batch JSON payload returned by Python, extracting track/episode titles, 
	 *       artists/shows, duration arrays, and {@code track_ids}.</li>
	 *   <li>Handles both top-level execution errors (e.g. rate limits, timeout) and item-level 
	 *       lookup failures (e.g. 404 Not Found).</li>
	 *   <li>For container entities ({@code playlist} or {@code album}), automatically invokes
	 *       {@link #populateIndividualTrackCache(List, List, List, List)} using the extracted {@code track_ids}
	 *       to pre-seed JVM cache for future single-track requests.</li>
	 *   <li>Guarantees semaphore permit release in a {@code finally} block regardless of execution outcome.</li>
	 * </ul>
	 *
	 * @param type the Spotify entity type ({@code "track"}, {@code "episode"}, {@code "playlist"}, or {@code "album"})
	 * @param id   the 22-character Spotify resource identifier
	 * @return a {@link SpotifyResult} containing parallel lists of track titles, artist/show names, 
	 *         and duration values; returns a failed result if Python exits unexpectedly, times out, or concurrency permits are exhausted
	 */
    private static SpotifyResult executeScript(String type, String id)
    {
        boolean permitAcquired = false;
        try
        {
            permitAcquired = SCRAPER_SEMAPHORE.tryAcquire(5, TimeUnit.SECONDS);
            if (!permitAcquired)
            {
                LOG.warn("Scraper concurrency limit reached ({}); dropping request for [{}:{}]", 
                        MAX_CONCURRENT_SCRAPERS, type, id);
                return new SpotifyResult(null, null, null, false);
            }

            String baseDir = System.getProperty("user.dir");
            String pythonPath = baseDir + File.separator + ".venv" + File.separator + "bin" + File.separator + "python"; 
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "scrapper.py", type, id);

            pb.redirectErrorStream(true);

            Process p = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder outputBuilder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null)
            {
                outputBuilder.append(line);
            }

            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            if (!finished)
            {
                p.destroyForcibly();
                LOG.error("Python scraper timed out after 20 seconds for [{}:{}]", type, id);
                return new SpotifyResult(null, null, null, false);
            }

            String rawOutput = outputBuilder.toString().trim();
            int exitCode = p.exitValue();

            if (exitCode != 0)
            {
                LOG.error("Python process exited with code {} for [{}:{}]. Output: {}", exitCode, type, id, rawOutput);
                return new SpotifyResult(null, null, null, false);
            }

            if (!rawOutput.isEmpty())
            {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(rawOutput);

                if (root.has("error"))
                {
                    String errorMsg = root.get("error").asText();
                    LOG.error("Python script returned error: {}", errorMsg);
                    return new SpotifyResult(null, null, null, false);
                }

                JsonNode resultsNode = root.get("results");
                if (resultsNode == null || !resultsNode.isArray() || resultsNode.isEmpty())
                {
                    LOG.error("Python script returned no results array for [{}:{}]", type, id);
                    return new SpotifyResult(null, null, null, false);
                }

                JsonNode itemNode = resultsNode.get(0);

                if (itemNode.has("error") || (itemNode.has("success") && !itemNode.get("success").asBoolean()))
                {
                    String itemError = itemNode.has("error") ? itemNode.get("error").asText() : "Unknown item error";
                    LOG.error("Python item lookup failed for [{}:{}]: {}", type, id, itemError);
                    return new SpotifyResult(null, null, null, false);
                }

                JsonNode idsNode = itemNode.has("track_ids") ? itemNode.get("track_ids") : itemNode.get("ids");
                JsonNode tracksNode = itemNode.get("tracks");
                JsonNode artistsNode = itemNode.get("artists");
                JsonNode durationMsNode = itemNode.get("duration_ms");

                List<String> idsList = new ArrayList<>();
                List<String> tracksList = new ArrayList<>();
                List<String> artistsList = new ArrayList<>();
                List<Integer> durationMsList = new ArrayList<>();

                if (tracksNode != null && tracksNode.isArray())
                {
                    for (int i = 0; i < tracksNode.size(); i++)
                    {
                        String trackIdVal = (idsNode != null && idsNode.has(i)) 
                                ? idsNode.get(i).asText() : "";
                        idsList.add(trackIdVal);
                        
                        tracksList.add(tracksNode.get(i).asText());

                        String artist = (artistsNode != null && artistsNode.has(i)) 
                                ? artistsNode.get(i).asText() : "";
                        artistsList.add(artist);

                        int duration = (durationMsNode != null && durationMsNode.has(i)) 
                                ? durationMsNode.get(i).asInt() : 0;
                        durationMsList.add(duration);
                    }
                }

                boolean isSuccess = !tracksList.isEmpty();
                SpotifyResult fullResult = new SpotifyResult(tracksList, artistsList, durationMsList, isSuccess);

                if (isSuccess && ("playlist".equalsIgnoreCase(type) || "album".equalsIgnoreCase(type)))
                {
                    populateIndividualTrackCache(idsList, tracksList, artistsList, durationMsList);
                }

                return fullResult;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            LOG.error("Thread interrupted while executing Python script for [{}:{}]", type, id, e);
        }
        catch (Exception e)
        {
            LOG.error("Exception when executing Python script: {}", e.getMessage(), e);
        }
        finally
        {
            if (permitAcquired)
            {
                SCRAPER_SEMAPHORE.release();
            }
        }

        return new SpotifyResult(null, null, null, false);
    }
    
    /**
     * Loops through playlist/album tracks and caches each track under its individual track ID.
     */
    private static void populateIndividualTrackCache(
            List<String> ids, 
            List<String> tracks, 
            List<String> artists, 
            List<Integer> durations)
    {
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

            CACHE.put("track", trackId, singleTrackResult);
            cachedCount++;
        }

        LOG.info("Pre-populated JVM cache with {} individual tracks from playlist/album.", cachedCount);
    }

    /**
     * Optional helper method to manually clear the cache if needed.
     */
    public static void clearCache()
    {
        CACHE.clear();
    }
}