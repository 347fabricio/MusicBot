package com.jagrosh.jmusicbot.spotify;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;

public class SpotifyBulkLoader {
	private static final Logger LOG = LoggerFactory.getLogger(MusicService.class);

	public static void loadPlaylist(Bot bot, Guild guild, Member member, TextChannel channel,
			SpotifyBridge.SpotifyResult result, MusicService musicService, InteractionHook hook, String successEmoji) {

		if (result.tracks.isEmpty())
			return;

		String firstTitle = result.tracks.get(0);
		String firstArtist = result.artists.get(0);
		Integer firstDurationMs = result.durationMs.get(0);
		String firstQuery = firstTitle + " " + firstArtist;

		bot.getPlayerManager().loadItemOrdered(guild, "ytsearch:" + firstQuery,
				bot.getAudioLoadWrapper().wrap(firstQuery, new AudioLoadResultHandler() {

					@Override
					public void trackLoaded(AudioTrack t) {
						processFirstTrackAndContinue(t);
					}

					@Override
					public void playlistLoaded(AudioPlaylist p) {
						if (!p.getTracks().isEmpty()) {
							AudioTrack bestMatch = SpotifyTrackMatcher.selectBestMatch(p.getTracks(), firstTitle,
									firstArtist, firstDurationMs);
							processFirstTrackAndContinue(bestMatch != null ? bestMatch : p.getTracks().get(0));
						} else {
							noMatches();
						}
					}

					@Override
					public void noMatches() {
						hook.editOriginal(bot.getConfig().getWarning()
								+ " Could not find a match for the first track: **" + firstTitle + "**")
								.setComponents(Collections.emptyList()).queue();
					}

					@Override
					public void loadFailed(FriendlyException e) {
						hook.editOriginal(
								bot.getConfig().getWarning() + " Failed to load first track: " + e.getMessage())
								.setComponents(Collections.emptyList()).queue();
					}

					private void processFirstTrackAndContinue(AudioTrack track) {
						if (track == null) {
							noMatches();
							return;
						}

						AudioHandler handler = musicService.getHandler(guild);
						RequestMetadata rm = new RequestMetadata(member.getUser(),
								new RequestMetadata.RequestInfo(firstQuery, track.getInfo().uri), channel.getIdLong());

						int pos = (handler.getPlayer().getPlayingTrack() == null) ? 0 : handler.getQueue().size() + 1;
						handler.addTrack(new QueuedTrack(track, rm));

						String addMsg = FormatUtil.filter(successEmoji + " Added **" + track.getInfo().title + "** (`"
								+ TimeUtil.formatTime(track.getDuration()) + "`) "
								+ (pos > 0 ? "to the queue at position " + pos : "to begin playing"));

						if (result.tracks.size() == 1) {
							hook.editOriginal(addMsg).setComponents(Collections.emptyList()).queue();
							return;
						}

						hook.editOriginal(
								addMsg + "\n🔄 Loading " + (result.tracks.size() - 1) + " additional tracks...")
								.setComponents(Collections.emptyList()).queue();

						loadRemainingTracks(addMsg);
					}

					private void loadRemainingTracks(String addMsg) {
						AtomicInteger progress = new AtomicInteger(1);
						AtomicInteger loadedCount = new AtomicInteger(0);

						for (int i = 1; i < result.tracks.size(); i++) {
							final String sTitle = result.tracks.get(i);
							final String sArtist = result.artists.get(i);
							final Integer sDurationMs = result.durationMs.get(i);
							final String trackQuery = sTitle + " " + sArtist;

							bot.getPlayerManager().loadItemOrdered(guild, "ytsearch:" + trackQuery,
									bot.getAudioLoadWrapper().wrap(trackQuery, new AudioLoadResultHandler() {

										@Override
										public void trackLoaded(AudioTrack t) {
											addT(t);
										}

										@Override
										public void playlistLoaded(AudioPlaylist p) {
											if (!p.getTracks().isEmpty()) {
												AudioTrack bestMatch = SpotifyTrackMatcher
														.selectBestMatch(p.getTracks(), sTitle, sArtist, sDurationMs);
												addT(bestMatch);
											} else {
												check();
											}
										}

										@Override
										public void noMatches() {
											check();
										}

										@Override
										public void loadFailed(FriendlyException e) {
											check();
										}

										private void addT(AudioTrack t) {
											if (t == null) {
												LOG.warn("[PlaylistLoader] Null track discarded for search: \"{}\"",
														trackQuery);
											} else if (musicService.isTooLong(t)) {
												LOG.warn(
														"[PlaylistLoader] Track exceeded maximum duration ({}) and was discarded: \"{}\"",
														t.getDuration(), t.getInfo().title);
											} else {
												AudioHandler h = musicService.getHandler(guild);
												RequestMetadata rm = new RequestMetadata(member.getUser(),
														new RequestMetadata.RequestInfo(trackQuery, t.getInfo().uri),
														channel.getIdLong());

												h.addTrack(new QueuedTrack(t, rm));
												loadedCount.incrementAndGet();
											}
											check();
										}

										private void check() {
											if (progress.incrementAndGet() == result.tracks.size()) {
												hook.editOriginal(addMsg + "\n" + bot.getConfig().getSuccess()
														+ " Loaded **" + loadedCount.get() + "** additional tracks!")
														.setComponents(Collections.emptyList()).queue();
											}
										}
									}));
						}
					}
				}));
	}
}
