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

`query` returns a vector of maps with keywordized column names.
`execute!` returns `{:rows-changed n :last-insert-rowid id}`. SQLite
types values per cell: INTEGER comes back as a long, REAL as a double,
TEXT as a string, BLOB as a byte array, NULL as nil.

Query vectors follow the `[sql & params]` shape, so
[honeysql](https://github.com/seancorfield/honeysql)-formatted vectors
work as-is. Binds accept integers, doubles, strings, byte arrays,
booleans (as 0/1) and nil.

`with-transaction` wraps statements in BEGIN IMMEDIATE .. COMMIT and rolls
back when the body throws:

```clojure
(sq/with-db [db "app.db"]
  (sq/with-transaction db
    (doseq [row rows]
      (sq/execute! db ["insert into events values (?, ?)" (:id row) (:data row)]))))
```

`interrupt!` aborts the connection's running query from another thread.

Connections set a 5 second busy timeout, so concurrent writers wait
instead of failing with SQLITE_BUSY. Pass `{:read-only true}` to `open`
to open an existing database read-only.

## Clojure functions in SQL

`create-function!` registers a Clojure function on a connection and SQL
can call it:

```clojure
(sq/with-db [db "app.db"]
  (sq/create-function! db "initials" 1
    (fn [s] (apply str (map first (str/split s #" ")))))
  (sq/query db "select name, initials(name) i from users"))
;;=> [{:name "rich hickey", :i "rh"}]
```

The function receives decoded values (longs, doubles, strings, byte
arrays, nil) and its return value becomes the SQL result. Pass -1 as the
argument count for a variadic function. An exception inside the function
becomes a SQL error. The argument count is optional and defaults to -1,
any number. Pass `{:deterministic true}` when the function always returns
the same result for the same arguments, which lets sqlite cache calls.

`create-aggregate!` registers a reduce-style aggregate, usable with group
by:

```clojure
(sq/create-aggregate! db "product"
  {:init 1
   :step (fn [acc v] (* acc v))})

(sq/query db "select grp, product(v) p from m group by grp")
```

`:finish` (default identity) turns the final accumulator into the SQL
result.

## Test

```bash
bb test
```

## License

Copyright (c) 2026 Michiel Borkent

Distributed under the MIT License. See LICENSE.
