(set-env! :dependencies '[[org.clojure/clojure "1.9.0"]
                          [org.clojure/clojurescript "1.9.946"]
                          [org.clojure/core.async "0.3.465"]
                          [org.clojure/tools.cli "0.3.5"]
                          [cljsjs/nodejs-externs "1.0.4-1"]
                          [adzerk/boot-cljs "2.1.4"]
                          [com.cemerick/piggieback "0.2.2"]
                          [org.clojure/tools.nrepl "0.2.13"]
                          [com.andrewmcveigh/cljs-time "0.5.2"]]
          :source-paths #{"src"})

(require 'boot.repl
         '[adzerk.boot-cljs :refer [cljs]]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell])

(task-options!
 cljs {:compiler-options {:target :nodejs
                          :optimizations :advanced
                          :externs ["src/externs/google.js"]}})

(swap! boot.repl/*default-middleware* conj 'cemerick.piggieback/wrap-cljs-repl)

(deftask npm
  "Install NPM dependencies."
  []
  (println (:out (shell/sh "npm" "install")))
  identity)

(deftask rename
  []
  (with-post-wrap _
    (.renameTo (io/file "target" "main.js") (io/file "gmail-archive.js"))))

(deftask build
  "Build gmail-archive cli app."
  []
  (comp (npm) (cljs) (target) (rename)))
