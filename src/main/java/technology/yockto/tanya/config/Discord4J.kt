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
package technology.yockto.tanya.config

data class Discord4J(val shardCount: Int = 0,
                     val pingTimeout: Int = 0,
                     val daemon: Boolean = false,
                     val serverRetryCount: Int = 0,
                     val maxMessageCacheCount: Int = 0,
                     val maxReconnectAttempts: Int = 0,
                     val recommendedShardCount: Boolean = false)