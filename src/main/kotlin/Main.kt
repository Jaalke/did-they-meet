package org.example

import java.util.*

fun main() {
    val handler = DbHandler("data/db/twitter.db")
    val threads = handler.getThreadsByStatus(Status.PENDING, 20)
    threads += handler.getThreadsByStatus(Status.ACCEPTED, null) + handler.getThreadsByStatus(Status.PROPOSED, null)

    var discBot: DiscordBot? = null
    var twitterBot: TwitterBot? = null

    val timer = Timer()

    class RestartTask(private var discBot: DiscordBot?, private var twitterBot: TwitterBot?): TimerTask() {
        /*
        Both the twitter and the discord bots are reconnected periodically to prevent hanging sessions
        */
        override fun run() {
            discBot?.stopBot()
            discBot = DiscordBot("data/json/discord.json", threads)
            discBot!!.startBot()

            twitterBot?.stopBot()
            twitterBot = TwitterBot("data/json/twitter.json", threads)
            twitterBot!!.startBot()
        }
    }

    discBot = DiscordBot("data/json/discord.json", threads)
    discBot!!.startBot()

    twitterBot = TwitterBot("data/json/twitter.json", threads)
    twitterBot!!.startBot()

    val nextRestartDate = Date((Math.floorDiv(System.currentTimeMillis(), 1200000) + 1) * 1200000 + 600000)
    timer.schedule(RestartTask(discBot, twitterBot), nextRestartDate, 1200000 )

}