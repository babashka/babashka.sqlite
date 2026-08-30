# babashka.sqlite

Use SQLite from babashka through
[babashka.ffi](https://github.com/babashka/babashka/blob/master/doc/ffi.md).

This library and `babashka.ffi` are experimental. This library requires a
babashka build that includes `babashka.ffi`.

macOS, Linux, and Windows include a SQLite shared library. This library uses
that file, so SQLite does not need a separate installation. The SQLite version
depends on the system.

## Query

```clojure
(require '[babashka.sqlite :as sq])

(sq/query nil "select sqlite_version() v, 1 + 1 sum")
;;=> [{:v "3.43.2", :sum 2}]
```

`query` returns a vector of row maps. Each map uses keywords for its column
names.

Use `nil` for an in-memory database. Use a string file path for a database.

```clojure
(sq/execute! "app.db" "create table if not exists users (name text, age integer)")
(sq/execute! "app.db" ["insert into users values (?, ?), (?, ?)" "rich" 17 "stu" 12])
(sq/query "app.db" ["select * from users where age > ?" 15])
;;=> [{:name "rich", :age 17}]
```

Use `execute!` for statements that do not return rows. It returns
`{:rows-changed n :last-insert-rowid id}`.

`:rows-changed` is the number of rows that the statement changed.
`:last-insert-rowid` is the row ID from the most recent insert on the
connection.

If the statement has no parameters, pass SQL as a string. If it has parameters,
use the `[sql & params]` vector form.

Each result value has the Clojure type for its SQLite storage class:

| SQLite storage class | Clojure value |
| --- | --- |
| `INTEGER` | long |
| `REAL` | double |
| `TEXT` | string |
| `BLOB` | byte array |
| `NULL` | `nil` |

Parameters accept the same Clojure types. Boolean parameters use `1` for
true and `0` for false.

### HoneySQL

[HoneySQL](https://github.com/seancorfield/honeysql) builds SQL from Clojure
data. Pass the result of `sql/format` to `query`:

```clojure
(require '[honey.sql :as sql])

(sq/query "app.db"
  (sql/format {:select [:name :age]
               :from [:users]
               :where [:> :age 15]
               :order-by [[:age :desc]]}))
;;=> [{:name "rich", :age 17}]
```

## Connections

A string file path opens and closes a connection for each call. For multiple
operations, keep one connection open:

```clojure
(sq/with-conn [db "app.db"]
  (sq/execute! db "create table if not exists events (at text, what text)")
  (sq/execute! db ["insert into events values (?, ?)" "2026-08-22" "ship"])
  (sq/query db "select * from events"))
```

Each connection has a five-second busy timeout. A concurrent writer waits
for the lock during this period instead of immediately returning
`SQLITE_BUSY`.

Use `(sq/open path opts)` and `(sq/close! db)` when you cannot use `with-conn`.
The option `{:read-only true}` opens an existing database without write
access.

## Thread safety

Use a connection from one thread at a time. SQLite serializes concurrent calls
on a shared connection, so these calls do not crash. However, concurrent calls
can return information about another thread's statement.

`:rows-changed` and `:last-insert-rowid` can report values from another
thread's statement. Statements from other threads can also join an open
transaction on the connection.

Use one of these safe patterns:

- Open one connection for each thread.
- Pass a string file path to `query` or `execute!`. Each call opens a private
  connection.

Concurrent writers coordinate through SQLite file locking and the five-second
busy timeout.

`interrupt!` is the one function for use from another thread. Never call
`close!` while another thread uses the connection.

## Transactions

`with-transaction` starts an immediate transaction and evaluates its body.
It commits the transaction when the body returns. It rolls back the
transaction when the body throws.

Use one transaction for a batch of inserts:

```clojure
(sq/with-conn [db "app.db"]
  (sq/with-transaction db
    (doseq [i (range 1000)]
      (sq/execute! db ["insert into events values (?, ?)" (str "day-" i) "tick"]))))
```

## Clojure functions in SQL

`create-function!` registers a Clojure function on a connection. SQL can
then call the function by its registered name.

```clojure
(sq/with-conn [db nil]
  (sq/create-function! db "initials"
    (fn [s] (apply str (map first (clojure.string/split s #" ")))))
  (sq/query db ["select initials(?) i" "gerald jay sussman"]))
;;=> [{:i "gjs"}]
```

The function receives Clojure values and returns a value to SQL. If you omit
the argument count, the function accepts any number of arguments.

Pass an argument count to require a fixed number. Pass
`{:deterministic true}` if the function always returns the same result for the
same arguments. SQLite can then cache or optimize calls. An exception from
the function becomes a SQL error.

`create-aggregate!` registers a reduce-style aggregate. You can use the
aggregate with `GROUP BY`.

```clojure
(sq/with-conn [db nil]
  (sq/execute! db "create table m (grp text, v integer)")
  (sq/execute! db ["insert into m values (?,?), (?,?), (?,?)" "a" 2 "a" 3 "b" 5])
  (sq/create-aggregate! db "product"
    {:init 1
     :step (fn [acc v] (* acc v))})
  (sq/query db "select grp, product(v) p from m group by grp order by grp"))
;;=> [{:grp "a", :p 6} {:grp "b", :p 5}]
```

The aggregate specification has these keys:

- `:init` is the initial accumulator value.
- `:step` receives the accumulator and the values from one row.
- `:finish` converts the final accumulator to the SQL result. Its default is
  `identity`.

Pass an argument count before the specification to require a fixed number of
arguments. Set `:deterministic true` if the aggregate always returns the same
result for the same arguments.

For zero rows, `:finish` receives the `:init` value. The aggregate keeps
separate state for each group.

## Interrupting a query

Call `(sq/interrupt! db)` from another thread to interrupt a query. The
interrupted query throws an exception.

## Test

Run the tests:

```bash
bb test
```

## License

Copyright (c) 2026 Michiel Borkent

Distributed under the MIT License. See LICENSE.
