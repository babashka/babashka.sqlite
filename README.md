# ffi-sqlite3

SQLite for babashka over [babashka.ffi](https://github.com/babashka/babashka/blob/master/doc/ffi.md).

Experimental, like babashka.ffi itself. Needs a babashka with
`babashka.ffi`. The SQLite shared library ships with macOS, Linux and
Windows, so there is nothing else to install. The SQLite version is
whatever the system provides, so it varies per platform.

## Query

```clojure
(require '[babashka.sqlite3 :as sq])

(sq/query nil "select sqlite_version() v, 1 + 1 sum")
;;=> [{:v "3.43.2", :sum 2}]
```

`query` takes a database and SQL, and returns a vector of maps with
keywordized column names. nil as the database means in memory; a string
is a file path:

```clojure
(sq/execute! "app.db" "create table if not exists users (name text, age integer)")
(sq/execute! "app.db" ["insert into users values (?, ?), (?, ?)" "rich" 17 "stu" 12])
(sq/query "app.db" ["select * from users where age > ?" 15])
;;=> [{:name "rich", :age 17}]
```

`execute!` is for statements: it returns
`{:rows-changed n :last-insert-rowid id}` instead of rows. Parameters
follow the `[sql & params]` vector shape, so
[honeysql](https://github.com/seancorfield/honeysql)-formatted vectors
work as-is.

Values come back typed per cell: INTEGER as a long, REAL as a double,
TEXT as a string, BLOB as a byte array, NULL as nil. Binds accept the
same, plus booleans as 0/1.

## Connections

A string database opens and closes the file around each call. For more
than one operation, hold a connection:

```clojure
(sq/with-db [db "app.db"]
  (sq/execute! db "create table if not exists events (at text, what text)")
  (sq/execute! db ["insert into events values (?, ?)" "2026-08-22" "ship"])
  (sq/query db "select * from events"))
```

Connections set a 5 second busy timeout, so concurrent writers wait
instead of failing with SQLITE_BUSY. `(sq/open path opts)` and
`(sq/close! db)` are the functions underneath; `{:read-only true}` opens
an existing database read-only.

## Transactions

`with-transaction` wraps statements in BEGIN IMMEDIATE .. COMMIT and
rolls back when the body throws. Batching inserts in one transaction is
also the difference between hundreds and hundreds of thousands of inserts
per second:

```clojure
(sq/with-db [db "app.db"]
  (sq/with-transaction db
    (doseq [i (range 1000)]
      (sq/execute! db ["insert into events values (?, ?)" (str "day-" i) "tick"]))))
```

## Clojure functions in SQL

`create-function!` registers a Clojure function on a connection, and SQL
can call it like any built-in:

```clojure
(sq/with-db [db nil]
  (sq/create-function! db "initials"
    (fn [s] (apply str (map first (clojure.string/split s #" ")))))
  (sq/query db ["select initials(?) i" "gerald jay sussman"]))
;;=> [{:i "gjs"}]
```

The function receives decoded values and its return value becomes the SQL
result. Without an argument count it accepts any number; pass one to fix
the arity, and `{:deterministic true}` when the same arguments always
give the same result, which lets sqlite cache calls. An exception inside
the function becomes a SQL error.

`create-aggregate!` registers a reduce-style aggregate that works with
group by:

```clojure
(sq/with-db [db nil]
  (sq/execute! db "create table m (grp text, v integer)")
  (sq/execute! db ["insert into m values (?,?), (?,?), (?,?)" "a" 2 "a" 3 "b" 5])
  (sq/create-aggregate! db "product"
    {:init 1
     :step (fn [acc v] (* acc v))})
  (sq/query db "select grp, product(v) p from m group by grp order by grp"))
;;=> [{:grp "a", :p 6} {:grp "b", :p 5}]
```

`:init` is the starting accumulator, `:step` folds in each row's values,
`:finish` (default identity) turns the final accumulator into the SQL
result.

## Interrupting a query

`(sq/interrupt! db)` aborts the connection's running query from another
thread; the interrupted query throws.

## Test

```bash
bb test
```

## License

Copyright (c) 2026 Michiel Borkent

Distributed under the MIT License. See LICENSE.
