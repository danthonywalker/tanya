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

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import sx.blah.discord.handle.audio.AudioEncodingType
import sx.blah.discord.handle.audio.AudioEncodingType.OPUS
import sx.blah.discord.handle.audio.IAudioProvider

class AudioFrameProvider(private val player: AudioPlayer) : IAudioProvider {
    private var lastFrame: AudioFrame? = null
        get() { //Every time frame is requested attempt to provide one
            takeIf { field == null }?.let { field = player.provide() }
            return field
        }

    override fun provide(): ByteArray? {
        val frameData = lastFrame?.data
        lastFrame = null
        return frameData
    }

    override fun getChannels(): Int = 2
    override fun isReady(): Boolean = (lastFrame != null)
    override fun getAudioEncodingType(): AudioEncodingType = OPUS
}