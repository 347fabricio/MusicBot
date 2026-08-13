package com.jagrosh.jmusicbot.spotify; // Adjust package name as per your project structure

import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for safely parsing Spotify URLs and URI schemes to extract entity types and 22-character Base62 IDs.
 */
public class SpotifyParser
{
	private static final Logger LOG = LoggerFactory.getLogger(SpotifyParser.class);
    private static final Pattern BASE62_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]{22}$");

    /**
     * Parses an input string for a valid Spotify URL or URI scheme and extracts its entity type and ID.
     *
     * @param args Raw input string (URL, URI, or command arguments)
     * @return {@link SpotifyData} containing the type and ID, or {@code null} if input is invalid
     */
    public static SpotifyData parse(String args)
    {
        if (args == null || args.isBlank())
            return null;

        String input = args.trim();

        if (input.startsWith("spotify:"))
        {
            return parseUri(input);
        }

        return parseUrl(input);
    }

    /**
     * Handles Spotify URI format (e.g., spotify:track:4uLU61m3OFy3A2Tf3L1A22).
     */
    private static SpotifyData parseUri(String input)
    {
        try
        {
            String[] parts = input.split(":");
            if (parts.length >= 3)
            {
                SpotifyType type = SpotifyType.fromString(parts[1]);
                String id = parts[2];

                if (type != null && BASE62_ID_PATTERN.matcher(id).matches())
                {
                    return new SpotifyData(type, id);
                }
            }
        }
        catch (Exception e)
        {
            LOG.debug("Failed to parse Spotify URI input: \"{}\"", input, e);
        }
        return null;
    }
    
    /**
     * Handles HTTP/HTTPS Spotify URLs via {@link java.net.URI}.
     */
    private static SpotifyData parseUrl(String input)
    {
        try
        {
            if (!input.startsWith("http://") && !input.startsWith("https://"))
            {
                input = "https://" + input;
            }

            URI uri = new URI(input);
            String scheme = uri.getScheme();

            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")))
            {
                LOG.debug("Rejected URL [{}]: Scheme is missing or unsupported ('{}')", input, scheme);
                return null;
            }

            String host = uri.getHost();
            if (host == null)
            {
                LOG.debug("Rejected URL [{}]: Host component is null", input);
                return null;
            }

            host = host.toLowerCase();

            if (!host.equals("spotify.com") && !host.endsWith(".spotify.com"))
            {
                LOG.debug("Rejected URL [{}]: Host '{}' is not a valid Spotify domain", input, host);
                return null;
            }

            String path = uri.getPath();
            if (path == null || path.isEmpty())
            {
                LOG.debug("Rejected URL [{}]: Path component is null or empty", input);
                return null;
            }

            String[] segments = path.split("/");
            for (int i = 0; i < segments.length - 1; i++)
            {
                SpotifyType type = SpotifyType.fromString(segments[i]);
                if (type != null)
                {
                    String possibleId = segments[i + 1];
                    if (BASE62_ID_PATTERN.matcher(possibleId).matches())
                    {
                        return new SpotifyData(type, possibleId);
                    }
                }
            }
        }
        catch (Exception e)
        {
            LOG.debug("Failed to parse Spotify URL input: \"{}\"", input, e);
        }

        return null;
    }

    /**
     * Immutable container representing an extracted Spotify entity type and ID.
     */
    public record SpotifyData(SpotifyType type, String id) {}
}