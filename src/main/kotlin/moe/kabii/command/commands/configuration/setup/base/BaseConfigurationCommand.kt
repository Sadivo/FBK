package moe.kabii.command.commands.configuration.setup.base

import discord4j.core.event.domain.interaction.ButtonInteractionEvent
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent
import discord4j.core.`object`.command.ApplicationCommandInteractionOption
import discord4j.core.`object`.command.ApplicationCommandOption
import discord4j.core.`object`.component.ActionRow
import discord4j.core.`object`.component.Button
import discord4j.core.`object`.component.SelectMenu
import discord4j.core.`object`.component.TextInput
import discord4j.core.`object`.entity.Attachment
import discord4j.core.`object`.entity.channel.*
import discord4j.core.spec.EmbedCreateFields
import discord4j.core.spec.EmbedCreateSpec
import discord4j.discordjson.json.ApplicationCommandOptionData
import discord4j.rest.http.client.ClientException
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import moe.kabii.command.Command
import moe.kabii.command.params.DiscordParameters
import moe.kabii.discord.util.Embeds
import moe.kabii.rusty.Err
import moe.kabii.rusty.Ok
import moe.kabii.rusty.Result
import moe.kabii.util.constants.EmojiCharacters
import moe.kabii.util.constants.MagicNumbers
import moe.kabii.util.extensions.awaitAction
import moe.kabii.util.extensions.orNull
import org.apache.commons.lang3.StringUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.reflect.KMutableProperty1

sealed class ConfigurationElement<T>(val fullName: String, val propName: String, propertyType: ApplicationCommandOption.Type, val autoComplete: Boolean = false) {
    val propertyType = propertyType.value

    fun elementIsStringInputtable() = this is StringElement<*> || this is LongElement<*> || this is CustomElement<*, *>
    fun getElementPrompt() = when(this) {
        is StringElement<*> -> prompt
        is LongElement<*> -> prompt
        else -> null
    }
}

// booleanelement: input as bool, stored as bool. presented as selectmenu by embed
class BooleanElement<T>(
    fullName: String,
    propName: String,
    val prop: KMutableProperty1<T, Boolean>
) : ConfigurationElement<T>(fullName, propName, ApplicationCommandOption.Type.INTEGER)

// longelement: input as integer, stored as long. not presented in embed.
class LongElement<T>(
    fullName: String,
    propName: String,
    val prop: KMutableProperty1<T, Long>,
    val range: LongRange,
    val prompt: String
) : ConfigurationElement<T>(fullName, propName, ApplicationCommandOption.Type.INTEGER)

// stringelement: input as string, stored as string. presented as modal by embed
class StringElement<T>(
    fullName: String,
    propName: String,
    val prop: KMutableProperty1<T, String>,
    val prompt: String,
    val default: String
) : ConfigurationElement<T>(fullName, propName, ApplicationCommandOption.Type.STRING)

// channel element: input as channel, stored as channel/snowflake. not presented in embed
class ChannelElement<T>(
    fullName: String,
    propName: String,
    val prop: KMutableProperty1<T, Long?>, // stored as channel id/snowflake -> long
    val validTypes: List<Types>
) : ConfigurationElement<T>(fullName, propName, ApplicationCommandOption.Type.CHANNEL) {
    enum class Types(val value: Int) {
        GUILD_TEXT(0),
        GUILD_VOICE(2),
        GUILD_NEWS(5),
        GUILD_STAGE_VOICE(13);

        companion object {
            fun getChannelSuperclass(types: List<Types>): Class<out Channel> {
                if(types.contains(GUILD_TEXT) && types.contains(GUILD_NEWS)) return MessageChannel::class.java
                if(types.contains(GUILD_TEXT)) return GuildMessageChannel::class.java
                if(types.contains(GUILD_NEWS)) return NewsChannel::class.java
                if(types.contains(GUILD_VOICE) || types.contains(GUILD_STAGE_VOICE)) return VoiceChannel::class.java
                return Channel::class.java
            }
        }
    }
}

// attachmentelement: input as attachment, stored as file path. not presented in embed
class AttachmentElement<T>(
    fullName: String,
    propName: String,
    val prop: KMutableProperty1<T, String?>, // stored as path to image
    val validator: suspend (DiscordParameters, Attachment?) -> Result<String, String>
) : ConfigurationElement<T>(fullName, propName, ApplicationCommandOption.Type.ATTACHMENT)

