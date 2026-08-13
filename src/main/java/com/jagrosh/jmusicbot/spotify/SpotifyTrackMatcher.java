package com.jagrosh.jmusicbot.spotify;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility matcher designed to resolve the optimal {@link AudioTrack} from YouTube search
 * results given Spotify track metadata.
 * <p>
 * Matching employs multi-tiered heuristics (official channel verification, title/artist
 * inclusion, keyword hints) coupled with strict two-sided duration bounds (70% – 130%)
 * to prevent false positives, short teasers, or full-album compilations.
 * </p>
 *
 * <h2>Matching Tiers:</h2>
 * <ol>
 *   <li><b>Perfect Match:</b> Official channel (VEVO, Topic, or artist channel) containing the track title.</li>
 *   <li><b>Official / Remastered Match:</b> Candidate containing title and artist with descriptive keywords (<i>official</i>, <i>audio</i>, <i>remastered</i>).</li>
 *   <li><b>Fallback Match:</b> First candidate in search results containing both artist and title.</li>
 *   <li><b>Emergency Match:</b> First candidate in search results satisfying duration bounds when text heuristics yield no direct match.</li>
 * </ol>
 */
public class SpotifyTrackMatcher
{
	private static final Logger LOG = LoggerFactory.getLogger(SpotifyTrackMatcher.class);

	private static final AtomicLong totalRequests = new AtomicLong(0);
	private static final AtomicLong perfectMatches = new AtomicLong(0);
	private static final AtomicLong officialRemasteredMatches = new AtomicLong(0);
	private static final AtomicLong fallbackMatches = new AtomicLong(0);
	private static final AtomicLong emergencyMatches = new AtomicLong(0);
	private static final AtomicLong durationRejections = new AtomicLong(0);
	private static final AtomicLong failedMatches = new AtomicLong(0);

	/**
     * Evaluates a list of candidate YouTube search results and selects the best matching
     * {@link AudioTrack} corresponding to the provided Spotify track metadata.
     *
     * @param youtubeResults    list of candidate {@link AudioTrack} instances returned by YouTube
     * @param spotifyTitle      the track title retrieved from Spotify metadata
     * @param spotifyArtist     the artist name retrieved from Spotify metadata (may be empty or null)
     * @param spotifyDurationMs the exact Spotify track duration in milliseconds, used for duration window filtering (70% – 130%)
     * @return the best matching {@link AudioTrack}, or {@code null} if results are empty, title is invalid, or no track meets duration bounds
     */
	public static AudioTrack selectBestMatch(List<AudioTrack> youtubeResults, String spotifyTitle, String spotifyArtist,
			Integer spotifyDurationMs)
	{
		totalRequests.incrementAndGet();

		if (youtubeResults == null || youtubeResults.isEmpty())
		{
			failedMatches.incrementAndGet();
			return null;
		}

		String spArtist = isolateArtistName(spotifyArtist);
		String spTitle = (spotifyTitle != null) ? spotifyTitle.toLowerCase().trim() : "";
		Integer spDurationMs = spotifyDurationMs;
		
		if (spTitle.isEmpty())
		{
			LOG.warn("Spotify track title is empty.");
			failedMatches.incrementAndGet();
			return null;
		}

		AudioTrack fallbackMatch = null;

		for (AudioTrack track : youtubeResults)
		{
			if (!isDurationValid(track.getDuration(), spDurationMs))
	        {
	            durationRejections.incrementAndGet();
	            continue;
	        }
			
			String ytTitle = track.getInfo().title.toLowerCase();
			String ytChannel = track.getInfo().author.toLowerCase();
			String ytFullText = ytChannel + " " + ytTitle;
			String ytArtist = isolateArtistName(track.getInfo().author);

			boolean containsArtist = !spArtist.isEmpty() && ytFullText.contains(spArtist);
	        boolean containsTitle = ytTitle.contains(spTitle);
			boolean isOfficialChannel = (!spArtist.isEmpty() && ytArtist.contains(spArtist))
					|| ytChannel.contains("vevo") || ytChannel.contains("- topic");

			if (isOfficialChannel && containsTitle)
	        {
	            perfectMatches.incrementAndGet();
	            return track;
	        } 
            else if (containsArtist && containsTitle)
			{
				if (ytFullText.contains("official") || ytFullText.contains("audio") || ytFullText.contains("áudio")
						|| ytFullText.contains("remaster") || ytFullText.contains("remastered"))
				{
					officialRemasteredMatches.incrementAndGet();
					return track;
				}

				if (fallbackMatch == null)
				{
					fallbackMatch = track;
				}
			}
		}

		if (fallbackMatch != null)
		{
			fallbackMatches.incrementAndGet();
			return fallbackMatch;
		}

		for (AudioTrack track : youtubeResults)
	    {
	        if (isDurationValid(track.getDuration(), spDurationMs))
	        {
	            emergencyMatches.incrementAndGet();
	            return track;
	        }
	    }

	    failedMatches.incrementAndGet();
	    return null;
	}
	
