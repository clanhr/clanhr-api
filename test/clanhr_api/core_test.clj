(ns clanhr-api.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [<!!]]
            [clanhr-api.core :as clanhr-api]
            [result.core :as result]))

(defn- test-api-home
  "Makes a request to api's root"
  [service-key expected-name]
  (let [client (clanhr-api/client {:live true})
        result-ch (clanhr-api/http-get client {:service service-key
                                               :path "/"})]
    (is result-ch)

    (let [result (<!! result-ch)
          data (:data result)]
      (is (result/succeeded? result))
      (is data)
      (is (= (:name data) expected-name)))))

(deftest apis-home
  (test-api-home :directory-api "ClanHR Directory API")
  (test-api-home :absences-api "ClanHR Absences API")
  #_(test-api-home :notifications-api "ClanHR Notifications API"))
