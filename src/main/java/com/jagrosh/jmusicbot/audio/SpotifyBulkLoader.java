package com.jagrosh.jmusicbot.audio;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.utils.SpotifyBridge;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;

public class SpotifyBulkLoader {

	public static void loadRestOfPlaylist(Bot bot, Guild guild, Member member, TextChannel channel,
			SpotifyBridge.SpotifyResult result, MusicService musicService, String addMsg, InteractionHook hook) {

		AtomicInteger progress = new AtomicInteger(1);
		AtomicInteger loadedCount = new AtomicInteger(0);

		for (int i = 1; i < result.tracks.size(); i++) {
			final String trackQuery = result.tracks.get(i) + " " + result.artists.get(i);

			bot.getPlayerManager().loadItemOrdered(guild, "ytsearch:" + trackQuery,
					bot.getAudioLoadWrapper().wrap(trackQuery, new AudioLoadResultHandler() {
						@Override
						public void trackLoaded(AudioTrack t) {
							addT(t);
						}

						@Override
						public void playlistLoaded(AudioPlaylist p) {
							if (!p.getTracks().isEmpty())
								addT(p.getTracks().get(0));
							else
								check();
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
							if (!musicService.isTooLong(t)) {
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
