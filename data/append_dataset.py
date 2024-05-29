import pandas as pd
import matplotlib.pyplot as plt 
import typing, time, csv, re, logging, sys
from rdflib import Graph, Namespace
from rdflib.namespace import XSD
from progress.bar import ShadyBar

ERR_TIMEOUT = 10

def convert_problematic_datestr_to_yearint(date: str) -> int:
    date = date.lower()
    if "bc" in date or "bce" in date:
        bce = True
    else:
        bce = False
    if "unknown" in date:
        return None
    matches = re.findall("[0-9]+", date)
    if len(matches) > 0:
        date = matches[0]
    else:
        return None
    if bce:
        date = "-" + date
    return(convert_datestr_to_yearint(date))

def is_problematic(date: str) -> bool:
    if len(date) == 0:
        return True
    has_num = False
    for c in date:
        if c.isalpha():
            return True
        if c.isnumeric():
            has_num = True
    if not has_num:
        return False

def convert_datestr_to_yearint(date: str) -> int:
    if is_problematic(date):
        return convert_problematic_datestr_to_yearint(date)
    bc = True if date[0] == '-' else False
    date = date.lstrip('-')
    if '-' in date:
        date = date.split('-')
        return int(date[0]) * (-1) if bc else int(date[0])
    else:
        return int(date) * (-1) if bc else int(date)

def extract_yod_dictionary(page_ids: list[int], first_n: int = -1, interval: int = 0, dump_log: bool = False) -> list[dict]:
    remote_g = Graph()
    extracted_dobs = []
    conversion_table  = []
    done = 0
    first_n = len(page_ids) if first_n == -1 else first_n

    bar = ShadyBar("[1] Querying year of death information...".ljust(42), max=first_n)
    for id, yob in zip(page_ids[:first_n], yobs[:first_n]):
        # Ensuring we don't exceed the 100 requests per second limit
        time.sleep(interval)
        query_text = f"""
        PREFIX dbo: <http://dbpedia.org/ontology/>
        PREFIX dbp: <http://dbpedia.org/property/>
        SELECT DISTINCT ?wiki_entry ?dod
        WHERE {{
            SERVICE <https://dbpedia.org/sparql> {{
                ?wiki_entry dbo:wikiPageID {id};
                dbo:deathYear|dbo:deathDate ?dod
            }} 
        }}
        """
        try:
            qres = remote_g.query(query_text)
        except Exception as e:
            print(e)
            time.sleep(ERR_TIMEOUT)

        if (len(qres)) == 0:
            # Trying the dbp properties if the dbo ones don't return anything
            # The dbp dates are much less strictly formatting, so we don't want to use them if we have a dbo binding
            query_text = query_text.replace("dbo:d", "dbp:d")
            remote_g = Graph()

            try:
                qres = remote_g.query(query_text)
            except Exception as e:
                print(e)
                time.sleep(ERR_TIMEOUT)

        for row in qres:
            yod = convert_datestr_to_yearint(str(row.dod))
            conversion_table.append({"raw": str(row.dod), "converted": str(yod)})
            for x in (1, -1): # Trying to fix BCE/CE mixups
                if yod and (x*yod - yob) < 125 and x*yod > yob: # Sanity check
                    extracted_dobs.append({"article_id": id, "death_year":x*yod})
                    break
            break

        bar.next()

    bar.finish()
    print(f"🗃️ Found YoD for {len(extracted_dobs)}/{first_n} entries.")

    if dump_log:
        with open("conversion_table.csv", "w+", encoding="utf-16") as f:
            writer = csv.DictWriter(f, ["raw", "converted"])
            writer.writerows(conversion_table)

    return extracted_dobs

def extract_thumbs_dictionary(page_ids: list[int], first_n: int = -1, interval: int = 0) -> list[dict]:
    remote_g = Graph()
    extracted_thumbs = []
    done = 0
    first_n = len(page_ids) if first_n == -1 else first_n

    bar = ShadyBar("[2] Querying thumbnail URLs...".ljust(42), max=first_n)
    for id in page_ids[:first_n]:
        # Ensuring we don't exceed the 100 requests per second limit
        time.sleep(interval)
        query_text = f"""
        PREFIX dbo: <http://dbpedia.org/ontology/>
        PREFIX dbp: <http://dbpedia.org/property/>
        SELECT DISTINCT ?wiki_entry ?thumbnail
        WHERE {{
            SERVICE <https://dbpedia.org/sparql> {{
                ?wiki_entry dbo:wikiPageID {id};
                dbo:thumbnail ?thumbnail
            }} 
        }}
        """
        try:
            qres = remote_g.query(query_text)
        except Exception as e:
            print(e)
            time.sleep(ERR_TIMEOUT)

        for row in qres:
            extracted_thumbs.append({"article_id": id, "thumbnail_url":str(row.thumbnail).rstrip("?width=300")})
            break

        bar.next()

    bar.finish()
    print(f"🗃️ Found thumbnails for {len(extracted_thumbs)}/{first_n} entries.")

    return extracted_thumbs

