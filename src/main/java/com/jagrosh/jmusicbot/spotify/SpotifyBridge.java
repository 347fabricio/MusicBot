package com.jagrosh.jmusicbot.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpotifyBridge
{
	private static final Logger LOG = LoggerFactory.getLogger(SpotifyBridge.class);

	public static class SpotifyResult
	{
		public List<String> tracks;
		public List<String> artists;
		public List<Integer> durationMs;
		public boolean success;

		public SpotifyResult(List<String> tracks, List<String> artists, List<Integer> durationMs, boolean success)
		{
			this.tracks = tracks;
			this.artists = artists;
			this.success = success;
			this.durationMs = durationMs;
		}
	}

	public static SpotifyResult getTrackInfo(String type, String id)
	{
		try
		{
			String baseDir = System.getProperty("user.dir");
			String pythonPath = baseDir + File.separator + ".venv" + File.separator + "bin" + File.separator + "python";
			ProcessBuilder pb = new ProcessBuilder(pythonPath, "scrapper.py", type, id);

			pb.redirectErrorStream(true);

			Process p = pb.start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String jsonRes = reader.readLine();

			int exitCode = p.waitFor();

			if (exitCode != 0)
			{
				LOG.error("Python failed with exit code {}: {}", exitCode, jsonRes);
				return new SpotifyResult(null, null, null, false);
			}

			if (jsonRes != null && !jsonRes.trim().isEmpty())
			{
				ObjectMapper mapper = new ObjectMapper();
				JsonNode root = mapper.readTree(jsonRes);

				if (root.has("success") && !root.get("success").asBoolean())
				{
					String errorMsg = root.has("error") ? root.get("error").asText() : "Python unknown error";
					LOG.error("Python script failed: {}", errorMsg);
					return new SpotifyResult(null, null, null, false);
				}

				JsonNode tracksNode = root.get("tracks");
				JsonNode artistsNode = root.get("artists");
				JsonNode durationMsNode = root.get("duration_ms");
				;

				List<String> tracksList = new ArrayList<>();
				List<String> artistsList = new ArrayList<>();
				List<Integer> durationMsList = new ArrayList<>();

				if (tracksNode != null && tracksNode.isArray())
				{
					for (int i = 0; i < tracksNode.size(); i++)
					{
						tracksList.add(tracksNode.get(i).asText());
						artistsList.add(artistsNode.get(i).asText());
						durationMsList.add(Integer.parseInt(durationMsNode.get(i).asText()));
					}
				}

				boolean isSuccess = !tracksList.isEmpty();
				return new SpotifyResult(tracksList, artistsList, durationMsList, isSuccess);
			}
		} catch (Exception e)
		{
			LOG.error("Exception when executing Python script: {}", e.getMessage(), e);
			e.printStackTrace();
		}
		return new SpotifyResult(null, null, null, false);
	}
}
