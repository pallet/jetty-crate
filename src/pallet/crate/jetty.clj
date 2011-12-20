(ns pallet.crate.jetty
  "Installation of jetty."
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.action.user :as user]
   [pallet.crate.etc-default :as etc-default]
   [pallet.crate.java :as java]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string])
  (:use pallet.thread-expr))

(def install-path "/usr/share/jetty7")
(def log-path "/var/log/jetty7")
(def jetty-user "jetty")
(def jetty-group "jetty")

;; 7.1.4.v20100610
;; 7.0.2.v20100331
(def default-version "7.0.2.v20100331")

(defn download-path [version]
  (format
   "http://download.eclipse.org/jetty/%s/dist/jetty-distribution-%s.tar.gz"
   version version))

(defn jetty
  "Install jetty via download"
  [session & {:as options}]
  (let [path (download-path (:version options default-version))]
    (->
     session
     (parameter/assoc-for
      [:jetty :base] install-path
      [:jetty :owner] jetty-user
      [:jetty :group] jetty-group)
     (java/java :openjdk)
     (user/group jetty-group :system true)
     (user/user jetty-user :system true :group jetty-group :shell "/bin/false")
     (remote-directory/remote-directory
      install-path
      :url path :unpack :tar :tar-options "xz"
      :owner jetty-user :group jetty-group)
     (directory/directory
      (str install-path "/webapps") :owner jetty-user :group jetty-group)
     (directory/directory
      (str install-path "/logs") :owner jetty-user :group jetty-group)
     (directory/directory
      log-path :owner jetty-user :group jetty-group)
     (etc-default/write
      "jetty7"
      "JETTY_USER" jetty-user
      "JETTY_LOGS" log-path
      "JETTY_HOME" install-path
      "JAVA_HOME" "/usr/lib/jvm/java-6-openjdk")
     (service/init-script
      "jetty"
      :remote-file (str install-path "/bin/jetty.sh"))
     (when-not-> (:no-enable options)
       (service/service "jetty" :action :enable))
     ;; Remove the default webapps
     (file/file
      (str install-path "/contexts/test.xml") :action :delete :force true)
     (file/file
      (str install-path "/contexts/demo.xml") :action :delete :force true)
     (file/file
      (str install-path "/contexts/javadoc.xml") :action :delete :force true)
     (file/file
      (str install-path "/webapps/test.war") :action :delete :force true)
     (directory/directory
      (str install-path "/contexts/test.d")
      :action :delete :recursive true :force true)
     (service/service "jetty" :action :enable))))


(def remote-file* (action/action-fn remote-file/remote-file-action))
(def directory* (action/action-fn directory/directory))

(action/def-collected-action configure
  "Configure jetty options for jetty.conf.  Each argument will be added to
   the server configuration file."
  {:arglists '([session option-string])}
  [session options]
  (stevedore/chain-commands
   (directory*
    session
    (str install-path "/etc") :owner jetty-user :group jetty-group)
   (remote-file*
    session
    (str install-path "/etc/jetty.conf")
    :content (string/join \newline (map first options)))))

(defn server
  "Configure the jetty server (jetty.xml)."
  [session content]
  (remote-file/remote-file
   session
   (str install-path "/etc/jetty.xml")
   :content content)) ;; (configure "etc/jetty.xml") ; read by start.jar

(defn ssl
  "Configure an ssl connector (jetty-ssl.xml)."
  [session content]
  (->
   session
   (remote-file/remote-file
    (str install-path "/etc/jetty-ssl.xml")
    :content content)
   (configure "etc/jetty-ssl.xml")))

(defn context
  "Configure an application context"
  [session name content]
  (remote-file/remote-file
   session
   (str install-path "/contexts/" name ".xml")
   :content content))

(defn deploy
  "Copies a .war file to the jetty server under webapps/${app-name}.war.  An
   app-name of \"ROOT\" or nil will deploy the source war file as the / webapp.

   Accepts options as for remote-file in order to specify the source.

   Other Options:
     :clear-existing true -- removes an existing exploded ${app-name} directory"
  [session app-name & {:as opts}]
  (let [exploded-app-dir (str install-path "/webapps/" (or app-name "ROOT"))
        deployed-warfile (str exploded-app-dir ".war")
        options (merge
                 {:owner jetty-user :group jetty-group :mode 600}
                 (select-keys opts remote-file/content-options))]
    (->
     session
     (when-not->
      (:clear-existing opts)
       ;; if we're not removing an existing, try at least to make sure
       ;; that jetty has the permissions to explode the war
      (apply->
        directory/directory
        exploded-app-dir
        (apply concat
               (merge {:owner jetty-user :group jetty-group :recursive true}
                      (select-keys options [:owner :group :recursive])))))
     (apply->
      remote-file/remote-file
      deployed-warfile
      (apply concat options))
     (when-> (:clear-existing opts)
       (exec-script/exec-script
        (rm ~exploded-app-dir ~{:r true :f true}))))))
