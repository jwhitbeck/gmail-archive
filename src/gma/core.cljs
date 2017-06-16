;; Copyright (c) 2017-2018 John Whitbeck. All rights reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt)
;; which can be found in the file al-v20.txt at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(ns gma.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:refer-clojure :exclude [exists?])
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cljs.core.async :as a :refer [<! >!]]
            [cljs.nodejs :as node]
            [cljs.reader :as edn]
            [cljs.tools.cli :as cli]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cljs-time.format :as tf]
            [cljs-time.periodic :as tp]
            [goog.crypt.base64 :as base64]))

;;; Clojure converience wrappers around node API

(def ^:private fs (node/require "fs"))
(def ^:private path (node/require "path"))
(def ^:private http (node/require "http"))
(def ^:private child-process (node/require "child_process"))
(def ^:private url (node/require "url"))
(def ^:private querystring (node/require "querystring"))
(def ^:private google (.-google (node/require "googleapis")))
(def ^:private auth (node/require "google-auth-library"))

(defn- log [msg]
  (binding [*print-fn* *print-err-fn*]
    (println msg)))

(defn- panic [msg]
  (log msg)
  (.exit js/process 1))

(defn- exists? [p] (.existsSync fs p))

(defn- mkdir! [p] (.mkdirSync fs p))

(defn- join [& ps] (apply (.-join path) ps))

(defn- rm! [f] (.unlinkSync fs f))

(defn- mv! [from to] (.renameSync fs from to))

(defn- spit [f s]
  (let [fd (.openSync fs f "w")]
    (try
      (.writeSync fs fd s)
      (.fsyncSync fs fd)
      (finally
        (.closeSync fs fd)))))

(defn- slurp [f] (.readFileSync fs f "UTF-8"))

(defn- ls [d] (.readdirSync fs d))

(defn- file-seq [d]
  (tree-seq
   #(.isDirectory (.statSync fs %))
   #(map (partial join %) (.readdirSync fs %))
   d))

(defn- basename [p] (.basename path p))

;;; Read/write Google OAuth tokens

(defn- write-tokens! [ct tokens]
  (let [{:keys [credentials-file]} ct]
    (spit credentials-file (js/JSON.stringify tokens))))

(defn- read-tokens [ct]
  (let [{:keys [credentials-file]} ct]
    (if-not (exists? credentials-file)
      (panic (str "Credentials file " credentials-file " does not exists. First run gmail-archive init?"))
      (-> credentials-file slurp js/JSON.parse))))

;;; Read/write gmail-archive configuration

(def ^:private config-keys [:query :after :before :period])

(defn- write-config! [ct]
  (let [{:keys [config-file]} ct]
    (spit config-file (pr-str (reduce (fn [m k] (update m k tc/to-date))
                                      (select-keys ct config-keys)
                                      [:after :before])))))

(defn- read-config [ct]
  (let [{:keys [config-file]} ct]
    (if-not (exists? config-file)
      (panic (str "Config file " config-file " does not exist. First run gmail-archive init?"))
      (reduce (fn [m k] (update m k tc/from-date))
              (-> config-file slurp edn/read-string)
              [:after :before]))))

;;;;;;;;;;;
;; Fetch ;;
;;;;;;;;;;;

;;; Polymorphic functions for handling the different year/month/day breakdowns.

(def ^:private formatters
  {:year (:year tf/formatters)
   :month (tf/formatter "yyyy.MM")
   :day (tf/formatter "yyyy.MM.DD")})

(defmulti date-floor identity)
(defmulti date-ceil identity)
(defmulti interval-count identity)
(defmulti as-interval identity)
(defmulti to-maildir identity)
(defmulti from-maildir identity)

(defmethod date-floor :year [_ dt] (t/date-time (t/year dt)))
(defmethod date-ceil :year [_ dt] (if (t/= dt (date-floor :year dt))
                                    dt
                                    (t/date-time (inc (t/year dt)))))
(defmethod as-interval :year [_ n] (t/years n))
(defmethod interval-count :year [_ i] (t/in-years i))
(defmethod to-maildir :year [_ dt] (tf/unparse (:year formatters) dt))
(defmethod from-maildir :year [_ s] (try (tf/parse (:year formatters) s)
                                         (catch js/Error _)))

