import sqlite3

def set_pending():
    print("This action is irreversible. Are you sure you want to continue? [Y/n]")
    if (input() == "Y"):
        with sqlite3.connect("db/twitter.db") as con:
            cur = con.cursor()
            cur.execute("""UPDATE threads
                    SET status = 'PENDING', message_id = NULL""")
            con.commit()

def scan():
    with sqlite3.connect("db/twitter.db") as con:
        cur = con.cursor()
        res = cur.execute("""SELECT id
                        FROM threads
                        WHERE status = 'ACCEPTED' or status = 'REJECTED'""")
        for row in res:
            print(row)

def example():
    with sqlite3.connect("db/twitter.db") as con:
        cur = con.cursor()
        res = cur.execute("""SELECT tweets, id
                        FROM threads
                        ORDER BY RANDOM()
                        LIMIT 1""")
        for row in res:
            print(f"\u25CF Tweet no. {row[1]}\n")
            print("\u25CB " + row[0].replace("╡", "\n\n\u25CB "))
            break

if __name__ == "__main__":
    print("""❓ Select your option:
    (P)END: Sets all threads in the database to the PENDING state
    (S)CAN: Returns all the threads that had their status changed to ACCPETED or REJECTED
    (E)XAMPLE: Prints out a randomly selected example of a tweet from the database""")
    match input().upper():
        case "PEND" | "P":
            set_pending()
        case "SCAN" | "S":
            scan()
        case "EXAMPLE" | "E":
            example()