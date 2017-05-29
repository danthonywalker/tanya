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
package technology.yockto.tanya

import com.zaxxer.hikari.HikariDataSource
import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.api.IDiscordClient
import sx.blah.discord.modules.Configuration
import technology.yockto.bc4d4j.impl.getCommandRegistry
import technology.yockto.tanya.command.Command
import technology.yockto.tanya.config.Config
import technology.yockto.tanya.json.getJsonFile
import java.util.ServiceLoader

object Tanya : AutoCloseable {
    private val dataSource = HikariDataSource().apply {
        Config::class.getJsonFile().apply {

            authorization.database.let {
                username = it.username
                password = it.password
                jdbcUrl = it.jdbcUrl
            }
        }
    }

    val database: Database = Database(dataSource)
    val client: IDiscordClient

    init { //Modules are not used so don't attempt to load
        Configuration.AUTOMATICALLY_ENABLE_MODULES = false
        Configuration.LOAD_EXTERNAL_MODULES = false

        client = ClientBuilder().apply {
            Config::class.getJsonFile().apply {

                discord4j.apply { //Settings specific for Discord4J.
                    withRecommendedShardCount(recommendedShardCount)
                    setMaxReconnectAttempts(maxReconnectAttempts)
                    setMaxMessageCacheCount(maxMessageCacheCount)
                    set5xxRetryCount(serverRetryCount)
                    withPingTimeout(pingTimeout)
                    withShards(shardCount)
                    setDaemon(daemon)
                }

                withToken(authorization.discordToken)
            }
        }.login()

        client.getCommandRegistry().apply { //Automatically get / register commands
            ServiceLoader.load(Command::class.java).forEach(this::registerCommands)
        }
    }

    override fun close() {
        client.logout()
        dataSource.close()
    }
}