(defmethod date-floor :month [_ dt] (t/date-time (t/year dt) (t/month dt)))
(defmethod date-ceil :month [_ dt] (if (t/= dt (date-floor :month dt))
                                     dt
                                     (t/date-time (t/year dt) (inc (t/month dt)))))
(defmethod as-interval :month [_ n] (t/months n))
(defmethod interval-count :month [_ i] (t/in-months i))
(defmethod to-maildir :month [_ dt] (tf/unparse (:month formatters) dt))
(defmethod from-maildir :month [_ s] (try (tf/parse (:month formatters) s)
                                          (catch js/Error _)))

(defmethod date-floor :day [_ dt] (t/date-time (t/year dt) (t/month dt) (t/day dt)))
(defmethod date-ceil :day [_ dt] (if (t/= dt (date-floor :day dt))
                                   dt
                                   (t/date-time (t/year dt) (t/month dt) (inc (t/day dt)))))
(defmethod as-interval :day [_ n] (t/days n))
(defmethod interval-count :day [_ i] (t/in-days i))
(defmethod to-maildir :day [_ dt] (tf/unparse (:day formatters) dt))
(defmethod from-maildir :day [_ s] (try (tf/parse (:day formatters) s)
                                        (catch js/Error _)))

;;; Prepare the Gmail query string.
;;; Search operators you can use with Gmail: https://support.google.com/mail/answer/7190?hl=en

(defn- pad2 [n] (str (when (< n 10) "0") n))

(defn- get-date-query [prefix dt]
  (str prefix ":" (t/year dt) "/" (pad2 (t/month dt)) "/" (pad2 (t/day dt))))

(defn- get-query
  "Returns the Gmail query string."
  [cfg]
  (let [{:keys [query before after]} cfg]
    (->> [query
          ;; Add a day of padding to both 'before' and 'after' because gmail's search API is ambiguous about
          ;; timezones.
          (when before (get-date-query "before" (t/plus (date-ceil :day before) (t/days 1))))
          (when after (get-date-query "after" (t/minus (date-floor :day after) (t/days 1))))
          ;; Exclude google chat messages from queries.
          "-is:chat"]
         (filter some?)
         (str/join " "))))

