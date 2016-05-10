(defproject clanhr/clanhr-api "1.8.0"
  :description "Raw clojure interface to ClanHR's APIs"
  :url "https://github.com/clanhr/clanhr-api"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.374"]
                 [ring/ring-codec "1.0.0"]
                 [environ "1.0.2"]
                 [aleph "0.4.1"]
                 [cheshire "5.6.1"]
                 [clanhr/analytics "1.9.1"]
                 [clanhr/result "0.11.0"]])
