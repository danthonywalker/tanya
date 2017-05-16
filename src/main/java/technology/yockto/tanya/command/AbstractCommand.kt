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
package technology.yockto.tanya.command

import com.darichey.discord.api.Command
import com.darichey.discord.api.CommandContext
import com.darichey.discord.api.FailureReason
import sx.blah.discord.handle.obj.Permissions
import technology.yockto.tanya.Tanya
import java.util.EnumSet

@Suppress("LeakingThis")
abstract class AbstractCommand(config: CommandConfig) : Command(config.name) {
    init { //Permissions are acquired by matching names of enum perfectly to an EnumSet
        Tanya.client.dispatcher.takeIf { config.eventListener }?.registerListener(this)
        val permissions = Permissions.values().filter { config.permissions.contains(it.name) }
        requirePermissions(EnumSet.noneOf(Permissions::class.java).apply { addAll(permissions.toList()) })

        deleteCommand(config.deleteCommand)
        caseSensitive(config.caseSensitive)
        withDescription(config.description)
        withAliases(*config.aliases)
        withUsage(config.usage)

        super.onExecuted(this::onExecuted)
        super.onFailure(this::onFailure)
    }

    protected abstract fun onExecuted(context: CommandContext)
    protected abstract fun onFailure(context: CommandContext, reason: FailureReason)
}