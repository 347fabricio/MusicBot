package com.jagrosh.jmusicbot.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
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

			pb.redirectErrorStream(true);

			Process p = pb.start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String jsonRes = reader.readLine();

			int exitCode = p.waitFor();

			if (exitCode != 0) {
				System.err.println("[SpotifyBridge] Python failed with exit code " + exitCode + ": " + jsonRes);
				return new SpotifyResult(null, null, false);
			}

			if (jsonRes != null && !jsonRes.trim().isEmpty()) {
				ObjectMapper mapper = new ObjectMapper();
				JsonNode root = mapper.readTree(jsonRes);

				if (root.has("success") && !root.get("success").asBoolean()) {
					String errorMsg = root.has("error") ? root.get("error").asText() : "Python unknown error";
					System.err.println("[SpotifyBridge] Python failed: " + errorMsg);
					return new SpotifyResult(null, null, false);
				}

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

				boolean isSuccess = !tracksList.isEmpty();
				return new SpotifyResult(tracksList, artistsList, isSuccess);
			}
		} catch (Exception e) {
			System.err.println("[SpotifyBridge] Exception when executing Python script: " + e.getMessage());
			e.printStackTrace();
		}
		return new SpotifyResult(null, null, false);
	}
}
