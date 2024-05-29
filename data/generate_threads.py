import pandas as pd
import typing, re, json, random
from progress.bar import ShadyBar
import sqlite3

TWT_L = 279

def prep_data_for_pairing(birth_cutoff: int, pop_perc: float) -> pd.DataFrame:
    if pop_perc <= 0 or pop_perc > 1:
        raise Exception("Popularity percentile must be in the (0 - 1] range!")
    
    dataframe = pd.read_csv("csv/database.csv", encoding="utf-16")
    dataframe["birth_year"] = pd.to_numeric(dataframe["birth_year"], errors="coerce")
    dataframe.dropna(subset=["death_year", "birth_year"], inplace=True)
    
    sorted_dataset = dataframe.sort_values("historical_popularity_index", ascending=False)
    filtered_dataset = sorted_dataset.query(f"birth_year < {birth_cutoff}")

    pop_index_cutoff = int(filtered_dataset.size * pop_perc)
    truncated_dataset = filtered_dataset[:pop_index_cutoff].reset_index(drop=True)

    return truncated_dataset

def check_overlap(fig_one: pd.Series, fig_two: pd.Series) -> int:
    first_fig, second_fig = sorted([fig_one, fig_two], key=lambda x: x.birth_year)

    if second_fig.birth_year < first_fig.death_year: # CASE 1: outer overlap
        return int(abs(first_fig.death_year - second_fig.birth_year))
    elif second_fig.death_year < first_fig.death_year: # CASE 2: inner overlap
        return int(abs(second_fig.death_year - second_fig.death_year))
    else:
        return -1

def get_random_pairing(min_overlap: int, max_overlap: int, birth_cutoff: int = 2000, pop_perc: float = 1) -> tuple[pd.Series]:
    dataset = prep_data_for_pairing(birth_cutoff, pop_perc)
    dataset = dataset.sample(frac=1)

    for i in range(0, len(dataset)):
        fig_one = dataset.iloc[i]
        for j in range(0, len(dataset)):
            fig_two = dataset.iloc[j]
            overlap = check_overlap(fig_one, fig_two)
            if overlap >= min_overlap and overlap <= max_overlap and i != j:
                return fig_one, fig_two
    return None, None

def get_all_pairings(min_overlap: int, max_overlap: int, birth_cutoff: int = 2000, pop_perc: float = 1) -> list[tuple]:
    dataset = prep_data_for_pairing(birth_cutoff, pop_perc)
    pairings = []

    rows_n = len(dataset)
    bar = ShadyBar("🗄️ Finding matches... ", max=rows_n)
    for i in range(rows_n):
        fig_one = dataset.iloc[i]
        for j, fig_two in dataset[i:].iterrows():
            overlap = check_overlap(fig_one, fig_two)
            if overlap >= min_overlap and overlap <= max_overlap and i != j:
                pairings.append((fig_one, fig_two))
        bar.next()
    bar.finish()

    print(f"🗃️ Found {len(pairings):,} unique pairings.")

    return pairings

def get_year_string(year: int) -> str:
    if year > 0:
        return str(year)
    else:
        return f"{str(-year)} BCE"

def get_pairing_string(figs: tuple[pd.Series]) -> str:
    name_a = min(figs[0], figs[1], key=lambda x: x.birth_year).full_name
    name_b = max(figs[0], figs[1], key=lambda x: x.birth_year).full_name
    overlap = check_overlap(figs[0], figs[1])
    start_int = max(int(figs[0].birth_year), int(figs[1].birth_year))
    start = get_year_string(start_int)
    end = get_year_string(start_int + overlap)

    string_f = [
        lambda: f"{name_a} and {name_b} lived concurrently for {overlap} years between {start} and {end}.",
        lambda: f"For {overlap} years between {start} and {end} {name_a} and {name_b} could have met.",
        lambda: f"There was a period of {overlap} years between {start} and {end} when {name_a} and {name_b} lived concurrently.",
        lambda: f"{name_a} and {name_b} could have met between {start} and {end}. {name_b} would have been at most {overlap} years old.",
        lambda: f"For {overlap} years between {start} and {end} {name_a} and {name_b} were alive at the same time.",
        lambda: f"{name_a} and {name_b} were alive at the same time for {overlap} years between {start} and {end}.",
        lambda: f"There was a period of {overlap} years between {start} and {end} when {name_a} and {name_b} could have met.",
    ]

    return random.choice(string_f)()
        
def split_sentence(sentence: str) -> list[str]:
    splits = [""]
    words_arr = sentence.split(" ")
    for word in words_arr:
        if len(splits[-1]) + len(word) + 1 < TWT_L:
            splits[-1] += word + ' '
        else:
            splits.append(word + ' ')
    return splits

def split_sentences(sentences: list[str]) -> list[str]:
    final_sentences = []
    for sen in sentences:
        if len(sen) > TWT_L:
            final_sentences.extend(split_sentence(sen))
        else:
            final_sentences.append(sen)
    return final_sentences

