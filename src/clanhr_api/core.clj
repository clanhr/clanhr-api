(ns clanhr-api.core
  "Manages and performs requests to ClanHR's API"
  (:require [environ.core :refer [env]]
            [clojure.core.async :refer [chan <!! >!! close! go]]
            [manifold.deferred :as d]
            [aleph.http :as http]
            [cheshire.core :as json]
            [result.core :as result]
            [clanhr.analytics.errors :as errors]
            [clanhr.analytics.metrics :as metrics]))

(def ^:dynamic *default-timeout* (Integer/parseInt
                                   (or (env :clanhr-internal-api-timeout)
                                       (str (* 10 1000)))))

(defn- setup
  "Creates configuration for executing requests"
  [opts]
  (merge opts
         {:directory-api (or (env :clanhr-directory-api) "http://directory.api.staging.clanhr.com")
          :absences-api (or (env :clanhr-absences-api) "http://absences.api.staging.clanhr.com")
          :notifications-api (or (env :clanhr-notifications-api) "http://notifications.api.staging.clanhr.com")
          :http-opts {:connection-timeout *default-timeout*
                      :request-timeout *default-timeout*}}))

(defn- track-api-response
  "Register metrics"
  [data response]
  (metrics/api-request (or (env :clanhr-env) "test")
                       (:service data)
                       (:request-time response)
                       data
                       response)
  response)

(defn- prepare-response
  "Handles post-response"
  [data response]
  (try
    (track-api-response data response)
    (-> response
        (assoc :status (:status response))
        (assoc :data (json/parse-string (slurp (:body response)) true)))
    (catch Exception e
      (errors/exception e))))

(defn- prepare-error
  "Handles post-response errors"
  [data response]
  (try
    (cond
      (instance? java.util.concurrent.TimeoutException response)
        (do
          (track-api-response data
            {:status 408
             :request-time (-> data :http-opts :request-timeout)
             :body {:message "Timed out"}}))
      (instance? clojure.lang.ExceptionInfo response)
        (do
          (track-api-response data
            {:status (.getMessage response)
             :data (.getData response)
             :request-time (:request-time (.getData response))
             :body (json/parse-string (slurp (:body (.getData response))) true)}))
      (instance? Throwable response)
        response
      :else
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
                         (>!! result-ch (result/success (prepare-response data x)))
                         (>!! result-ch (result/failure (prepare-response data x))))
                       (close! result-ch))
                     (fn [x]
                       (>!! result-ch (result/failure (prepare-error data x)))
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
                :request-method method
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
