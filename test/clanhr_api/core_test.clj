(ns clanhr-api.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [<!!]]
            [clanhr-api.core :as clanhr-api]
            [result.core :as result]))

(defn- test-api-home
  "Makes a request to api's root"
  [service-key expected-name]
  (let [result-ch (clanhr-api/http-get {:service service-key :path "/"})]
    (is result-ch)
    (let [result (<!! result-ch)]
      (is (result/succeeded? result))
      (is (= 200 (:status result)))
      (is (= 1 (:requests result)))
      (is (= (:name result) expected-name)))))

(deftest apis-home
  (test-api-home :directory-api "ClanHR Directory API")
  (test-api-home :absences-api "ClanHR Absences API")
  (test-api-home :reports-api "ClanHR Reports API")
  (test-api-home :notifications-api "notifications-api"))

(deftest error
  (let [result (<!! (clanhr-api/http-get {:service :directory-api :path "/waza"}))]
    (is (result/failed? result))))

(deftest test-post
  (let [result (<!! (clanhr-api/http-post {:service :directory-api :path "/login"
                                           :body {:email "donbonifacio@gmail.com"
                                                  :password "wazabi"}}))]
    (result/failed? result)
    (is (= "invalid-email-or-password" (first (-> result :errors))))))

(deftest test-timeout
  (let [result (<!! (clanhr-api/http-get {:service :directory-api :path "/"
                                          :retries 5
                                          :http-opts {:connection-timeout 1}}))]
    (is (result/failed? result))
    (is (= 5 (:requests result)))))
