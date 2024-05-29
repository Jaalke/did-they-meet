package org.example

import com.github.scribejava.core.model.OAuth2AccessToken
import com.twitter.clientlib.ApiClientCallback
import com.twitter.clientlib.TwitterCredentialsOAuth2
import com.twitter.clientlib.api.TwitterApi
import com.twitter.clientlib.model.TweetCreateRequest
import com.twitter.clientlib.model.TweetCreateRequestMedia
import com.twitter.clientlib.model.TweetCreateRequestReply
import com.twitter.clientlib.model.TweetCreateResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import twitter4j.Twitter
import java.io.File
import java.lang.System.currentTimeMillis
import java.net.URL
import java.util.*
import kotlin.collections.ArrayList

@Serializable
data class TwitterCredentials(val apiKey: String, val apiSecret: String,
                              val bearerToken: String, var ownAccessToken: String,
                              var ownAccessSecret: String, var accessToken: String,
                              val accessSecret: String, val clientID: String,
                              val clientSecret: String, var refreshToken: String)

class TwitterBot(val jsonPath: String,
                 val threads: ArrayList<Thread>,
                 val credentials: TwitterCredentials = Json.decodeFromString(TwitterCredentials.serializer(), File(jsonPath).readText()),
                 private var api1Instance: Twitter = Twitter.newBuilder()
                     .prettyDebugEnabled(true)
                     .oAuthConsumer(credentials.apiKey, credentials.apiSecret)
                     .oAuthAccessToken(credentials.ownAccessToken, credentials.ownAccessSecret)
                     .build(),
                 private val api2Credentials: TwitterCredentialsOAuth2 = TwitterCredentialsOAuth2(
                     credentials.clientID,
                     credentials.clientSecret,
                     credentials.accessToken,
                     credentials.refreshToken,
                     true
                 ),
                 private val api2Instance: TwitterApi = TwitterApi(api2Credentials),
                 private val timer: Timer = Timer(),
                 ): Bot {

    init {
        api2Instance.addCallback(TokenUpdater(this))
        api2Instance.refreshToken()
    }

    class TokenUpdater(private val bot: TwitterBot): ApiClientCallback {
        private val json = Json { this.prettyPrint = true; this.prettyPrintIndent="    "}

        override fun onAfterRefreshToken(p0: OAuth2AccessToken?) {
            if (p0 != null) {
                val jsonFile = File(bot.jsonPath)
                bot.credentials.refreshToken = p0.refreshToken
                bot.credentials.accessToken = p0.accessToken
                jsonFile.writeText(json.encodeToString(TwitterCredentials.serializer(), bot.credentials))
            }
        }
    }
    private fun downloadThumbnail(urlString: String): File {
        val url = URL(urlString)
        val bytes = url.readBytes()
        val fileName = urlString.split('/').last()
        val file = File("tmp/$fileName")
        file.writeBytes(bytes)
        return file
    }

    private fun sanitizeTweetText(text: String): String {
        /*
        Twitter converts all text resembling links into hyperlinks. This is regrettable as it looks misleading
        and contributes 23 chars towards the tweet limit. To combat this, we replace all period characters in 
        the text with a visually simliar character.
         */
        if ("?curid=" in text) {
            return text // These are the links we put in the tweets on purpose (wikipedia urls)
        } else {
            val regex = """.\..""".toRegex()
            val sanitized = text.replace(regex) {
                it.value.replace(".", "\u2024")
            }
            return sanitized
        }
    }

    private fun postTweet(text: String, replyID: String? = null, mediaIDs: ArrayList<String>? = null): TweetCreateResponse {
        val request = TweetCreateRequest().text(text)
        if (mediaIDs != null) {
            request.media(TweetCreateRequestMedia().mediaIds(mediaIDs))
        }
        if (replyID != null) {
            request.reply(TweetCreateRequestReply().inReplyToTweetId(replyID))
        }
        return api2Instance.tweets().createTweet(request).execute()
    }

    private fun postMedia(urlString: String): String {
        val file = downloadThumbnail(urlString.replace("http:", "https:"))
        val media = api1Instance.v1().tweets().uploadMedia(file)
        file.delete()
        return media.mediaId.toString()
    }

    fun postThread(thread: Thread) {
        var replyID: String? = null
        var mediaIDs: ArrayList<String>? = ArrayList()
        // We mark the thread as sent in advance to avoid threads getting sent repeatedly if one tweet fails
        thread.status = Status.SENT
        val handler = DbHandler("data/db/twitter.db")
        handler.updateThreads(threads)
        try {
            for (url in thread.thumbnailUrls) {
                mediaIDs?.add(postMedia(url))
            }
        } catch (exc: Exception) {
            mediaIDs = null // If one of the images can't be fetched, we don't show the other one (would be confusing!)
        }
        for (tweet in thread.tweets) {
            val reply = postTweet(sanitizeTweetText(tweet), replyID, mediaIDs)
            mediaIDs = null // Emptying the list so that only the first tweet in thread gets the images
            replyID = reply.data?.id
        }
    }

    class SendTask(private val bot: TwitterBot) : TimerTask() {
        override fun run() {
            for (thread in bot.threads) {
                if (thread.status == Status.ACCEPTED) {
                    bot.postThread(thread)
                    break
                }
            }
        }
    }

    override fun startBot() {
        val nextSendDate = Date((Math.floorDiv(currentTimeMillis(), 14400000) + 1) * 14400000)
        timer.schedule(SendTask(this), nextSendDate, 14400000 )
    }

    override fun stopBot() {
        timer.cancel()
    }

    override fun sayHello() {
        postTweet("Hello world!")
    }
}