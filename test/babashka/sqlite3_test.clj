(ns babashka.sqlite3-test
  (:require [babashka.sqlite3 :as sq]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest version-test
  (is (re-find #"^3\." (sq/version))))

(deftest query-test
  (sq/with-db [db nil]
    (testing "ddl and insert report rows changed"
      (sq/execute! db "create table t (i integer, r real, s text, b blob)")
      (is (= 2
             (:rows-changed
              (sq/execute! db ["insert into t values (?, ?, ?, ?), (?, ?, ?, ?)"
                               1 1.5 "a" (byte-array [1 2 3])
                               2 2.5 nil nil])))))
    (testing "values come back typed per cell, NULL as nil"
      (let [[r1 r2] (sq/query db "select * from t order by i")]
        (is (= [1 1.5 "a"] [(:i r1) (:r r1) (:s r1)]))
        (is (= [1 2 3] (vec (:b r1))))
        (is (= [2 2.5 nil nil] [(:i r2) (:r r2) (:s r2) (:b r2)]))))
    (testing "multibyte text round trip: byte length, not char count"
      (is (= [{:s "héllo wörld ✓"}]
             (sq/query db ["select ? s" "héllo wörld ✓"]))))
    (testing "booleans bind as integers"
      (is (= [{:v 1}] (sq/query db ["select ? v" true]))))
    (testing "bad sql throws with the sqlite message"
      (is (thrown-with-msg? Exception #"sqlite3:"
                            (sq/query db "select nope from nothing"))))
    (testing "a failing statement leaves the connection usable"
      (is (thrown-with-msg? Exception #"sqlite3:"
                            (sq/query db ["select nope from nothing where x = ?" 1])))
      (is (= [{:one 1}] (sq/query db "select 1 one"))))))

(deftest create-function-test
  (sq/with-db [db nil]
    (testing "a Clojure fn is callable from SQL"
      (sq/create-function! db "plus2" 1 #(+ % 2))
      (is (= [{:v 42}] (sq/query db "select plus2(40) v"))))
    (testing "string in, string out, multibyte"
      (sq/create-function! db "shout" 1 #(str (str/upper-case %) "!"))
      (is (= [{:v "HÉLLO!"}] (sq/query db ["select shout(?) v" "héllo"]))))
    (testing "functions apply over rows and nil flows through"
      (sq/execute! db "create table t (s text)")
      (sq/execute! db ["insert into t values (?), (?), (?)" "a" "b" nil])
      (sq/create-function! db "tag" 1 #(some->> % (str "x-")))
      (is (= [{:v "x-a"} {:v "x-b"} {:v nil}]
             (sq/query db "select tag(s) v from t"))))
    (testing "variadic nargs -1"
      (sq/create-function! db "sum_all" -1 (fn [& xs] (reduce + 0 xs)))
      (is (= [{:v 6}] (sq/query db "select sum_all(1, 2, 3) v"))))
    (testing "an exception in the fn becomes a SQL error, connection survives"
      (sq/create-function! db "boom" 0 #(throw (ex-info "kapow" {})))
      (is (thrown-with-msg? Exception #"kapow"
                            (sq/query db "select boom()")))
      (is (= [{:one 1}] (sq/query db "select 1 one"))))
    (testing "deterministic opt registers fine"
      (sq/create-function! db "det" 1 inc {:deterministic true})
      (is (= [{:v 2}] (sq/query db "select det(1) v"))))))

(deftest rowid-and-transaction-test
  (sq/with-db [db nil]
    (sq/execute! db "create table t (id integer primary key, s text)")
    (testing "execute! reports the generated rowid"
      (is (= 1 (:last-insert-rowid (sq/execute! db ["insert into t (s) values (?)" "a"]))))
      (is (= 2 (:last-insert-rowid (sq/execute! db ["insert into t (s) values (?)" "b"])))))
    (testing "with-transaction commits and returns the body value"
      (is (= :done (sq/with-transaction db
                     (sq/execute! db ["insert into t (s) values (?)" "c"])
                     :done)))
      (is (= [{:n 3}] (sq/query db "select count(*) n from t"))))
    (testing "a throw rolls back everything since begin"
      (is (thrown-with-msg? Exception #"kaboom"
                            (sq/with-transaction db
                              (sq/execute! db ["insert into t (s) values (?)" "d"])
                              (throw (ex-info "kaboom" {})))))
      (is (= [{:n 3}] (sq/query db "select count(*) n from t"))))))

(deftest create-aggregate-test
  (sq/with-db [db nil]
    (sq/execute! db "create table m (grp text, v integer)")
    (sq/execute! db ["insert into m values (?,?), (?,?), (?,?), (?,?)"
                     "a" 2 "a" 3 "b" 5 "b" 7])
    (testing "reduce-style aggregate over group by"
      (sq/create-aggregate! db "product" {:init 1 :step (fn [acc v] (* acc v))})
      (is (= [{:grp "a" :p 6} {:grp "b" :p 35}]
             (sq/query db "select grp, product(v) p from m group by grp order by grp"))))
    (testing ":finish maps the accumulator, zero rows give (finish init)"
      (sq/create-aggregate! db "cnt2"
                            {:init 0
                             :step (fn [acc _] (inc acc))
                             :finish #(* 2 %)})
      (is (= [{:c 8}] (sq/query db "select cnt2(v) c from m")))
      (is (= [{:c 0}] (sq/query db "select cnt2(v) c from m where v > 100"))))
    (testing "an exception in step becomes a SQL error"
      (sq/create-aggregate! db "bad" {:init 0 :step (fn [_ _] (throw (ex-info "agg-boom" {})))})
      (is (thrown-with-msg? Exception #"agg-boom"
                            (sq/query db "select bad(v) from m"))))))

(deftest nargs-default-test
  (sq/with-db [db nil]
    (testing "create-function! without nargs is variadic"
      (sq/create-function! db "plus" (fn [& xs] (reduce + 0 xs)))
      (is (= [{:a 3 :b 6}] (sq/query db "select plus(1,2) a, plus(1,2,3) b"))))))

(deftest interrupt-test
  (sq/with-db [db nil]
    (testing "interrupt! from another thread aborts a running query"
      (let [fut (future
                  (try (sq/query db "with recursive c(x) as
                                     (select 1 union all select x+1 from c limit 100000000)
                                     select count(*) n from c")
                       :finished
                       (catch Exception _ :interrupted)))]
        (Thread/sleep 100)
        (sq/interrupt! db)
        (is (= :interrupted (deref fut 10000 :timeout)))))))

(deftest open-v2-test
  (testing "read-only refuses writes but reads"
    (let [path (str (System/getProperty "java.io.tmpdir")
                    "/ffi-sqlite3-ro-" (System/currentTimeMillis) ".db")]
      (sq/execute! path "create table k (v integer)")
      (sq/execute! path "insert into k values (7)")
      (let [db (sq/open path {:read-only true})]
        (try
          (is (= [{:v 7}] (sq/query db "select * from k")))
          (is (thrown-with-msg? Exception #"sqlite3:"
                                (sq/execute! db "insert into k values (8)")))
          (finally (sq/close! db)))))))

(deftest path-test
  (testing "a string db opens and closes around the call"
    (let [path (str (System/getProperty "java.io.tmpdir")
                    "/ffi-sqlite3-test-" (System/currentTimeMillis) ".db")]
      (sq/execute! path "create table k (answer integer)")
      (sq/execute! path "insert into k values (42)")
      (is (= [{:answer 42}] (sq/query path "select * from k"))))))
