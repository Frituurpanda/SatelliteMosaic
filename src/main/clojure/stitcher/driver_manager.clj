(ns stitcher.driver-manager
  "ChromeDriver version detection and compatibility checking.
   
   Ensures ChromeDriver version matches installed Chrome browser
   and provides clear instructions if there's a mismatch."
  (:require
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]))

;; =============================================================================
;; Version Detection
;; =============================================================================

(defn- parse-version
  "Extracts version number from version string output."
  [output]
  (when output
    (second (re-find #"(\d+\.\d+\.\d+\.\d+)" output))))

(defn get-chrome-version
  "Gets the installed Chrome browser version."
  []
  (let [chrome-paths ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                      "/usr/bin/google-chrome"
                      "/usr/bin/google-chrome-stable"
                      "google-chrome"]
        result (some #(let [{:keys [exit out]} (sh % "--version")]
                        (when (zero? exit) out))
                     chrome-paths)]
    (parse-version result)))

(defn get-chromedriver-version
  "Gets the installed ChromeDriver version."
  []
  (let [{:keys [exit out]} (sh "chromedriver" "--version")]
    (when (zero? exit)
      (parse-version out))))

(defn major-version
  "Extracts major version number from full version string."
  [version]
  (when version
    (first (str/split version #"\."))))

(defn versions-compatible?
  "Checks if Chrome and ChromeDriver major versions match."
  [chrome-version driver-version]
  (and chrome-version
       driver-version
       (= (major-version chrome-version)
          (major-version driver-version))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn check-versions
  "Checks Chrome and ChromeDriver versions and returns status map."
  []
  (let [chrome-ver (get-chrome-version)
        driver-ver (get-chromedriver-version)
        compatible (versions-compatible? chrome-ver driver-ver)]
    {:chrome-version chrome-ver
     :chromedriver-version driver-ver
     :compatible compatible
     :chrome-major (major-version chrome-ver)
     :driver-major (major-version driver-ver)}))

(defn ensure-compatible-driver!
  "Ensures ChromeDriver is installed and compatible with Chrome.
   Throws with helpful instructions if there's an issue."
  []
  (let [{:keys [chrome-version chromedriver-version compatible chrome-major driver-major]}
        (check-versions)]
    
    (cond
      ;; No Chrome found
      (nil? chrome-version)
      (throw (ex-info "Chrome browser not found. Please install Google Chrome."
                      {:type :chrome-not-found}))
      
      ;; No ChromeDriver found
      (nil? chromedriver-version)
      (throw (ex-info (str "ChromeDriver not found.\n\n"
                           "Install it with Homebrew:\n"
                           "  brew install chromedriver\n\n"
                           "Then allow it in System Settings > Privacy & Security if prompted.")
                      {:type :chromedriver-not-found
                       :chrome-version chrome-version}))
      
      ;; Version mismatch
      (not compatible)
      (throw (ex-info (format "ChromeDriver/Chrome version mismatch!

  Chrome:       %s (major: %s)
  ChromeDriver: %s (major: %s)

To fix, update ChromeDriver to match your Chrome version:
  brew upgrade chromedriver

Or update Chrome to match ChromeDriver:
  Open Chrome → Menu → Help → About Google Chrome"
                              chrome-version chrome-major
                              chromedriver-version driver-major)
                      {:type :version-mismatch
                       :chrome-version chrome-version
                       :chromedriver-version chromedriver-version}))
      
      ;; All good
      :else
      (println (format "✓ Chrome %s + ChromeDriver %s"
                       chrome-version chromedriver-version)))))

(defn print-version-info
  "Prints version information for debugging."
  []
  (let [{:keys [chrome-version chromedriver-version compatible]} (check-versions)]
    (println "Chrome version:      " (or chrome-version "NOT FOUND"))
    (println "ChromeDriver version:" (or chromedriver-version "NOT FOUND"))
    (println "Compatible:          " (if compatible "Yes" "NO - version mismatch!"))))
