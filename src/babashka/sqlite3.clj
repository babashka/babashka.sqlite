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
(defcfn ^:private c-close "sqlite3_close" [:pointer] :int)
(defcfn ^:private c-errmsg "sqlite3_errmsg" [:pointer] :string)
(defcfn ^:private c-busy-timeout "sqlite3_busy_timeout" [:pointer :int] :int)
(defcfn ^:private c-prepare "sqlite3_prepare_v2" [:pointer :string :int :pointer :pointer] :int)
(defcfn ^:private c-finalize "sqlite3_finalize" [:pointer] :int)
(defcfn ^:private c-step "sqlite3_step" [:pointer] :int)
(defcfn ^:private c-changes "sqlite3_changes" [:pointer] :int)
(defcfn ^:private c-last-insert-rowid "sqlite3_last_insert_rowid" [:pointer] :int64)
(defcfn ^:private c-interrupt "sqlite3_interrupt" [:pointer] :void)
(defcfn ^:private c-aggregate-context "sqlite3_aggregate_context" [:pointer :int] :pointer)
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
(defcfn ^:private c-open-v2 "sqlite3_open_v2" [:string :pointer :int :pointer] :int)
(defcfn ^:private c-create-function "sqlite3_create_function_v2"
  [:pointer :string :int :int :pointer :pointer :pointer :pointer :pointer] :int)
(defcfn ^:private c-value-type "sqlite3_value_type" [:pointer] :int)
(defcfn ^:private c-value-int64 "sqlite3_value_int64" [:pointer] :int64)
(defcfn ^:private c-value-double "sqlite3_value_double" [:pointer] :double)
(defcfn ^:private c-value-text "sqlite3_value_text" [:pointer] :string)
(defcfn ^:private c-value-blob "sqlite3_value_blob" [:pointer] :pointer)
(defcfn ^:private c-value-bytes "sqlite3_value_bytes" [:pointer] :int)
(defcfn ^:private c-result-int64 "sqlite3_result_int64" [:pointer :int64] :void)
(defcfn ^:private c-result-double "sqlite3_result_double" [:pointer :double] :void)
(defcfn ^:private c-result-text "sqlite3_result_text" [:pointer :string :int :pointer] :void)
(defcfn ^:private c-result-blob "sqlite3_result_blob" [:pointer :pointer :int :pointer] :void)
(defcfn ^:private c-result-null "sqlite3_result_null" [:pointer] :void)
(defcfn ^:private c-result-error "sqlite3_result_error" [:pointer :string :int] :void)

;; SQLITE_TRANSIENT: sqlite must copy text and blob buffers during the bind
;; call, because the buffers babashka.ffi passes are freed when it returns
(def ^:private sqlite-transient -1)

(defn version
  "The SQLite library version string."
  []
  (c-libversion))

(def ^:private SQLITE-OPEN-READONLY 0x1)
(def ^:private SQLITE-OPEN-READWRITE 0x2)
(def ^:private SQLITE-OPEN-CREATE 0x4)

(defn open
  "Opens the database at path, nil for in-memory. Returns a connection for
  use with execute!, query, create-function! and close!. Sets a 5s busy
  timeout, so concurrent writers wait instead of failing with SQLITE_BUSY.

  opts: :read-only opens the existing database read-only; :flags passes
  raw SQLITE_OPEN_* flags instead."
  ([path] (open path nil))
  ([path {:keys [read-only flags]}]
   (let [pdb (ffi/alloc (ffi/sizeof :pointer))
         flags (or flags
                   (if read-only
                     SQLITE-OPEN-READONLY
                     (bit-or SQLITE-OPEN-READWRITE SQLITE-OPEN-CREATE)))]
     (try
       (let [rc (c-open-v2 (or path ":memory:") pdb flags ffi/null)
             db (ffi/read pdb :pointer)]
         (when-not (zero? rc)
           (let [msg (c-errmsg db)]
             (c-close db)
             (throw (ex-info (str "sqlite3: " msg) {:path path}))))
         (c-busy-timeout db 5000)
         {:db db :fns (atom [])})
       (finally (ffi/free pdb))))))

(defn close!
  "Closes a connection returned by open and releases its registered
  functions."
  [{:keys [db fns]}]
  (c-close db)
  (when fns
    (doseq [cb @fns] (ffi/free-callback cb))
    (reset! fns []))
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
            {:rows-changed (c-changes db)
             :last-insert-rowid (c-last-insert-rowid db)})))
      (finally
        ;; finalizing a NULL statement (failed prepare) is a no-op
        (c-finalize (ffi/read pstmt :pointer))
        (ffi/free pstmt)))))

(def ^:private SQLITE-UTF8 1)
(def ^:private SQLITE-DETERMINISTIC 0x800)

(defn- decode-value [pv]
  (case (c-value-type pv)
    1 (c-value-int64 pv)
    2 (c-value-double pv)
    3 (c-value-text pv)
    4 (let [n (c-value-bytes pv)
            p (c-value-blob pv)
            arr (byte-array n)]
        (dotimes [j n] (aset arr j (byte (ffi/read p :int8 j))))
        arr)
    5 nil))

(defn- set-result! [ctx v]
  (cond
    (nil? v) (c-result-null ctx)
    (boolean? v) (c-result-int64 ctx (if v 1 0))
    (integer? v) (c-result-int64 ctx v)
    (float? v) (c-result-double ctx v)
    (string? v) (c-result-text ctx v -1 sqlite-transient)
    (bytes? v) (let [n (alength ^bytes v)
                     p (ffi/alloc (max n 1))]
                 (try
                   (dotimes [j n] (ffi/write p :int8 j (aget ^bytes v j)))
                   (c-result-blob ctx p n sqlite-transient)
                   (finally (ffi/free p))))
    :else (c-result-error ctx (str "cannot return " (type v) " from a function") -1)))

