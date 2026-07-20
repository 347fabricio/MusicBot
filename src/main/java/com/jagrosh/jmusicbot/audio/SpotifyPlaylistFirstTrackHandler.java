package com.jagrosh.jmusicbot.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.service.MusicService.OutputAdapter;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.SpotifyBridge;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.jagrosh.jmusicbot.utils.SpotifyBridge.SpotifyResult;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;

public class SpotifyPlaylistFirstTrackHandler implements AudioLoadResultHandler {
	private final Bot bot;
	private final Guild guild;
	private final Member member;
	private final TextChannel channel;
	private final OutputAdapter output;
	private final SpotifyResult result;
	private final String query;

	private final String successEmoji;
	private final String warningEmoji;
	private final String errorEmoji;

	private final MusicService musicService;

	private static final Logger LOG = LoggerFactory.getLogger(MusicService.class);

	public SpotifyPlaylistFirstTrackHandler(Bot bot, Guild guild, Member member, TextChannel channel,
			OutputAdapter output, SpotifyBridge.SpotifyResult result, String query, MusicService musicService) {
		this.bot = bot;
		this.guild = guild;
		this.member = member;
		this.channel = channel;
		this.output = output;
		this.result = result;
		this.query = query;
		this.musicService = musicService;

		this.successEmoji = bot.getConfig().getSuccess();
		this.warningEmoji = bot.getConfig().getWarning();
		this.errorEmoji = bot.getConfig().getError();
	}

	@Override
	public void trackLoaded(AudioTrack track) {
		processFirstTrack(track);
	}

	@Override
	public void playlistLoaded(AudioPlaylist playlist) {
		if (!playlist.getTracks().isEmpty()) {
			processFirstTrack(playlist.getTracks().get(0));
		} else {
			noMatches();
		}
	}

	@Override
	public void noMatches() {
		channel.sendMessage(warningEmoji + " No results found for the first track.").queue();
	}

	@Override
	public void loadFailed(FriendlyException exception) {
		channel.sendMessage(errorEmoji + " Error loading first track.").queue();
	}

	private void processFirstTrack(AudioTrack track) {
		if (musicService.isTooLong(track)) {
			channel.sendMessage(FormatUtil.filter(warningEmoji + " Track too long.")).queue();
			return;
		}

		AudioHandler handler = musicService.getHandler(guild);
		RequestMetadata rm = new RequestMetadata(member.getUser(),
				new RequestMetadata.RequestInfo(query, track.getInfo().uri), channel.getIdLong());

		int pos = (handler.getPlayer().getPlayingTrack() == null) ? 0 : handler.getQueue().size() + 1;

		String addMsg = FormatUtil.filter(
				successEmoji + " Added **" + track.getInfo().title + "** (`" + TimeUtil.formatTime(track.getDuration())
						+ "`) " + (pos > 0 ? " to the queue at position " + pos : "to begin playing"));

		String promptMsg = addMsg + "\n" + warningEmoji + " This track has a playlist of **" + result.tracks.size()
				+ "** tracks attached.\n" + "⚠️ **Loading Spotify playlists is discouraged:**\n"
				+ "\t • **Low Accuracy:** It plays the first YouTube result, which may be a cover, live version, or incorrect video.\n"
				+ "\t • **High Overhead:** Searching many tracks at once can trigger YouTube rate limits.\n\n"
				+ "*Do you still want to load it?*";

		List<Button> buttons = new ArrayList<>();
		buttons.add(Button.success("load_playlist", "📥 Load Full Playlist"));
		buttons.add(Button.danger("cancel_playlist", "🚫 Cancel"));

		StringBuilder sb = new StringBuilder("");
		sb.append(promptMsg);
		MessageEditBuilder editBuilder = new MessageEditBuilder().setContent(sb.toString())
				.setComponents(ActionRow.of(buttons));
		LOG.info("Loading spotify playlist prompt: guild={}, user={}, total_tracks={}", guild.getId(),
				member.getUser().getName(), result.tracks.size());

		output.editMessage(sb.toString(), m -> {
			handler.addTrack(new QueuedTrack(track, rm));
			m.editMessage(editBuilder.build()).queue(msg -> {
				bot.getWaiter().waitForEvent(ButtonInteractionEvent.class,
						e -> e.getMessageId().equals(msg.getId()) && e.getUser().getIdLong() == member.getIdLong(),
						e -> {
							if (e.getComponentId().equals("cancel_playlist")) {
								e.editMessage(addMsg).setComponents().queue();
								LOG.info("Spotify playlist loading canceled by user: guild={}, user={}", guild.getId(),
										member.getUser().getName());
								return;
							}
							if (e.getComponentId().equals("load_playlist")) {
								e.deferEdit().queue(hook -> {
									hook.editOriginal("🔄 Loading **" + result.tracks.size()
											+ "** tracks from Spotify playlist!")
											.setComponents(java.util.Collections.emptyList())
											.queue(message -> SpotifyBulkLoader.loadRestOfPlaylist(bot, guild, member,
													channel, result, musicService, addMsg, hook));
								});
								LOG.info(
										"Spotify playlist loading approved by user: guild={}, user={}, loading_tracks={}",
										guild.getId(), member.getUser().getName(), result.tracks.size() - 1);
							}
						}, 20, TimeUnit.SECONDS, () -> {
							msg.editMessage(addMsg).setComponents().queue();
							LOG.info("Spotify playlist prompt timed out: guild={}, user={}", guild.getId(),
									member.getUser().getName());
						});
			});
		});
	}
}