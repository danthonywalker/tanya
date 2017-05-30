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

import mu.KotlinLogging
import sx.blah.discord.api.IDiscordClient
import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.util.EmbedBuilder
import sx.blah.discord.util.RequestBuffer
import sx.blah.discord.util.RequestBuilder
import java.awt.Color

private val logger = KotlinLogging.logger {}

fun IChannel.sendMessage(exception: Exception): IMessage = EmbedBuilder().let {
    logger.info(exception, { "Sending message to IChannel ${this.longID}..." })

    val owner = client.applicationOwner
    it.withDescription("An error has occurred! Contact ${owner.name}#${owner.discriminator} " +
        "if you do not recognize this error **and** may not know why it may have occurred.")

    it.appendField("Exception", exception.toString(), false)
    it.appendField("Cause", exception.cause.toString(), false)
    it.appendField("Message", exception.message.toString(), false)

    it.withColor(Color.RED)
    sendMessage(it.build())
}

fun IDiscordClient.getRequestBuilder(channel: IChannel): RequestBuilder = getRequestBuilder().apply {
    onMissingPermissionsError { RequestBuffer.request { channel.sendMessage(it) } }
    onDiscordError { RequestBuffer.request { channel.sendMessage(it) } }
    onGeneralError { RequestBuffer.request { channel.sendMessage(it) } }
}

fun IDiscordClient.getRequestBuilder(): RequestBuilder = RequestBuilder(this).apply {
    shouldBufferRequests(true) //In most use cases retrying failed requests is wanted
}