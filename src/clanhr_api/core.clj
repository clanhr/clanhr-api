(ns clanhr-api.core
  "Manages and performs requests to ClanHR's API"
  (:require [environ.core :refer [env]]
            [clojure.core.async :refer [chan <!! >!! close! go <! timeout]]
            [manifold.deferred :as d]
            [aleph.http :as http]
            [cheshire.core :as json]
            [result.core :as result]
            [clojure.string :as clj-str]
            [ring.util.codec :as codec]
            [clanhr.analytics.errors :as errors]
            [clanhr.analytics.metrics :as metrics]))

(def ^:dynamic *default-timeout* (Integer/parseInt
                                   (or (env :clanhr-internal-api-timeout)
                                       (str (* 10 1000)))))

(defn setup
  "Creates configuration for executing requests"
  [opts]
  (merge {:directory-api (or (env :clanhr-directory-api) "http://directory.api.staging.clanhr.com")
          :absences-api (or (env :clanhr-absences-api) "http://absences.api.staging.clanhr.com")
          :notifications-api (or (env :clanhr-notifications-api) "http://notifications.api.staging.clanhr.com")
          :reports-api (or (env :clanhr-reports-api) "http://reports.api.staging.clanhr.com")
          :http-opts (merge {:connection-timeout *default-timeout*
                             :request-timeout *default-timeout*}
                            (:http-opts opts))}
         opts))

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
    (merge {:status (:status response)
            :requests (inc (:requests data))}
           (json/parse-string (slurp (:body response)) true))
    (catch Exception e
      (errors/exception e))))

(defn- register-exception
  "Registers an exception with proper data"
  [ex info]
  (errors/exception (ex-info (:error info) info ex)))

(defn- prepare-error
  "Handles post-response errors"
  [data response]
  (try
    (cond
      (instance? java.util.concurrent.TimeoutException response)
        (let [info {:status 408
                    :error (str "Error getting " (:url data))
                    :request-time (-> data :http-opts :request-timeout)
                    :requests (inc (:requests data))
                    :data {:message "Timed out"}}]
          (register-exception response info)
          (track-api-response data info))
      (instance? clojure.lang.ExceptionInfo response)
        (let [info (merge {:status (.getMessage response)
                           :error (str "Error getting " (:url data))
                           :requests (inc (:requests data))
                           :request-time (:request-time (.getData response))}
                   (json/parse-string (slurp (:body (.getData response))) true))]
          (register-exception response info)
          (track-api-response data info))
      (instance? Throwable response)
        (let [info {:error (str "Error getting " (:url data))
                    :caused-by response}]
          (register-exception response info)
          response)
      :else
        (-> response
            (assoc :error (str "Error getting " (:url data)))
            (assoc :requests (inc (:requests data)))
            (assoc :status (-> response :data :cause))
            (assoc :body-data (slurp (-> response :data :body)))))
    (catch Exception e
      (let [info {:error (str "Error getting " (:url data))
                  :exception (.getMessage e)}]
        (register-exception e info)))))

(defn- retry?
  "Verifies that the given error response is the final one, or that
  we should try it again."
  [data response]
  (and (instance? java.util.concurrent.TimeoutException response)
       (not= 0 (int (:retries data)))))

(def final-response? (comp not retry?))

(defn- fetch-response
  "Fetches the response for a given URL"
  [data]
  (try
    (let [result-ch (or (:result-ch data) (chan 1))
          async-stream ((:method-fn data) (:url data) (:http-opts data))]
      (d/on-realized async-stream
                     (fn [x]
                       (if x
                         (>!! result-ch (result/success (prepare-response data x)))
                         (>!! result-ch (result/failure (prepare-response data x))))
                       (close! result-ch))
                     (fn [x]
                       (if (final-response? data x)
                         (do
                           (>!! result-ch (result/failure (prepare-error data x)))
                           (close! result-ch))
                         (go
                           (<! (timeout (* 300 (+ 1 (int (:requests data))))))
                           (fetch-response (-> data
                                               (assoc :result-ch result-ch)
                                               (update :retries dec)
                                               (update :requests inc)))))))
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
    (assoc http-opts :body (if (or (map? body) (seq? body)) (json/generate-string body) body))
    http-opts))

(defn mothership?
  "True if the configs states that we are running on a mothership"
  [data]
  (or (= "true" (env :clanhr-mothership))
      (:mothership? data)))

(defn service-port
  "Gets the port on the local machine where the service is running"
  [data]
  (let [service (:service data)
        port-str (str "clanhr-" (name service) "-port")
        port-key (keyword port-str)]
    (or (env port-key)
        (get data port-key)
        80)))

(defn service-host
  "Gets the host of the given service"
  [data]
  (if (mothership? data)
    (str "http://localhost:" (service-port data))
    (get data (:service data))))

(defn url-encode
  [query-params]
  (codec/url-encode query-params "UTF-8"))

(defn query-string-param-builder
  [query-string-key data]
  (when data
    (if (coll? data)
      (str query-string-key "=" (clj-str/join "," data))
      (str query-string-key "=" data))))

(defn clean-record
  [record]
  (apply dissoc record (for [[k v] record :when (nil? v)] k)))

(defn build-query-string
  [query-params]
  (clj-str/join "&"
    (for [[k v] query-params] (query-string-param-builder (name k)
                                                          (url-encode v)))))

(defn build-url
  [host path query-params]
  (if query-params
    (str host path "?" (build-query-string (clean-record query-params)))
    (str host path)))

(defn- prepare-data
  "Builds data from data"
  [data method]
  (let [data (setup data)
        host (service-host data)
        url (build-url host (:path data) (:query-params data))
        http-opts (-> (authentify data (:http-opts data))
                      (add-body data))]
    (assoc data :host host
                :requests 0
                :retries (- (or (:retries data) 3) 1)
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
