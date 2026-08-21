(ns babashka.sqlite3-test
  (:require [babashka.sqlite3 :as sq]
            [clojure.test :refer [deftest is testing]]))

(deftest version-test
  (is (re-find #"^3\." (sq/version))))

(deftest query-test
  (sq/with-db [db nil]
    (testing "ddl and insert report rows changed"
      (sq/execute! db "create table t (i integer, r real, s text, b blob)")
      (is (= {:rows-changed 2}
             (sq/execute! db ["insert into t values (?, ?, ?, ?), (?, ?, ?, ?)"
                              1 1.5 "a" (byte-array [1 2 3])
                              2 2.5 nil nil]))))
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

(deftest path-test
  (testing "a string db opens and closes around the call"
    (let [path (str (System/getProperty "java.io.tmpdir")
                    "/ffi-sqlite3-test-" (System/currentTimeMillis) ".db")]
      (sq/execute! path "create table k (answer integer)")
      (sq/execute! path "insert into k values (42)")
      (is (= [{:answer 42}] (sq/query path "select * from k"))))))
