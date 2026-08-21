(ns babashka.sqlite3
  "SQLite over babashka.ffi against libsqlite3. Query vectors follow the
  [sql & params] shape, so honeysql-formatted vectors work as-is:

      (require '[babashka.sqlite3 :as sq])
      (sq/query \"app.db\" [\"select * from users where name = ?\" \"rich\"])

  A string db argument opens and closes the database around the call; nil
  opens an in-memory database. For many operations hold a connection:

      (sq/with-db [db \"app.db\"]
        (sq/execute! db \"create table t (i integer, s text)\")
        (sq/query db \"select * from t\"))

  Rows come back as maps with keywordized column names. SQLite types values
  per cell: INTEGER comes back as a long, REAL as a double, TEXT as a
  string, BLOB as a byte array, NULL as nil."
  (:require [babashka.ffi :as ffi :refer [defcfn]]))

(ffi/load-library {:mac "libsqlite3.dylib"
                   :linux "libsqlite3.so.0"
                   ;; winsqlite3.dll ships with Windows itself
                   :windows ["sqlite3.dll" "winsqlite3.dll"]})

(defcfn ^:private c-libversion "sqlite3_libversion" [] :string)
(defcfn ^:private c-open "sqlite3_open" [:string :pointer] :int)
(defcfn ^:private c-close "sqlite3_close" [:pointer] :int)
(defcfn ^:private c-errmsg "sqlite3_errmsg" [:pointer] :string)
(defcfn ^:private c-busy-timeout "sqlite3_busy_timeout" [:pointer :int] :int)
(defcfn ^:private c-prepare "sqlite3_prepare_v2" [:pointer :string :int :pointer :pointer] :int)
(defcfn ^:private c-finalize "sqlite3_finalize" [:pointer] :int)
(defcfn ^:private c-step "sqlite3_step" [:pointer] :int)
(defcfn ^:private c-changes "sqlite3_changes" [:pointer] :int)
(defcfn ^:private c-column-count "sqlite3_column_count" [:pointer] :int)
(defcfn ^:private c-column-name "sqlite3_column_name" [:pointer :int] :string)
(defcfn ^:private c-column-type "sqlite3_column_type" [:pointer :int] :int)
(defcfn ^:private c-column-int64 "sqlite3_column_int64" [:pointer :int] :int64)
(defcfn ^:private c-column-double "sqlite3_column_double" [:pointer :int] :double)
(defcfn ^:private c-column-text "sqlite3_column_text" [:pointer :int] :string)
(defcfn ^:private c-column-blob "sqlite3_column_blob" [:pointer :int] :pointer)
(defcfn ^:private c-column-bytes "sqlite3_column_bytes" [:pointer :int] :int)
(defcfn ^:private c-bind-int64 "sqlite3_bind_int64" [:pointer :int :int64] :int)
(defcfn ^:private c-bind-double "sqlite3_bind_double" [:pointer :int :double] :int)
(defcfn ^:private c-bind-text "sqlite3_bind_text" [:pointer :int :string :int :pointer] :int)
(defcfn ^:private c-bind-blob "sqlite3_bind_blob" [:pointer :int :pointer :int :pointer] :int)
(defcfn ^:private c-bind-null "sqlite3_bind_null" [:pointer :int] :int)

;; SQLITE_TRANSIENT: sqlite must copy text and blob buffers during the bind
;; call, because the buffers babashka.ffi passes are freed when it returns
(def ^:private sqlite-transient -1)

(defn version
  "The SQLite library version string."
  []
  (c-libversion))

(defn open
  "Opens the database at path, nil for in-memory. Returns a connection for
  use with execute!, query and close!. Sets a 5s busy timeout, so
  concurrent writers wait instead of failing with SQLITE_BUSY."
  [path]
  (let [pdb (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (let [rc (c-open (or path ":memory:") pdb)
            db (ffi/read pdb :pointer)]
        (when-not (zero? rc)
          (let [msg (c-errmsg db)]
            (c-close db)
            (throw (ex-info (str "sqlite3: " msg) {:path path}))))
        (c-busy-timeout db 5000)
        {:db db})
      (finally (ffi/free pdb)))))

(defn close!
  "Closes a connection returned by open."
  [{:keys [db]}]
  (c-close db)
  nil)

(defmacro with-db
  "(with-db [db \"app.db\"] ...) - opens the database (nil path =
  in-memory), binds the connection, closes it after the body."
  [[sym path] & body]
  `(let [~sym (open ~path)]
     (try ~@body
          (finally (close! ~sym)))))

;; sqlite types values per cell, not per column
(defn- column-value [stmt i]
  (case (c-column-type stmt i)
    1 (c-column-int64 stmt i)
    2 (c-column-double stmt i)
    3 (c-column-text stmt i)
    4 (let [n (c-column-bytes stmt i)
            p (c-column-blob stmt i)
            arr (byte-array n)]
        (dotimes [j n]
          (aset arr j (byte (ffi/read p :int8 j))))
        arr)
    5 nil))

(defn- bind-params! [db stmt sql params]
  (doseq [[i v] (map-indexed vector params)]
    (let [i (inc i)
          rc (cond
               (nil? v) (c-bind-null stmt i)
               (boolean? v) (c-bind-int64 stmt i (if v 1 0))
               (integer? v) (c-bind-int64 stmt i v)
               (float? v) (c-bind-double stmt i v)
               ;; nbytes -1: read the UTF-8 C string up to its NUL. Text
               ;; with embedded NUL characters is not supported.
               (string? v) (c-bind-text stmt i v -1 sqlite-transient)
               (bytes? v) (let [n (alength ^bytes v)
                                p (ffi/alloc (max n 1))]
                            (try
                              (dotimes [j n]
                                (ffi/write p :int8 j (aget ^bytes v j)))
                              (c-bind-blob stmt i p n sqlite-transient)
                              (finally (ffi/free p))))
               :else (throw (ex-info (str "sqlite3: cannot bind " (type v))
                                     {:value v})))]
      (when-not (zero? rc)
        (throw (ex-info (str "sqlite3: " (c-errmsg db)) {:sql sql :param v}))))))

(def ^:private SQLITE-ROW 100)
(def ^:private SQLITE-DONE 101)

(defn- run* [conn q collect-rows?]
  (let [[sql & params] (if (string? q) [q] q)
        db (:db conn)
        pstmt (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (when-not (zero? (c-prepare db sql -1 pstmt ffi/null))
        (throw (ex-info (str "sqlite3: " (c-errmsg db)) {:sql sql})))
      (let [stmt (ffi/read pstmt :pointer)]
        (bind-params! db stmt sql params)
        (if collect-rows?
          (let [cols (delay (mapv (fn [i] [(keyword (c-column-name stmt i)) i])
                                  (range (c-column-count stmt))))]
            (loop [rows (transient [])]
              (let [rc (c-step stmt)]
                (cond
                  (= SQLITE-ROW rc)
                  (recur (conj! rows (into {} (map (fn [[k i]] [k (column-value stmt i)]))
                                           @cols)))
                  (= SQLITE-DONE rc) (persistent! rows)
                  :else (throw (ex-info (str "sqlite3: " (c-errmsg db)) {:sql sql}))))))
          (let [rc (c-step stmt)]
            (when-not (or (= SQLITE-DONE rc) (= SQLITE-ROW rc))
              (throw (ex-info (str "sqlite3: " (c-errmsg db)) {:sql sql})))
            {:rows-changed (c-changes db)})))
      (finally
        ;; finalizing a NULL statement (failed prepare) is a no-op
        (c-finalize (ffi/read pstmt :pointer))
        (ffi/free pstmt)))))

(defn- with-conn [db-or-path f]
  (if (map? db-or-path)
    (f db-or-path)
    (with-db [db db-or-path] (f db))))

(defn query
  "Runs a select. db is a connection from open, or a path (opened and closed
  around the call, nil for in-memory). q is a SQL string or a [sql & params]
  vector. Returns a vector of maps with keywordized column names."
  [db q]
  (with-conn db (fn [db] (run* db q true))))

(defn execute!
  "Runs a statement. Arguments as in query. Returns {:rows-changed n}."
  [db q]
  (with-conn db (fn [db] (run* db q false))))
