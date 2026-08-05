package com.jagrosh.jmusicbot.spotify;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Matching engine that evaluates YouTube search results against Spotify track metadata 
 * to find the best audio match using artist/title heuristic scoring, duration thresholds, and metrics tracking.
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
	 * Evaluates a list of candidate YouTube tracks and selects the optimal match based on official channels,
	 * keywords, and duration safety bounds.
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
		String spTitle = spotifyTitle.toLowerCase().trim();
		Integer spDurationMs = spotifyDurationMs;

		AudioTrack fallbackMatch = null;

		for (AudioTrack track : youtubeResults)
		{
			String ytTitle = track.getInfo().title.toLowerCase();
			String ytChannel = track.getInfo().author.toLowerCase();
			String ytFullText = ytChannel + " " + ytTitle;

			String ytArtist = isolateArtistName(track.getInfo().author);

			boolean containsArtist = !spArtist.isEmpty() && ytFullText.contains(spArtist);
			boolean containsTitle = !spTitle.isEmpty() && ytTitle.contains(spTitle);

			boolean isOfficialChannel = ytArtist.contains(spArtist) || ytChannel.contains("vevo")
					|| ytChannel.contains("- topic");

			if (isOfficialChannel && ytTitle.contains(spTitle))
			{
				perfectMatches.incrementAndGet();
				return track;
			} else if (containsArtist && containsTitle)
			{
				if (spDurationMs != null && spDurationMs > 0)
				{
					long maxAllowedDuration = (long) (spDurationMs * 1.30);
					if (track.getDuration() > maxAllowedDuration)
					{
						durationRejections.incrementAndGet();
						continue;
					}
				}

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

		emergencyMatches.incrementAndGet();
		AudioTrack emergencyMatch = youtubeResults.get(0);
		return emergencyMatch;
	}

	/**
	 * Extracts and normalizes the primary artist or channel name from author metadata.
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

		LOG.info("\n--- Spotify Matcher Statistics ---\n" + "Total Queries:         {}\n"
				+ "  - Perfect Matches:   {} ({})\n" + "  - Official/Remaster Audio: {} ({})\n"
				+ "  - Fallback Matches:  {} ({})\n" + "  - Emergency Matches: {} ({})\n"
				+ "Duration Rejections:   {}\n" + "Failed Searches:       {}\n" + "---------------------------------",
				total, perfect, String.format("%.2f%%", perfectRate), official, String.format("%.2f%%", officialRate),
				fallback, String.format("%.2f%%", fallbackRate), emergency, String.format("%.2f%%", emergencyRate),
				rejected, failed);
	}

	/**
	 * Resets all internal atomic counters tracking heuristic match metrics.
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