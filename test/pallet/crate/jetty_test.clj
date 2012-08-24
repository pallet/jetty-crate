(ns pallet.crate.jetty-test
  (:use
   [pallet.parameter :only [get-target-settings]]
   [pallet.parameter-test :only [settings-test]]
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.blobstore :as blobstore]
   [pallet.build-actions :as build-actions]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.java :as java]
   [pallet.crate.jetty :as jetty]
   [pallet.crate.network-service :as network-service]
   [pallet.enlive :as enlive]
   [pallet.live-test :as live-test]
   [pallet.parameter :as parameter]
   [pallet.parameter-test :as parameter-test]
   [pallet.phase :as phase]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (jetty/jetty-settings {})
       (jetty/install-jetty)
       (jetty/configure "")
       (jetty/server "")
       (jetty/ssl "")
       (jetty/context "" "")
       (jetty/deploy "" :content "c"))))

(def settings-map {})

(def jetty-unsupported
  [])

(def ^{:doc "An html file for jetty to serve to verify we have it running."}
  index-html
  "<html>
<head>
  <title>Pallet-live-test</title>
  <%@ page language=\"java\" %>
</head>
<body>
<h3><%= java.net.InetAddress.getLocalHost().getHostAddress() %></h3>
<h3><%= java.lang.System.getProperty(\"java.vendor\") %></h3>
<h3><%= java.lang.System.getProperty(\"java.runtime.name\") %></h3>
<h3><%= java.lang.System.getProperty(\"java.vm.name\") %></h3>
LIVE TEST
</body></html>")

(def app-name "pallet-live-test")

(def
  ^{:doc "An application configuration context"}
  application-config
  (format "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<Configure class=\"org.eclipse.jetty.webapp.WebAppContext\">
  <Set name=\"contextPath\">/%1$s</Set>
  <Set name=\"resourceBase\"><SystemProperty name=\"jetty.home\" default=\".\"/>/webapps/%1$s</Set>
</Configure>"
          app-name))

(deftest live-test
  (live-test/test-for
   [image (live-test/exclude-images (live-test/images) jetty-unsupported)]
   (live-test/test-nodes
    [compute node-map node-types]
    {:jetty
     {:image image
      :count 1
      :phases {:bootstrap (phase/phase-fn
                           (automated-admin-user/automated-admin-user))
               :settings (phase/phase-fn
                          (java/java-settings {})
                          (jetty/jetty-settings settings-map))
               :configure (fn [session]
                            (let [settings (get-target-settings
                                            session :jetty nil)
                                  appdir (str (:webapps settings) app-name "/")]
                              (->
                               session
                               (java/install-java)
                               (jetty/install-jetty)
                               (jetty/install-jetty-service)
                               ;; (jetty/server)
                               (jetty/context app-name application-config)
                               (directory/directory appdir)
                               (remote-file/remote-file
                                (str appdir "index.jsp")
                                :content index-html :literal true
                                :flag-on-changed
                                jetty/jetty-config-changed-flag)
                               (jetty/init-service
                                :if-config-changed true
                                :action :restart))))
               :verify (phase/phase-fn
                        (network-service/wait-for-http-status
                         (format "http://localhost:8080/%s/" app-name)
                         200 :url-name "jetty server")
                        (exec-script/exec-checked-script
                         "check jetty is running with  jdk"
                         (pipe
                          (wget "-O-"
                                (str "http://localhost:8080/" ~app-name "/"))
                          (grep -i (quoted "")))))}}}
    (core/lift (:jetty node-types) :phase :verify :compute compute))))