(defn- msg-ids-ch
  "Returns a channel containing the list of Gmail message ids matching the provided `query`. The ids on the
  channel are subject to transducer `xform`."
  [fc xform query]
  ;; We use three channels in this function:
  ;; - list-ch: stores the response pages returned from the 'list' call to Gmail API.
  ;; - buf-ch: stores list of message ids, one for each page in the response.
  ;; - out-ch: the returned channel containing message ids.
  ;; Having distinct buf-ch and out-ch channels allows this function to fetch the next page of message ids in
  ;; the background before the consumer of out-ch has processed of all its message ids.
  (let [{:keys [gmsgs]} fc
        params (js-obj "userId" "me" ; The user's email address. The special value 'me' can be used to
                                     ; indicate the authenticated user.
                       "q" query)
        list-ch (a/chan)
        buf-ch (a/chan)
        out-ch (a/chan 1 (comp cat xform))]
    (a/pipe buf-ch out-ch)
    (go-loop []
      (.list gmsgs params (fn [err resp]
                            (when err (panic err))
                            (let [data (aget resp "data")]
                              (a/put! list-ch {:next-page-token (aget data "nextPageToken")
                                               :messages (mapv #(aget % "id") (aget data "messages"))}))))
      (let [{:keys [next-page-token messages]} (<! list-ch)]
        (>! buf-ch messages)
        (if-not next-page-token
          (do (a/close! list-ch)
              (a/close! buf-ch))
          (do (aset params "pageToken" next-page-token)
              (recur)))))
    out-ch))

(defn- make-maildir!
  "Ensures that `dir` has a proper maildir structure."
  [dir]
  (doseq [d (cons dir (map (partial join dir) ["cur" "tmp" "new"]))]
    (when-not (exists? d)
      (mkdir! d))))

;;; In email messages, headers can be wrapped over several lines
;;; https://www.w3.org/Protocols/rfc822/3_Lexical.html#z1
(defn- unfold [lines]
  (letfn [(step [cl ls]
            (lazy-seq
             (let [[nl & rls] ls]
               (cond
                 (not nl) [cl]
                 (re-matches #"\s" (.charAt nl 0)) (step (str cl " " (str/triml nl)) rls)
                 :else (cons cl (step nl rls))))))]
    (step (first lines) (rest lines))))

(defn- extract-headers [hdrs msg]
  (->> (str/split-lines msg)
       unfold
       (take-while seq)
       (reduce (fn [m line]
                 (let [[hdr val] (str/split line #": " 2)]
                   (if (and hdr val (hdrs hdr))
                     (assoc m hdr (str/trim val))
                     m)))
               {})))

(def ^:private extract-date
  ;; Lenient regex for RFC822 dates (https://www.w3.org/Protocols/rfc822/#z28)
  (let [rfc822-re #"\d+\s+\w+\s+\d+\s+\d{2}:\d{2}:\d{2}\s+([+-]\d+|\w+)?"
        rfc822-fmt (tf/formatter "dd MMM yyyy HH:mm:ss Z")
        rfc822-fmt-alt (tf/formatter "dd MMM yyyy HH:mm:ss z")]
    (fn [s]
      (let [dt-str (first (re-find rfc822-re s))]
        (loop [fmts [rfc822-fmt rfc822-fmt-alt]]
          (if (empty? fmts)
            (throw (ex-info "Failed to parse date." {:date s}))
            (try
              (tf/parse (first fmts) dt-str)
              (catch js/Error _
                (recur (rest fmts))))))))))

(defn- msg-info
  "Returns a map containing all the required data about the email."
  [id msg file]
  (try (let [hdrs (extract-headers #{"Subject" "From" "Date"} msg)]
         {:id id
          :file file
          :subject (hdrs "Subject")
          :from (hdrs "From")
          :date (extract-date (hdrs "Date"))})
       (catch js/Error e
         (log (str "Error parsing message " id))
         (panic (.message e)))))

(defn- get-in-time-window-fn
  "Returns a function that returns true if the date is between `after` (included) and `before` (excluded)."
  [after before]
  (fn [date]
    (not (or (and before (not (t/before? date before)))
             (and after (t/before? date after))))))

;;; See https://en.wikipedia.org/wiki/Maildir for a full explanation of maildir file suffixes.
;;; Here '2' means maildir suffix version 2, and 'S' means "seen".
(defn- id->filename [id] (str id ":2,S"))

(defn- filename->id [f] (.substr f 0 (- (alength f) 4)))

(defn- get-message-ids
  "Returns the set of all Gmail message ids contained in the maildir `mdir`."
  [mdir]
  (let [cur (join mdir "cur")]
    (if-not (exists? cur)
      #{}
      (->> (file-seq cur)
           rest
           (map (comp filename->id basename))
           set))))

(defn- fetch-msg
  "Downloads message `id` from Gmail to the tmp-dir (or reads it from the tmp-dir if previously downloaded), and
  places it's info map on `ch`."
  [fc id ch]
  (let [{:keys [gmsgs tmp-dir dry-run]} fc]
    (let [tmp-file (join tmp-dir (id->filename id))]
      (if (exists? tmp-file)
        (do (a/put! ch (msg-info id (slurp tmp-file) tmp-file))
            (a/close! ch))
        (.get gmsgs (js-obj "id" id
                            "userId" "me"
                            "format" "raw")
              (fn [err resp]
                (when err (panic err))
                (let [msg (-> resp (aget "data") (aget "raw") (base64/decodeString true))]
                  ;; In a dry-run, don't write the file to disk and pass a nil tmp-file in the msg-info.
                  (when-not dry-run
                    (spit tmp-file msg))
                  (a/put! ch (msg-info id msg (or dry-run tmp-file)))
                  (a/close! ch))))))))

(defn- msgs-info-ch
  "Read message ids from `msg-ids-ch` and return a channel containing the info map of downloaded
  messages (subject to transducer `xform`)."
  [fc xform msg-ids-ch]
  (let [{:keys [concurrency]} fc
        out-ch (a/chan 1 xform)]
    (a/pipeline-async concurrency
                      out-ch
                      (partial fetch-msg fc)
                      msg-ids-ch)
    out-ch))

(defn- summary [msg-info]
  (let [{:keys [date from subject]} msg-info]
    (str (tf/unparse (:date-time-no-ms tf/formatters) date) " <" from ">: " subject)))

;;; Simple sync

(defn- sync-simple!
  "Performs a simple sync between a maildir with no yearly, monthly, or daily sub-maildirs and the configured
  Gmail query. Returns a channel that closes once the sync is complete."
  [fc cfg]
  (let [{:keys [dir verbose]} fc
        {:keys [before after]} cfg
        previously-fetched-ids (get-message-ids dir)
        msg-ids (msg-ids-ch fc (remove previously-fetched-ids) (get-query cfg))
        msgs-info (msgs-info-ch fc (filter (comp (get-in-time-window-fn after before) :date)) msg-ids)]
    (make-maildir! dir)
    (go-loop [{:keys [id file] :as msg-info} (<! msgs-info)]
      (when msg-info
        (when verbose
          (log (summary msg-info)))
        (when file
          ;; Atomically write the email to the appropriate maildir.
          (mv! file (join dir "cur" (id->filename id))))
        (recur (<! msgs-info))))))

;;; Periodic sync

(defn- sync-period!
  "Performs a sync between a maildir with yearly, monthly, or daily sub-maildirs and the configured Gmail query
  with no yearly, monthly, or daily sub-maildirs. Only fetches the emails between start-date and
  end-date. Each downloaded email is moved to the appropriate maildir based on its 'Date' header."
  [fc cfg in-time-window? previously-fetched-ids start-date end-date]
  (let [{:keys [dir verbose]} fc
        {:keys [period]} cfg
        query (get-query (assoc cfg :after start-date :before end-date))
        msg-ids (msg-ids-ch fc (remove previously-fetched-ids) query)
        xform (filter (comp in-time-window? :date))
        msgs-info (msgs-info-ch fc xform msg-ids)]
    (go-loop [{:keys [id date file] :as msg} (<! msgs-info)]
      (when msg
        (when verbose
          (log (summary msg)))
        (when (and file
                   ;; Emails after the period remain in the tmp-dir and will be moved when their period
                   ;; is subsequently synced. We do this to avoid the situation where given consecutive
                   ;; periods P1 < P2, a sync of P1 is interrupted but we fetched a few messages for P2 due to
                   ;; Gmail's ambiguous 'before' queries, and therefore created the P2 maildir. The subsequent
                   ;; fetch will start at P2, which risks skipping some emails from P1.
                   (t/before? date end-date))
          (let [mdir (join dir (to-maildir period date))]
            ;; Atomically write the email to the appropriate yearly, monthly, or daily maildir.
            (make-maildir! mdir)
            (mv! file (join mdir "cur" (id->filename id)))))
        (recur (<! msgs-info))))))

;;; GMail's API returns emails in reverse chronological order so there is no easy way to find the date of the
;;; first email matching a query. When the user hasn't provided an 'after', we perform the following steps to
;;; find the earliest period containing a message:
;;;  1. Repeatedly double how far back in time we query until we get an empty result. This provides both an
;;;     upper and a lower bound on the first period with a message.
;;;  2. Perform a binary search to find the exact earliest period with a message.

(defn- has-message-before?
  "Returns a channel that contains true or false depending on whether or not there is a before `dt` the matches
  the provided `query`."
  [fc query dt]
  (let [{:keys [gmsgs]} fc
        ch (a/chan)
        params (js-obj "userId" "me"
                       "q" (str query " " (get-date-query "before" (date-floor :day dt)))
                       "maxResults" 1)]
    (.list gmsgs params (fn [err resp]
                          (when err (panic err))
                          (a/put! ch (boolean (seq (-> resp (aget "data") (aget "messages")))))
                          (a/close! ch)))
    ch))

(defn- geometric-search-interval
  "Returns a channel contain a [lower-bound upper-bound] pair for the earliest period with results for the
  `query`."
  [fc query period]
  (let [{:keys [now]} fc
        end-dates (map #(t/minus now (as-interval period %)) (iterate (partial * 2) 1))]
    (go-loop [intervals (map vector (rest end-dates) end-dates)]
      (let [[begin end] (first intervals)]
        (if (<! (has-message-before? fc query begin))
          (recur (next intervals))
          [begin end])))))

(defn- binary-search-period
  "Returns a channel containing the earliest period with results for `query` between the `begin` and `end`
  periods."
  [fc query period begin end]
  (let [n (interval-count period (t/interval begin end))]
    (go-loop [a 0 b n]
      (cond
        (= a b) (t/plus begin (as-interval period a))
        (= (inc a) b) (if (<! (has-message-before? fc query (t/plus begin (as-interval period a))))
                        (t/plus begin (as-interval period b))
                        (t/plus begin (as-interval period a)))
        :else (let [c (quot (+ a b) 2)]
                (if (<! (has-message-before? fc query (t/plus begin (as-interval period c))))
                  (recur a c)
                  (recur c b)))))))

(defn- find-oldest-period [fc query period]
  (go (let [[begin end] (<! (geometric-search-interval fc query period))]
        (date-floor period (<! (binary-search-period fc query period begin end))))))

(defn- list-fetched-periods-sorted [period dir]
  (->> (ls dir)
       (keep (partial from-maildir period))
       (sort-by identity t/before?)))

(defn- tmax
  "Like max but for dates."
  [dt-a dt-b]
  (if (t/before? dt-a dt-b)
    dt-b
    dt-a))

(defn- sync-periodic!
  "Performs a periodic sync between a maildir with yearly, monthly, or daily sub-maildirs and the configured
  Gmail query. Returns a channel that closes once the sync is complete."
  [fc cfg]
  (go (let [{:keys [full since re-sync dir verbose]} fc
            {:keys [period query after before]} cfg
            fetched-periods (list-fetched-periods-sorted period dir)
            start-period (cond full (or after (<! (find-oldest-period fc query period)))
                               since (cond-> since after (tmax after))
                               (seq fetched-periods) (cond-> (last fetched-periods)
                                                       re-sync (t/minus (as-interval period re-sync)))
                               after after
                               :else (<! (find-oldest-period fc query period)))
            end-period (cond before (date-ceil period before)
                             :else (date-ceil period (t/now)))
            one-period (as-interval period 1)
            periods (tp/periodic-seq (t/minus start-period one-period) (t/plus end-period one-period) one-period)
            period-strides (map vector
                                periods
                                (next periods)
                                (next (next periods)))
            in-time-window? (get-in-time-window-fn after before)]
        (doseq [[prev cur nxt :as pers] period-strides]
          ;; Because Gmail's API is ambiguous on date boundaries in results, it is possible to fetch emails
          ;; from the previous or next period. To avoid downloading them multiple time, we consider the
          ;; neighboring periods when retrieving the previously fetched message ids.
          (when verbose
            (log (str "Sync " (tf/unparse (:date tf/formatters) cur))))
          (let [previously-fetched-ids (->> pers
                                            (filter some?)
                                            (map #(join dir (to-maildir period %)))
                                            (map get-message-ids)
                                            (reduce set/union))
                start-date (if (and after (t/before? cur after)) after cur)
                end-date (if (and before (t/after? nxt before)) before nxt)]
            (<! (sync-period! fc cfg in-time-window? previously-fetched-ids start-date end-date)))))))

(defn- fetch [fc]
  (let [{:keys [client-id client-secret tmp-dir]} fc
        {:keys [period] :as cfg} (read-config fc)
        tokens (read-tokens fc)
        auth (doto (auth.OAuth2Client. client-id client-secret)
               (.setCredentials tokens))
        gmail (.gmail google (js-obj "version" "v1" "auth" auth))
        fc (assoc fc :gmsgs (.. gmail -users -messages))]
    ;; Initialize the temporary directory in which we write emails before moving them to the appropriate
    ;; maildir.
    (if-not (exists? tmp-dir)
      (mkdir! tmp-dir)
      (doseq [file (rest (file-seq tmp-dir))]
        (rm! file)))
    (if (not period)
      (sync-simple! fc cfg)
      (sync-periodic! fc cfg))))

;;;;;;;;;;
;; Init ;;
;;;;;;;;;;

;;; Google OAUTH2 workflow: https://developers.google.com/identity/protocols/OAuth2InstalledApp
;;; 1. Get an auth code
;;; 2. Use it to retrieve an access token and refresh token pair

(defn- get-new-tokens [ic]
  (let [{:keys [client-id client-secret scope open]} ic
        code-ch (a/chan)
        close-ch (a/chan)
        handler (fn [req resp]
                  (a/close! close-ch)
                  (let [qs (.-query (.parse url (.-url req)))
                        code (aget (.parse querystring qs) "code")]
                    (when code
                      (a/put! code-ch code)
                      (a/close! code-ch)
                      (.write resp "This window may be safely closed.")
                      (.end resp))))
        server (doto (.createServer http handler) .listen)
        uri (str "http://localhost:" (.-port (.address server)))
        oauth2-client (auth.OAuth2Client. client-id client-secret uri)
        oauth2-url (.generateAuthUrl oauth2-client (js-obj "access_type" "offline" "scope" scope))]
    (.spawn child-process open (into-array [oauth2-url]))
    (go (<! close-ch) (.close server))
    (go (let [ch (a/chan)
              code (<! code-ch)]
          (.getToken oauth2-client code (fn [err tokens]
                                          (when err (panic err))
                                          (a/put! ch tokens)
                                          (a/close! ch)))
          (<! ch)))))

(defn- init [ic]
  (let [{:keys [dir gma-dir]} ic]
    (when (exists? dir)
      (panic (str "Directory " dir " already exists.")))
    (mkdir! dir)
    (mkdir! gma-dir)
    (go (write-tokens! ic (<! (get-new-tokens ic)))
        (write-config! ic))))

;;; CLI interface

(defn- with-context [options]
  (let [dir (:dir options (.cwd js/process))
        gma-dir (join dir ".gma")]
    (assoc options
           :now (t/now)
           :dir dir
           :open "xdg-open"
           :client-id "667695553924-8q43olovnq1av8v6kgn3lbj1qcj4jbrn.apps.googleusercontent.com"
           :client-secret "_VprwQkUIK1aU5BpC1Gyapuv"
           :scope "https://www.googleapis.com/auth/gmail.readonly"
           :gma-dir gma-dir
           :tmp-dir (join gma-dir "tmp")
           :credentials-file (join gma-dir "credentials.json")
           :config-file (join gma-dir "config.edn"))))

(defn- parse-cli-date [s]
  (or (tf/parse s) (panic (str "Could not parse date: " s))))

(def ^:private fetch-cli-spec
  [["-h" "--help" "Display help."]
   [nil "--concurrency N" "Download N emails in parallel." :default 10 :parse-fn #(js/parseInt %)]
   [nil "--verbose" "If passed, print the sender and subject of each fetched message."]
   [nil "--dir DIR" "Use this directory as the root of the maildir hierarchy. Defaults to current dir."]
   [nil "--dry-run" "Display subjects of emails that would be downloaded, but don't actually download them."]
   [nil "--full" "Perform a full sync, re-checking all previously completed periods."]
   [nil "--re-sync N"
    (str "Fully re-sync the last N previously completed periods, before proceeding with the in-progress "
         "period. Useful if some older emails were previously excluded from the archive (e.g., if the "
         "query excludes 'Inbox' labels). ")]
   [nil "--since DATE"
    (str "Start the sync at this date, fully re-sync previously completed periods after this date before "
         "proceeding with the in-progress period.")
    :parse-fn parse-cli-date]])

(defn- fetch-main [args]
  (let [{:keys [summary options arguments]} (cli/parse-opts args fetch-cli-spec)]
    (if (:help options)
      (panic (str "Usage: gmail-archive fetch [OPTIONS]\n\n" summary))
      (fetch (with-context options)))))

(defn- parse-period [p]
  (let [kp (keyword p)]
    (when-not (#{:year :month :day} kp)
      (panic (str "Invalid period: " p)))
    kp))

(def ^:private init-cli-spec
  [["-h" "--help" "Display help."]
   [nil "--dir DIR" "Use this directory as the root of the maildir hierarchy. Defaults to current dir."]
   [nil "--query QUERY"
    (str "Only archive emails matching this GMail query "
         "(see https://support.google.com/mail/answer/7190?hl=en for a walkthrough of the query syntax).")]
   [nil "--after DATE"
    "Only archive emails after this date (UTC - RFC 33991). Examples: 2017-04-03, 2017-04-03T12:01"
    :parse-fn parse-cli-date]
   [nil "--before DATE"
    "Only archive emails before this date (UTC - RFC 33991). Examples: 2017-04-03, 2017-04-03T12:01"
    :parse-fn parse-cli-date]
   [nil "--period period" "If provided either year, month, or day."
    :parse-fn parse-period]])

(defn- init-main [args]
  (let [{:keys [summary options arguments]} (cli/parse-opts args init-cli-spec)]
    (if (:help options)
      (panic (str "Usage: gmail-archive init [options]\n\n" summary))
      (init (with-context options)))))

(defn -main [& args]
  (case (first args)
    "init" (init-main (rest args))
    "fetch" (fetch-main (rest args))
    (panic (str/join "\n" ["Usage: gmail-archive <action> [options]"
                           ""
                           "Where <action> is one of: init, fetch."
                           "For help on an action, run: gmail-archive <action> --help"]))))

(node/enable-util-print!)
(set! *main-cli-fn* -main)
