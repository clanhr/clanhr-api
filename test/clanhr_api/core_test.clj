(ns clanhr-api.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [<!!]]
            [clanhr-api.core :as clanhr-api]
            [result.core :as result]))

(deftest smoke
  (let [client (clanhr-api/client {:live true})
        result-ch (clanhr-api/http-get client {:service :directory-api
                                               :context {}
                                               :path "/"})]
    (is result-ch)

    (let [result (<!! result-ch)]
      (is (result/succeeded? result)))))
