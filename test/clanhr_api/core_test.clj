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
      (is (:request-time result))
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
                                          :retries 1
                                          :http-opts {:request-timeout 1}}))]
    (is (result/failed? result))
    (is (= 1 (:requests result)))))

(deftest mothership-bypass-test
  (testing "defaults to remote service"
    (let [data (clanhr-api/setup {:service :directory-api})]
      (is (= 80 (clanhr-api/service-port data)))
      (is (= "http://directory.api.staging.clanhr.com" (clanhr-api/service-host data)))))

  (testing "local mothership override"
    (let [data {:mothership? true
                :service :directory-api
                :clanhr-directory-api-port 5000}]
      (is (= 5000 (clanhr-api/service-port data)))
      (is (= "http://localhost:5000" (clanhr-api/service-host data))))))

(deftest query-string-builder
  (testing "with list"
    (is (= "key=1,2" (clanhr-api/query-string-param-builder "key" [1 2]))))
  (testing "with value"
    (is (= "key=1" (clanhr-api/query-string-param-builder "key" 1))))
  (testing "with nil"
    (is (nil? (clanhr-api/query-string-param-builder "key" nil)))))

(deftest build-query-string-test
  (testing "simple case"
    (is (= "key1=1" (clanhr-api/build-query-string {:key1 1}))))
  (testing "space case"
    (is (= "key1=1&key2=1%202"
           (clanhr-api/build-query-string {:key1 1
                                           :key2 "1 2"})))))

(deftest build-url-test
  (testing "simple case"
    (is (= "http://host.com/path?key1=1&key2=1%202"
           (clanhr-api/build-url "http://host.com"
                                 "/path"
                                 {:key1 1
                                  :key2 "1 2"}))))
  (testing "nil properties"
    (is (= "http://host.com/path?key1=1"
           (clanhr-api/build-url "http://host.com"
                                 "/path"
                                 {:key1 1
                                  :key2 nil}))))
    )
