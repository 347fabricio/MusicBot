package com.jagrosh.jmusicbot.audio;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.utils.SpotifyBridge;
import com.jagrosh.jmusicbot.utils.SpotifyTrackMatcher;
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

	public static void loadRestOfPlaylist(Bot bot, Guild guild, Member member, TextChannel channel,
			SpotifyBridge.SpotifyResult result, MusicService musicService, String addMsg, InteractionHook hook) {

		AtomicInteger progress = new AtomicInteger(1);
		AtomicInteger loadedCount = new AtomicInteger(0);

		for (int i = 1; i < result.tracks.size(); i++) {
			final String sTitle = result.tracks.get(i);
			final String sArtist = result.artists.get(i);

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
								AudioTrack bestMatch = SpotifyTrackMatcher.selectBestMatch(p.getTracks(), sTitle,
										sArtist);
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
								LOG.warn("[PlaylistLoader] Null track discarded for search: \"{}\"", trackQuery);
							} else if (musicService.isTooLong(t)) {
								LOG.warn("[PlaylistLoader] Track exceeded maximum duration ({}) and was discarded: \"{}\" ({})",t.getDuration(), t.getInfo().title, t.getInfo().uri);
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
								hook.editOriginal(addMsg + "\n" + bot.getConfig().getSuccess() + " Loaded **"
										+ loadedCount.get() + "** additional tracks!")
										.setComponents(Collections.emptyList()).queue();
							}
						}
					}));
		}
	}
}
