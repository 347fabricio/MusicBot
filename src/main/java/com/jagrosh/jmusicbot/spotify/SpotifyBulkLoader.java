package com.jagrosh.jmusicbot.spotify;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.service.MusicService;
import com.jagrosh.jmusicbot.utils.FormatUtil;
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

    private static final ConcurrentHashMap<Long, Long> ACTIVE_LOAD_TOKENS = new ConcurrentHashMap<>();
    private static final AtomicLong TOKEN_GENERATOR = new AtomicLong(0);

    /**
     * Cancels any active Spotify background loading task for the specified guild.
     * Call this from commands like /stop, /clear, or when starting a new playback command.
     *
     * @param guildId the ID of the guild whose loading tasks should be cancelled
     */
    public static void cancelLoading(long guildId)
    {
        ACTIVE_LOAD_TOKENS.remove(guildId);
        LOG.debug("Cancelled active Spotify bulk load for guild {}", guildId);
    }

    private static boolean isCancelled(long guildId, long token)
    {
        Long activeToken = ACTIVE_LOAD_TOKENS.get(guildId);
        return activeToken == null || activeToken != token;
    }

    /**
     * Resolves Spotify metadata queries against YouTube and adds the resulting tracks sequentially to the guild's queue.
     */
    public static void loadPlaylist(Bot bot, Guild guild, Member member, TextChannel channel,
            SpotifyBridge.SpotifyResult result, MusicService musicService, InteractionHook hook)
    {
        if (result == null || result.tracks == null || result.tracks.isEmpty())
            return;

        long guildId = guild.getIdLong();
        long loadToken = TOKEN_GENERATOR.incrementAndGet();
        ACTIVE_LOAD_TOKENS.put(guildId, loadToken);

        String successEmoji = bot.getConfig().getSuccess();
        String warningEmoji = bot.getConfig().getWarning();

        String firstTitle = result.tracks.get(0);
        String firstArtist = (result.artists != null && !result.artists.isEmpty()) ? result.artists.get(0) : "";
        Integer firstDurationMs = (result.durationMs != null && !result.durationMs.isEmpty()) ? result.durationMs.get(0) : null;
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
                        } 
                        else
                        {
                            noMatches();
                        }
                    }

                    @Override
                    public void noMatches()
                    {
                        ACTIVE_LOAD_TOKENS.remove(guildId, loadToken);
                        hook.editOriginal(bot.getConfig().getWarning()
                                + " Could not find a match for the first track: **" + firstTitle + "**")
                                .setComponents(Collections.emptyList()).queue();
                    }

                    @Override
                    public void loadFailed(FriendlyException e)
                    {
                        ACTIVE_LOAD_TOKENS.remove(guildId, loadToken);
                        hook.editOriginal(
                                bot.getConfig().getWarning() + " Failed to load first track: " + e.getMessage())
                                .setComponents(Collections.emptyList()).queue();
                    }

                    private void processFirstTrackAndContinue(AudioTrack track)
                    {
                        if (isCancelled(guildId, loadToken))
                        {
                            LOG.debug("Spotify bulk load cancelled before processing first track for guild {}", guildId);
                            return;
                        }

                        if (track == null)
                        {
                            noMatches();
                            return;
                        }

                        MusicService.TrackAddResult addResult = musicService.addTrackToQueue(guild, member, track,
                                "Spotify: " + track.getInfo().uri, channel);

                        String addMsg;

                        if (addResult == null)
                        {
                            addMsg = FormatUtil.filter(warningEmoji + " " + musicService.formatTooLongError(track));

                            if (result.tracks.size() == 1)
                            {
                                ACTIVE_LOAD_TOKENS.remove(guildId, loadToken);
                                hook.editOriginal(addMsg).setComponents(Collections.emptyList()).queue();
                                return;
                            }
                        } 
                        else
                        {
                            addMsg = FormatUtil.filter(successEmoji + " " + addResult.formattedMessage);
                        }

                        if (result.tracks.size() == 1)
                        {
                            ACTIVE_LOAD_TOKENS.remove(guildId, loadToken);
                            hook.editOriginal(addMsg).setComponents(Collections.emptyList()).queue();
                            return;
                        }

                        hook.editOriginal(
                                addMsg + "\n🔄 Loading " + (result.tracks.size() - 1) + " additional tracks...")
                                .setComponents(Collections.emptyList()).queue();

                        loadRemainingTracks(addMsg, loadToken);
                    }

                    private void loadRemainingTracks(String addMsg, long token)
                    {
                        int totalTracks = result.tracks.size();
                        int remainingCount = totalTracks - 1;

                        if (remainingCount <= 0)
                        {
                            ACTIVE_LOAD_TOKENS.remove(guildId, token);
                            return;
                        }

                        AudioTrack[] resolvedTracks = new AudioTrack[totalTracks];
                        AtomicInteger completedTasks = new AtomicInteger(0);
                        AtomicInteger loadedCount = new AtomicInteger(0);

                        ExecutorService executor = Executors.newFixedThreadPool(
                                Math.min(8, Runtime.getRuntime().availableProcessors() * 2));

                        for (int i = 1; i < totalTracks; i++)
                        {
                            final int index = i;
                            final String sTitle = result.tracks.get(i);
                            final String sArtist = (result.artists != null && result.artists.size() > i) ? result.artists.get(i) : "";
                            final Integer sDurationMs = (result.durationMs != null && result.durationMs.size() > i) ? result.durationMs.get(i) : null;
                            final String trackQuery = sTitle + " " + sArtist;

                            executor.submit(() -> {
                                try
                                {
                                    if (isCancelled(guildId, token))
                                        return;

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
                                        try
                                        {
                                            if (isCancelled(guildId, token))
                                            {
                                                LOG.debug("Spotify bulk load cancelled prior to queueing for guild {}", guildId);
                                                return;
                                            }

                                            for (int j = 1; j < totalTracks; j++)
                                            {
                                                if (isCancelled(guildId, token))
                                                {
                                                    LOG.debug("Spotify queueing interrupted by cancellation for guild {}", guildId);
                                                    break;
                                                }

                                                AudioTrack track = resolvedTracks[j];
                                                if (track == null)
                                                {
                                                    LOG.warn("No match found for index {}; skipping.", j);
                                                    continue;
                                                }

                                                String searchQuery = result.tracks.get(j) + " " + result.artists.get(j);
                                                
                                                MusicService.TrackAddResult addResult = musicService.addTrackToQueue(
                                                        guild, member, track, searchQuery, channel);

                                                if (addResult != null)
                                                {
                                                    loadedCount.incrementAndGet();
                                                }
                                            }

                                            if (!isCancelled(guildId, token))
                                            {
                                                hook.editOriginal(addMsg + "\n" + bot.getConfig().getSuccess()
                                                        + " Loaded **" + loadedCount.get() + "** additional tracks!")
                                                        .setComponents(Collections.emptyList())
                                                        .queue();
                                            }
                                        }
                                        catch (Exception ex)
                                        {
                                            LOG.error("Error during Spotify bulk queueing completion for guild {}", guildId, ex);
                                            hook.editOriginal(addMsg + "\n" + bot.getConfig().getWarning() 
                                                    + " Finished processing with errors. Loaded **" + loadedCount.get() + "** tracks.")
                                                    .setComponents(Collections.emptyList())
                                                    .queue();
                                        }
                                        finally
                                        {
                                            ACTIVE_LOAD_TOKENS.remove(guildId, token);
                                        }
                                    }
                                }
                            });
                        }
                    }
                }));
    }
}