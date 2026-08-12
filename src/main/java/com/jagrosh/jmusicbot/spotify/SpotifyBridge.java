package com.jagrosh.jmusicbot.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.utils.PythonScriptManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

	private static final String SMOKE_TEST_TYPE = "track";
	private static final String SMOKE_TEST_ID = "02vw0tjLamMJAzMlCSiNH3";

	private static boolean enabled = false;

	/**
	 * Validates that the Python interpreter, scraper.py script extraction, and spotify_scraper dependency are present
	 * and functional at startup.
	 */
	public static void init(BotConfig config)
	{
		try
		{
			LOG.info("Initializing SpotifyBridge: verifying scraper.py extraction");

			File scriptFile = PythonScriptManager.getScriptFile();
			if (scriptFile == null || !scriptFile.exists())
			{
				enabled = false;
				LOG.error("SpotifyBridge startup failed: Unable to locate or extract scraper.py from resources.");
				return;
			}

			LOG.info("scraper.py verified at: [{}]", scriptFile.getAbsolutePath());

			SpotifyResult testResult = executeScript(SMOKE_TEST_TYPE, SMOKE_TEST_ID);

			if (testResult != null && testResult.success)
			{
				enabled = true;
				LOG.info("SpotifyBridge initialized successfully. Pre-flight check passed.");
			} else
			{
				enabled = false;
				LOG.warn("SpotifyBridge pre-flight verification failed. Spotify features will be disabled.");
			}
		} catch (Exception e)
		{
			enabled = false;
			LOG.warn("SpotifyBridge initialization exception: {}. Spotify integration will be disabled.",
					e.getMessage(), e);
		}
	}

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
		if (!isEnabled())
		{
			LOG.warn("Spotify request for [{}:{}] dropped because SpotifyBridge is disabled.", type, id);
			return new SpotifyResult(null, null, null, false);
		}

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

	public static boolean isEnabled()
	{
		return enabled;
	}

	/**
	 * Spawns an external Python process to scrape Spotify metadata via {@code scraper.py}.
	 * 
	 * <p>
	 * This method executes the following workflow:
	 * <ul>
	 * <li>Acquires a permit from {@link #SCRAPER_SEMAPHORE} (max 4 parallel executions, 5s acquire timeout) to bound
	 * system resource usage and mitigate Spotify rate limiting.</li>
	 * <li>Executes {@code scraper.py} inside the local virtual environment ({@code .venv}) enforcing a 20-second hard
	 * execution timeout.</li>
	 * <li>Redirects stderr to stdout via {@link ProcessBuilder#redirectErrorStream(boolean)} to prevent stream
	 * deadlocks and capture full Python tracebacks upon failure.</li>
	 * <li>Parses the index-aligned batch JSON payload returned by Python, extracting track/episode titles,
	 * artists/shows, duration arrays, and {@code track_ids}.</li>
	 * <li>Handles both top-level execution errors (e.g. rate limits, timeout) and item-level lookup failures (e.g. 404
	 * Not Found).</li>
	 * <li>For container entities ({@code playlist} or {@code album}), automatically invokes
	 * {@link #preseedTracks(List, List, List, List)} using the extracted {@code track_ids} to pre-seed JVM cache for
	 * future single-track requests.</li>
	 * <li>Guarantees semaphore permit release in a {@code finally} block regardless of execution outcome.</li>
	 * </ul>
	 *
	 * @param type the Spotify entity type ({@code "track"}, {@code "episode"}, {@code "playlist"}, or {@code "album"})
	 * @param id   the 22-character Spotify resource identifier
	 * @return a {@link SpotifyResult} containing parallel lists of track titles, artist/show names, and duration
	 *         values; returns a failed result if Python exits unexpectedly, times out, or concurrency permits are
	 *         exhausted
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
				LOG.error("Cannot execute scraper: scraper.py is missing and extraction failed.");
				return new SpotifyResult(null, null, null, false);
			}

			String pythonPath = PythonScriptManager.getPythonExecutablePath();
			ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptFile.getAbsolutePath(), type, id);

			pb.redirectErrorStream(false);

			Process p = pb.start();

			CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> { 
				StringBuilder sb = new StringBuilder();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream())))
				{
					String line;
					while ((line = reader.readLine()) != null)
					{
						sb.append(line);
					}
				} catch (IOException e)
				{
					LOG.warn("Error reading stdout for [{}:{}]", type, id, e);
				}
				return sb.toString();
			});

			CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
				StringBuilder sb = new StringBuilder();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream())))
				{
					String line;
					while ((line = reader.readLine()) != null)
					{
						if (sb.length() > 0)
							sb.append("\n");
						sb.append(line);
					}
				} catch (IOException e)
				{
					LOG.warn("Error reading stderr for [{}:{}]", type, id, e);
				}
				return sb.toString();
			});

			boolean finished = p.waitFor(20, TimeUnit.SECONDS);
			if (!finished)
			{
				p.destroyForcibly();
				stdoutFuture.cancel(true);
				stderrFuture.cancel(true);
				LOG.error("Python scraper timed out after 20 seconds for [{}:{}]", type, id);
				return new SpotifyResult(null, null, null, false);
			}

			String rawOutput = stdoutFuture.get(2, TimeUnit.SECONDS).trim();
			String stderrOutput = stderrFuture.get(2, TimeUnit.SECONDS).trim();
			int exitCode = p.exitValue();

			if (!stderrOutput.isEmpty())
			{
				if (exitCode != 0)
				{
					LOG.error("Python scraper stderr for [{}:{}]: {}", type, id, stderrOutput);
				} else
				{
					LOG.debug("Python scraper stderr log for [{}:{}]: {}", type, id, stderrOutput);
				}
			}

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

				SpotifyResult fullResult = new SpotifyResult(payload.tracks(), payload.artists(), payload.durations(),
						isSuccess);

				if (isSuccess && ("playlist".equalsIgnoreCase(type) || "album".equalsIgnoreCase(type)))
				{
					preseedTracks(payload.ids(), payload.tracks(), payload.artists(), payload.durations());
				}

				return fullResult;
			}
		} catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			LOG.error("Thread interrupted while executing Python script for [{}:{}]", type, id, e);
		} catch (Exception e)
		{
			LOG.error("Exception when executing Python script: {}", e.getMessage(), e);
		} finally
		{
			if (permitAcquired)
			{
				SCRAPER_SEMAPHORE.release();
			}
		}

		return new SpotifyResult(null, null, null, false);
	}

	/**
	 * Pre-populates the in-memory JVM cache with individual track metadata returned from a batch playlist or album
	 * resolution.
	 * <p>
	 * This eliminates the need to spawn a Python process if any of these tracks are subsequently requested as single
	 * items.
	 *
	 * @param ids       List of Spotify track IDs matching the order of the items
	 * @param tracks    List of track titles
	 * @param artists   List of artist names
	 * @param durations List of track durations in milliseconds
	 */
	public static void preseedTracks(List<String> ids, List<String> tracks, List<String> artists,
			List<Integer> durations)
	{
		CACHE.populateIndividualTrackCache(ids, tracks, artists, durations);
	}

	private static final ExecutorService BRIDGE_EXECUTOR = Executors.newFixedThreadPool(MAX_CONCURRENT_SCRAPERS,
			r -> new Thread(r, "SpotifyBridge-Worker"));

	/**
	 * Asynchronously retrieves Spotify track metadata without blocking the calling thread.
	 */
	public static CompletableFuture<SpotifyResult> getTrackInfoAsync(String type, String id)
	{
		return CompletableFuture.supplyAsync(() -> getTrackInfo(type, id), BRIDGE_EXECUTOR);
	}
}