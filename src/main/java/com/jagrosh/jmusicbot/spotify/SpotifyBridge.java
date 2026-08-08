package com.jagrosh.jmusicbot.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jagrosh.jmusicbot.utils.PythonScriptManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
     *    <li>Acquires a permit from {@link #SCRAPER_SEMAPHORE} (max 4 parallel executions, 5s acquire timeout)
     *        to bound system resource usage and mitigate Spotify rate limiting.</li>
     *    <li>Executes {@code scrapper.py} inside the local virtual environment ({@code .venv})
     *        enforcing a 20-second hard execution timeout.</li>
     *    <li>Redirects stderr to stdout via {@link ProcessBuilder#redirectErrorStream(boolean)} to prevent stream 
     *        deadlocks and capture full Python tracebacks upon failure.</li>
     *    <li>Parses the index-aligned batch JSON payload returned by Python, extracting track/episode titles, 
     *        artists/shows, duration arrays, and {@code track_ids}.</li>
     *    <li>Handles both top-level execution errors (e.g. rate limits, timeout) and item-level 
     *        lookup failures (e.g. 404 Not Found).</li>
     *    <li>For container entities ({@code playlist} or {@code album}), automatically invokes
     *        {@link #preseedTracks(List, List, List, List)} using the extracted {@code track_ids}
     *        to pre-seed JVM cache for future single-track requests.</li>
     *    <li>Guarantees semaphore permit release in a {@code finally} block regardless of execution outcome.</li>
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
            
            File scriptFile = PythonScriptManager.getScriptFile();
            if (scriptFile == null) 
            {
                LOG.error("Cannot execute scraper: scrapper.py is missing and extraction failed.");
                return new SpotifyResult(null, null, null, false);
            }

            String pythonPath = PythonScriptManager.getPythonExecutablePath();
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptFile.getAbsolutePath(), type, id);
            
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
                JsonNode root = OBJECT_MAPPER.readTree(rawOutput);

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
                    LOG.error("Python item lookup failed for [{:{}]: {}", type, id, itemError);
                    return new SpotifyResult(null, null, null, false);
                }

                TrackPayloadParser.ParsedTrackPayload payload = TrackPayloadParser.parseTrackPayload(itemNode);
                boolean isSuccess = !payload.isEmpty();

                SpotifyResult fullResult = new SpotifyResult(
                        payload.tracks(), 
                        payload.artists(), 
                        payload.durations(), 
                        isSuccess
                );

                if (isSuccess && ("playlist".equalsIgnoreCase(type) || "album".equalsIgnoreCase(type)))
                {
                    preseedTracks(payload.ids(), payload.tracks(), payload.artists(), payload.durations());
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
     * Pre-populates the in-memory JVM cache with individual track metadata
     * returned from a batch playlist or album resolution.
     * <p>
     * This eliminates the need to spawn a Python process if any of these
     * tracks are subsequently requested as single items.
     *
     * @param ids       List of Spotify track IDs matching the order of the items
     * @param tracks    List of track titles
     * @param artists   List of artist names
     * @param durations List of track durations in milliseconds
     */
    public static void preseedTracks(List<String> ids, List<String> tracks, List<String> artists, List<Integer> durations)
    {
        CACHE.populateIndividualTrackCache(ids, tracks, artists, durations);
    }
}