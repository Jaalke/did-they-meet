# Did They Meet - Twitter bot

Currently at [@didtheymeet](https://x.com/didtheymeet) on Twitter.

There are three services that the bot needs to function:

1. Generating pairings of historical figures
2. Submitting proposed Twitter threads for approval
3. Posting Twitter threads according to a schedule

Service 1 is performed statically through python scripts, with all found pairings saved to a SQLite database. An additional python script appends information that was missing from the original dataset, most importantly **date of death**. This information is queried from DBpedia using SPARQL and then further sanitized/formatted. 

Services 2 and 3 are part of the Kotlin server application running on a RaspberryPI. 

## Thread generation

The original dataset that the bot relies on is the [Pantheon Project](https://www.kaggle.com/datasets/mit/pantheon-project) table created by the MIT Media Lab. Alongside useful basic information about ~11k historical figures, the dataset also provides an incredibly useful 'popularity index' column: a score synthesized from features like number of Wikipedia articles in different languages written about a given person and the number of their views. This allows me to select mostly figures people are likely to have heard of. I try to stride a balance between exposing viewers to less known historical figures and providing entertaining information that they might have some context for.

The original dataset is missing a crucial column that makes this project possible: the information on the date of death. Fortunately, all rows have an associated DBpedia article ID, so I was able to find this information using a SPARQL query. A proportion of the dataset doesn't have any viable information available; mostly people who are still alive today, but also others that lack reliable historical records.

## Discord bot

The Discord bot is used to submit threads for manual approval before posting (inspired by the implementation of the [California DMV Twitter bot.](https://x.com/ca_dmv_bot2?lang=en). Some pairings of historical figures may be considered insensitive or downright offensive; the dataset often spits out 20th century historical figures overlapping with Joseph Goebbels for instance. Sometimes factual errors also appear in the date ranges - these can be quickly cross-referenced with the previews of Wikipedia articles contained in the threads.

There are a number of good libraries for deploying Discord bots, but I wanted to do this one manually for fun. Fortunately the Discord Websocket API is quite well documented. The bot retrieves threads from the SQLite database and posts them on one channel it's approved on. It then listens for emoji reaction events on those messages and marks the threads appropriately.
 
## Twitter bot

The Twitter bot uses the Twitter4j library as well as the official Twitter Java SDK to post the threads. Twitter currently offers free access to a small part of the v2.0 API (mostly just the ability to post Tweets and replies) as well as the v1.1 API for uploading media. Some basic exception handling is implemented for I/O errors around uploading thumbnails (these must first be downloaded to `tmp` using the url in the dataset).
