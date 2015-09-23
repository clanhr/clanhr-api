(ns clanhr-api.core
  "Manages and performs requests to ClanHR's API"
  (:require [environ.core :refer [env]]
            [clojure.core.async :refer [chan <!! >!! close! go]]
            [manifold.deferred :as d]
            [aleph.http :as http]
            [result.core :as result]
            [clanhr.analytics.errors :as errors]))

(defn client
  "Creates configuration for executing requests"
  [opts]
  (merge opts
         {:live true
          :directory-api (env :clanhr-directory-api-endpoint)
          :http-opts {:connection-timeout 1000
                      :request-timeout 1000}}))

(defn- fetch-response
  "Fetches the response for a given URL"
  [client data]
  (try
    (let [result-ch (chan)
          async-stream (http/get (:url data) (:http-ops client))]
      (d/on-realized async-stream
                     (fn [x]
                       (println "success")
                       (if x
                         (>!! result-ch (result/success x))
                         (>!! result-ch (result/failure x)))
                       (close! result-ch))
                     (fn [x]
                       (println "error")
                       (>!! result-ch (result/failure x))
                       (close! result-ch)))
      result-ch)
    (catch Exception e
      (go (errors/exception e)))))

(defn- prepare-data
  "Builds data from data"
  [client data]
  data)

(defn http-get
  "Makes a GET request to the given API"
  [client data]
  (let [result-ch (fetch-response client (prepare-data client data))]
    result-ch))
