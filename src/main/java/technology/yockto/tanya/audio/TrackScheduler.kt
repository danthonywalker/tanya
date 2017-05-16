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
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import java.util.LinkedList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TrackScheduler(private val player: AudioPlayer) {
    private val backingQueue = LinkedList<AudioTrack>()
    private val lock = ReentrantLock()

    //Since an ArrayList constructor uses some iterator a lock must be required for this
    val queue: MutableList<AudioTrack> get() = lock.withLock { ArrayList(backingQueue) }

    @Volatile var currentTrack: AudioTrack? = null
        private set //Only allows "this" to modify

    init { //Hides listener to preserve class's hierarchy
        player.addListener(object : AudioEventAdapter() {

            override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
                endReason.takeIf(AudioTrackEndReason::mayStartNext)?.let { skip() } //Start playing next song
            }
        })
    }
    
    fun remove(track: AudioTrack): Boolean = lock.withLock { backingQueue.remove(track) }

    fun play(track: AudioTrack): Boolean = lock.withLock {
        val playing = player.startTrack(track, true)

        if(playing) {
            currentTrack = track

        } else { //Waits to be played
            backingQueue.offer(track)
        }

        playing
    }

    fun skip(): AudioTrack? = lock.withLock {
        val previousTrack = currentTrack

        currentTrack = backingQueue.poll()
        player.startTrack(currentTrack, false)

        previousTrack
    }
}