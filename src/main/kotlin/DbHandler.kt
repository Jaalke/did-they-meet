package org.example

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class DbHandler(val dbPath: String) {
    init {
        Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
    }

    object ThreadsTable: Table() {
        val id: Column<Int> = integer("id")
        val tweets: Column<String> = varchar("tweets", 1240)
        val status: Column<String> = varchar("status", length = 16)
        val thumbnailUrls: Column<String> = varchar("thumbnail_urls", 1240)
        val wikipediaUrls: Column<String> = varchar("wikipedia_urls", 1240)
        val messageID: Column<Long?> = long("message_id").nullable()
    }

    fun getThreadsByStatus(status: Status, n: Int?): ArrayList<Thread> {
        val threadsList = ArrayList<Thread>()
        transaction {
            val query = ThreadsTable
                .select(ThreadsTable.id, ThreadsTable.tweets,
                    ThreadsTable.status, ThreadsTable.thumbnailUrls,
                    ThreadsTable.wikipediaUrls, ThreadsTable.messageID)
                .where { ThreadsTable.status eq status.toString() }
                .orderBy(ThreadsTable.id, SortOrder.ASC)
                if (n != null) {
                    query.limit(n)
                }
            for (row in query) {
                val id: Int = row[ThreadsTable.id]
                val status: Status = Status.valueOf(row[ThreadsTable.status])
                val tweets: ArrayList<String> = ArrayList<String>(row[ThreadsTable.tweets].split("╡"))
                val thumbnailUrls: ArrayList<String> = ArrayList<String>(row[ThreadsTable.thumbnailUrls].split("╡"))
                val wikipediaUrls: ArrayList<String> = ArrayList<String>(row[ThreadsTable.wikipediaUrls].split("╡"))
                val messageID: Long? = row[ThreadsTable.messageID]
                threadsList.add(Thread(id, status, tweets, thumbnailUrls, wikipediaUrls, messageID))
            }
        }
        return threadsList
    }

    fun updateThreads(threads: ArrayList<Thread>) {
        for (thread in threads) {
            transaction {
                ThreadsTable
                    .update({ThreadsTable.id eq thread.id}) {
                        it[this.status] = thread.status.toString()
                        it[this.messageID] = thread.messageID
                    }
            }
        }
    }
}