(defn- decode-args [argc argv]
  (mapv (fn [i] (decode-value (ffi/read argv :pointer (* 8 i))))
        (range argc)))

(defn- register! [{:keys [db fns] :as conn} name nargs deterministic xfunc xstep xfinal]
  (let [cbs (into [] (keep identity) [xfunc xstep xfinal])
        rc (c-create-function db name nargs
                              (cond-> SQLITE-UTF8 deterministic (bit-or SQLITE-DETERMINISTIC))
                              ffi/null
                              (or xfunc ffi/null)
                              (or xstep ffi/null)
                              (or xfinal ffi/null)
                              ffi/null)]
    (when-not (zero? rc)
      (run! ffi/free-callback cbs)
      (throw (ex-info (str "sqlite3: " (c-errmsg db)) {:function name})))
    (swap! fns into cbs)
    conn))

(defn create-function!
  "Registers Clojure function f as SQL function name on the connection.
  nargs is the argument count SQL calls must use, -1 (the default) for any
  number. f receives decoded values (longs, doubles, strings, byte arrays,
  nil) and its return value becomes the SQL result. An exception inside f
  becomes a SQL error. The function stays registered until close!.

  opts: :deterministic declares that f always returns the same result for
  the same arguments, which lets sqlite cache and optimize calls."
  ([conn name f] (create-function! conn name -1 f nil))
  ([conn name nargs-or-f f-or-opts]
   (if (fn? nargs-or-f)
     (create-function! conn name -1 nargs-or-f f-or-opts)
     (create-function! conn name nargs-or-f f-or-opts nil)))
  ([conn name nargs f {:keys [deterministic]}]
   (let [xfunc (ffi/callback
                (fn [ctx argc argv]
                  ;; an exception crossing the C boundary would abort the
                  ;; process: everything is caught and reported to sqlite
                  (try
                    (set-result! ctx (apply f (decode-args argc argv)))
                    (catch Throwable e
                      (c-result-error ctx (str (ex-message e)) -1))))
                [:pointer :int :pointer] :void)]
     (register! conn name nargs deterministic xfunc nil nil))))

(defn create-aggregate!
  "Registers a Clojure aggregate as SQL function name on the connection,
  reduce-style:

      (create-aggregate! db \"product\"
        {:init 1
         :step (fn [acc v] (* acc v))})

  :init is the starting accumulator value, :step receives the accumulator
  and the decoded row values and returns the next accumulator, :finish
  (default identity) turns the final accumulator into the SQL result.
  Over zero rows the result is (finish init). nargs as in
  create-function!, opts likewise (:deterministic).

  State lives per group, so group by works. An exception inside step or
  finish becomes a SQL error."
  ([conn name spec] (create-aggregate! conn name -1 spec))
  ([conn name nargs {:keys [init step finish deterministic]}]
   (let [finish (or finish identity)
         states (atom {})
         next-id (atom 0)
         ;; sqlite3_aggregate_context gives an 8-byte per-group slot; it
         ;; holds an id into states, since the accumulator is a Clojure
         ;; value that cannot live in C memory
         slot-id (fn [ctx]
                   (let [slot (c-aggregate-context ctx 8)
                         id (ffi/read slot :int64)]
                     (if (zero? id)
                       (let [id (swap! next-id inc)]
                         (ffi/write slot :int64 id)
                         (swap! states assoc id init)
                         id)
                       id)))
         xstep (ffi/callback
                (fn [ctx argc argv]
                  (try
                    (let [id (slot-id ctx)
                          args (decode-args argc argv)]
                      (swap! states update id #(apply step % args)))
                    (catch Throwable e
                      (c-result-error ctx (str (ex-message e)) -1))))
                [:pointer :int :pointer] :void)
         xfinal (ffi/callback
                 (fn [ctx]
                   (try
                     ;; nbytes 0: only look up the slot. NULL when step
                     ;; never ran (zero rows)
                     (let [slot (c-aggregate-context ctx 0)
                           id (if (ffi/null? slot) 0 (ffi/read slot :int64))
                           acc (if (zero? id) init (get @states id init))]
                       (swap! states dissoc id)
                       (set-result! ctx (finish acc)))
                     (catch Throwable e
                       (c-result-error ctx (str (ex-message e)) -1))))
                 [:pointer] :void)]
     (register! conn name nargs deterministic nil xstep xfinal))))

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
  "Runs a statement. Arguments as in query. Returns {:rows-changed n
  :last-insert-rowid id}."
  [db q]
  (with-conn db (fn [db] (run* db q false))))

(defmacro with-transaction
  "Runs body inside BEGIN IMMEDIATE .. COMMIT on connection db, rolling
  back when body throws. IMMEDIATE takes the write lock up front, so
  concurrent writers wait on the busy timeout instead of deadlocking on a
  lock upgrade. Statements inside the body must use the same connection."
  [db & body]
  `(let [db# ~db]
     (execute! db# "begin immediate")
     (try
       (let [res# (do ~@body)]
         (execute! db# "commit")
         res#)
       (catch Throwable e#
         (execute! db# "rollback")
         (throw e#)))))

(defn interrupt!
  "Interrupts the running query on connection db, from any thread. The
  interrupted query throws."
  [{:keys [db]}]
  (c-interrupt db)
  nil)