// customelement: elements that are input as a string, validated and stored as <PT>
// presented as modal for string input -> parsing by embed
// ex. emojis, durations, color codes
open class CustomElement<T, VT>(
    fullName: String,
    propName: String,
    val prop: KMutableProperty1<T, Any?>,
    val prompt: String,
    val default: VT?,
    val parser: (DiscordParameters, String) -> Result<VT?, String>, // given input, produce value or invalid
    val value: (T) -> String, // given value, produce string for embed output
    autoComplete: Boolean = false
) : ConfigurationElement<T>(fullName, propName, ApplicationCommandOption.Type.STRING, autoComplete)

class ViewElement<T, ANY : Any?>(
    fullName: String,
    propName: String,
    val prop: KMutableProperty1<T, ANY>,
    val redirection: String,
) : ConfigurationElement<T>(fullName, propName, ApplicationCommandOption.Type.STRING)

open class ConfigurationModule<T>(val name: String, val command: Command, vararg val elements: ConfigurationElement<T>) {
    var subCommands = mutableListOf<ApplicationCommandOptionData>()
}

class Configurator<T>(private val name: String, private val module: ConfigurationModule<T>, private val instance: T) {
    companion object {
        const val embedTimeout = 120_000L
    }

    suspend fun run(origin: DiscordParameters): Boolean { // returns if a property was modified and the config should be saved
        val command = origin.subCommand
        return when(command.name) {
            "config", "all" -> embed(origin)
            else -> property(command, origin)
        }
    }

    private fun getValue(element: ConfigurationElement<T>) = when(element) {
        is BooleanElement -> if(element.prop.get(instance)) "enabled" else "disabled"
        is LongElement -> element.prop.get(instance).toString()
        is StringElement -> element.prop.get(instance).ifEmpty { " " }
        is ChannelElement -> {
            val value = element.prop.get(instance)
            if(value != null) "<#$value>" else "not set"
        }
        is AttachmentElement -> if(element.prop.get(instance) != null) "file has been SET" else "file is NOT SET"
        is CustomElement<T, *> -> element.value(instance)
        is ViewElement<T, *> -> element.prop.get(instance).toString()
    }

    private fun getName(element: ConfigurationElement<*>) = "${element.fullName} **(${element.propName})**"

    private fun getBoolElements() = module.elements.filterIsInstance<BooleanElement<T>>()

    private fun currentConfig(): EmbedCreateSpec {

        // generate embed containing current states of properties
        val configFields = mutableListOf<EmbedCreateFields.Field>()

        val boolElements = getBoolElements()
        // get enabled bools
        val enabledElements = boolElements
            .filter { e -> e.prop.get(instance) }
            .joinToString("\n") { e -> "・ ${getName(e)}" }
            .ifEmpty { "No ${module.name} features are enabled." }
        configFields.add(EmbedCreateFields.Field.of("Enabled Features", enabledElements, true))

        val availableElements = boolElements
            .filter { e -> !e.prop.get(instance) }
            .joinToString("\n") { e -> "・ ${getName(e)}"}
            .ifEmpty { "All ${module.name} features are enabled." }
        configFields.add(EmbedCreateFields.Field.of("Available (Disabled) Features", availableElements, true))

        val customElements =
            (module.elements.toList() - boolElements.toSet())
                .map { e -> "・ ${getName(e)}:\n${EmojiCharacters.spacer}**=** ${getValue(e)}" }
        if(customElements.isNotEmpty()) {
            configFields.add(EmbedCreateFields.Field.of(
                "Custom Settings:",
                StringUtils.abbreviate(customElements.joinToString("\n"), MagicNumbers.Embed.FIELD.VALUE),
                false
            ))
        }

        return Embeds.fbk()
            .withAuthor(EmbedCreateFields.Author.of(name, null, null))
            .withFields(configFields)
    }

    private fun configComponents() = sequence {
        // components should be generated each time: selectmenu will be updated with correct enabled options
        // generate selectmenu for boolean
        val boolElements = getBoolElements()
        val options = boolElements
            .map { bool ->
                SelectMenu.Option
                    .of(bool.propName, bool.propName)
                    .withDescription(bool.fullName)
                    .withDefault(bool.prop.get(instance))
            }

        val menu = if(options.isNotEmpty()) {
            SelectMenu
                .of("toggleable", options)
                .withMaxValues(options.size)
        } else null

        // buttons technically don't change - but components will be overwritten to update selectmenu
        val buttons = listOf(Button.success("exit", "Save and Exit")) + module.elements
            .filter(ConfigurationElement<*>::elementIsStringInputtable)
            .map { e -> Button.primary(e.propName, "Edit ${e.propName}") }

        // produce layoutcomponents from menu/buttons
        if(menu != null) yield(ActionRow.of(menu))
        yieldAll(buttons.chunked(5).map(ActionRow::of))
    }

