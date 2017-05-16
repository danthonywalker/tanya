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
package technology.yockto.tanya.audio

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import sx.blah.discord.handle.obj.IGuild
import technology.yockto.tanya.config.Config
import technology.yockto.tanya.json.getJsonFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

class GuildAudioManager : AutoCloseable {
    private val metadata = ConcurrentHashMap<IGuild, AudioMetadata>()
    private val playerManager = DefaultAudioPlayerManager().apply {
        getJsonFile(Config::class).lavaPlayer.apply {

            setItemLoaderThreadPoolSize(itemLoaderThreadPoolSize)
            setPlayerCleanupThreshold(playerCleanupThreshold)
            setTrackStuckThreshold(trackStuckThreshold)
            setFrameBufferDuration(frameBufferDuration)
            setUseSeekGhosting(seekGhosting)
            useRemoteNodes(*remoteNodes)
        }

        AudioSourceManagers.registerRemoteSources(this)
        AudioSourceManagers.registerLocalSource(this)
    }

    fun getMetadata(guild: IGuild): AudioMetadata {
        return metadata.computeIfAbsent(guild, { AudioMetadata(playerManager) }).apply {
            guild.audioManager.audioProvider = provider //For new but equal guild object
        }
    }

    fun process(trackUrl: String, guild: IGuild, resultHandler: AudioLoadResultHandler): Future<Void> {
        return playerManager.loadItemOrdered(getMetadata(guild), trackUrl, resultHandler)
    }

    override fun close() = playerManager.shutdown()
    fun removeMetadata(guild: IGuild): AudioMetadata? = metadata.remove(guild)
}