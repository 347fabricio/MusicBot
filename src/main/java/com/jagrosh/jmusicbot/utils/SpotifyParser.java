package com.jagrosh.jmusicbot.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotifyParser {
    private static final Pattern ID_PATTERN = Pattern.compile("([a-zA-Z0-9]{22})(?![a-zA-Z0-9])");

    public static SpotifyData parse(String args) {
        if (!args.contains("spotify.com")) return null;

        String type = null;
        if (args.contains("/track/") || args.contains("/episode/")) type = "track";
        else if (args.contains("/playlist/")) type = "playlist";
        else if (args.contains("/album/")) type = "album";

        if (type == null) return null;

        Matcher idM = ID_PATTERN.matcher(args);
        if (idM.find()) {
            return new SpotifyData(type, idM.group(1));
        }
        return null;
    }

    public record SpotifyData(String type, String id) {} 
}