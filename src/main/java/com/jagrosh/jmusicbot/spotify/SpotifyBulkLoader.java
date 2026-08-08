package com.jagrosh.jmusicbot.spotify;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

/**
 * Bulk loader component for asynchronously resolving and enqueuing track batches retrieved via Spotify Bridge.
 */
public class SpotifyBulkLoader
{
	private static final Logger LOG = LoggerFactory.getLogger(SpotifyBulkLoader.class);
	
	/**
	 * Resolves Spotify metadata queries against YouTube and adds the resulting tracks sequentially to the guild's queue.
	 */
	public static void loadPlaylist(Bot bot, Guild guild, Member member, TextChannel channel,
			SpotifyBridge.SpotifyResult result, MusicService musicService, InteractionHook hook, String successEmoji)
	{

		if (result.tracks.isEmpty())
			return;

		String firstTitle = result.tracks.get(0);
		String firstArtist = result.artists.get(0);
		Integer firstDurationMs = result.durationMs.get(0);
		String firstQuery = firstTitle + " " + firstArtist;

		bot.getPlayerManager().loadItemOrdered(guild, "ytsearch:" + firstQuery,
				bot.getAudioLoadWrapper().wrap(firstQuery, new AudioLoadResultHandler() {

					@Override
					public void trackLoaded(AudioTrack t)
					{
						processFirstTrackAndContinue(t);
					}

					@Override
					public void playlistLoaded(AudioPlaylist p)
					{
						if (!p.getTracks().isEmpty())
						{
							AudioTrack bestMatch = SpotifyTrackMatcher.selectBestMatch(p.getTracks(), firstTitle,
									firstArtist, firstDurationMs);
							processFirstTrackAndContinue(bestMatch != null ? bestMatch : p.getTracks().get(0));
						} else
						{
							noMatches();
						}
					}

					@Override
					public void noMatches()
					{
						hook.editOriginal(bot.getConfig().getWarning()
								+ " Could not find a match for the first track: **" + firstTitle + "**")
								.setComponents(Collections.emptyList()).queue();
					}

					@Override
					public void loadFailed(FriendlyException e)
					{
						hook.editOriginal(
								bot.getConfig().getWarning() + " Failed to load first track: " + e.getMessage())
								.setComponents(Collections.emptyList()).queue();
					}

					private void processFirstTrackAndContinue(AudioTrack track)
					{
						if (track == null)
						{
							noMatches();
							return;
						}

						AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
						RequestMetadata rm = new RequestMetadata(member.getUser(),
								new RequestMetadata.RequestInfo(firstQuery, track.getInfo().uri), channel.getIdLong());
						
						handler.addTrack(new QueuedTrack(track, rm));
						int pos = (handler.getPlayer().getPlayingTrack() == null) ? 0 : handler.getQueue().size() + 1;

						String addMsg = FormatUtil.filter(successEmoji + " Added **" + track.getInfo().title + "** (`"
								+ TimeUtil.formatTime(track.getDuration()) + "`) "
								+ (pos > 0 ? "to the queue at position " + pos : "to begin playing"));

						if (result.tracks.size() == 1)
						{
							hook.editOriginal(addMsg).setComponents(Collections.emptyList()).queue();
							return;
						}

						hook.editOriginal(
								addMsg + "\n🔄 Loading " + (result.tracks.size() - 1) + " additional tracks...")
								.setComponents(Collections.emptyList()).queue();

						loadRemainingTracks(addMsg);
					}

					/**
					 * Asynchronously resolves and queues the remaining tracks of a Spotify playlist or album
					 * (from index 1 onward) using a bounded thread pool for parallel execution.
					 * <p>
					 * YouTube search lookups are dispatched concurrently to significantly cut down loading times.
					 * Results are held in an index-aligned array to ensure tracks are added to the queue in 
					 * their exact original playlist order once all lookups complete.
					 * <p>
					 * All track additions, queueing logic, and duration checks are routed through 
					 * {@link MusicService#addTrackToQueue}, avoiding direct access to low-level audio handlers.
					 *
					 * @param addMsg The initial Discord response message string displayed when the first track 
					 *               was queued, used as a header for the final completion message.
					 */
					private void loadRemainingTracks(String addMsg)
					{
					    int totalTracks = result.tracks.size();
					    int remainingCount = totalTracks - 1;

					    if (remainingCount <= 0)
					    {
					        return;
					    }

					    AudioTrack[] resolvedTracks = new AudioTrack[totalTracks];
					    AtomicInteger completedTasks = new AtomicInteger(0);
					    AtomicInteger loadedCount = new AtomicInteger(0);

					    ExecutorService executor = Executors.newFixedThreadPool(Math.min(8, Runtime.getRuntime().availableProcessors() * 2));

					    for (int i = 1; i < totalTracks; i++)
					    {
					        final int index = i;
					        final String sTitle = result.tracks.get(i);
					        final String sArtist = result.artists.get(i);
					        final Integer sDurationMs = result.durationMs.get(i);
					        final String trackQuery = sTitle + " " + sArtist;

					        executor.submit(() -> {
					            try
					            {
					                bot.getPlayerManager().loadItem("ytsearch:" + trackQuery, new AudioLoadResultHandler() {

					                    @Override
					                    public void trackLoaded(AudioTrack t)
					                    {
					                        resolvedTracks[index] = t;
					                    }

					                    @Override
					                    public void playlistLoaded(AudioPlaylist p)
					                    {
					                        if (!p.getTracks().isEmpty())
					                        {
					                            AudioTrack bestMatch = SpotifyTrackMatcher
					                                    .selectBestMatch(p.getTracks(), sTitle, sArtist, sDurationMs);
					                            resolvedTracks[index] = bestMatch;
					                        }
					                    }

					                    @Override
					                    public void noMatches() {}

					                    @Override
					                    public void loadFailed(FriendlyException e) {}

					                }).get();
					            }
					            catch (Exception e)
					            {
					                LOG.warn("Failed to search YouTube for: \"{}\"", trackQuery, e);
					            }
					            finally
					            {
					                if (completedTasks.incrementAndGet() == remainingCount)
					                {
					                    executor.shutdown();

					                    AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
					                    
					                    for (int j = 1; j < totalTracks; j++)
					                    {
					                        AudioTrack track = resolvedTracks[j];
					                        if (track == null)
					                        {
					                            LOG.warn("No match found for index {}; skipping.", j);
					                            continue;
					                        }

					                        String searchQuery = result.tracks.get(j) + " " + result.artists.get(j);
					                        RequestMetadata rm = new RequestMetadata(member.getUser(),
					                                new RequestMetadata.RequestInfo(searchQuery, track.getInfo().uri),
					                                channel.getIdLong());

					                        handler.addTrack(new QueuedTrack(track, rm));
					                        loadedCount.incrementAndGet();
					                    }

					                    hook.editOriginal(addMsg + "\n" + bot.getConfig().getSuccess()
					                            + " Loaded **" + loadedCount.get() + "** additional tracks!")
					                            .setComponents(Collections.emptyList())
					                            .queue();
					                }
					            }
					        });
					    }
					}
				}));
	}
}
