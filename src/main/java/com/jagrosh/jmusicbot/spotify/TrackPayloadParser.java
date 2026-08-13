package com.jagrosh.jmusicbot.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for parsing standardized parallel JSON arrays from scraper.py 
 * into type-safe SpotifyTrack records.
 */
public class TrackPayloadParser
{
    public static SpotifyResult parseTrackPayload(JsonNode itemNode)
    {
        if (itemNode == null || !itemNode.isObject())
        {
            return SpotifyResult.failure("Invalid item node in JSON payload");
        }

        JsonNode tracksNode = itemNode.get("tracks");
        if (tracksNode == null || !tracksNode.isArray() || tracksNode.isEmpty())
        {
            return SpotifyResult.failure("No tracks found in item payload");
        }

        JsonNode idsNode = itemNode.has("track_ids") ? itemNode.get("track_ids") : itemNode.get("ids");
        JsonNode artistsNode = itemNode.get("artists");
        JsonNode durationsNode = itemNode.get("duration_ms");

        List<SpotifyTrack> parsedTracks = new ArrayList<>();

        for (int i = 0; i < tracksNode.size(); i++)
        {
            String title = tracksNode.get(i).asText("");
            String id = (idsNode != null && idsNode.size() > i) ? idsNode.get(i).asText("") : "";
            String artist = (artistsNode != null && artistsNode.size() > i) ? artistsNode.get(i).asText("") : "";
            long durationMs = (durationsNode != null && durationsNode.size() > i) ? durationsNode.get(i).asLong(0L) : 0L;

            if (!title.isBlank())
            {
                parsedTracks.add(new SpotifyTrack(id, title, artist, durationMs));
            }
        }

        if (parsedTracks.isEmpty())
        {
            return SpotifyResult.failure("Failed to parse valid tracks from arrays");
        }

        return SpotifyResult.success(parsedTracks);
    }
}