def extract_abstract_dictionary(page_ids: list[int], first_n: int = -1, interval: int = 0) -> list[dict]:
    remote_g = Graph()
    extracted_abstracts = []
    done = 0
    first_n = len(page_ids) if first_n == -1 else first_n

    bar = ShadyBar("[3] Querying Wikipedia abstracts...".ljust(42), max=first_n)
    for id in page_ids[:first_n]:
        # Ensuring we don't exceed the 100 requests per second limit
        time.sleep(interval)
        query_text = f"""
        PREFIX dbo: <http://dbpedia.org/ontology/>
        PREFIX dbp: <http://dbpedia.org/property/>
        SELECT DISTINCT ?wiki_entry ?abstract
        WHERE {{
            SERVICE <https://dbpedia.org/sparql> {{
                ?wiki_entry dbo:wikiPageID {id};
                dbo:abstract ?abstract
            }}
            FILTER (lang(?abstract) = "en") 
        }}
        """
        try:
            qres = remote_g.query(query_text)
        except Exception as e:
            print(e)
            time.sleep(ERR_TIMEOUT)

        for row in qres:
            extracted_abstracts.append({"article_id": id, "abstract":str(row.abstract)})
            break

        bar.next()

    bar.finish()
    print(f"🗃️ Found abstracts for {len(extracted_abstracts)}/{first_n} entries.")

    return extracted_abstracts

def write_yod_csv(filename: str):
    dobs_dict, _ = extract_yod_dictionary(page_ids, interval=0.02)

    with open(filename, "w+") as file:
        csv_writer = csv.DictWriter(file, ["article_id", "death_year"])
        csv_writer.writeheader()
        csv_writer.writerows(dobs_dict)

if __name__ == "__main__":
    if input("🌐 This operation will take several minutes and requires a stable internet connection. Type YES to proceed: ").lower() != "yes":
        sys.exit()
    logging.getLogger("rdflib.term").setLevel(logging.CRITICAL)

    og_dataframe = pd.read_csv("csv/og_database.csv")
    og_dataframe["birth_year"] = pd.to_numeric(og_dataframe["birth_year"], errors="coerce")
    og_dataframe.dropna(subset=["birth_year"], inplace=True)
    yobs = [int(year) for year in og_dataframe["birth_year"]]
    page_ids = [int(id) for id in og_dataframe["article_id"]]

    first_n = 10 if "-d" in sys.argv else -1 # Faster performance in debug mode

    yod_dataframe = pd.DataFrame.from_dict(extract_yod_dictionary(page_ids, interval=0.02, dump_log=True, first_n=first_n))
    thumbs_dataframe = pd.DataFrame.from_dict(extract_thumbs_dictionary(page_ids, interval=0.02, first_n=first_n))
    abstracts_dataframe = pd.DataFrame.from_dict(extract_abstract_dictionary(page_ids=page_ids, interval=0.02, first_n=first_n))

    dataframe = pd.merge(og_dataframe, yod_dataframe, on="article_id", how="left")
    dataframe = pd.merge(dataframe, thumbs_dataframe, on="article_id", how="left")
    dataframe = pd.merge(dataframe, abstracts_dataframe, on="article_id", how="left")

    cols = ["article_id", "full_name", "sex", "birth_year", "death_year", "city", "state", "country", 
            "continent", "latitude", "longitude", "occupation", "industry", "domain", "thumbnail_url", "article_languages", "abstract", 
            "page_views", "average_views", "historical_popularity_index"]
    dataframe = dataframe[cols]

    with open("database.csv", "w+", encoding='utf-16') as f:
        if "-d" not in sys.argv: # Not ammending the file in debug mode
            dataframe.to_csv(f)
            print("💾 Appended information dumped into csv/database.csv!")
        else:
            print("💾 Stored database unnafected [DEBUGGING MODE]")