package com.jagrosh.jmusicbot.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class SpotifyBridge {
	public static class SpotifyResult {
		public List<String> tracks;
		public List<String> artists;
		public boolean success;

		public SpotifyResult(List<String> tracks, List<String> artists, boolean success) {
			this.tracks = tracks;
			this.artists = artists;
			this.success = success;
		}
	}

	public static SpotifyResult getTrackInfo(String type, String id) {
		try {
			String baseDir = System.getProperty("user.dir");
			String pythonPath = baseDir + File.separator + ".venv" + File.separator + "bin" + File.separator + "python";
			ProcessBuilder pb = new ProcessBuilder(pythonPath, "scrapper.py", type, id);
			Process p = pb.start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String jsonResposta = reader.readLine();
			if (jsonResposta != null && !jsonResposta.isEmpty()) {
				ObjectMapper mapper = new ObjectMapper();
				JsonNode root = mapper.readTree(jsonResposta);			
				JsonNode tracksNode = root.get("tracks");
				JsonNode artistsNode = root.get("artists");
			
				List<String> tracksList = new ArrayList<>();
				List<String> artistsList = new ArrayList<>();

				if (tracksNode != null && tracksNode.isArray()) {
				    for (int i = 0; i < tracksNode.size(); i++) {
				        tracksList.add(tracksNode.get(i).asText());
			            artistsList.add(artistsNode.get(i).asText());
				    }
				}
				
				return new SpotifyResult(tracksList, artistsList, true);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return new SpotifyResult(null, null, false);
	}
}
