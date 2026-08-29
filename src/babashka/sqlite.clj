(ns babashka.sqlite
  "Use SQLite from babashka through babashka.ffi. Pass SQL as a string or
  as a [sql & params] vector:

      (require '[babashka.sqlite3 :as sq])
      (sq/query \"app.db\" [\"select * from users where name = ?\" \"rich\"])

  A string file path opens and closes a connection for each call. nil uses an
  in-memory database. Keep one connection open for multiple operations:

      (sq/with-db [db \"app.db\"]
        (sq/execute! db \"create table t (i integer, s text)\")
        (sq/query db \"select * from t\"))

  query returns a vector of row maps with keyword column names. Result values
  use longs, doubles, strings, byte arrays, and nil for the corresponding
  SQLite storage classes."
  (:require [babashka.ffi :as ffi :refer [defcfn]]))

(ffi/load-library {:mac "libsqlite3.dylib"
                   :linux "libsqlite3.so.0"
                   ;; winsqlite3.dll ships with Windows itself
                   :windows ["sqlite3.dll" "winsqlite3.dll"]})

(defcfn ^:private c-initialize "sqlite3_initialize" [] :int)
(defcfn ^:private c-close "sqlite3_close" [:pointer] :int)
(defcfn ^:private c-errmsg "sqlite3_errmsg" [:pointer] :string)
(defcfn ^:private c-busy-timeout "sqlite3_busy_timeout" [:pointer :int] :int)
(defcfn ^:private c-prepare "sqlite3_prepare_v2" [:pointer :string :int :pointer :pointer] :int)
(defcfn ^:private c-finalize "sqlite3_finalize" [:pointer] :int)
(defcfn ^:private c-step "sqlite3_step" [:pointer] :int)
(defcfn ^:private c-changes "sqlite3_changes" [:pointer] :int)
(defcfn ^:private c-last-insert-rowid "sqlite3_last_insert_rowid" [:pointer] :int64)
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
;; call, because the buffers babashka.ffi passes are freed when it returns.
;; It is the pseudo pointer (void*)-1, not a real address, so it is written
;; as a raw one
(def ^:private sqlite-transient (ffi/segment -1))

(defcfn version
  "Returns the SQLite library version."
  "sqlite3_libversion" [] :string)

;; builds compiled with SQLITE_OMIT_AUTOINIT (common for standalone
;; Windows dlls) crash in sqlite3_open unless the library is initialized
(defonce ^:private initialized (delay (c-initialize)))

(def ^:private SQLITE-OPEN-READONLY 0x1)
(def ^:private SQLITE-OPEN-READWRITE 0x2)
(def ^:private SQLITE-OPEN-CREATE 0x4)

(defn open
  "Opens a SQLite connection. A nil path creates an in-memory database for
  this connection. A file path opens or creates a database.

  Use the connection from one thread at a time. The connection has a five-second
  busy timeout. The :read-only option opens an existing database without write
  access. The :flags option sets raw SQLITE_OPEN_* flags instead. Returns a
  connection for use with query, execute!, create-function!, create-aggregate!,
  and close!."
  ([path] (open path nil))
  ([path {:keys [read-only flags]}]
   @initialized
   (with-open [arena (ffi/confined-arena)]
     (let [pdb (ffi/alloc arena :pointer)
           flags (or flags
                     (if read-only
                       SQLITE-OPEN-READONLY
                       (bit-or SQLITE-OPEN-READWRITE SQLITE-OPEN-CREATE)))
           rc (c-open-v2 (or path ":memory:") pdb flags ffi/null)
           db (ffi/read pdb :pointer)]
       (when-not (zero? rc)
         (let [msg (c-errmsg db)]
           (c-close db)
           (throw (ex-info (str "sqlite3: " msg) {:path path}))))
       (c-busy-timeout db 5000)
       ;; one shared arena per registered function: sqlite calls a callback
       ;; on whichever thread runs the statement, so it cannot be confined,
       ;; and a registration that fails closes its own arena instead of
       ;; leaving the stubs behind. close! closes whatever is left.
       {:db db :arenas (atom [])}))))

(defn close!
  "Closes a connection from open and releases its registered functions.
  Returns nil."
  [{:keys [db arenas]}]
  (c-close db)
  (when arenas
    ;; releases every callback that create-function! and create-aggregate!
    ;; allocated
    (doseq [a @arenas] (.close ^java.lang.AutoCloseable a))
    (reset! arenas []))
  nil)

(defmacro with-db
  "Opens a database for the enclosed code. Closes the connection after the
  code finishes. Returns the result of the enclosed code. Use nil for an
  in-memory database.

      (with-db [db \"app.db\"]
        (query db \"select * from users\"))"
  [[sym path] & body]
  `(let [~sym (open ~path)]
     (try ~@body
          (finally (close! ~sym)))))

(defn- blob-bytes
  "Copies the n-byte blob at pointer p into a byte array. sqlite gives a
  NULL pointer for an empty blob."
  ^bytes [n p]
  (if (pos? n)
    ;; a pointer from C has size 0; give it the blob's size first
    (ffi/read-bytes (ffi/reinterpret p n) n)
    (byte-array 0)))

;; sqlite types values per cell, not per column
(defn- column-value [stmt i]
  (case (c-column-type stmt i)
    1 (c-column-int64 stmt i)
    2 (c-column-double stmt i)
    3 (c-column-text stmt i)
    4 (blob-bytes (c-column-bytes stmt i) (c-column-blob stmt i))
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
               (bytes? v) (with-open [arena (ffi/confined-arena)]
                            (let [n (alength ^bytes v)
                                  p (ffi/alloc arena (max n 1))]
                              (ffi/write-bytes p v)
                              (c-bind-blob stmt i p n sqlite-transient)))
               :else (throw (ex-info (str "sqlite3: cannot bind " (type v))
                                     {:value v})))]
      (when-not (zero? rc)
        (throw (ex-info (str "sqlite3: " (c-errmsg db)) {:sql sql :param v}))))))

(def ^:private SQLITE-ROW 100)
(def ^:private SQLITE-DONE 101)

(defn- run* [conn q collect-rows?]
  (with-open [arena (ffi/confined-arena)]
    (let [[sql & params] (if (string? q) [q] q)
          db (:db conn)
          pstmt (ffi/alloc arena :pointer)]
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
          (c-finalize (ffi/read pstmt :pointer)))))))

(def ^:private SQLITE-UTF8 1)
(def ^:private SQLITE-DETERMINISTIC 0x800)

(defn- decode-value [pv]
  (case (c-value-type pv)
    1 (c-value-int64 pv)
    2 (c-value-double pv)
    3 (c-value-text pv)
    4 (blob-bytes (c-value-bytes pv) (c-value-blob pv))
    5 nil))

(defn- set-result! [ctx v]
  (cond
    (nil? v) (c-result-null ctx)
    (boolean? v) (c-result-int64 ctx (if v 1 0))
    (integer? v) (c-result-int64 ctx v)
    (float? v) (c-result-double ctx v)
    (string? v) (c-result-text ctx v -1 sqlite-transient)
    (bytes? v) (with-open [arena (ffi/confined-arena)]
                 (let [n (alength ^bytes v)
                       p (ffi/alloc arena (max n 1))]
                   (ffi/write-bytes p v)
                   (c-result-blob ctx p n sqlite-transient)))
    :else (c-result-error ctx (str "cannot return " (type v) " from a function") -1)))

(defn- decode-args [argc argv]
  ;; argv comes from C with size 0; it holds argc pointers
  (let [argv (ffi/reinterpret argv (* 8 argc))]
    (mapv (fn [i] (decode-value (ffi/read argv :pointer (* 8 i))))
          (range argc))))

(defn- register! [{:keys [db arenas] :as conn} arena name nargs deterministic xfunc xstep xfinal]
  (let [rc (c-create-function db name nargs
                              (cond-> SQLITE-UTF8 deterministic (bit-or SQLITE-DETERMINISTIC))
                              ffi/null
                              (or xfunc ffi/null)
                              (or xstep ffi/null)
                              (or xfinal ffi/null)
                              ffi/null)]
    (when-not (zero? rc)
      ;; sqlite did not take the callbacks, so nothing can call them
      (.close ^java.lang.AutoCloseable arena)
      (throw (ex-info (str "sqlite3: " (c-errmsg db)) {:function name})))
    (swap! arenas conj arena)
    conn))

(defn create-function!
  "Registers f on conn as a SQL function. SQL calls the function by name.
  Returns conn.

  f receives longs, doubles, strings, byte arrays, and nil. It can return these
  values or a boolean. An exception from f becomes a SQL error.

  Pass nargs before f to require a fixed argument count. If you omit nargs,
  the function accepts any number of arguments.

  The :deterministic option declares that f always returns the same result for
  the same arguments. SQLite can then cache or optimize calls. The registration
  lasts until close! closes conn."
  ([conn name f] (create-function! conn name -1 f nil))
  ([conn name nargs-or-f f-or-opts]
   (if (fn? nargs-or-f)
     (create-function! conn name -1 nargs-or-f f-or-opts)
     (create-function! conn name nargs-or-f f-or-opts nil)))
  ([conn name nargs f {:keys [deterministic]}]
   (let [arena (ffi/shared-arena)
         xfunc (ffi/callback
                arena
                (fn [ctx argc argv]
                  ;; an exception crossing the C boundary would abort the
                  ;; process: everything is caught and reported to sqlite
                  (try
                    (set-result! ctx (apply f (decode-args argc argv)))
                    (catch Throwable e
                      (c-result-error ctx (str (ex-message e)) -1))))
                [:pointer :int :pointer] :void)]
     (register! conn arena name nargs deterministic xfunc nil nil))))

(defn create-aggregate!
  "Registers a reduce-style aggregate on conn. SQL calls the aggregate by name.
  Returns conn.

      (create-aggregate! db \"product\"
        {:init 1
         :step (fn [acc v] (* acc v))})

  :init is the initial accumulator value. :step receives the accumulator and
  the values from one row. It returns the next accumulator. :finish converts
  the final accumulator to the SQL result. Its default is identity.

  For zero rows, the :finish function receives the :init value. Pass the
  required argument count before spec. If you omit the count, the aggregate
  accepts any number of arguments. :deterministic has the same meaning as in
  create-function!.

  The aggregate keeps separate state for each group. An exception from :step
  or :finish becomes a SQL error."
  ([conn name spec] (create-aggregate! conn name -1 spec))
  ([conn name nargs {:keys [init step finish deterministic]}]
   (let [arena (ffi/shared-arena)
         finish (or finish identity)
         states (atom {})
         next-id (atom 0)
         ;; sqlite3_aggregate_context gives an 8-byte per-group slot; it
         ;; holds an id into states, since the accumulator is a Clojure
         ;; value that cannot live in C memory
         slot-id (fn [ctx]
                   (let [slot (ffi/reinterpret (c-aggregate-context ctx 8) 8)
                         id (ffi/read slot :int64)]
                     (if (zero? id)
                       (let [id (swap! next-id inc)]
                         (ffi/write slot :int64 id)
                         (swap! states assoc id init)
                         id)
                       id)))
         xstep (ffi/callback
                arena
                (fn [ctx argc argv]
                  (try
                    (let [id (slot-id ctx)
                          args (decode-args argc argv)]
                      (swap! states update id #(apply step % args)))
                    (catch Throwable e
                      (c-result-error ctx (str (ex-message e)) -1))))
                [:pointer :int :pointer] :void)
         xfinal (ffi/callback
                 arena
                 (fn [ctx]
                   (try
                     ;; nbytes 0: only look up the slot. NULL when step
                     ;; never ran (zero rows)
                     (let [slot (c-aggregate-context ctx 0)
                           id (if (ffi/null? slot) 0 (ffi/read (ffi/reinterpret slot 8) :int64))
                           acc (if (zero? id) init (get @states id init))]
                       (swap! states dissoc id)
                       (set-result! ctx (finish acc)))
                     (catch Throwable e
                       (c-result-error ctx (str (ex-message e)) -1))))
                 [:pointer] :void)]
     (register! conn arena name nargs deterministic nil xstep xfinal))))

(defn- with-conn [db-or-path f]
  (if (map? db-or-path)
    (f db-or-path)
    (with-db [db db-or-path] (f db))))

(defn query
  "Runs a query and returns a vector of maps. Each map is one result row.
  Column names are keywords. SQL NULL values become nil.

  db can be a connection from open, a database file path, or nil. q can be a
  SQL string or a vector. The vector starts with SQL. Each ? in SQL uses the
  next value in the vector. A file path or nil opens and closes a connection
  for this call."
  [db q]
  (with-conn db (fn [db] (run* db q true))))

(defn execute!
  "Runs a statement and returns {:rows-changed n :last-insert-rowid id}.

  :rows-changed is the number of rows that the statement changed.
  :last-insert-rowid is the row ID from the most recent insert on the
  connection. db and q accept the same values as query."
  [db q]
  (with-conn db (fn [db] (run* db q false))))

(defmacro with-transaction
  "Evaluates body in an immediate transaction on db. Commits when body returns
  and returns its result. Rolls back when body throws.

  An immediate transaction takes the write lock before body starts. The
  transaction owns db until body finishes. All statements in body must use db."
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

(defcfn interrupt!
  "Interrupts the running query on db. Call this function from another thread.
  The interrupted query throws an exception. Returns nil."
  "sqlite3_interrupt" [:pointer] :void
  interrupt-native
  [{:keys [db]}]
  (interrupt-native db)
  nil)
