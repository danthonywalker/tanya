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

import technology.yockto.tanya.command.CommandConfig
import technology.yockto.tanya.json.JsonFile

@JsonFile(fileName = "music_config.json")
data class MusicConfig(override val permissions: Array<String> = emptyArray(),
                       override val aliases: Array<String> = emptyArray(),
                       override val deleteCommand: Boolean = false,
                       override val caseSensitive: Boolean = false,
                       override val eventListener: Boolean = false,
                       override val description: String = "",
                       override val usage: String = "",
                       override val name: String = "") : CommandConfig