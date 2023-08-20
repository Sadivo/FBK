package moe.kabii.command.commands.audio.search

import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent
import discord4j.core.`object`.component.ActionRow
import discord4j.core.`object`.component.SelectMenu
import discord4j.core.spec.EmbedCreateFields
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.command.commands.audio.AudioCommandContainer
import moe.kabii.command.commands.audio.AudioStateUtil
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.discord.audio.ExtractedQuery
import moe.kabii.discord.audio.FallbackHandler
import moe.kabii.discord.util.Embeds
import moe.kabii.util.constants.MagicNumbers
import moe.kabii.util.extensions.awaitAction
import java.time.Duration

object SearchTracks : AudioCommandContainer {
    object SearchSource : Command("search") {
        override val wikiPath = "Music-Player#playing-music-with-the-play-command"

        init {
            chat {
                // /search <text> (youtube/soundcloud)
                channelFeatureVerify(FeatureChannel::musicChannel)
                val site = args.optInt("site")?.toInt() ?: 1
                val source = when(site) {
                    1 -> AudioSource.YOUTUBE
                    else -> AudioSource.SOUNDCLOUD
                }
                val query = args.string("search")
                event.deferReply()
                    .withEphemeral(true)
                    .awaitAction()
                val search = source.handler.search(query)
                if(search.isEmpty()) {
                    event.editReply()
                        .withEmbeds(Embeds.error(i18n("audio_search_none", "source" to source.fullName, "query" to query)))
                        .awaitSingle()
                    return@chat
                }

                // build search selection menu until 10 songs or 2000 chars
                val menu = StringBuilder()
                val options = mutableListOf<SelectMenu.Option>()
                for(index in search.indices) {
                    val id = index + 1
                    val track = search[index]
                    val author = if(track.info.author != null) " Uploaded by ${track.info.author}" else ""
                    val entry = "$id. ${trackString(track, includeAuthor = false)}$author\n"
                    if(menu.length + entry.length > MagicNumbers.Embed.NORM_DESC) break
                    menu.append(entry)

                    val option = SelectMenu.Option
                        .of(id.toString(), id.toString())
                    options.add(option)
                }
                val embed = Embeds.fbk(menu.toString())
                    .withAuthor(EmbedCreateFields.Author.of(i18n("audio_search_results", "source" to source.fullName, "query" to query), null, null))
                    .withTitle(i18n("audio_search_select"))
                val selectMenu = SelectMenu.of("menu", options).withMaxValues(options.size)

                event.editReply()
                    .withEmbeds(embed)
                    .withComponents(ActionRow.of(selectMenu))
                    .awaitSingle()

                val response = listener(SelectMenuInteractionEvent::class, true, Duration.ofMinutes(5), "menu")
                    .switchIfEmpty {
                        event.editReply()
                            .withEmbeds(Embeds.error(i18n("audio_search_none_selected")))
                            .withComponentsOrNull(null)
                    }
                    .awaitFirstOrNull() ?: return@chat
                response.deferEdit().awaitAction()
                val selected = response.values.map(String::toInt)
                if(selected.isNotEmpty()) {
                    val voice = AudioStateUtil.checkAndJoinVoice(this)
                    if(voice is AudioStateUtil.VoiceValidation.Failure) {
                        event.editReply()
                            .withEmbeds(Embeds.error(voice.error))
                            .withComponentsOrNull(null)
                            .awaitSingle()
                        return@chat
                    }
                }
                selected.forEach { selection ->
                    val track = search[selection - 1]
                    // fallback handler = don't search or try to resolve a different track if videos is unavailable
                    FallbackHandler(this, extract = ExtractedQuery.default(track.identifier)).trackLoadedModifiers(track, silent = true, deletePlayReply = false)
                }
                event.editReply()
                    .withEmbeds(Embeds.fbk(i18n("audio_search_adding", selected.size)))
                    .withComponentsOrNull(null)
                    .awaitSingle()
                chan
                    .createMessage(Embeds.fbk(i18n("audio_search_user_added", "user" to author.mention, "count" to selected.size)))
                    .awaitSingle()
            }
        }
    }
}