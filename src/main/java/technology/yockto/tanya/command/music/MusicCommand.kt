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

import com.darichey.discord.api.CommandContext
import com.darichey.discord.api.FailureReason
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEvent
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackStartEvent
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import mu.KLogging
import sx.blah.discord.api.events.EventSubscriber
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelLeaveEvent
import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.handle.obj.IVoiceChannel
import sx.blah.discord.handle.obj.Permissions.ADMINISTRATOR
import sx.blah.discord.util.EmbedBuilder
import sx.blah.discord.util.MessageBuilder
import sx.blah.discord.util.RequestBuilder
import technology.yockto.tanya.Tanya
import technology.yockto.tanya.audio.GuildAudioManager
import technology.yockto.tanya.command.AbstractCommand
import technology.yockto.tanya.json.getJsonFile
import java.awt.Color
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

class MusicCommand : AbstractCommand(getJsonFile(MusicConfig::class)), AudioEventListener {
    private val metadata = ConcurrentHashMap<AudioTrack, IMessage>()
    private val guildAudioManager = GuildAudioManager()

    override fun onFailure(context: CommandContext, reason: FailureReason) {
        logger.info { "${context.message.content} failed with the following reason: $reason" }
        //When commands fail it's because they're missing permissions so just silently ignore.
    }

    override fun onExecuted(context: CommandContext) {
        val arguments = context.args
        when(arguments.size) {

            0 -> { //Attachment means user wanted to upload
                if(context.message.attachments.size == 1) {
                    return play(context)
                }
            }

            1 -> { //Cam assume link
                when(arguments[0]) {
                    "enable" -> return enable(context)
                    "help" -> return help(context)
                    else -> return play(context)
                }
            }
        }

        help(context)
    }

    override fun onEvent(event: AudioEvent) {
        event.let { it as? TrackEndEvent }?.let { onTrackEndEvent(it.player, it.track, it.endReason) }
        event.let { it as? TrackStartEvent }?.let { onTrackStartEvent(it.player, it.track) }
    }

