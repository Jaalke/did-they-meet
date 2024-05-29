package org.example

enum class Status {
    PENDING,
    PROPOSED,
    ACCEPTED,
    REJECTED,
    SENT
}

class Thread(val id: Int,
             var status: Status,
             val tweets: ArrayList<String>,
             val thumbnailUrls: ArrayList<String>,
             val wikipediaUrls: ArrayList<String>,
             var messageID: Long? = null) {

    override fun toString(): String {
        var tweetString = "ID: $id"
        val tweetsN = tweets.size
        for (i in 0..<tweetsN) {
            tweetString += "\n\n[${i + 1}/$tweetsN]\n" + tweets[i]
        }
        return tweetString
    }

    companion object {
        fun getIDFromString(str: String): Int {
            var firstLine = str.split('\n')[0]
            return firstLine.replace("ID: ", "").toInt()
        }
    }
}