def get_abstract_tweets(fig: pd.Series, max_tweets: int=1) -> list[str]:
    tweets = [""]
    full_abs = str(fig.abstract)
    abs_sentences = re.split(r"(.\. [A-Z])", full_abs)

    for i, tok in enumerate(abs_sentences):
        if i % 2 == 1:
            delim = tok.split(" ")
            abs_sentences[i-1] += delim[0]
            abs_sentences[i+1] = delim[1] + abs_sentences[i+1]
    abs_sentences = split_sentences([abs_sentences[i] for i in range(len(abs_sentences)) if i % 2 == 0])
    
    for sen in abs_sentences:
        if len(sen) + len(tweets[-1]) < TWT_L:
            tweets[-1] += sen + ' '  # Adding to the most recent tweet
        elif len(sen) < TWT_L:
            tweets.append(sen + ' ') # Adding a new tweet to the thread
    return list(tweets[:max_tweets])

def get_tweets_text_for_pairing(pairing: tuple[pd.Series], max_bio_tweets: int=1) -> list[str]:
    header = [get_pairing_string(pairing)]
    abstr_one = get_abstract_tweets(pairing[0], max_tweets=3)
    link_one = [f"https://en.wikipedia.org/?curid={pairing[0].article_id}"]
    abstr_two = get_abstract_tweets(pairing[1], max_tweets=3)
    link_two = [f"https://en.wikipedia.org/?curid={pairing[1].article_id}"]
    return header + abstr_one + link_one + abstr_two + link_two

def pprint_tweets(tweets: list[str]):
    for tweet in tweets:
        print(tweet + "\n")

def get_thread_dict_for_pairing(id: int, pairing: tuple[pd.Series], max_bio_tweets: int=1) -> dict:
    tweet_dict = {
        "id": id,
        "status": "PENDING",
        "tweets": get_tweets_text_for_pairing(pairing, max_bio_tweets=max_bio_tweets),
        "thumbnail_urls": [str(fig.thumbnail_url) for fig in pairing],
        "wikipedia_urls": [f"https://en.wikipedia.org/?curid={fig.article_id}" for fig in pairing]
    }
    return tweet_dict

def get_json_for_pairings(pairings: list, max_bio_tweets: int=1) -> str:
    pairing_dicts = []
    for i, pair in enumerate(pairings):
        pairing_dicts.append(get_thread_dict_for_pairing(i, pair, max_bio_tweets=max_bio_tweets))
    return json.dumps(pairing_dicts, indent=4)

def dump_threads_json(min_overlap: int, max_overlap: int, birth_cutoff: int, pop_perc: int, max_bio_tweets: int, seed: int=1688):
    rng = random.Random(seed)
    pairings = get_all_pairings(min_overlap, max_overlap, birth_cutoff, pop_perc)
    rng.shuffle(pairings)
    json = get_json_for_pairings(pairings, max_bio_tweets)
    with open("json/threads.json", "w+", encoding="utf-16") as f:
        f.write(json)
        print("💾 Generated tweets dumped int json/threads.json!")

def dump_threads_db(min_overlap: int, max_overlap: int, birth_cutoff: int, pop_perc: int, max_bio_tweets: int, seed: int=1688):
    con = sqlite3.connect("db/twitter.db")
    cur = con.cursor()
    try:
        cur.execute("DROP TABLE threads")
    except sqlite3.OperationalError:
        print("> No table exits, creating the table")
    cur.execute("CREATE TABLE threads(id, status, tweets, thumbnail_urls, wikipedia_urls, message_id)")

    rng = random.Random(seed)
    pairings = get_all_pairings(min_overlap, max_overlap, birth_cutoff, pop_perc)
    rng.shuffle(pairings)
    threads = [get_thread_dict_for_pairing(i, p, max_bio_tweets) for i, p in enumerate(pairings)]
    bar = ShadyBar("🗄️ Updating Database... ", max=len(threads))

    rows = []
    for thread in threads:
        tweets = "╡".join(thread["tweets"])
        thumbnail_urls = "╡".join(thread["thumbnail_urls"])
        wikipedia_urls = "╡".join(thread["wikipedia_urls"])
        rows.append([thread["id"], thread["status"], tweets, thumbnail_urls, wikipedia_urls, None])
        bar.next()

    cur.executemany("INSERT INTO threads VALUES(?, ?, ?, ?, ?, ?)", rows)

    con.commit()
    con.close()
    bar.finish()

if __name__ == "__main__":
    print("❓ Please input the maximum number of tweets per bio.")
    tweet_n = int(input())
    print("❓ Please input the popularity percentile for pairing sampling. Press enter to use default value (0.05).")
    perc = input()
    perc = float(perc) if perc != "" else 0.05
    print("❓ Please input a seed to shuffle the twitter threads. If no seed is provided, a fixed seed will be used, resulting in the same order each run.")
    seed = input()
    print("❓ Please type 'json' to output to a json file or 'db' to output to an sql database.")
    mode = input().lower()
    if mode == "json":
        if seed == '':
            dump_threads_json(min_overlap=15, max_overlap=20, birth_cutoff=1900, pop_perc=perc, max_bio_tweets=tweet_n)
        else:
            dump_threads_json(min_overlap=15, max_overlap=20, birth_cutoff=1900, pop_perc=perc, max_bio_tweets=tweet_n, seed=int(seed))
    elif mode == "db":
        if seed == '':
            dump_threads_db(min_overlap=15, max_overlap=20, birth_cutoff=1900, pop_perc=perc, max_bio_tweets=tweet_n)
        else:
            dump_threads_db(min_overlap=15, max_overlap=20, birth_cutoff=1900, pop_perc=perc, max_bio_tweets=tweet_n, seed=int(seed))