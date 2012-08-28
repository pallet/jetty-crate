(ns pallet.crate.jetty
  "Installation of jetty."
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action :as action]
   [pallet.action.file :as file]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.action.user :as user]
   [pallet.crate.etc-default :as etc-default]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore])
  (:use
   pallet.thread-expr
   [pallet.action :only [action-fn def-collected-action]]
   [pallet.action.directory :only [directory]]
   [pallet.action.exec-script :only [exec-script]]
   [pallet.action.file :only [file]]
   [pallet.action.remote-directory :only [remote-directory]]
   [pallet.action.remote-file :only [remote-file content-options]]
   [pallet.common.context :only [throw-map]]
   [pallet.core :only [server-spec]]
   [pallet.parameter :only [assoc-target-settings get-target-settings]]
   [pallet.phase :only [phase-fn]]
   [pallet.utils :only [apply-map]]
   [pallet.version-dispatch
    :only [defmulti-version-crate defmulti-version defmulti-os-crate
           multi-version-session-method multi-version-method
           multi-os-session-method]]
   [pallet.versions :only [version-string]]))

(def ^{:doc "Flag for recognising changes to configuration"}
  jetty-config-changed-flag "jetty-config")

;; 7.1.4.v20100610
;; 7.0.2.v20100331
;; 8.1.5.v20120716
(def ^:dynamic *default-version* "8.1.5.v20120716")

(def ^:dynamic *eclipse-jetty-url*
  "http://download.eclipse.org/jetty/%1$s/dist/jetty-distribution-%1$s.tar.gz")

(defn- download-path [version]
  (format *eclipse-jetty-url* version))

(def ^:dynamic *jetty-defaults*
  {:user "jetty"
   :group "jetty"
   :log-path "/var/log/jetty"
   :install-path "/usr/share/jetty"
   :webapps "/usr/share/jetty/webapps/"
   :service "jetty"
   :service-defaults {}
   :defaults-file "jetty"})

;;; Based on supplied settings, decide which install strategy we are using
;;; for jetty.

(defmulti-version-crate jetty-version-settings [version session settings])

(multi-version-session-method
    jetty-version-settings {:os :linux}
    [os os-version version session settings]
  (cond
    (:strategy settings) settings
    (:download settings) (assoc settings :strategy :download)
    :else (let [dl (download-path (:version settings))]
            (assoc settings
              :strategy :download
              :download {:url dl :md5-url (str dl ".md5")}))))

;;; ## Settings
(defn- settings-map
  "Dispatch to either openjdk or oracle settings"
  [session settings]
  (jetty-version-settings
   session (:version settings) (merge *jetty-defaults* settings)))

(defn jetty-settings
  "Capture settings for jetty

:user
:group
:home
:version"
  [session {:keys [version user group install-path instance-id]
            :or {version *default-version*}
            :as settings}]
  (let [settings (settings-map session (merge {:version version} settings))]
    (assoc-target-settings session :jetty instance-id settings)))


(defmulti install-method (fn [session settings] (:strategy settings)))

(defmethod install-method :download
  [session {:keys [download install-path log-path user group webapps]}]
  (->
   session
   (user/group group :system true)
   (user/user user :system true :shell "/bin/false" :group group)
   (apply-map-> remote-directory install-path :user user :group group download)
   (directory webapps :owner user :group group)
   (directory (str install-path "/logs") :owner user :group group)
   (directory log-path :owner user :group group)
   (file (str install-path "/contexts/test.xml") :action :delete :force true)
   (file (str install-path "/contexts/demo.xml") :action :delete :force true)
   (file (str install-path "/contexts/javadoc.xml") :action :delete :force true)
   (file (str install-path "/webapps/test.war") :action :delete :force true)
   (directory (str install-path "/contexts/test.d")
              :action :delete :recursive true :force true)))

(defn install-jetty
  "Install jetty."
  [session & {:keys [instance-id]}]
  (let [settings (get-target-settings session :jetty instance-id ::no-settings)]
    (logging/debugf "install-jetty settings %s" settings)
    (if (= settings ::no-settings)
      (throw-map
       "Attempt to install jetty without specifying settings"
       {:message "Attempt to install jetty without specifying settings"
        :type :invalid-operation})
      (install-method session settings))))


