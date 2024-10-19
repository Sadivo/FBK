package moe.kabii

import kotlinx.coroutines.runBlocking
import moe.kabii.command.commands.trackers.util.GlobalTrackSuggestionGenerator
import moe.kabii.data.flat.GQLQueries
import moe.kabii.data.flat.Keys
import moe.kabii.data.flat.KnownStreamers
import moe.kabii.data.flat.MetaData
import moe.kabii.data.mongodb.MongoDBConnection
import moe.kabii.data.relational.PostgresConnection
import moe.kabii.discord.audio.AudioManager
import moe.kabii.discord.event.guild.welcome.WelcomeImageGenerator
import moe.kabii.discord.tasks.DiscordTaskPool
import moe.kabii.instances.DiscordInstances
import moe.kabii.net.NettyFileServer
import moe.kabii.net.api.videos.YoutubeVideosService
import moe.kabii.net.oauth.discord.DiscordOAuthRedirectServer
import moe.kabii.terminal.TerminalListener
import moe.kabii.translation.Translator
import moe.kabii.util.extensions.stackTraceString
import reactor.core.publisher.Mono
import kotlin.concurrent.thread

@Suppress("UNUSED_VARIABLE")
fun main() {
    // init global objects
    val threadPools = DiscordTaskPool
    val mongo = MongoDBConnection
    val postgres = PostgresConnection
    LOG.info("FBK version: ${MetaData.buildInfo}")

    val gqlQueries = GQLQueries
    val keys = Keys.config
    val audio = AudioManager

    // non-priority, blocking initialization that can make outgoing api calls thus is potentially very slow
    thread(start = true, name = "Initialization") {
        runBlocking {
            NettyFileServer.server.start()
            DiscordOAuthRedirectServer.server.start()
            val welcomer = WelcomeImageGenerator
            GlobalTrackSuggestionGenerator.cacheAll()
            val streamers = KnownStreamers
            YoutubeVideosService.server.start()
            val translator = Translator.detector.detectLanguageOf("initializing translator")
        }
    }

    // connect bot instances to discord
    val discord = DiscordInstances()
    val publishers = runBlocking {
        discord.launchInstances()
    }

    // once connected, try to log out properly
    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "Logout") {
        runBlocking {
            discord.logout()
        }
    })

    // begin listening for terminal commands
    TerminalListener(discord).launch()

    // start lifetime task threads
    discord.services.launch()

    // subscribe to bot lifetime discord events
    Mono.`when`(publishers)
        .onErrorResume { t ->
            LOG.error("Uncaught exception in event handler: ${t.message}")
            LOG.warn(t.stackTraceString)
            Mono.empty()
        }
        .subscribe()
}