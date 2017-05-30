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
import technology.yockto.bc4d4j.api.CommandContext
import technology.yockto.bc4d4j.api.MainCommand
import technology.yockto.tanya.Tanya
import technology.yockto.tanya.audio.GuildAudioManager
import technology.yockto.tanya.command.Command
import technology.yockto.tanya.getRequestBuilder
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

    private fun IVoiceChannel.joinAndRegister() {
        join() //Join voice channel and guarantee 1 listener instance
        val player = guildAudioManager.getAudioMetadata(guild).player
        player.removeListener(this@MusicCommand)
        player.addListener(this@MusicCommand)
    }

    private companion object : KLogging()
}