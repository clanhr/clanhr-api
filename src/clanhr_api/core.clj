(ns clanhr-api.core
  "Manages and performs requests to ClanHR's API"
  (:require [environ.core :refer [env]]
            [clojure.core.async :refer [chan <!! >!! close! go]]
            [manifold.deferred :as d]
            [aleph.http :as http]
            [cheshire.core :as json]
            [result.core :as result]
            [clanhr.analytics.errors :as errors]))

(defn client
  "Creates configuration for executing requests"
  [opts]
  (merge opts
         {:live true
          :directory-api (or (env :clanhr-directory-api) "http://directory.api.staging.clanhr.com")
          :absences-api (or (env :clanhr-absences-api) "http://absences.api.staging.clanhr.com")
          :notifications-api (or (env :clanhr-notifications-api) "http://notifications.api.staging.clanhr.com")
          :http-opts {:connection-timeout 1000
                      :request-timeout 1000}}))

(defn- prepare-response
  "Handles post-response"
  [response]
  (-> response
      (assoc :data (json/parse-string (slurp (:body response)) true))))

(defn- fetch-response
  "Fetches the response for a given URL"
  [client data]
  (try
    (let [result-ch (chan 1)
          async-stream (http/get (:url data) (:http-ops client))]
      (d/on-realized async-stream
                     (fn [x]
                       (if x
                         (>!! result-ch (result/success (prepare-response x)))
                         (>!! result-ch (result/failure (prepare-response x))))
                       (close! result-ch))
                     (fn [x]
                       (>!! result-ch (result/failure x))
                       (close! result-ch)))
      result-ch)
    (catch Exception e
      (go (errors/exception e)))))

(defn- prepare-data
  "Builds data from data"
  [client data]
  (let [host (get client (:service data))
        url (str host (:path data))]
    (assoc data :host host
                :url url)))

(defn http-get
  "Makes a GET request to the given API"
  [client data]
  (let [result-ch (fetch-response client (prepare-data client data))]
    result-ch))
