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
package technology.yockto.tanya.command.status

import com.sun.management.OperatingSystemMXBean
import org.apache.commons.lang3.time.DurationFormatUtils
import sx.blah.discord.Discord4J
import sx.blah.discord.util.EmbedBuilder
import technology.yockto.bc4d4j.api.CommandContext
import technology.yockto.bc4d4j.api.MainCommand
import technology.yockto.tanya.Tanya
import technology.yockto.tanya.command.Command
import technology.yockto.tanya.getRequestBuilder
import java.awt.Color
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.LocalDateTime

class StatusCommand : Command {
    private val startTime = LocalDateTime.now()

    init { //Automatically registers class as listener
        Tanya.client.dispatcher.registerListener(this)
    }

    @MainCommand(
        prefix = "~",
        name = "status",
        usage = "~status",
        aliases = arrayOf("status", "stats"),
        description = "Displays the bot's statistics.")
    fun status(context: CommandContext) {

        val now = LocalDateTime.now()
        val message = context.message
        val textChannel = message.channel
        val client = message.client

        client.getRequestBuilder(textChannel).doAction {
            EmbedBuilder().apply {

                appendField("Discord4J Version", Discord4J.VERSION, true)
                appendField("Shards", client.shardCount.toString(), true)
                appendField("Servers", client.guilds.size.toString(), true)
                appendField("Users", client.users.size.toString(), true)

                val ping = Duration.between(message.timestamp, now)
                appendField("Ping", "${ping.toMillis()}ms", true)

                val osBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
                appendField("CPU Usage (System)", "${(osBean.systemCpuLoad * 100)}%", true)
                appendField("CPU Usage (JVM)", "${(osBean.processCpuLoad * 100)}%", true)

                val runtime = Runtime.getRuntime()
                val max = runtime.maxMemory() / 1000000
                val free = runtime.freeMemory() / 1000000
                val used = Math.round((free.toDouble() / max.toDouble()) * 100)
                appendField("Memory Usage", "${free}MB / ${max}MB ($used%)", true)

                val uptime = Duration.between(startTime, now)
                val format = "dd 'days,' HH 'hours,' mm 'minutes,' ss 'seconds, and' SSS 'milliseconds'"
                appendField("Uptime", DurationFormatUtils.formatDuration(uptime.toMillis(), format), false)

                withAuthorIcon(client.applicationIconURL)
                withThumbnail(client.applicationIconURL)
                withAuthorName(client.applicationName)
                withColor(Color.PINK)
                withTimestamp(now)

                textChannel.sendMessage(build())
            } is EmbedBuilder
        }.execute()
    }
}