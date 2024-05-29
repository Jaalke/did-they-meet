package org.example

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import org.json.JSONObject
import java.lang.Thread.sleep
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.logging.FileHandler
import java.util.logging.Logger
import kotlin.collections.ArrayList
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log

fun getResponseField(response: String, fieldName: String): String {
    val resJson = JSONObject(response)
    return if (resJson.has(fieldName)) {
        resJson[fieldName].toString()
    } else {
        resJson.getJSONObject("d")[fieldName].toString()
    }
}

@Serializable
data class DiscordCredentials(val token: String, val id: String, val server: String, val channel: String)

@Serializable
data class DiscordMessage(val content: String)

class DiscordBot(private val jsonPath: String,
                 private var threads: ArrayList<Thread>,
                 private val credentials: DiscordCredentials = Json.decodeFromString(DiscordCredentials.serializer(), File(jsonPath).readText()),
                 private val httpClient: OkHttpClient = OkHttpClient(),
                 private val apiBaseUrl: String = "https://discord.com/api/v10",
                 private var gatewayUrl: String? = null,
                 private var gateway: WebSocket? = null,
                 private var started: Boolean = false,
                 private val logger: Logger = Logger.getLogger("DiscordBot"),
                 private val listener: DiscordSocketListener = DiscordSocketListener(threads, credentials = credentials, logger = logger)): Bot {

    init {
        val filename = "logs/" +
                ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT).replace(" ", "_").replace(":", "-") +
                ".log"
        logger.addHandler(FileHandler(filename))
    }

    override fun startBot() {
        started = true
        openGateway()
        monitorConnection()
        proposeThreads()
    }

    private fun monitorConnection() {
        while (!listener.connected) {
            sleep(3000)
        }
        thread {
            while (true) {
                sleep(3000)
                if (started) {
                    if (!listener.connected) {
                        reconnectGateway()
                        listener.connected = true
                    }
                } else {
                    return@thread
                }
            }
        }
    }

    private fun getPendingThread(): Thread? {
        for (thread in threads.sortedByDescending { t -> t.status }) {
            if (thread.status == Status.PROPOSED) {
                return null
            } else if (thread.status == Status.PENDING) {
                return thread
            }
        }
        val dbHandler = DbHandler("data/db/twitter.db")
        threads += dbHandler.getThreadsByStatus(Status.PENDING, 20)
        return getPendingThread()
    }

    private fun proposeThreads() {
        thread {
            while (true) {
                sleep(1000)
                if (started && listener.connected) {
                    getPendingThread()?.let {
                        listener.proposeThread(it)
                        updateThreads()
                    }
                } else if (!started) {
                    return@thread
                }
            }
        }
    }

    private fun updateThreads() {
        val handler = DbHandler("data/db/twitter.db")
        handler.updateThreads(threads)
    }

    class DiscordSocketListener(private var threads: ArrayList<Thread>,
                                private var seq: Long? = null,
                                private var interval: Long = 0,
                                private val credentials: DiscordCredentials,
                                private val logger: Logger,
                                private var gotId: Boolean = false,
                                private var gotAck: Boolean = false,
                                private val apiBaseUrl: String = "https://discord.com/api/v10",
                                private val httpClient: OkHttpClient = OkHttpClient(),
                                internal var connected: Boolean = false,
                                internal var resumeUrl: String? = null,
                                private var sessionId: String? = null,
                                private var discordSocket: WebSocket? = null): WebSocketListener() {

        private fun sendHeartbeat() {
            discordSocket?.send("""
                {
                "op" : 1,
                "d" : "${seq.toString()}"
                }
            """.trimIndent())
        }

        private fun sendMessage(mess: String): Response {
            val url = apiBaseUrl + "/channels/${credentials.channel}/messages"
            val bodyString = Json.encodeToString(DiscordMessage.serializer(),
                DiscordMessage(mess)
            )
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bot ${credentials.token}")
                .post(bodyString.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            httpClient.newCall(request).execute().use {
                if (!it.isSuccessful) throw IOException("Unexpected code $it")
                return it
            }
        }

        fun proposeThread(thread: Thread) {
            if (thread.status == Status.PENDING) {
                sendMessage(thread.toString().take(2000))
                thread.status = Status.PROPOSED
            }
        }

        private fun sendId() {
            discordSocket?.send("""
                {
                "op" : 2,
                "d" : {
                    "token" : "${credentials.token}",
                    "intents" : 34304,
                    "properties" : {
                        "os" : "linux",
                        "browser" : "okhttp3",
                        "device" : "okhttp3"
                        }
                    }
                }
            """.trimIndent())
        }

        private fun sendResume() {
            discordSocket?.send("""
                {
                "op" : 6,
                "d" : {
                    "token" : "${credentials.token}",
                    "session_id" : "$sessionId",
                    "seq" : ${seq.toString()}
                    }
                }
            """.trimIndent())
        }

        private fun updateSeq(text: String) {
            val seqString = getResponseField(text, "s")
            seq = if (seqString == "null") {
                null
            } else {
                seqString.toLong()
            }
        }

        private fun updateThreadStatus(text: String) {
            val messageID = getResponseField(text, "message_id").toLong()
            val emoji = getResponseField(getResponseField(text, "emoji"),"name")
            val status: Status
            status = when (emoji) {
                "\uD83D\uDC4D" -> Status.ACCEPTED
                "\uD83D\uDC4E" -> Status.REJECTED
                else -> return
            }
            for (thread in threads) {
                if (thread.messageID == messageID) {
                    thread.status = status
                    val icon = if (thread.status == Status.ACCEPTED) "\uD83C\uDF89" else "⛔"
                    sendMessage("\n# Thread no. ${thread.id} has been ${status.toString().lowercase()}! $icon\n")
                    break
                }
            }
        }

        private fun updateThreadID(text: String) {
            val messageID = getResponseField(text, "id").toLong()
            val threadID = Thread.getIDFromString(getResponseField(text, "content"))
            for (thread in threads) {
                if (thread.id == threadID) {
                    thread.messageID = messageID
                    break
                }
            }
        }

        // GATEWAY_EVENTS
        private fun processCode0(text: String) {
            when (getResponseField(text, "t")) {
                "READY" -> {
                    resumeUrl = getResponseField(text, "resume_gateway_url")
                    sessionId = getResponseField(text, "session_id")
                    connected = true
                }
                "MESSAGE_CREATE" -> {
                    println("Received a message: \"${getResponseField(text, "content")}\"")
                    if (getResponseField(getResponseField(text, "author"), "id") == credentials.id &&
                        getResponseField(text, "content").startsWith("ID: ")) {
                        updateThreadID(text)
                        }
                }
                "MESSAGE_REACTION_ADD" -> {
                    updateThreadStatus(text)
                }
            }
        }

        // RECONNECT REQUEST
        private fun processCode7() {
            connected = false
            discordSocket?.close(4009, "Session timed out.")
        }

        // INVALID SESSION
        private fun processCode9() {
            connected = false
            sessionId = null
            resumeUrl = null
            discordSocket?.close(1002, "Invalid session.")
        }

        // GREETING
        private fun processCode10(text: String) {
            interval = getResponseField(text, "heartbeat_interval").toLong()
            thread(name = "heartbeat", isDaemon = false) {
                sleep(interval / 100)
                sendHeartbeat()
                logger.info("Heartbeat (fuzzed)!")
                println("Heartbeat (fuzzed)!")
                while (true) {
                    for (i in 1 .. floor(interval / 250.0).toInt())
                        sleep(250)
                        // We sleep in 1s intervals to be able to shut down the thread quickly
                        if (!this.connected) {
                            // Exiting thread if connection is closed
                            return@thread
                        }
                    if (!gotAck) {
                        discordSocket?.close(1002, "NO_HEARTBEAT_ACK")
                        this.connected = false
                        // Exiting thread if no heartbeat ACK was received
                        return@thread
                    }
                    sendHeartbeat()
                    gotAck = false
                    logger.info("Heartbeat! s: ${seq.toString()}")
                    println("Heartbeat! s: ${seq.toString()}")
                }
            }
        }

        // HEARTBEAT_ACK
        private fun processCode11() {
            if (!gotId) {
                sendId()
                gotId = true
            }
            gotAck = true
        }
        override fun onOpen(webSocket: WebSocket, response: Response) {
            super.onOpen(webSocket, response)
            discordSocket = webSocket
            if (sessionId != null) {
                sendResume()
            }
            logger.info("Socket open successfully!")
            println("Socket open successfully!")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            super.onMessage(webSocket, text)
            logger.info(text)
            println(text)
            updateSeq(text)
            when (getResponseField(text, "op")) {
                "0" -> processCode0(text)
                "1" -> sendHeartbeat()
                "7" -> processCode7()
                "9" -> processCode9()
                "10" -> processCode10(text)
                "11" -> processCode11()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            super.onClosing(webSocket, code, reason)
            logger.info("Gateway closed! Code: $code; reason: $reason")
            println("Gateway closed! Code: $code; reason: $reason")
            connected = false
        }
    }

    private fun sendMessage(mess: String): Response {
        val url = apiBaseUrl + "/channels/${credentials.channel}/messages"
        val bodyString = Json.encodeToString(DiscordMessage.serializer(),
                                                             DiscordMessage(mess)
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bot ${credentials.token}")
            .post(bodyString.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        httpClient.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("Unexpected code $it")

            for ((name, value) in it.headers) {
                println("$name: $value")
            }
            return it
        }
    }

    override fun sayHello() {
        sendMessage("Hello World! I am a bot and this message was sent programmatically!")
    }

    override fun stopBot() {
        closeGateway()
        logger.handlers[0].close()
    }

    private fun openGateway() {
        var request = Request.Builder()
            .url("$apiBaseUrl/gateway/bot")
            .header("Authorization", "Bot ${credentials.token}")
            .get()
            .build()
        httpClient.newCall(request).execute().use {
            if (it.body != null) {
                gatewayUrl = getResponseField(it.body!!.string(), "url")
                request = Request.Builder()
                    .url(gatewayUrl!!)
                    .build()
                gateway = httpClient.newWebSocket(request, listener)
            }
        }
    }

    private fun reconnectGateway() {
        if (listener.resumeUrl != null) {
            gatewayUrl = listener.resumeUrl
            val request = Request.Builder()
                .url(gatewayUrl!!)
                .build()
            gateway = httpClient.newWebSocket(request, listener)
        } else {
            openGateway()
        }
    }

    private fun closeGateway() {
        started = false
        listener.connected = false
        sleep(1500) // Waiting for threads to finish
        gateway?.close(1001, "CLIENT_INTENT")
    }
}
