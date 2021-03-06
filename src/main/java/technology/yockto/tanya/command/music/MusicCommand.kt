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
import sx.blah.discord.api.events.Event
import sx.blah.discord.api.events.EventSubscriber
import sx.blah.discord.api.events.IListener
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelLeaveEvent
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelMoveEvent
import sx.blah.discord.handle.obj.IGuild
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.handle.obj.IUser
import sx.blah.discord.handle.obj.IVoiceChannel
import sx.blah.discord.handle.obj.Permissions.MANAGE_CHANNEL
import sx.blah.discord.handle.obj.Permissions.MANAGE_SERVER
import sx.blah.discord.util.EmbedBuilder
import technology.yockto.bc4d4j.api.CommandContext
import technology.yockto.bc4d4j.api.MainCommand
import technology.yockto.bc4d4j.api.SubCommand
import technology.yockto.bc4d4j.impl.getCommandRegistry
import technology.yockto.tanya.Tanya
import technology.yockto.tanya.audio.GuildAudioManager
import technology.yockto.tanya.command.Command
import technology.yockto.tanya.command.music.MusicPermission.*
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

    @EventSubscriber
    fun onUserVoiceChannelLeaveEvent(event: UserVoiceChannelLeaveEvent) {
        val guild = event.guild //Skips / delete from the queue from user
        val userTracks = event.user.getSongsInQueue(guild)

        if(userTracks.isNotEmpty()) {
            Tanya.database.useConnection {
                val sql = "{call music_configuration_voice(?)}"
                logger.info { "PrepareCall SQL: $sql" }
                it.prepareCall(sql).use {

                    it.setLong("g_id", guild.longID)
                    it.executeQuery().use {
                        if(it.next()) {

                            //Check if a guild forces the user to be in voice to hold status
                            val permissions = it.getInt("permissions").getMusicPermissions()
                            if(permissions.contains(REQUIRE_VOICE)) {

                                val scheduler = guildAudioManager.getAudioMetadata(guild).scheduler
                                userTracks.forEach { audioTrack ->

                                    scheduler.remove(audioTrack)
                                    trackMetadata.remove(audioTrack)
                                    scheduler.takeIf { it.currentTrack == audioTrack }?.skip()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventSubscriber
    fun onUserVoiceChannelMoveEvent(event: UserVoiceChannelMoveEvent) { //Both share behaviors
        onUserVoiceChannelLeaveEvent(UserVoiceChannelLeaveEvent(event.oldChannel, event.user))
    }

    @MainCommand(
        prefix = "~",
        name = "music",
        deleteMessage = true,
        aliases = arrayOf("music"),
        usage = "~music <link | attachment>",
        description = "Plays music via link or attachment.",
        subCommands = arrayOf("music help", "music enable", "music restart", "music disable", "music queue",
            "music current", "music history", "music skip", "music voteskip", "music shuffle", "music play",
            "music pause", "music forward", "music rewind", "music seek", "music repeat", "music config"))
    fun musicCommand(context: CommandContext) {

        val attachmentUrl = context.message.attachments.singleOrNull()?.url
        val linkUrl = context.arguments.singleOrNull()
        val trackUrl = linkUrl ?: attachmentUrl

        //Play if a valid trackUrl, else just print out help
        trackUrl?.let { play(it, context) } ?: help(context)
    }

    @SubCommand(
        name = "music help",
        usage = "~music help",
        aliases = arrayOf("help"),
        description = "Displays the help menu.")
    fun musicHelp(context: CommandContext): Unit = help(context)

    @SubCommand(
        name = "music enable",
        usage = "\\* ~music enable",
        aliases = arrayOf("enable", "start"),
        permissions = arrayOf(MANAGE_SERVER),
        description = "Enables the music module.")
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
        name = "music disable",
        usage = "\\* ~music disable",
        aliases = arrayOf("disable"),
        permissions = arrayOf(MANAGE_SERVER),
        description = "Disables the music module.")
    fun musicDisable(context: CommandContext) {

        val message = context.message
        val textChannel = message.channel
        val guild = message.guild

        message.client.getRequestBuilder(textChannel).doAction {
            guild.connectedVoiceChannel?.leave() != null

        }.andThen { //Removes all settings
            Tanya.database.useConnection {
                val sql = "{call music_configuration_delete(?)}"
                logger.info { "PrepareCall SQL: $sql" }
                it.prepareCall(sql).use {

                    it.setLong("g_id", guild.longID)
                    !it.execute()
                }
            }

        }.andThen {
            EmbedBuilder().apply {

                withDescription("The music module has been disabled for **${guild.name}**! All previous " +
                    "settings have been erased. To re-enable the module, use the *~music enable* command.")
                withTitle("Music Module Disabled")
                withColor(Color.YELLOW)

                textChannel.sendMessage(build())
            } is EmbedBuilder
        }.execute()
    }

    @SubCommand(
        name = "music queue",
        usage = "~music queue",
        aliases = arrayOf("queue", "list"),
        description = "Displays the songs in queue.")
    fun musicQueue(context: CommandContext) {

        val message = context.message
        val textChannel = message.channel

        val client = message.client
        val guild = message.guild

        Tanya.database.useConnection {
            val sql = "{call music_configuration_permissions(?)}"
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

                                val timeEst = queue.map { it.info.length }.sum() + (nowPlaying?.position ?: 0)
                                withTitle("Song Queue - (${queue.size}) | (EST: " + //Display song counter/EST
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

    @SubCommand(
        name = "music current",
        usage = "~music current",
        aliases = arrayOf("current", "song"),
        description = "Displays the current playing song.")
    fun musicCurrent(context: CommandContext) {

        val message = context.message
        val textChannel = message.channel
        val guild = message.guild

        Tanya.database.useConnection {
            val sql = "{call music_configuration_permissions(?)}"
            logger.info { "PrepareCall SQL: $sql" }
            it.prepareCall(sql).use {

                it.setLong("g_id", guild.longID)
                it.executeQuery().use {

                    if(it.validate(message, CURRENT_REQUIRE_TEXT, CURRENT_REQUIRE_VOICE)) {
                        val scheduler = guildAudioManager.getAudioMetadata(guild).scheduler

                        message.client.getRequestBuilder(textChannel).doAction {
                            val nowPlaying = scheduler.currentTrack
                            EmbedBuilder().apply {

                                if(nowPlaying == null) { //User could ask for non-playing
                                    withFooterText("There is no song currently playing!")
                                } else { //Uses standard format.
                                    appendAudioTrack(nowPlaying)
                                }

                                withTitle("Now Playing")
                                withColor(Color.CYAN)

                                textChannel.sendMessage(build())
                            } is EmbedBuilder
                        }.execute()
                    }
                }
            }
        }
    }

    @SubCommand(
        name = "music history",
        usage = "~music history",
        aliases = arrayOf("history", "past"),
        description = "Displays the song history.")
    fun musicHistory(context: CommandContext) {

        val message = context.message
        val textChannel = message.channel

        val client = message.client
        val guild = message.guild

        Tanya.database.useTransaction {
            val musicHistoryIds = mutableSetOf<Int>()
            var sql = "{call music_history_retrieve(?)}"
            logger.info { "PrepareCall SQL w/ TYPE_SCROLL_INSENSITIVE and CONCUR_READ_ONLY: $sql" }
            it.prepareCall(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY).use {

                it.setLong("g_id", guild.longID)
                it.executeQuery().use {
                    if(it.next()) {

                        it.absolute(0) //Music is enabled so this won't send that error message
                        if(it.validate(message, HISTORY_REQUIRE_TEXT, HISTORY_REQUIRE_VOICE)) {

                            var appendCounter = 0
                            textChannel.typingStatus = true
                            val embedBuilder = EmbedBuilder().apply {
                                do { //Go ahead and compute first row

                                    val musicId = it.getInt("id")
                                    val userId = it.getLong("user_id")
                                    val trackUrl = it.getString("track_url")

                                    guildAudioManager.process(trackUrl, guild, object : AudioLoadResultHandler {
                                        override fun loadFailed(exception: FriendlyException) {
                                            logger.info(exception, { "Failed to load history" })
                                            musicHistoryIds.add(musicId)
                                        }

                                        override fun playlistLoaded(playlist: AudioPlaylist) {
                                            throw IllegalStateException("No playlist allowed")
                                        }

                                        override fun trackLoaded(track: AudioTrack) {
                                            if(appendCounter < EmbedBuilder.FIELD_COUNT_LIMIT) {

                                                val info = track.info
                                                val title = info.title
                                                val uploader = info.author
                                                val requester = client.getUserByID(userId)?.name
                                                val length = DurationFormatUtils.formatDuration(info.length, "HH:mm:ss")

                                                appendField("${++appendCounter}: $title", "```\nUploader:  $uploader" +
                                                    "\nLength:    $length\nRequester: $requester```", false)

                                            } else { //Impossible to display
                                                musicHistoryIds.add(musicId)
                                            }
                                        }

                                        override fun noMatches() = musicHistoryIds.add(musicId).let {}
                                    }).get() //Waits for ResultHandler to finish before going forward.
                                } while(it.next())

                                withColor(Color.CYAN)
                                withTitle("Song History - ($appendCounter)")
                                takeIf { appendCounter == 0 }?.withFooterText("No history to display!")
                            }

                            client.getRequestBuilder(textChannel).doAction {
                                textChannel.typingStatus = false //Bot not processing
                                textChannel.sendMessage(embedBuilder.build()) != null
                            }.execute()
                        }
                    }
                }
            }

            if(musicHistoryIds.isNotEmpty()) {
                sql = "{call music_history_delete(?)}"
                logger.info { "PrepareCall SQL: $sql" }
                it.prepareCall(sql).use {

                    //Bundle delete for one command
                    musicHistoryIds.forEach { id ->
                        it.setInt("m_id", id)
                        it.addBatch()
                    }

                    it.execute()
                }
            }
        }
    }

    @SubCommand(
        name = "music skip",
        aliases = arrayOf("skip"),
        usage = "\\*\\* ~music skip",
        description = "Force skips the current playing song.")
    fun musicSkip(context: CommandContext) {

        val message = context.message
        val textChannel = message.channel

        if(message.validate(SKIP_REQUIRE_TEXT)) { //May require the user to use in chat
            val scheduler = guildAudioManager.getAudioMetadata(message.guild).scheduler

            message.client.getRequestBuilder(textChannel).doAction {
                EmbedBuilder().apply {

                    withDescription("Skipping the current song...")
                    withTitle("Force Skip")
                    withColor(Color.WHITE)

                    textChannel.sendMessage(build())
                } is EmbedBuilder

            }.andThen { //Force the skip
                scheduler.skip() != null
            }.execute()
        }
    }

    @SubCommand(
        name = "music voteskip",
        usage = "~music voteskip",
        aliases = arrayOf("voteskip"),
        description = "Vote to skip the current playing song.")
    fun musicVoteSkip(context: CommandContext) {

        val message = context.message
        val textChannel = message.channel

        val client = message.client
        val guild = message.guild

        var voters = skipVoters.putIfAbsent(guild, ConcurrentHashMap.newKeySet())
        if(voters == null) { //If null then there was no voteskip so make new one

            Tanya.database.useConnection {
                val sql = "{call music_configuration_voteskip(?)}"
                logger.info { "PrepareCall SQL: $sql" }
                it.prepareCall(sql).use {

                    it.setLong("g_id", guild.longID)
                    it.executeQuery().use {

                        if(it.validate(message, VOTESKIP_REQUIRE_TEXT, VOTESKIP_REQUIRE_VOICE)) {
                            val scheduler = guildAudioManager.getAudioMetadata(guild).scheduler
                            val skipThreshold = it.getByte("skip_threshold").toInt()
                            val voiceChannelId = it.getLong("voice_id")
                            val currentSong = scheduler.currentTrack

                            if(skipThreshold == -1) { //Guild disabled voteskip.
                                client.getRequestBuilder(textChannel).doAction {
                                    EmbedBuilder().apply {

                                        withDescription("Vote skip is currently disabled for this server. Ask " +
                                            "an Administrator to change this via the *~music config* command.")
                                        withTitle("Vote Skip Disabled")
                                        withColor(Color.RED)

                                        textChannel.sendMessage(build())
                                    } is EmbedBuilder
                                }.execute()

                            } else if(currentSong != null) {
                                voters = skipVoters[guild]!!
                                val dispatcher = client.dispatcher

                                dispatcher.registerListener(object : IListener<Event> { //Annotations don't work
                                    val users get() = client.getVoiceChannelByID(voiceChannelId)?.connectedUsers

                                    override fun handle(event: Event) = when(event) {
                                        is UserVoiceChannelLeaveEvent -> onUserVoiceChannelLeaveEvent(event)
                                        is UserVoiceChannelMoveEvent -> onUserVoiceChannelMoveEvent(event)
                                        is MessageReceivedEvent -> onMessageReceivedEvent(event)
                                        else -> { /* Ignored */ }
                                    }

                                    fun onMessageReceivedEvent(event: MessageReceivedEvent) {
                                        if(shouldProcessEvent() && (event.channel.longID == textChannel.longID)) {
                                            val voter = event.author //Checks if the user is even allowed to vote.
                                            voter.takeIf { users?.contains(voter) == true }?.let {

                                                when(event.message.content) {
                                                    "voteyes" -> { //Add vote
                                                        voters!!.add(it)
                                                        calculateCurrentThreshold()
                                                    }

                                                    "voteno" -> { //Removes
                                                        voters!!.remove(it)
                                                        calculateCurrentThreshold()
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    fun onUserVoiceChannelLeaveEvent(event: UserVoiceChannelLeaveEvent) {
                                        if(shouldProcessEvent() && (event.voiceChannel.longID == voiceChannelId)) {

                                            voters!!.remove(event.user)
                                            calculateCurrentThreshold()
                                        }
                                    }

                                    fun onUserVoiceChannelMoveEvent(event: UserVoiceChannelMoveEvent) {
                                        val voiceEvent = UserVoiceChannelLeaveEvent(event.oldChannel, event.user)
                                        onUserVoiceChannelLeaveEvent(voiceEvent) //Act as if the user really left
                                    }

                                    private fun calculateCurrentThreshold() {
                                        val votersSize = voters!!.size.toDouble()
                                        val usersSize = (users?.size?.toDouble() ?: 2.0) - 1
                                        val currentThreshold = (votersSize / usersSize) * 100
                                        val skip = currentThreshold >= skipThreshold

                                        client.getRequestBuilder(textChannel).doAction {
                                            EmbedBuilder().apply {

                                                appendField("Voted Users", "${votersSize.toInt()}", true)
                                                appendField("Users in Voice", "${usersSize.toInt()}", true)
                                                appendField("Threshold", "${currentThreshold.toInt()}%", true)
                                                takeIf { skip }?.withFooterText("Skipping current song...")
                                                withTitle("Vote Skip Status")
                                                withColor(Color.WHITE)

                                                textChannel.sendMessage(build())
                                            } is EmbedBuilder

                                        }.andThen { skip }.andThen {
                                            unregisterTempListener()
                                            scheduler.skip() != null
                                        }.execute()
                                    }

                                    private fun shouldProcessEvent(): Boolean {
                                        val songChanged = scheduler.currentTrack != currentSong
                                        takeIf { songChanged }?.unregisterTempListener()
                                        return !songChanged
                                    }

                                    private fun unregisterTempListener() {
                                        dispatcher.unregisterListener(this)
                                        skipVoters.remove(guild)
                                    }
                                })

                                client.getRequestBuilder(textChannel).doAction {
                                    EmbedBuilder().apply {

                                        withDescription("A vote to skip the currently playing song has been " +
                                            "initiated! Type either `voteyes` or `voteno` in this channel to " +
                                            "vote. Vote will be determined by a $skipThreshold% \"yes\" threshold.")
                                        withTitle("Vote Skip Initiated")
                                        withColor(Color.WHITE)

                                        textChannel.sendMessage(build())
                                    } is EmbedBuilder
                                }.execute()
                            }
                        }
                    }
                }
            }

        } else { //Display to the user to use current voting
            client.getRequestBuilder(textChannel).doAction {
                EmbedBuilder().apply {

                    withDescription("A vote to skip the current song is already in progress! To vote, " +
                        "either enter `voteyes` or `voteno` in the channel the vote was initiated!")
                    withTitle("Vote Skip In Progress")
                    withColor(Color.YELLOW)

                    textChannel.sendMessage(build())
                } is EmbedBuilder
            }.execute()
        }
    }

    private fun help(context: CommandContext) {
        val mainCommand = context.mainCommand
        val message = context.message
        val textChannel = message.channel
        val client = message.client

        client.getRequestBuilder(textChannel).doAction {
            EmbedBuilder().apply {

                appendField(mainCommand.usage, mainCommand.description, true)
                client.getCommandRegistry().getSubCommands(mainCommand).sortedBy { it.name }.forEach {
                    appendField(it.usage, it.description, true) //Commands will come out in same order
                }

                appendField("Footnotes", "[\\*] Only those with the Manage Server permission " +
                    "may use this command.\n[\\*\\*] Only those with the Manage Channel " +
                    "permission and/or the song requester may use this command.", false)
                withTitle("Music Help Menu")
                withColor(Color.PINK)

                textChannel.sendMessage(build())
            } is EmbedBuilder
        }.execute()
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
                                            appendField("User Request Limit", rText, true)

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

        appendField("Position", DurationFormatUtils.formatDuration(track.position, "HH:mm:ss"), true)
        appendField("Length", DurationFormatUtils.formatDuration(trackInfo.length, "HH:mm:ss"), true)
        appendField("Requester", trackMetadata[track]?.author?.name.toString(), true)
        appendField("Link", "<${trackInfo.uri}>", true)

        return this
    }

    private fun IUser.getSongsInQueue(guild: IGuild) = trackMetadata.filterValues {
        (it.author == this) && (it.guild == guild) //Gets songs from current guild.
    }.keys

    private fun IMessage.validate(permission: MusicPermission): Boolean {
        val scheduler = guildAudioManager.getAudioMetadata(guild).scheduler
        val metadata = trackMetadata[scheduler.currentTrack]

        val hasPermission = channel.getModifiedPermissions(author).contains(MANAGE_CHANNEL)
        if((metadata != null) && ((author == metadata.author) || hasPermission)) {

            return Tanya.database.useConnection {
                val sql = "{call music_configuration_permissions(?)}"
                logger.info { "PrepareCall SQL: $sql" }
                it.prepareCall(sql).use {

                    it.setLong("g_id", guild.longID)
                    it.executeQuery().use { it.validate(this, permission, null) }
                }
            }
        }

        return false
    }

    private fun ResultSet.validate(message: IMessage, text: MusicPermission, voice: MusicPermission?): Boolean {
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