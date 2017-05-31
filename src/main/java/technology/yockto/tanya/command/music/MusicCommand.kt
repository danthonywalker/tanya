/*
 * Tanya - Discord Bot
 * Copyright (C) 2017  Yockto Technologies
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package technology.yockto.tanya.command.music

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason.LOAD_FAILED
import mu.KLogging
import org.apache.commons.lang3.time.DurationFormatUtils
import sx.blah.discord.api.events.EventSubscriber
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.obj.IGuild
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.handle.obj.IUser
import sx.blah.discord.handle.obj.IVoiceChannel
import sx.blah.discord.handle.obj.Permissions.ADMINISTRATOR
import sx.blah.discord.util.EmbedBuilder
import technology.yockto.bc4d4j.api.CommandContext
import technology.yockto.bc4d4j.api.MainCommand
import technology.yockto.bc4d4j.api.SubCommand
import technology.yockto.tanya.Tanya
import technology.yockto.tanya.audio.GuildAudioManager
import technology.yockto.tanya.command.Command
import technology.yockto.tanya.command.music.MusicPermission.ALLOW_STREAM
import technology.yockto.tanya.command.music.MusicPermission.PLAY_REQUIRE_TEXT
import technology.yockto.tanya.command.music.MusicPermission.PLAY_REQUIRE_VOICE
import technology.yockto.tanya.command.music.MusicPermission.QUEUE_REQUIRE_TEXT
import technology.yockto.tanya.command.music.MusicPermission.QUEUE_REQUIRE_VOICE
import technology.yockto.tanya.getRequestBuilder
import technology.yockto.tanya.sendMessage
import technology.yockto.tanya.withFooterText
import java.awt.Color
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MusicCommand : AudioEventAdapter(), Command {
    private val skipVoters = ConcurrentHashMap<IGuild, MutableSet<IUser>>()
    private val trackMetadata = ConcurrentHashMap<AudioTrack, IMessage>()
    private val guildAudioManager = GuildAudioManager()

    init { //Automatically registers class as listener
        Tanya.client.dispatcher.registerListener(this)
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        trackMetadata.remove(track)?.takeUnless { endReason == LOAD_FAILED }?.let { message ->

            Tanya.database.useConnection {
                val sql = "{call music_history_insert(?, ?, ?)}"
                logger.info { "PrepareCall SQL: $sql" }
                it.prepareCall(sql).use {

                    it.setLong("g_id", message.guild.longID)
                    it.setLong("u_id", message.author.longID)
                    it.setString("t_url", track.info.uri)
                    it.execute()
                }
            }
        }
    }

    @EventSubscriber
    fun onReadyEvent(event: ReadyEvent): Unit = Tanya.database.useTransaction {
        //Use a transaction so that delete() calls will be all executed at once

        val sql = "{call music_configuration_startup()}"
        logger.info { "PrepareCall SQL w/ TYPE_FORWARD_ONLY and CONCUR_UPDATABLE: $sql" }
        it.prepareCall(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE).use {

            it.executeQuery().use {
                while(it.next()) {

                    val client = event.client
                    val textChannelId = it.getLong("text_id")
                    val voiceChannelId = it.getLong("voice_id")
                    val textChannel = client.getChannelByID(textChannelId)
                    val voiceChannel = client.getVoiceChannelByID(voiceChannelId)

                    if((textChannel == null) || (voiceChannel == null)) {
                        logger.info { "DELETE WHERE textChannel = $textChannelId and voiceChannel = $voiceChannelId" }
                        it.deleteRow() //Essentially disables the module since bot could not have used either channel.

                    } else { //Tries to connect to VoiceChannel
                        fun onException(exception: Exception) {
                            logger.info(exception, { "DELETE WHERE voiceChannel = $voiceChannelId" })
                            it.deleteRow() //Since bot can't connect to voice just disable the module
                        }

                        client.getRequestBuilder(textChannel).apply {
                            onMissingPermissionsError { onException(it) }
                            onDiscordError { onException(it) }
                            onGeneralError { onException(it) }

                            doAction { voiceChannel.joinAndRegister() == Unit }
                        }.execute()
                    }
                }
            }
        }
    }

    @MainCommand(
        prefix = "~",
        name = "music",
        deleteMessage = true,
        aliases = arrayOf("music"),
        subCommands = arrayOf("music help", "music enable", "music restart", "music disable", "music queue",
            "music current", "music history", "music skip", "music voteskip", "music shuffle", "music play",
            "music pause", "music forward", "music rewind", "music seek", "music repeat", "music config"))
    fun musicCommand(context: CommandContext) {

        val attachmentUrl = context.message.attachments.getOrNull(0)?.url
        val linkUrl = context.arguments.singleOrNull()
        val trackUrl = linkUrl ?: attachmentUrl
        trackUrl?.let { play(it, context) }
    }

    @SubCommand(
        name = "music enable",
        aliases = arrayOf("enable", "start"),
        permissions = arrayOf(ADMINISTRATOR))
    fun musicEnable(context: CommandContext) {

        val message = context.message
        val client = message.client
        val author = message.author
        val guild = message.guild

        val textChannel = message.channel
        val firstVoiceChannel = guild.voiceChannels.getOrNull(0)
        val connectedVoiceChannel = author.getVoiceStateForGuild(guild).channel
        val voiceChannel: IVoiceChannel? = connectedVoiceChannel ?: firstVoiceChannel

        //If voice channel is available attempt to start
        client.getRequestBuilder(textChannel).doAction {

            if(voiceChannel == null) {
                EmbedBuilder().apply {
                    withDescription("An error occurred while attempting to enable the " +
                        "music module. Refer to the following for possible solutions:")

                    appendField("1. Create a Voice Channel", "Create a voice channel " +
                        "dedicated for playing music and retry this command.", false)
                    appendField("2. Join a Voice Channel", "Join the voice channel " +
                        "dedicated for playing music and retry this command.", false)

                    withTitle("Voice Channel Not Found")
                    withFooterText(client)
                    withColor(Color.RED)

                    textChannel.sendMessage(build())
                }
            }

            voiceChannel != null
        }.andThen { //Automatically join the channel
            voiceChannel!!.joinAndRegister() == Unit

        }.andThen { //Insert/updates entry
            Tanya.database.useConnection {
                val sql = "{call music_configuration_insert(?, ?, ?)}"
                logger.info { "PrepareCall SQL: $sql" }
                it.prepareCall(sql).use {

                    it.setLong("g_id", guild.longID)
                    it.setLong("v_id", voiceChannel!!.longID)
                    it.setLong("t_id", textChannel.longID)
                    !it.execute()
                }
            }

        }.andThen {
            EmbedBuilder().apply {
                withDescription("Successfully enabled the music module for **${guild.name}**!")
                withFooterText("Settings may be altered via the ~music config command.")
                appendField("Voice Channel", voiceChannel!!.name, true)
                appendField("Text Channel", textChannel.name, true)
                withTitle("Music Module Enabled")
                withColor(Color.GREEN)

                textChannel.sendMessage(build())
            } is EmbedBuilder
        }.execute()
    }

    @SubCommand(
        name = "music queue",
        aliases = arrayOf("queue", "list"))
    fun musicQueue(context: CommandContext) {

        val message = context.message
        val textChannel = message.channel

        val client = message.client
        val guild = message.guild

        Tanya.database.useConnection {
            val sql = "{call music_configuration_channelPermissions(?)}"
            logger.info { "PrepareCall SQL: $sql" }
            it.prepareCall(sql).use {

                it.setLong("g_id", guild.longID)
                it.executeQuery().use {

                    if(it.validate(message, QUEUE_REQUIRE_TEXT, QUEUE_REQUIRE_VOICE)) {
                        val scheduler = guildAudioManager.getAudioMetadata(guild).scheduler
                        val nowPlaying = scheduler.currentTrack
                        val nowPlayingInfo = nowPlaying?.info
                        val queue = scheduler.queue

                        client.getRequestBuilder(textChannel).doAction {
                            EmbedBuilder().apply {
                                setLenient(true)

                                if(queue.isEmpty()) { //Minimizes post if no songs are available
                                    withFooterText("There are currently no songs in the queue!")

                                } else { //Print out as many fields as possible
                                    queue.forEachIndexed { index, audioTrack ->
                                        val info = audioTrack.info
                                        val uploader = info.author
                                        val title = info.title

                                        val requester = trackMetadata[audioTrack]?.author?.name
                                        val length = DurationFormatUtils.formatDuration(info.length, "HH:mm:ss")

                                        appendField("${index + 1}: $title", "```\nUploader:  $uploader" +
                                            "\nLength:    $length\nRequester: $requester```", false)
                                    }
                                }

                                nowPlaying?.let { withDescription("Now Playing: ${nowPlayingInfo!!.title} by " +
                                    "${nowPlayingInfo.author} as requested by ${trackMetadata[it]?.author?.name}") }

                                val timeEst = queue.map { it.info.length }.sum() + (nowPlaying?.info?.length ?: 0)
                                withTitle("Song Queue (${queue.size}) | (" + //Displays song counter and time left
                                    "${DurationFormatUtils.formatDuration(timeEst, "HH:mm:ss")})")
                                withColor(Color.CYAN)

                                textChannel.sendMessage(build())
                            } is EmbedBuilder
                        }.execute()
                    }
                }
            }
        }
    }

    private fun play(trackUrl: String, context: CommandContext) {
        val message = context.message
        val textChannel = message.channel

        val client = message.client
        val author = message.author
        val guild = message.guild

        Tanya.database.useConnection {
            val sql = "{call music_configuration_play(?)}"
            logger.info { "PrepareCall SQL: $sql" }
            it.prepareCall(sql).use {

                it.setLong("g_id", guild.longID)
                it.executeQuery().use {

                    if(it.validate(message, PLAY_REQUIRE_TEXT, PLAY_REQUIRE_VOICE)) {
                        val scheduler = guildAudioManager.getAudioMetadata(guild).scheduler
                        val fakeQueue = scheduler.queue //Copies original queue to validate

                        val permissions = it.getInt("permissions").getMusicPermissions()
                        val streamable = permissions.contains(ALLOW_STREAM)
                        val playlistLimit = it.getInt("playlist_limit")
                        val requestLimit = it.getInt("request_limit")
                        val queueLimit = it.getInt("queue_limit")
                        val timeLimit = it.getInt("time_limit")

                        fun AudioTrack.isValid(): Boolean {
                            if(!streamable && info.isStream) {
                                return false //Disable streams
                            }

                            val pastTimeLimit = TimeUnit.MILLISECONDS.toSeconds(info.length) > timeLimit
                            if((timeLimit != -1) && !info.isStream && pastTimeLimit) {
                                return false //Ignore streams as they have high length
                            }

                            if((queueLimit != -1) && ((fakeQueue.size) + 1 > queueLimit)) {
                                return false //Simulates fakeQueue as if the song was added
                            }

                            if((requestLimit != -1) && (author.getSongsInQueue(guild).size + 1) > requestLimit) {
                                return false //Checks if user is going to request more song than they are allowed
                            }

                            return true
                        }

                        //Process the url, updating the queue if necessary, and responds to the user
                        guildAudioManager.process(trackUrl, guild, object : AudioLoadResultHandler {

                            override fun loadFailed(exception: FriendlyException) {
                                client.getRequestBuilder(textChannel).doAction {
                                    textChannel.sendMessage(exception) is IMessage
                                }.execute()
                            }

                            override fun playlistLoaded(playlist: AudioPlaylist) {
                                val tracks = ArrayList<AudioTrack>(playlistLimit)
                                val playlistTracks = playlist.tracks
                                var successfulQueueCount = 0

                                var index = 0 //Keep iterating through playlist to find all valid tracks to play
                                while((index < playlistTracks.size) && (successfulQueueCount < playlistLimit)) {

                                    val track = playlistTracks[index]
                                    if(track.isValid()) { //Can be queued
                                        trackMetadata.put(track, message)
                                        successfulQueueCount++
                                        fakeQueue.add(track)
                                        tracks.add(track)
                                    }

                                    index++
                                }

                                client.getRequestBuilder(textChannel).doAction {
                                    tracks.forEach { scheduler.play(it) } == Unit

                                }.andThen {
                                    EmbedBuilder().apply {

                                        appendField("Name", playlist.name, true)
                                        appendField("Song Count", playlistTracks.size.toString(), true)

                                        val playlistLength = playlistTracks.map { it.info.length }.sum()
                                        appendField("Length", //Shows how much music is in the playlist.
                                            DurationFormatUtils.formatDuration(playlistLength, "HH:mm:ss"), true)

                                        appendField("Songs Queued", successfulQueueCount.toString(), true)

                                        val songsQueuedLength = tracks.map { it.info.length }.sum()
                                        appendField("Songs Queued Length", //Shows how much music was added to queue
                                            DurationFormatUtils.formatDuration(songsQueuedLength, "HH:mm:ss"), true)

                                        appendField("Requester", author.name, true)
                                        appendField("Link", "<$trackUrl>", true)

                                        withTitle("Successful Playlist Queue Request")
                                        withColor(Color.GREEN)

                                        textChannel.sendMessage(build())
                                    } is EmbedBuilder
                                }.execute()
                            }

                            override fun trackLoaded(track: AudioTrack) {
                                if(track.isValid()) { //Can add the track
                                    trackMetadata.put(track, message)
                                    scheduler.play(track)

                                    client.getRequestBuilder(textChannel).doAction {
                                        EmbedBuilder().apply {

                                            withTitle("Successful Queue Request")
                                            appendAudioTrack(track)
                                            withColor(Color.GREEN)

                                            textChannel.sendMessage(build())
                                        } is EmbedBuilder
                                    }.execute()

                                } else { //Show user restrictions in place on server
                                    client.getRequestBuilder(textChannel).doAction {
                                        EmbedBuilder().apply {

                                            val tText = if(timeLimit < 0) "Infinite" else "${timeLimit}s"
                                            appendField("Time Limit", tText, true)

                                            val qText = if(queueLimit < 0) "Infinite" else queueLimit.toString()
                                            appendField("Queue Limit", qText, true)

                                            val rText = if(requestLimit < 0) "Infinite" else requestLimit.toString()
                                            appendField("Request Limit", rText, true)

                                            appendField("Streamable", "This server ${if(streamable)
                                                "does" else "does not"} support streaming.", false)

                                            withDescription("Ensure the audio is bounded by the restrictions " +
                                                "that are specifically configured for this server as detailed:")
                                            withTitle("Queue Request Failed")
                                            withColor(Color.RED)

                                            textChannel.sendMessage(build())
                                        } is EmbedBuilder
                                    }.execute()
                                }
                            }

                            override fun noMatches() { //Invoked if not an audio
                                client.getRequestBuilder(textChannel).doAction {
                                    EmbedBuilder().apply {

                                        withDescription("Could not find audio data for <$trackUrl>. Check if " +
                                            "the audio is from a supported source and/or in a supported format.")
                                        withTitle("Audio Not Found")
                                        withColor(Color.RED)

                                        textChannel.sendMessage(build())
                                    } is EmbedBuilder
                                }.execute()
                            }
                        })
                    }
                }
            }
        }
    }

    private fun IVoiceChannel.joinAndRegister() {
        join() //Join voice channel and guarantee 1 listener instance
        val player = guildAudioManager.getAudioMetadata(guild).player
        player.removeListener(this@MusicCommand)
        player.addListener(this@MusicCommand)
    }

    private fun EmbedBuilder.appendAudioTrack(track: AudioTrack): EmbedBuilder {
        //All messages relating to AudioTrack should follow a very similar style
        val trackInfo = track.info

        appendField("Title", trackInfo.title, true)
        appendField("Uploader", trackInfo.author, true)

        appendField("Length", DurationFormatUtils.formatDuration(track.info.length, "HH:mm:ss"), true)
        appendField("Requester", trackMetadata[track]?.author?.name.toString(), true)
        appendField("Link", "<${trackInfo.uri}>", true)

        return this
    }

    private fun IUser.getSongsInQueue(guild: IGuild) = trackMetadata.filterValues {
        (it.author == this) && (it.guild == guild) //Gets songs from current guild.
    }.keys

    private fun ResultSet.validate(message: IMessage, text: MusicPermission, voice: MusicPermission): Boolean {
        val textChannel = message.channel
        val client = message.client
        val guild = message.guild

        if(next()) { //Assume for internal use that this has never been called yet
            val voiceChannel = message.author.getVoiceStateForGuild(guild).channel
            val permissions = getInt("permissions").getMusicPermissions()

            val inVoice = !(permissions.contains(voice) && (voiceChannel?.longID != getLong("voice_id")))
            val inText = !(permissions.contains(text) && (textChannel?.longID != getLong("text_id")))
            return inVoice && inText

        } else { //No results means server is not registered
            client.getRequestBuilder(textChannel).doAction {
                EmbedBuilder().apply {

                    withDescription("Music module is currently disabled for **${guild.name}**! Contact " +
                        "an Administrator to enable the module via utilizing the *~music enable* command.")
                    withTitle("Music Module Disabled")
                    withColor(Color.RED)

                    textChannel.sendMessage(build())
                } is EmbedBuilder
            }.execute()
        }

        return false
    }

    private companion object : KLogging()
}