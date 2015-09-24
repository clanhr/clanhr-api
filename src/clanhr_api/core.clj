(ns clanhr-api.core
  "Manages and performs requests to ClanHR's API"
  (:require [environ.core :refer [env]]
            [clojure.core.async :refer [chan <!! >!! close! go]]
            [manifold.deferred :as d]
            [aleph.http :as http]
            [cheshire.core :as json]
            [result.core :as result]
            [clanhr.analytics.errors :as errors]))

(defn- setup
  "Creates configuration for executing requests"
  [opts]
  (merge opts
         {:directory-api (or (env :clanhr-directory-api) "http://directory.api.staging.clanhr.com")
          :absences-api (or (env :clanhr-absences-api) "http://absences.api.staging.clanhr.com")
          :notifications-api (or (env :clanhr-notifications-api) "http://notifications.api.staging.clanhr.com")
          :http-opts {:connection-timeout 1000
                      :request-timeout 1000}}))

(defn- prepare-response
  "Handles post-response"
  [response]
  (try
    (-> response
        (assoc :status (:status response))
        (assoc :data (json/parse-string (slurp (:body response)) true)))
    (catch Exception e
      (errors/exception e))))

(defn- prepare-error
  "Handles post-response errors"
  [response]
  (try
    (if (instance? Throwable response)
      (do
        {:message (.getMessage response)
         :data (.getData response)
         :body (json/parse-string (slurp (:body (.getData response))) true)})
      (-> response
          (assoc :status (-> response :data :cause))
          (assoc :data (slurp (-> response :data :body)))))
    (catch Exception e
      (errors/exception e))))

(defn- fetch-response
  "Fetches the response for a given URL"
  [data]
  (try
    (let [result-ch (chan 1)
          async-stream ((:method-fn data) (:url data) (:http-opts data))]
      (d/on-realized async-stream
                     (fn [x]
                       (if x
                         (>!! result-ch (result/success (prepare-response x)))
                         (>!! result-ch (result/failure (prepare-response x))))
                       (close! result-ch))
                     (fn [x]
                       (>!! result-ch (result/failure (prepare-error x)))
                       (close! result-ch)))
      result-ch)
    (catch Exception e
      (go (errors/exception e)))))

(defn- authentify
  "Builds proper auth headers"
  [data http-opts]
  (if-let [token (:token data)]
    (assoc http-opts :headers {"x-clanhr-auth-token" token})
    http-opts))

(defn- add-body
  "Adds proper body to be sent"
  [http-opts data]
  (if-let [body (:body data)]
    (assoc http-opts :body (if (map? body) (json/generate-string body) body))
    http-opts))

(defn- prepare-data
  "Builds data from data"
  [data method]
  (let [data (setup data)
        host (get data (:service data))
        url (str host (:path data))
        http-opts (-> (authentify data (:http-opts data))
                      (add-body data))]
    (assoc data :host host
                :url url
                :http-opts http-opts
                :method-fn (cond
                             (= :post method) http/post
                             (= :put method) http/put
                             :else http/get))))

(defn http-get
  "Makes a GET request to the given API"
  [data]
  (fetch-response (prepare-data data :get)))

(defn http-post
  "Makes a POST request to the given API"
  [data]
  (fetch-response (prepare-data data :post)))

(defn http-put
  "Makes a PUT request to the given API"
  [data]
  (fetch-response (prepare-data data :put)))