(defn install-jetty-service
  "Install jetty via download"
  [session  & {:keys [instance-id]}]
  (let [{:keys [user install-path log-path service no-enable service-defaults
                defaults-file]}
        (get-target-settings session :jetty instance-id ::no-settings)]
    (->
     session
     (apply-map->
      etc-default/write
      defaults-file
      "JETTY_USER" user
      "JETTY_LOGS" log-path
      "JETTY_HOME" install-path
      service-defaults)
     (service/init-script
      service :remote-file (str install-path "/bin/jetty.sh"))
     (when-not-> no-enable
       (service/service "jetty" :action :enable)))))

(defn init-service
  "Control the jetty service.

   Specify `:if-config-changed true` to make actions conditional on a change in
   configuration.

   Other options are as for `pallet.action.service/service`. The service
   name is looked up in the request parameters."
  [session & {:keys [action if-config-changed if-flag instance-id] :as options}]
  (let [{:keys [service]} (get-target-settings session :jetty instance-id)
        options (if if-config-changed
                  (assoc options :if-flag jetty-config-changed-flag)
                  options)]
    (-> session (apply-map-> service/service service options))))

(def remote-file* (action-fn remote-file/remote-file-action))
(def directory* (action-fn directory))

(def-collected-action configure
  "Configure jetty options for jetty.conf.  Each argument will be added to
   the server configuration file."
  {:arglists '([session option-string])}
  [session options]
  (let [{:keys [user group install-path]}
        (get-target-settings
         session :jetty (:instance-id (ffirst options)) ::no-settings)]
    (stevedore/chain-commands
     (directory* session (str install-path "/etc") :owner user :group group)
     (remote-file*
      session
      (str install-path "/etc/jetty.conf")
      :content (string/join \newline (map first options))
      :flag-on-changed jetty-config-changed-flag))))

(defn server
  "Configure the jetty server (jetty.xml)."
  [session content & {:keys [instance-id]}]
  (let [{:keys [user group install-path]}
        (get-target-settings session :jetty instance-id ::no-settings)]
    (remote-file
     session
     (str install-path "/etc/jetty.xml")
     :content content :user user :group group
     :flag-on-changed jetty-config-changed-flag)))

(defn ssl
  "Configure an ssl connector (jetty-ssl.xml)."
  [session content & {:keys [instance-id]}]
  (let [{:keys [user group install-path]}
        (get-target-settings session :jetty instance-id ::no-settings)]
    (->
     session
     (remote-file
      (str install-path "/etc/jetty-ssl.xml")
      :content content :user user :group group
      :flag-on-changed jetty-config-changed-flag)
     (configure "etc/jetty-ssl.xml"))))

(defn context
  "Configure an application context"
  [session name content & {:keys [instance-id]}]
  (let [{:keys [user group install-path]}
        (get-target-settings session :jetty instance-id ::no-settings)]
    (remote-file
     session
     (str install-path "/contexts/" name ".xml")
     :content content
     :flag-on-changed jetty-config-changed-flag)))

(defn deploy
  "Copies a .war file to the jetty server under webapps/${app-name}.war.  An
   app-name of \"ROOT\" or nil will deploy the source war file as the / webapp.

   Accepts options as for remote-file in order to specify the source.

   Other Options:
     :clear-existing true -- removes an existing exploded ${app-name} directory"
  [session app-name & {:keys [instance-id] :as opts}]
  (let [{:keys [user group install-path webapps]}
        (get-target-settings session :jetty instance-id ::no-settings)
        exploded-app-dir (str webapps "/" (or app-name "ROOT"))
        deployed-warfile (str exploded-app-dir ".war")
        options (merge {:owner user :group group :mode 600}
                       (select-keys opts content-options))]
    (->
     session
     (when-not->
      (:clear-existing opts)
       ;; if we're not removing an existing, try at least to make sure
       ;; that jetty has the permissions to explode the war
      (apply->
        directory
        exploded-app-dir
        (apply concat
               (merge {:owner user :group group :recursive true}
                      (select-keys options [:owner :group :recursive])))))
     (apply-> remote-file deployed-warfile (apply concat options))
     (when-> (:clear-existing opts)
       (exec-script (rm ~exploded-app-dir ~{:r true :f true}))))))

;;; # Server spec
(defn jetty
  "Returns a service-spec for installing jetty."
  [settings]
  (server-spec
   :phases {:settings (phase-fn
                        (jetty-settings settings))
            :configure (phase-fn
                         (install-jetty)
                         (install-jetty-service))}))
