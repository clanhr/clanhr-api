(defproject clanhr/clanhr-api "1.7.1"
  :description "Raw clojure interface to ClanHR's APIs"
  :url "https://github.com/clanhr/clanhr-api"
  :dependencies.edn "https://raw.githubusercontent.com/clanhr/dependencies/master/dependencies.edn"

  :dependency-sets [:clojure :common :clanhr :aleph]
  :dependencies []

  :plugins [[clanhr/shared-deps "0.2.6"]])
