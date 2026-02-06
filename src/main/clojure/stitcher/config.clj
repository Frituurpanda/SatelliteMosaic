(ns stitcher.config
  "Configuration and constants for the Apple Maps tile stitcher.")

;; =============================================================================
;; Default Configuration
;; =============================================================================

(def default-config
  {:viewport-size 1000          ; Square viewport for easy math
   :tile-wait-ms  5000          ; Max wait time for tiles to load (safety net)
   :city          "sanfrancisco"
   :rows          10
   :cols          10
   :zoom          100           ; Max zoom (100+ all give same result)
   :output-dir    "output"
   :headless      false         ; Set true for headless mode
   :hidpi         true          ; Use 2x resolution (Retina)
   ;; Default center coordinates (San Francisco downtown)
   :center-lat    37.7749
   :center-lon    -122.4194})

;; =============================================================================
;; City Centers
;; =============================================================================

(def city-centers
  "Pre-defined city center coordinates for quick access."
  {"sanfrancisco" {:lat 37.7749  :lon -122.4194}
   "newyork"      {:lat 40.7128  :lon -74.0060}
   "london"       {:lat 51.5074  :lon -0.1278}
   "paris"        {:lat 48.8566  :lon 2.3522}
   "tokyo"        {:lat 35.6762  :lon 139.6503}
   "sydney"       {:lat -33.8688 :lon 151.2093}
   "losangeles"   {:lat 34.0522  :lon -118.2437}
   "chicago"      {:lat 41.8781  :lon -87.6298}})

(defn get-city-coords
  "Returns coordinates for a city, or nil if not found."
  [city]
  (get city-centers city))

(defn resolve-coords
  "Resolves coordinates from config. Priority: explicit coords > city lookup > SF default."
  [{:keys [center-lat center-lon city]}]
  (or (when (and center-lat center-lon)
        {:lat center-lat :lon center-lon})
      (get-city-coords city)
      {:lat 37.7749 :lon -122.4194}))
