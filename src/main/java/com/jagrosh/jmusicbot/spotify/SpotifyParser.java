package com.jagrosh.jmusicbot.spotify;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing Spotify URLs and extracting track, episode, playlist, or album identifiers.
 */
public class SpotifyParser
{
	private static final Pattern ID_PATTERN = Pattern.compile("([a-zA-Z0-9]{22})(?![a-zA-Z0-9])");
	
	/**
	 * Parses an input string for a Spotify URL and extracts its entity type and 22-character Base62 ID.
	 */
	public static SpotifyData parse(String args)
	{
		if (!args.contains("spotify.com"))
			return null;
	
		String type = null;
		if (args.contains("/track/"))
			type = "track";
		else if (args.contains("/episode/"))
			type = "episode";
		else if (args.contains("/playlist/"))
			type = "playlist";
		else if (args.contains("/album/"))
			type = "album";
	
		if (type == null)
			return null;
	
		Matcher idM = ID_PATTERN.matcher(args);
		if (idM.find())
		{
			return new SpotifyData(type, idM.group(1));
		}
		return null;
	}
	
	/**
	 * Immutable container representing an extracted Spotify entity type and ID.
	 */
	public record SpotifyData(String type, String id) {
	}
}