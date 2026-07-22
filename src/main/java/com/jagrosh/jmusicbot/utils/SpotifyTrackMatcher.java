package com.jagrosh.jmusicbot.utils;

import com.jagrosh.jmusicbot.service.MusicService;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpotifyTrackMatcher {
	private static final Logger LOG = LoggerFactory.getLogger(MusicService.class);

	public static AudioTrack selectBestMatch(List<AudioTrack> youtubeResults, String spotifyTitle,
			String spotifyArtist) {
		if (youtubeResults == null || youtubeResults.isEmpty()) {
			return null;
		}

		String spArtist = isolateArtistName(spotifyArtist);
		String spTitle = spotifyTitle.toLowerCase().trim();

		AudioTrack fallbackMatch = null;

		for (AudioTrack track : youtubeResults) {
			String ytTitle = track.getInfo().title.toLowerCase();
			String ytChannel = track.getInfo().author.toLowerCase();
			String ytFullText = ytChannel + " " + ytTitle;

			String ytArtist = isolateArtistName(track.getInfo().author);

			boolean containsArtist = !spArtist.isEmpty() && ytFullText.contains(spArtist);
			boolean containsTitle = !spTitle.isEmpty() && ytTitle.contains(spTitle);

			boolean isOfficialChannel = ytArtist.contains(spArtist) || ytChannel.contains("vevo")
					|| ytChannel.contains("- topic");

			if (isOfficialChannel && ytTitle.contains(spTitle)) {
				LOG.info("[Perfect Match] ytTitle: \"{}\" | ytCh: \"{}\"", ytTitle, ytArtist);
				return track;
			} else if (containsArtist && containsTitle) {
				if (ytFullText.contains("official") || ytFullText.contains("audio") || ytFullText.contains("áudio")
						|| ytFullText.contains("remaster") || ytFullText.contains("remastered")) {
					LOG.info("[Official Match] ytTitle: \"{}\" | ytCh: \"{}\"", ytTitle, ytArtist);
					return track;
				}

				if (fallbackMatch == null) {
					fallbackMatch = track;
				}
			}
		}

		if (fallbackMatch != null) {
			LOG.info("[Fallback Match] ytTitle: \"{}\" | ytCh: \"{}\"", fallbackMatch.getInfo().title,
					fallbackMatch.getInfo().author);
			return fallbackMatch;
		}

		AudioTrack emergencyMatch = youtubeResults.get(0);
		LOG.warn("[Emergency Match] ytTitle: \"{}\" | ytCh: \"{}\"", emergencyMatch.getInfo().title,
				emergencyMatch.getInfo().author);
		return emergencyMatch;
	}

	private static String isolateArtistName(String ytAuthor) {
		if (ytAuthor == null || ytAuthor.isEmpty()) {
			return "";
		}
		String rawArtist = ytAuthor.split(" - |/")[0];
		return rawArtist.toLowerCase();
	}
}