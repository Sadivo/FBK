package moe.kabii.command

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.Role
import discord4j.core.`object`.entity.channel.GuildChannel
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.`object`.entity.channel.TopLevelGuildMessageChannel
import discord4j.rest.http.client.ClientException
import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.data.flat.Keys
import moe.kabii.discord.util.Embeds
import moe.kabii.util.extensions.success
import reactor.core.publisher.Flux

class MemberPermissionsException(vararg val perms: Permission) : RuntimeException()
class BotAdminException : RuntimeException()
class BotSendMessageException(override val message: String, val channel: Long) : RuntimeException()

@Throws(BotAdminException::class)
fun ChatInputInteractionEvent.verifyBotAdmin() {
    val admin = BotAdmin.check(interaction.user.id.asLong(), interaction.channelId.asLong())
    if(!admin) throw BotAdminException()
}

object BotAdmin {
    fun check(userID: Long? = null, channelID: Long? = null): Boolean {
        if(userID != null && Keys.config[Keys.Admin.users].contains(userID)) return true
        if(channelID != null && Keys.config[Keys.Admin.channels].contains(channelID)) return true
        return false
    }
}

suspend fun Member.hasPermissions(vararg permissions: Permission): Boolean {
    if(BotAdmin.check(userID = id.asLong())) return true
    val perms = basePermissions.awaitFirstOrNull() ?: return false
    if(perms.contains(Permission.ADMINISTRATOR)) return true
    return perms.containsAll(permissions.toList())
}

suspend fun Member.hasPermissions(channel: GuildChannel, vararg permissions: Permission): Boolean {
    if(BotAdmin.check(userID = id.asLong())) return true
    return if(channel is TopLevelGuildMessageChannel) {
        channel.getEffectivePermissions(id).awaitFirstOrNull()?.containsAll(permissions.toList()) == true
    } else {
        // Checking permissions such as MANAGE_CHANNELS in a forum channel seems to cause a ClassCastException in D4J outside our control
        // default to guild permissions for this case
        hasPermissions(*permissions)
    }
}

@Throws(MemberPermissionsException::class)
suspend fun Member.verify(vararg permissions: Permission) {
    if(this.hasPermissions(*permissions)) return
    throw MemberPermissionsException(*permissions)
}

@Throws(MemberPermissionsException::class)
suspend fun Member.channelVerify(channel: GuildChannel, vararg permissions: Permission) {
    if(hasPermissions(channel, *permissions)) return
    throw MemberPermissionsException(*permissions)
}

suspend fun MessageChannel.verifyBotAccess(message: String): Boolean {
    // test that the bot itself has permission to send messages in this channel.
    // used where messages will only otherwise be sent arbitrarily later, such as trackers.
    val test = try {
        createMessage(Embeds.fbk(message)).awaitSingle()
    } catch(ce: ClientException) {
        if(ce.status.code() == 403) return false
        else throw ce
    }
    test.delete().success().subscribe()
    return true
}

object PermissionUtil {
    fun filterSafeRoles(roles: Flux<Role>, forMember: Member, inGuild: Guild, managed: Boolean, everyone: Boolean): Flux<Role> {
        return if (inGuild.ownerId == forMember.id) roles else // owner can apply all roles, otherwise get any roles below
            forMember.highestRole
                .flatMap(Role::getPosition)
                .flatMapMany { highest ->
                    roles.filter { role -> role.position.block()!! < highest }
                }
                .filter { role -> managed || !role.isManaged }
                .filter { role -> everyone || !role.isEveryone }
    }

    suspend fun isSafeRole(role: Role, forMember: Member, inGuild: Guild, managed: Boolean, everyone: Boolean): Boolean =
        filterSafeRoles(Flux.just(role), forMember, inGuild, managed, everyone).hasElement(role).awaitFirst()
}
