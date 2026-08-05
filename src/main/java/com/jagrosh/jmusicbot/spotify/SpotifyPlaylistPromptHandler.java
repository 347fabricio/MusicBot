package com.jagrosh.jmusicbot.spotify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.service.MusicService.OutputAdapter;
import com.jagrosh.jmusicbot.spotify.SpotifyBridge.SpotifyResult;
import com.jagrosh.jmusicbot.utils.FormatUtil;
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

/**
 * Handler responsible for prompting users with interactive Discord components (buttons)
 * before enqueueing multi-track Spotify playlists.
 */
public class SpotifyPlaylistPromptHandler implements AudioLoadResultHandler
{
	private final Bot bot;
	private final Guild guild;
	private final Member member;
	private final TextChannel channel;
	private final OutputAdapter output;
	private final SpotifyResult result;

	private final String successEmoji;
	private final String warningEmoji;
	private final String errorEmoji;

	private final MusicService musicService;

	private static final Logger LOG = LoggerFactory.getLogger(SpotifyPlaylistPromptHandler.class);

	/**
	 * Constructs a new handler to display an interactive confirmation prompt for loading Spotify playlists.
	 */
	public SpotifyPlaylistPromptHandler(Bot bot, Guild guild, Member member, TextChannel channel,
			OutputAdapter output, SpotifyBridge.SpotifyResult result, MusicService musicService)
	{
		this.bot = bot;
		this.guild = guild;
		this.member = member;
		this.channel = channel;
		this.output = output;
		this.result = result;
		this.musicService = musicService;

		this.successEmoji = bot.getConfig().getSuccess();
		this.warningEmoji = bot.getConfig().getWarning();
		this.errorEmoji = bot.getConfig().getError();
	}

	@Override
	public void trackLoaded(AudioTrack track)
	{
		processFirstTrack(track);
	}

	@Override
	public void playlistLoaded(AudioPlaylist playlist)
	{
		if (!playlist.getTracks().isEmpty())
		{
			processFirstTrack(playlist.getTracks().get(0));
		} else
		{
			noMatches();
		}
	}

	@Override
	public void noMatches()
	{
		channel.sendMessage(warningEmoji + " No results found for the first track.").queue();
	}

	@Override
	public void loadFailed(FriendlyException exception)
	{
		channel.sendMessage(errorEmoji + " Error loading first track.").queue();
	}

	/**
	 * Evaluates the first resolved track and builds the confirmation button message wait loop.
	 */
	private void processFirstTrack(AudioTrack track)
	{
		if (musicService.isTooLong(track))
		{
			channel.sendMessage(FormatUtil.filter(warningEmoji + " Track too long.")).queue();
			return;
		}

		String promptMsg = warningEmoji + " This track has a playlist of **" + result.tracks.size()
				+ "** tracks attached.\n"
				+ "⚠️ **Loading Spotify playlists may not always play the exact desired tracks!**\n"
				+ "\t*Do you still want to load it?*";

		List<Button> buttons = new ArrayList<>();
		buttons.add(Button.success("load_playlist", Emoji.fromUnicode("\uD83D\uDCE5")).withLabel("Load Playlist"));
		buttons.add(Button.danger("cancel_playlist", Emoji.fromUnicode("\uD83D\uDEAB")).withLabel("Cancel"));

		StringBuilder sb = new StringBuilder("");
		sb.append(promptMsg);
		MessageEditBuilder editBuilder = new MessageEditBuilder().setContent(sb.toString())
				.setComponents(ActionRow.of(buttons));

		LOG.info("Action: PROMPT_CREATED | GuildId: {} | User: {} ({}) | TotalTracks: {}", guild.getId(),
				member.getUser().getName(), member.getUser().getId(), result.tracks.size());

		output.editMessage(sb.toString(), m -> {
			m.editMessage(editBuilder.build()).queue(msg -> {
				bot.getWaiter().waitForEvent(ButtonInteractionEvent.class,
						e -> e.getMessageId().equals(msg.getId()) && e.getUser().getIdLong() == member.getIdLong(),
						e -> {
							if (e.getComponentId().equals("cancel_playlist"))
							{
								e.editMessage(promptMsg).setComponents().queue();
								LOG.info("Action: CANCELLED | GuildId: {} | User: {} ({})", guild.getId(),
										member.getUser().getName(), member.getUser().getId());
								return;
							}
							if (e.getComponentId().equals("load_playlist"))
							{
								e.deferEdit().queue(hook -> {
									hook.editOriginal("🔄 Loading " + result.tracks.size() + " Spotify tracks.")
											.setComponents(Collections.emptyList())
											.queue(message -> SpotifyBulkLoader.loadPlaylist(bot, guild, member,
													channel, result, musicService, hook, successEmoji));
								});
								LOG.info("Action: APPROVED | GuildId: {} | User: {} ({}) | LoadingTracks: {}",
										guild.getId(), member.getUser().getName(), member.getUser().getId(),
										result.tracks.size());
							}
						}, 30, TimeUnit.SECONDS, () -> {
							msg.editMessage(promptMsg).setComponents().queue();
							LOG.info("Action: TIMEOUT | GuildId: {} | User: {} ({})", guild.getId(),
									member.getUser().getName(), member.getUser().getId());
						});
			});
		});
	}
}