    @EventSubscriber
    fun onReadyEvent(event: ReadyEvent) = Tanya.database.useTransaction {
        val sql = "SELECT guild_id, voice_id, text_id FROM music_configuration"
        logger.info { "PreparedStatement SQL with TYPE_FORWARD_ONLY and CONCUR_UPDATABLE: $sql" }
        it.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE).use {
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

                    } else { //Try a voice connection.
                        RequestBuilder(client).apply {
                            shouldBufferRequests(true)

                            onMissingPermissionsError { exception ->
                                logger.info(exception, { "DELETE WHERE voiceChannel = $voiceChannelId" })
                                it.deleteRow() //Since bot can't connect to voice just disable the module
                            }

                            doAction { voiceChannel.join() == Unit }

                            andThen { //guild_id is guaranteed to exist if it hits here
                                val guild = client.getGuildByID(it.getLong("guild_id"))

                                //Ensure only one player listener exists per equal guild
                                val player = guildAudioManager.getMetadata(guild).player
                                player.removeListener(this@MusicCommand)
                                player.addListener(this@MusicCommand) == Unit
                            }
                        }.execute()
                    }
                }
            }
        }
    }

    @EventSubscriber
    fun onUserVoiceChannelLeaveEvent(event: UserVoiceChannelLeaveEvent) {
        val guild = event.guild //Check for guild as the user may have tracks from other guilds too
        val userTracks = metadata.filterValues { (it.guild == guild) && (it.author == event.user) }

        if(userTracks.isNotEmpty()) {
            Tanya.database.useConnection {
                val sql = "SELECT auto_remove FROM music_configuration WHERE guild_id = ?"
                logger.info { "PrepareStatement SQL: $sql" }
                it.prepareStatement(sql).use {

                    it.setLong(1, guild.longID)
                    it.executeQuery().use {

                        if(it.next() && it.getBoolean("auto_remove")) { //Clear from queue
                            val scheduler = guildAudioManager.getMetadata(guild).scheduler
                            userTracks.forEach { audioTrack, iMessage ->

                                scheduler.remove(audioTrack)
                                metadata.remove(audioTrack, iMessage)
                                scheduler.takeIf { it.currentTrack == audioTrack }?.skip()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onTrackEndEvent(player: AudioPlayer, track: AudioTrack, reason: AudioTrackEndReason) {
        metadata.remove(track) //Allow GC to work its magic, this is required after every track ending
    }

    private fun onTrackStartEvent(player: AudioPlayer, track: AudioTrack) {
        val metadata = metadata[track]!!
        val client = metadata.client

        RequestBuilder(client).apply {
            shouldBufferRequests(true)

            doAction { //Mimic a RequestBuffer
                MessageBuilder(client).apply {
                    EmbedBuilder().apply {

                        withTitle("Now Playing")
                        appendAudioTrack(track)
                        withColor(Color.CYAN)
                        withEmbed(build())
                    }

                    withChannel(metadata.channel)
                }.send() != null
            }
        }.execute()
    }

    private fun enable(context: CommandContext) {
        val message = context.message
        val channel = message.channel
        val content = message.content
        val client = message.client
        val guild = message.guild
        val user = message.author
        val username = user.name

        if(user.getPermissionsForGuild(guild).contains(ADMINISTRATOR)) {
            var voiceChannelName: String? = null
            var textChannelName: String? = null

            var voiceChannel: IVoiceChannel? = null
            var textChannel: IChannel? = null

            RequestBuilder(client).apply {
                shouldBufferRequests(true)

                doAction { //Mimic a RequestBuffer
                    MessageBuilder(client).apply {
                        EmbedBuilder().apply {

                            appendField("Enabling the Music Module", "1) **ENTER** the *full name* of the __voice " +
                                "channel__ that music should be played through.\n2) **ENTER** the *name* of the " +
                                "__text channel__ that commands should be responded to.\n\n**NOTE:** Do **not** " +
                                "*mention* any channel for guaranteed results.", false)
                            withFooterText("$username: $content")
                            withColor(Color.YELLOW)
                            withEmbed(build())
                        }

                        withChannel(channel)
                    }.send() != null
                }

                andThen { //Gets next two messages from user as well as the same channel
                    voiceChannelName = client.dispatcher.waitFor<MessageReceivedEvent>({
                        (it.channel == channel) && (it.author == user)
                    }, 30, SECONDS)?.message?.content

                    if(voiceChannelName != null) { //If responded, then get a next response
                        textChannelName = client.dispatcher.waitFor<MessageReceivedEvent>({
                            (it.channel == channel) && (it.author == user)
                        }, 30, SECONDS)?.message?.content
                    }

                    (voiceChannelName != null) && (textChannelName != null)
                }

                andThen { //Check to make sure that provided channels are valid for use
                    textChannel = guild.getChannelsByName(textChannelName).getOrNull(0)
                    voiceChannel = guild.getVoiceChannelsByName(voiceChannelName).getOrNull(0)

                    MessageBuilder(client).apply {
                        EmbedBuilder().apply {

                            appendField("Status", "**Voice Channel:** *${voiceChannel?.name}*\n**Text Channel:** " +
                                "*${textChannel?.name}*\n\n**NOTE:** If *null*, then the channel is *invalid*!", false)
                            withFooterText("Requested Voice: $voiceChannelName | Requested Text: $textChannelName")
                            withColor(Color.MAGENTA)
                            withEmbed(build())
                        }

                        withChannel(channel)
                    }.send()

                    (textChannel != null) && (voiceChannel != null)
                }

                andThen { voiceChannel?.join() == Unit }

                andThen { //By this point, do save
                    Tanya.database.useConnection {
                        val sql = "INSERT INTO music_configuration (guild_id, voice_id, text_id) " +
                            "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE voice_id = ?, text_id = ?"
                        logger.info { "PrepareStatement SQL: $sql" }
                        it.prepareStatement(sql).use {

                            it.setLong(1, guild.longID)
                            it.setLong(2, voiceChannel!!.longID)
                            it.setLong(3, textChannel!!.longID)
                            it.setLong(4, voiceChannel!!.longID)
                            it.setLong(5, textChannel!!.longID)

                            it.executeUpdate() == 1
                        }
                    }
                }
            }.execute()

            //Ensure only one player listener exists per equal guild
            val player = guildAudioManager.getMetadata(guild).player
            player.removeListener(this)
            player.addListener(this)

        } else { //Only lets trusted users
            RequestBuilder(client).apply {
                shouldBufferRequests(true)

                doAction { //Mimic a RequestBuffer
                    MessageBuilder(client).apply {
                        EmbedBuilder().apply {

                            withDescription("**$username**, you must have the following " +
                                "permission(s) to use that command:\n**${ADMINISTRATOR.name}**")
                            withTitle("Insufficient Permissions")
                            withFooterText("$username: $content")
                            withColor(Color.RED)
                            withEmbed(build())
                        }

                        withChannel(channel)
                    }.send() != null
                }
            }.execute()
        }
    }

    private fun play(context: CommandContext) {
        val message = context.message
        val channel = message.channel
        val content = message.content
        val client = message.client
        val user = message.author
        val guild = message.guild
        val username = user.name

        val trackUrl = context.args.getOrNull(0) ?: message.attachments[0].url
        val voiceChannel: IVoiceChannel? = user.getVoiceStateForGuild(guild).channel

        Tanya.database.useConnection {
            val sql = "SELECT * FROM music_configuration WHERE guild_id = ?"
            logger.info { "PrepareStatement SQL: $sql" }
            it.prepareStatement(sql).use {

                it.setLong(1, guild.longID)
                it.executeQuery().use {
                    if(it.next()) {

                        if(it.getBoolean("force_text") && (it.getLong("text_id") != channel.longID)) {
                            return@useConnection //Text usage should be obvious so ignore the commands

                        } else if(it.getBoolean("force_voice") && (it.getLong("voice_id") != voiceChannel?.longID)) {
                            RequestBuilder(client).apply {
                                shouldBufferRequests(true)

                                doAction { //Mimic a RequestBuffer
                                    MessageBuilder(client).apply {
                                        EmbedBuilder().apply {

                                            withDescription("**$username**, in order to make a request " +
                                                "you *must* be in the dedicated music voice channel!")
                                            withFooterText("$username: $content")
                                            withColor(Color.RED)
                                            withEmbed(build())
                                        }

                                        withChannel(channel)
                                    }.send() != null
                                }
                            }.execute()

                            @Suppress("LABEL_NAME_CLASH")
                            return@use //Will do a delete
                        }

                        val scheduler = guildAudioManager.getMetadata(guild).scheduler
                        val fakeQueue = scheduler.queue //Queue simply copies original

                        //Get results as ResultSet will be closed later
                        val playlistLimit = it.getInt("playlist_limit")
                        val streamable = it.getBoolean("allow_stream")
                        val requestLimit = it.getInt("request_limit")
                        val queueLimit = it.getInt("queue_limit")
                        val timeLimit = it.getInt("time_limit")

                        fun isAudioTrackValid(track: AudioTrack): Boolean {
                            val trackInfo = track.info

                            if(!streamable && trackInfo.isStream) {
                                return false //A stream not allowed
                            }

                            if((timeLimit != -1) && (TimeUnit.MILLISECONDS.toSeconds(trackInfo.length) > timeLimit)) {
                                return false //time_limit of -1 indicates to disable the limit so ignore that case too
                            }

                            if((queueLimit != -1) && ((fakeQueue.size + 1) > queueLimit)) {
                                return false //queue_limit of -1 indicates to disable limit
                            }

                            val requests = metadata.values.filter { (it.guild == guild) && (it.author == user) }.size
                            if((requestLimit != -1) && ((requests + 1) > requestLimit)) {
                                return false //request_limit of -1 means to disable limit
                            }

                            return true
                        }

                        guildAudioManager.process(trackUrl, guild, object : AudioLoadResultHandler {
                            override fun loadFailed(exception: FriendlyException) {
                                logger.warn(exception, { "Caused by message: $message" })

                                RequestBuilder(client).apply {
                                    shouldBufferRequests(true)

                                    doAction { //Mimic a RequestBuffer
                                        MessageBuilder(client).apply {
                                            EmbedBuilder().apply {

                                                withDescription("An unexpected error occurred! Contact " +
                                                    "the developers on Github immediately!\n$exception")
                                                withFooterText("$username: $content")
                                                withColor(Color.RED)
                                                withEmbed(build())
                                            }

                                            withChannel(channel)
                                        }.send() != null
                                    }
                                }.execute()
                            }

                            override fun playlistLoaded(playlist: AudioPlaylist) {
                                val tracks = ArrayList<AudioTrack>(playlistLimit)
                                val playlistTracks = playlist.tracks
                                var successfulUploadCount = 0

                                var index = 0 //Keeps iterating through playlist to find a valid track to play
                                while(index < playlistTracks.size && successfulUploadCount <= playlistLimit) {

                                    val track = playlistTracks[index]
                                    if(isAudioTrackValid(track)) {
                                        metadata.put(track, message)
                                        fakeQueue.add(track)
                                        tracks.add(track)

                                        successfulUploadCount++
                                    }

                                    index++
                                }

                                RequestBuilder(client).apply {
                                    shouldBufferRequests(true)

                                    doAction { //Mimic a RequestBuffer
                                        MessageBuilder(client).apply {
                                            EmbedBuilder().apply {

                                                withDescription("Successfully queued $successfulUploadCount " +
                                                    "song(s) from the ${playlist.name} playlist!")
                                                withFooterText("$username: $content")
                                                withColor(Color.CYAN)
                                                withEmbed(build())
                                            }

                                            withChannel(channel)
                                        }.send() != null
                                    }

                                    andThen { tracks.forEach { scheduler.play(it) } == Unit }
                                }.execute()
                            }

                            override fun trackLoaded(track: AudioTrack) {
                                if(isAudioTrackValid(track)) {
                                    metadata.put(track, message)
                                    fakeQueue.add(track)

                                    RequestBuilder(client).apply {
                                        shouldBufferRequests(true)

                                        doAction { //Mimic a RequestBuffer
                                            MessageBuilder(client).apply {
                                                EmbedBuilder().apply {

                                                    withFooterText("$username: $content")
                                                    withTitle("Successful Queue Request")
                                                    appendAudioTrack(track)
                                                    withColor(Color.CYAN)
                                                    withEmbed(build())
                                                }

                                                withChannel(channel)
                                            }.send() != null
                                        }

                                        andThen { scheduler.play(track) is Boolean }
                                    }.execute() //Ignore even if play() return false

                                } else { //Shows the restrictions.
                                    RequestBuilder(client).apply {
                                        shouldBufferRequests(true)

                                        doAction { //Mimic a RequestBuffer
                                            MessageBuilder(client).apply {
                                                EmbedBuilder().apply {

                                                    appendField("Streamable", streamable.toString(), true)
                                                    appendField("Time Limit", "${timeLimit}s", true)
                                                    appendField("Queue Limit", queueLimit.toString(), true)
                                                    appendField("Request Limit", requestLimit.toString(), true)

                                                    withDescription("Ensure the audio is bounded by the restrictions " +
                                                        "that are specifically configured for this server as detailed.")
                                                    withFooterText("$username: $content")
                                                    withTitle("Queue Request Failed")
                                                    withColor(Color.RED)
                                                    withEmbed(build())
                                                }

                                                withChannel(channel)
                                            }.send() != null
                                        }
                                    }.execute()
                                }
                            }

                            override fun noMatches() {
                                RequestBuilder(client).apply {
                                    shouldBufferRequests(true)

                                    doAction { //Mimic a RequestBuffer
                                        MessageBuilder(client).apply {
                                            EmbedBuilder().apply {

                                                withDescription("Could not find audio data for <$trackUrl>. Ensure " +
                                                    "the audio is from a supported format and/or supported source.")
                                                withFooterText("$username: $content")
                                                withColor(Color.RED)
                                                withEmbed(build())
                                            }

                                            withChannel(channel)
                                        }.send() != null
                                    }
                                }.execute()
                            }
                        })

                    } else { //A module is not enabled
                        RequestBuilder(client).apply {
                            shouldBufferRequests(true)

                            doAction { //Mimic a RequestBuffer
                                MessageBuilder(client).apply {
                                    EmbedBuilder().apply {

                                        withDescription("The music module is not enabled on this server! Ask an " +
                                            "*Administrator* to enable it by using the *~music enable* command.")
                                        withFooterText("$username: $content")
                                        withColor(Color.RED)
                                        withEmbed(build())
                                    }

                                    withChannel(channel)
                                }.send() != null
                            }
                        }.execute()
                    }
                }
            }
        }
    }

    private fun help(context: CommandContext) {
    }

    private fun EmbedBuilder.appendAudioTrack(track: AudioTrack): EmbedBuilder {
        //All messages relating to AudioTrack should follow a very similar style
        val metadata = metadata[track]!!
        val trackInfo = track.info

        appendField("Title", trackInfo.title, true)
        appendField("Uploader", trackInfo.author, true)

        val length = trackInfo.length
        val milliSecondsTimeUnit = TimeUnit.MILLISECONDS
        val formattedTime = String.format("%02d:%02d:%02d", milliSecondsTimeUnit.toHours(length),
            milliSecondsTimeUnit.toMinutes(length) - TimeUnit.HOURS.toMinutes(milliSecondsTimeUnit.toHours(length)),
            milliSecondsTimeUnit.toSeconds(length) - TimeUnit.MINUTES.toSeconds(milliSecondsTimeUnit.toMinutes(length)))
        appendField("Length", formattedTime, true)

        val requester = metadata.author.name
        appendField("Requester", requester, true)
        appendField("Link", "<${trackInfo.uri}>", true)

        return withFooterText("$requester: ${metadata.content}")
    }

    private companion object : KLogging()
}