(ns stitcher.browser
  "Browser setup and map control functions."
  (:require
   [etaoin.api :as e]
   [stitcher.config :as config]
   [stitcher.driver-manager :as dm]))

;; =============================================================================
;; Driver Creation
;; =============================================================================

(defn create-driver
  "Creates a Chrome driver with optional high DPI support for maximum resolution.
   When hidpi is true, uses 2x device scale factor for Retina-quality output.
   
   Options:
   - :viewport-size - Window size in pixels (default from config)
   - :headless      - Run without visible browser (default: false)
   - :hidpi         - Use 2x scale factor for Retina (default: true)
   - :check-version - Check Chrome/ChromeDriver compatibility (default: true)"
  [{:keys [viewport-size headless hidpi check-version]
    :or {check-version true}}]
  
  ;; Check version compatibility before starting
  (when check-version
    (dm/ensure-compatible-driver!))
  
  (e/chrome {:headless headless
             :size [viewport-size viewport-size]
             :args (when hidpi
                     ["--force-device-scale-factor=2"
                      "--high-dpi-support=1"])}))

;; =============================================================================
;; Map Control
;; =============================================================================

(defn center-map
  "Centers the map on specific coordinates."
  [driver lat lon]
  (e/js-execute driver
                (str "var center = new mapkit.Coordinate(" lat ", " lon ");
                      var region = new mapkit.CoordinateRegion(center, map.region.span);
                      map.setRegionAnimated(region, false);")))

(defn get-current-region
  "Gets the current map region (center + span)."
  [driver]
  (e/js-execute driver
                "return {
                   centerLat: map.region.center.latitude,
                   centerLon: map.region.center.longitude,
                   spanLat: map.region.span.latitudeDelta,
                   spanLon: map.region.span.longitudeDelta
                 };"))

(defn get-map-type
  "Gets the current map type (satellite, standard, hybrid)."
  [driver]
  (e/js-execute driver "return map.mapType;"))

(defn set-zoom-level
  "Sets the zoom level. Higher values = more zoomed in."
  [driver zoom]
  (let [zoom-multiplier (max 0.0001 (/ 0.1 zoom))]
    (e/js-execute driver
                  (str "var region = map.region;
                        var newSpan = new mapkit.CoordinateSpan(" zoom-multiplier ", " zoom-multiplier ");
                        var newRegion = new mapkit.CoordinateRegion(region.center, newSpan);
                        map.setRegionAnimated(newRegion, false);"))))

(defn hide-all-ui
  "Hides all UI elements for clean capture."
  [driver]
  ;; Hide Apple Maps UI
  (e/js-execute driver "map.showsZoomControl = false;")
  (e/js-execute driver "map.showsMapTypeControl = false;")
  (e/js-execute driver "map.showsCompass = false;")
  (e/js-execute driver "map.showsScale = false;")
  
  ;; Inject CSS to hide remaining elements
  (e/js-execute driver "
    var style = document.createElement('style');
    style.id = 'hide-ui-style';
    style.textContent = `
      .mk-compass, [class*='compass'], [class*='Compass'],
      .mk-attribution, [class*='attribution'], [class*='Attribution'],
      .mk-legal, [class*='legal'], [class*='Legal'],
      .mk-logo, [class*='logo'], [class*='watermark'],
      .mk-controls, [class*='controls'], [class*='Controls'] {
        display: none !important;
      }
    `;
    document.head.appendChild(style);
  "))

;; =============================================================================
;; Map Setup
;; =============================================================================

(defn setup-map
  "Navigate to Apple Maps and configure the view for scripted capture."
  [driver {:keys [city map-type] :as cfg}]
  (let [url (str "https://maps.apple.com/imagecollection/map?path=" city)
        coords (config/resolve-coords cfg)
        map-type (or map-type "satellite")]
    (e/go driver url)
    (e/wait 3)
    
    ;; Dismiss any alerts
    (when (e/has-alert? driver)
      (e/dismiss-alert driver))
    
    ;; Set map type and hide UI controls via JS API
    (e/js-execute driver (str "map.mapType = '" map-type "';"))
    (hide-all-ui driver)
    
    ;; Inject CSS to forcefully hide all UI overlays
    (e/js-execute driver "
      var style = document.createElement('style');
      style.textContent = `
        .mk-compass, [class*='compass'], [class*='Compass'] {
          display: none !important;
        }
        .mk-attribution, [class*='attribution'], [class*='Attribution'],
        .mk-legal, [class*='legal'], [class*='Legal'],
        .mk-logo, [class*='logo'], [class*='watermark'] {
          display: none !important;
        }
        .mk-controls, [class*='controls'], [class*='Controls'] {
          display: none !important;
        }
        .mk-map-view > div:not([class*='region']):not([class*='overlay']) > :last-child {
          display: none !important;
        }
      `;
      document.head.appendChild(style);
    ")
    (e/wait 1)
    
    ;; Center on the specified coordinates
    (println (format "Centering on: %.4f, %.4f" (:lat coords) (:lon coords)))
    (center-map driver (:lat coords) (:lon coords))
    (e/wait 1)))

(defn setup-map-interactive
  "Navigate to Apple Maps for interactive mode (standard view, no UI hiding)."
  [driver city]
  (e/go driver (str "https://maps.apple.com/imagecollection/map?path=" city))
  (e/wait 3)
  
  ;; Dismiss alerts
  (when (e/has-alert? driver)
    (e/dismiss-alert driver))
  
  ;; Start in standard map mode (user will switch to satellite)
  (e/js-execute driver "map.mapType = 'standard';")
  (e/wait 1))
