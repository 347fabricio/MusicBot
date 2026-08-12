package com.jagrosh.jmusicbot.spotify;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for safely parsing index-aligned Spotify track metadata 
 * payloads from Jackson JsonNodes returned by the scraper script.
 */
public class TrackPayloadParser
{
    /**
     * Container holding index-aligned lists of track metadata extracted from JSON.
     */
    public record ParsedTrackPayload(
            List<String> ids,
            List<String> tracks,
            List<String> artists,
            List<Integer> durations
    )
    {
        public boolean isEmpty()
        {
            return tracks == null || tracks.isEmpty();
        }
    }

    /**
     * Safely extracts index-aligned track metadata lists from a Jackson JsonNode.
     *
     * @param itemNode Jackson JsonNode representing a track, album, or playlist response
     * @return ParsedTrackPayload containing safely populated lists
     */
    public static ParsedTrackPayload parseTrackPayload(JsonNode itemNode)
    {
        if (itemNode == null || !itemNode.isObject())
        {
            return new ParsedTrackPayload(List.of(), List.of(), List.of(), List.of());
        }

        JsonNode tracksNode = itemNode.get("tracks");
        if (tracksNode == null || !tracksNode.isArray() || tracksNode.isEmpty())
        {
            return new ParsedTrackPayload(List.of(), List.of(), List.of(), List.of());
        }

        int count = tracksNode.size();

        JsonNode idsNode = itemNode.hasNonNull("track_ids") ? itemNode.get("track_ids") : itemNode.get("ids");
        JsonNode artistsNode = itemNode.get("artists");
        JsonNode durationMsNode = itemNode.get("duration_ms");

        List<String> idsList = new ArrayList<>(count);
        List<String> tracksList = new ArrayList<>(count);
        List<String> artistsList = new ArrayList<>(count);
        List<Integer> durationMsList = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            tracksList.add(getStringValue(tracksNode.get(i), ""));

            String idVal = (idsNode != null && idsNode.isArray() && i < idsNode.size())
                    ? getStringValue(idsNode.get(i), "")
                    : "";
            idsList.add(idVal);

            String artistVal = (artistsNode != null && artistsNode.isArray() && i < artistsNode.size())
                    ? getStringValue(artistsNode.get(i), "")
                    : "";
            artistsList.add(artistVal);

            int durationVal = (durationMsNode != null && durationMsNode.isArray() && i < durationMsNode.size())
                    ? getIntValue(durationMsNode.get(i), 0)
                    : 0;
            durationMsList.add(durationVal);
        }

        return new ParsedTrackPayload(idsList, tracksList, artistsList, durationMsList);
    }

    private static String getStringValue(JsonNode node, String defaultValue)
    {
        return (node != null && !node.isNull()) ? node.asText(defaultValue) : defaultValue;
    }

    private static int getIntValue(JsonNode node, int defaultValue)
    {
        return (node != null && !node.isNull()) ? node.asInt(defaultValue) : defaultValue;
    }
}