    suspend fun embed(origin: DiscordParameters): Boolean {
        // /command config -> full menu embed

        // generate components for initial embed response
        // compile boolean elements into select menu
        val boolElements = getBoolElements()

        // register component listeners - component ids will not change so these do not need to be redone
        val listeners = mutableListOf<Flux<*>>()
        if(boolElements.isNotEmpty()) {
            val menuListener = origin.listener(SelectMenuInteractionEvent::class, true, null, "toggleable")
                .flatMap { response ->
                    val (enabled, disabled) = boolElements
                        .partition { e -> response.values.contains(e.propName) }
                    enabled.forEach { e ->
                        e.prop.set(instance, true)
                    }
                    disabled.forEach { e ->
                        e.prop.set(instance, false)
                    }
                    response.edit()
                        .withEmbeds(currentConfig())
                        .withComponents(configComponents().toList())
                }
            listeners.add(menuListener)
        }

        val exitListener = origin.listener(ButtonInteractionEvent::class, true, null, "exit")
            .flatMap { _ ->
                mono {
                    origin.config.save()
                }.then(
                    origin.event.editReply()
                        .withEmbeds(Embeds.fbk("Configuration saved."))
                        .withComponentsOrNull(null)
                )
            }
        listeners.add(exitListener)

        // string input elements (stringelement, customelement) presented as button -> modal for text input
        module.elements
            .filter(ConfigurationElement<*>::elementIsStringInputtable)
            .forEach { e ->
                // listener for button creates modal for input
                val buttonListener = origin.listener(ButtonInteractionEvent::class, true, null, e.propName)
                    .flatMap { press ->
                        val modal = press
                            .presentModal()
                            .withComponents(
                                ActionRow.of(
                                    TextInput.small(e.propName, "New value for ${module.name} -> ${e.propName}")
                                        .required()
                                        .prefilled(getValue(e))
                                )
                            )
                            .withCustomId("modal")
                            .withTitle("Editing ${e.propName}")
                            .thenReturn(Unit)
                        val prompt = e.getElementPrompt() ?: "Waiting for response..."
                        val reselectButton = ActionRow.of(
                            Button.primary(e.propName, "Edit ${e.propName}")
                        )
                        val edit = origin.event
                            .editReply()
                            .withEmbeds(Embeds.fbk("Selected **${e.propName}**. $prompt"))
                            .withComponentsOrNull(listOf(reselectButton))
                        Mono
                            .`when`(modal, edit)
                            .thenReturn(Unit)
                    }
                    .flatMap { _ ->
                        // create listener for modal itself
                        origin.listener(ModalSubmitInteractionEvent::class, true, null, "modal")
                            .take(1)
                            .flatMap { submission ->
                                val raw = submission.getComponents(TextInput::class.java)[0].value.get()
                                when(e) {
                                    is StringElement -> {
                                        e.prop.set(instance, raw.ifBlank { "" })
                                        val edit = submission.edit()
                                            .withEmbeds(currentConfig())
                                            .withComponents(configComponents().toList())
                                            // may result in multiple reactions due to some weirdness with discord sending cancelled modals(?) when submit finally pressed
                                            .onErrorResume(ClientException::class.java) { _ -> Mono.empty() }
                                        val notice = submission
                                            .createFollowup()
                                            .withEmbeds(Embeds.fbk("**${e.propName}** has been set to `$raw`."))
                                            .withEphemeral(true)
                                            .then()
                                        Mono.`when`(notice, edit)
                                    }
                                    is LongElement<T> -> {
                                        val value = raw.toLongOrNull()
                                        if(value != null) {
                                            e.prop.set(instance, value)
                                            submission.edit()
                                                .withEmbeds(currentConfig())
                                                .withComponents(configComponents().toList())
                                                .onErrorResume(ClientException::class.java) { _ -> Mono.empty() }
                                        } else {
                                            val edit = submission.edit()
                                                .withEmbeds(currentConfig())
                                                .withComponents(configComponents().toList())
                                            val notice = submission.createFollowup()
                                                .withEmbeds(Embeds.error("**$raw** is not valid for **${e.propName}**. Please enter a whole number."))
                                                .withEphemeral(true)
                                            Mono.`when`(edit, notice)
                                        }
                                    }
                                    is CustomElement<T, *> -> {
                                        // parse user input
                                        val value = e.parser(origin, raw)
                                        when(value) {
                                            is Ok -> {
                                                e.prop.set(instance, value.value)
                                                val edit = submission.edit()
                                                    .withEmbeds(currentConfig())
                                                    .withComponents(configComponents().toList())
                                                    .onErrorResume(ClientException::class.java) { _ -> Mono.empty() }
                                                val notice = submission.createFollowup()
                                                    .withEmbeds(Embeds.fbk("**${e.propName}** has been set to **${getValue(e)}**."))
                                                    .withEphemeral(true)
                                                Mono.`when`(notice, edit)
                                            }
                                            is Err -> {
                                                val edit = submission.edit()
                                                    .withEmbeds(currentConfig())
                                                    .withComponents(configComponents().toList())
                                                val notice = submission.createFollowup()
                                                    .withEmbeds(Embeds.error("**$raw** is not a valid value for **${e.propName}**: ${value.value}"))
                                                    .withEphemeral(true)
                                                Mono.`when`(edit, notice)
                                            }
                                        }
                                    }
                                    else -> Mono.empty()
                                }
                            }
                    }
                listeners.add(buttonListener)
            }

        origin.event
            .reply()
            .withEmbeds(currentConfig())
            .withEphemeral(true)
            .withComponents(configComponents().toList())
            .awaitAction()

//        Mono.`when`(listeners)
        Flux.firstWithSignal(listeners)
            .timeout(Duration.ofMinutes(30))
            .onErrorResume(TimeoutException::class.java) { _ -> Mono.empty() }
            .switchIfEmpty { origin.event.editReply().withComponentsOrNull(null).then() }
            .awaitFirstOrNull()
        origin.event.deleteReply().thenReturn(Unit).awaitFirstOrNull()
        return true
    }