	/**
     * Verifies that a candidate track's duration falls within allowable bounds [70%, 130%]
     * relative to the expected target duration.
     *
     * @param trackDurationMs   duration of the YouTube candidate track in milliseconds
     * @param spotifyDurationMs expected duration of the Spotify track in milliseconds
     * @return {@code true} if the candidate falls within allowable bounds or if target duration is unmapped/zero; {@code false} otherwise
     */
	private static boolean isDurationValid(long trackDurationMs, Integer spotifyDurationMs)
	{
	    if (spotifyDurationMs == null || spotifyDurationMs <= 0)
	    {
	        return true;
	    }

	    long minAllowed = (long) (spotifyDurationMs * 0.70);
	    long maxAllowed = (long) (spotifyDurationMs * 1.30);

	    return trackDurationMs >= minAllowed && trackDurationMs <= maxAllowed;
	}

	/**
     * Extracts and normalizes the primary artist or channel name from author metadata.
     * <p>
     * Strips channel delimiters (such as {@code " - "} or {@code "/"}) and converts the result to lowercase.
     * </p>
     *
     * @param ytAuthor the raw author/channel string retrieved from track metadata
     * @return the normalized primary artist name, or an empty string if input is null or blank
     */
	private static String isolateArtistName(String ytAuthor)
	{
		if (ytAuthor == null || ytAuthor.isEmpty())
		{
			return "";
		}
		String rawArtist = ytAuthor.split(" - |/")[0];
		return rawArtist.toLowerCase();
	}

	/**
     * Formats and logs current heuristic match percentages and execution metrics to the application logger.
     * <p>
     * Computes success rates across perfect, official/remastered, fallback, and emergency match categories.
     * </p>
     */
	public static void logMatchingStatistics()
	{
		long total = totalRequests.get();
		if (total == 0)
		{
			LOG.info("No tracks processed yet.");
			return;
		}

		long perfect = perfectMatches.get();
		long official = officialRemasteredMatches.get();
		long fallback = fallbackMatches.get();
		long emergency = emergencyMatches.get();
		long rejected = durationRejections.get();
		long failed = failedMatches.get();

		long totalSuccessful = perfect + official + fallback + emergency;
		double successRate = (double) totalSuccessful / total * 100.0;
		double perfectRate = (double) perfect / total * 100.0;
		double officialRate = (double) official / total * 100.0;
		double fallbackRate = (double) fallback / total * 100.0;
		double emergencyRate = (double) emergency / total * 100.0;

		LOG.info(
				"\n--- Spotify Matcher Statistics ---\n" + "Total Queries:         {}\n"
						+ "Overall Success Rate:  {} ({})\n" + "  - Perfect Matches:   {} ({})\n"
						+ "  - Official/Remaster: {} ({})\n" + "  - Fallback Matches:  {} ({})\n"
						+ "  - Emergency Matches: {} ({})\n" + "Duration Rejections:   {}\n"
						+ "Failed Searches:       {}\n" + "---------------------------------",
				total, totalSuccessful, String.format("%.2f%%", successRate), perfect,
				String.format("%.2f%%", perfectRate), official, String.format("%.2f%%", officialRate), fallback,
				String.format("%.2f%%", fallbackRate), emergency, String.format("%.2f%%", emergencyRate), rejected,
				failed);
	}

	/**
     * Resets all internal atomic counters tracking heuristic match metrics back to zero.
     */
	public static void resetStatistics()
	{
		totalRequests.set(0);
		perfectMatches.set(0);
		officialRemasteredMatches.set(0);
		fallbackMatches.set(0);
		emergencyMatches.set(0);
		durationRejections.set(0);
		failedMatches.set(0);
	}
}