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

import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import mu.KLogging
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
import technology.yockto.tanya.getRequestBuilder
import java.awt.Color
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

class MusicCommand : AudioEventAdapter(), Command {
    private val skipVoters = ConcurrentHashMap<IGuild, MutableSet<IUser>>()
    private val trackMetadata = ConcurrentHashMap<AudioTrack, IMessage>()
    private val guildAudioManager = GuildAudioManager()

    init { //Automatically registers class as listener
        Tanya.client.dispatcher.registerListener(this)
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
        aliases = arrayOf("music"),
        subCommands = arrayOf("music help", "music enable", "music restart", "music disable", "music queue",
            "music current", "music history", "music skip", "music voteskip", "music shuffle", "music play",
            "music pause", "music forward", "music rewind", "music seek", "music repeat", "music config"))
    fun musicCommand(context: CommandContext) {
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

                    val owner = client.applicationOwner
                    withFooterText("If none of these solutions resolve the " +
                        "issue contact ${owner.name}#${owner.discriminator}.")

                    withTitle("Voice Channel Not Found")
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

    private fun IVoiceChannel.joinAndRegister() {
        join() //Join voice channel and guarantee 1 listener instance
        val player = guildAudioManager.getAudioMetadata(guild).player
        player.removeListener(this@MusicCommand)
        player.addListener(this@MusicCommand)
    }

    private companion object : KLogging()
}