    suspend fun property(subCommand: ApplicationCommandInteractionOption, origin: DiscordParameters): Boolean {
        // /command <subCommand -> property> (value: T?) (reset: bool?)
        val element = module.elements.find { e -> e.propName.lowercase() == subCommand.name } ?: error("mismatched property '${subCommand.name} :: $module")

        val args = origin.subArgs(subCommand)
        val reset = args.optBool("reset")
        if(reset == true) {
            // user requested 'reset' this should only be listed on resettable properties
            val resetValue = when(element) {
                is StringElement -> {
                    element.prop.set(instance, element.default)
                    element.default
                }
                is ChannelElement -> {
                    element.prop.set(instance, null)
                    null
                }
                is AttachmentElement -> {
                    element.prop.set(instance, null)
                    null
                }
                is CustomElement<T, *> -> {
                    element.prop.set(instance, element.default)
                    element.default
                }
                else -> error("mismatched property '${subCommand.name} :: not resettable")
            }
            val resetTo = resetValue?.toString() ?: "{empty}"
            origin.ereply(Embeds.fbk("**${element.propName}** has been reset to $resetTo.")).awaitSingle()
            return true
        }

        if(subCommand.getOption("value").isEmpty) {
            // display current value of property
            origin.ereply(
                Embeds.fbk()
                    .withTitle("From ${module.name} configuration:")
                    .withFields(EmbedCreateFields.Field.of(getName(element), getValue(element), false))
            ).awaitSingle()
            return false
        }

        // set property. value arg will exist here
        val newValue = when(element) {
            is BooleanElement -> args
                .int("value")
                .apply { element.prop.set(instance, this == 1L) }
            is LongElement -> args
                .int("value")
                .apply { element.prop.set(instance, this) }
            is StringElement -> args
                .string("value")
                .apply { element.prop.set(instance, this) }
            is ChannelElement -> args
                .baseChannel("value").awaitSingle().id.asLong()
                .apply { element.prop.set(instance, this) }
            is AttachmentElement -> {
                // get attachment, validate
                val attachment = origin.interaction.commandInteraction.orNull()?.resolved?.orNull()?.attachments?.values?.firstOrNull()
                when(val validation = element.validator(origin, attachment)) {
                    is Ok -> validation.value.apply { element.prop.set(instance, this) }
                    is Err -> {
                        origin.ereply(Embeds.error(validation.value)).awaitSingle()
                        null
                    }
                }
            }
            is CustomElement<T, *> -> {
                val input = args.string("value")
                when(val validation = element.parser(origin, input)) {
                    is Ok -> validation.value.apply { element.prop.set(instance, this) }
                    is Err -> {
                        origin.ereply(Embeds.error(validation.value)).awaitSingle()
                        null
                    }
                }
            }
            is ViewElement<*, *> -> {
                origin.ereply(Embeds.error(element.redirection)).awaitSingle()
                null
            }
        }
        return if(newValue != null) {
            val newState = when(element) {
                is BooleanElement -> if (newValue == 1L) "**enabled**" else "**disabled**"
                else -> "set to **${newValue.toString().ifBlank { "empty" }}**"
            }
            origin.ereply(Embeds.fbk("The option **${element.propName}** has been $newState")
                .withTitle("Configuration Updated"))
                .awaitSingle()
            true
        } else false
    }
}