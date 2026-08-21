(require '[babashka.ffi :as ffi])
(prn :lib (ffi/load-library ["sqlite3.dll" "winsqlite3.dll"]))
(prn :libversion-addr (ffi/find-symbol "sqlite3_libversion"))
(prn :open-addr (ffi/find-symbol "sqlite3_open"))
(prn :open-v2-addr (ffi/find-symbol "sqlite3_open_v2"))
(prn :create-fn-v2-addr (ffi/find-symbol "sqlite3_create_function_v2"))
(prn :aggregate-ctx-addr (ffi/find-symbol "sqlite3_aggregate_context"))
(prn :missing-addr (ffi/find-symbol "definitely_not_a_symbol_xyz"))
(prn :version ((ffi/cfn "sqlite3_libversion" [] :string)))
(prn :open-rc
     (let [f (ffi/cfn "sqlite3_open" [:string :pointer] :int)
           pdb (ffi/alloc 8)]
       (try (f ":memory:" pdb) (finally (ffi/free pdb)))))
