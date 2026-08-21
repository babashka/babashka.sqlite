# ffi-sqlite3

SQLite for babashka over [babashka.ffi](https://github.com/babashka/babashka/blob/master/doc/ffi.md).

Experimental, like babashka.ffi itself. Needs a babashka with
`babashka.ffi`. The SQLite shared library ships with macOS, Linux and
Windows, so there is nothing else to install.

## Usage

```clojure
(require '[babashka.sqlite3 :as sq])

(sq/with-db [db "app.db"]
  (sq/execute! db "create table if not exists users (name text, age integer)")
  (sq/execute! db ["insert into users values (?, ?), (?, ?)" "rich" 17 "stu" 12])
  (sq/query db ["select * from users where age > ?" 15]))
;;=> [{:name "rich", :age 17}]
```

Pass nil instead of a path for an in-memory database. A string db argument
opens and closes the database around a single call:

```clojure
(sq/query "app.db" "select count(*) n from users")
;;=> [{:n 2}]
```

`query` returns a vector of maps with keywordized column names. SQLite
types values per cell: INTEGER comes back as a long, REAL as a double,
TEXT as a string, BLOB as a byte array, NULL as nil.

Query vectors follow the `[sql & params]` shape, so
[honeysql](https://github.com/seancorfield/honeysql)-formatted vectors
work as-is. Binds accept integers, doubles, strings, byte arrays,
booleans (as 0/1) and nil.

Connections set a 5 second busy timeout, so concurrent writers wait
instead of failing with SQLITE_BUSY.

## Test

```bash
bb test
```

## License

Copyright (c) 2026 Michiel Borkent

Distributed under the MIT License. See LICENSE.
