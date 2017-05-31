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

enum class MusicPermission(bitOffset: Int) {
    PLAY_REQUIRE_TEXT(0),
    PLAY_REQUIRE_VOICE(1),
    QUEUE_REQUIRE_TEXT(2),
    QUEUE_REQUIRE_VOICE(3),
    CURRENT_REQUIRE_TEXT(4),
    CURRENT_REQUIRE_VOICE(5),
    HISTORY_REQUIRE_TEXT(6),
    HISTORY_REQUIRE_VOICE(7),
    VOTESKIP_REQUIRE_TEXT(8),
    VOTESKIP_REQUIRE_VOICE(9),

    ALLOW_STREAM(30),
    REQUIRE_VOICE(31);

    val value: Int = (1).shl(bitOffset)
    fun hasPermission(value: Int): Boolean = (value.and(this.value) > 0)
}