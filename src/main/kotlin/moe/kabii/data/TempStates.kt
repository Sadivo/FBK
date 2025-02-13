package moe.kabii.data

import discord4j.common.util.Snowflake
import discord4j.core.`object`.reaction.ReactionEmoji
import moe.kabii.data.relational.posts.twitter.NitterFeed
import moe.kabii.translation.TranslationResult
import java.util.concurrent.ConcurrentHashMap

// basic non-persistent in-memory storage
object TempStates {
    data class BotReactionRemove(val messageId: Snowflake, val userId: Snowflake, val emoji: ReactionEmoji)
    val emojiRemove = mutableListOf<BotReactionRemove>()

    val musicPermissionWarnings = mutableMapOf<Snowflake, Boolean>()

    var skipTwitter = false

    val translationCache = mutableMapOf<Snowflake, TranslationResult>()
}

object TwitterFeedCache {
    data class FeedCacheState(val initialBound: Long, val seenTweets: MutableList<Long> = mutableListOf())
    private val cache = ConcurrentHashMap<String, FeedCacheState>()
    fun getOrPut(feed: NitterFeed): FeedCacheState = cache.getOrPut(feed.username.lowercase()) { FeedCacheState(feed.lastPulledTweet ?: 0L) }
    operator fun get(username: String) = cache[username.lowercase()]
}

object GuildMemberCounts {
    private val cache: MutableMap<Long, Long> = mutableMapOf()

    operator fun get(guildId: Long) = cache[guildId]
    operator fun set(guildId: Long, memberCount: Long) {
        cache[guildId] = memberCount
    }
}