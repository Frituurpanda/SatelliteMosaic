(ns stitcher.grid
  "Grid calculation and tile loading functions."
  (:require
   [etaoin.api :as e]
   [stitcher.browser :as browser]))

;; =============================================================================
;; Grid Position Calculations
;; =============================================================================

(defn calculate-grid-positions
  "Calculates all tile positions for the grid.
   Returns a sequence of {:row :col :lat :lon} maps in row-major order."
  [driver rows cols]
  (let [region (browser/get-current-region driver)
        center-lat (:centerLat region)
        center-lon (:centerLon region)
        span-lat (:spanLat region)
        span-lon (:spanLon region)
        ;; Each tile shows the same span as the current view
        tile-lat span-lat
        tile-lon span-lon]
    (println (format "  Area per tile: %.4f° x %.4f° (~%dm x %dm)" 
                     tile-lat tile-lon
                     (int (* tile-lat 111000))    ; rough meters conversion
                     (int (* tile-lon 85000))))   ; at ~37° latitude
    (for [row (range rows)
          col (range cols)]
      {:row row
       :col col
       ;; Position for each tile - move by full tile size
       :lat (- center-lat (* tile-lat (- row (/ (dec rows) 2.0))))
       :lon (+ center-lon (* tile-lon (- col (/ (dec cols) 2.0))))
       :tile-lat tile-lat
       :tile-lon tile-lon})))

(defn get-actual-tile-span
  "Sets a tile region and returns the ACTUAL span the map displays.
   MapKit may clamp to minimum zoom, so we query what it actually shows."
  [driver zoom]
  (let [requested-lat (max 0.0001 (/ 0.1 zoom))
        requested-lon (* requested-lat 1.3)]
    (e/js-execute driver 
                  (str "var span = new mapkit.CoordinateSpan(" requested-lat ", " requested-lon ");
                        var center = map.region.center;
                        map.region = new mapkit.CoordinateRegion(center, span);"))
    (Thread/sleep 500)
    (let [actual (e/js-execute driver "return {lat: map.region.span.latitudeDelta, lon: map.region.span.longitudeDelta};")]
      {:tile-lat (:lat actual)
       :tile-lon (:lon actual)})))

(defn calculate-grid-from-selection
  "Calculates tile positions from user's selection box."
  [driver selection]
  (let [{:keys [center-lat center-lon rows cols zoom]} selection
        {:keys [tile-lat tile-lon]} (get-actual-tile-span driver zoom)]
    (for [row (range rows)
          col (range cols)]
      {:row row
       :col col
       :lat (- center-lat (* tile-lat (- row (/ (dec rows) 2.0))))
       :lon (+ center-lon (* tile-lon (- col (/ (dec cols) 2.0))))
       :tile-lat tile-lat
       :tile-lon tile-lon})))

;; =============================================================================
;; Tile Movement
;; =============================================================================

(defn move-to-tile
  "Moves the map view to center on a specific tile.
   Resets tile detection BEFORE moving so we track new tile fetches."
  [driver {:keys [lat lon tile-lat tile-lon]}]
  ;; Reset tile detection BEFORE moving - this ensures we track the NEW fetches
  (try
    (e/js-execute driver "window.resetTileDetection && window.resetTileDetection();")
    (catch Exception _ nil))
  
  ;; Direct region assignment (no animation) for reliable positioning
  (e/js-execute driver
                (str "var center = new mapkit.Coordinate(" lat ", " lon ");
                      var span = new mapkit.CoordinateSpan(" tile-lat ", " tile-lon ");
                      var region = new mapkit.CoordinateRegion(center, span);
                      map.region = region;"))
  ;; Brief pause to let fetch requests start
  (Thread/sleep 50))

;; =============================================================================
;; Tile Loading Detection
;; =============================================================================

(defn setup-tile-detection
  "Sets up JavaScript for intelligent tile loading detection.
   
   Intercepts fetch() calls and tracks when image data is fully decoded,
   not just when the network request completes. This provides accurate
   detection of when tiles are actually ready for screenshot.
   
   Detection criteria:
   1. All pending fetches completed (pendingFetches === 0)
   2. At least one tile was loaded (completedFetches > 0)
   3. All image blobs fully decoded (decodedImages >= completedFetches)
   4. Small buffer after last decode for GPU upload (~50ms)"
  [driver]
  (e/js-execute driver "
    // Initialize tile tracking state with generation counter
    if (!window.tileState) {
      window.tileState = {
        generation: 0,
        pendingFetches: 0,
        completedFetches: 0,
        decodedImages: 0,
        failedFetches: 0,
        lastDecodeTime: 0,
        hasActivity: false
      };
    }
    
    // Intercept fetch to track tile loads AND image decoding
    if (!window._tileFetchPatched) {
      window._tileFetchPatched = true;
      var originalFetch = window.fetch;
      
      window.fetch = function(url, options) {
        var urlStr = (typeof url === 'string') ? url : (url.url || '');
        var isTile = urlStr.includes('tile?') || urlStr.includes('/tile/');
        
        // Capture current generation to ignore stale callbacks after reset
        var fetchGeneration = window.tileState.generation;
        
        if (isTile) {
          window.tileState.pendingFetches++;
          window.tileState.hasActivity = true;
        }
        
        return originalFetch.apply(this, arguments)
          .then(function(response) {
            // Only count if same generation (not stale from before reset)
            if (isTile && fetchGeneration === window.tileState.generation) {
              window.tileState.pendingFetches--;
              window.tileState.completedFetches++;
              
              // Track when image data is fully decoded by reading the blob
              var clone = response.clone();
              clone.blob().then(function(blob) {
                if (fetchGeneration === window.tileState.generation) {
                  window.tileState.decodedImages++;
                  window.tileState.lastDecodeTime = performance.now();
                }
              }).catch(function() {
                if (fetchGeneration === window.tileState.generation) {
                  window.tileState.decodedImages++;
                  window.tileState.lastDecodeTime = performance.now();
                }
              });
            }
            return response;
          })
          .catch(function(err) {
            if (isTile && fetchGeneration === window.tileState.generation) {
              window.tileState.pendingFetches--;
              window.tileState.failedFetches++;
            }
            throw err;
          });
      };
    }
    
    // Reset state for new tile position
    window.resetTileDetection = function() {
      window.tileState.generation++;
      window.tileState.pendingFetches = 0;
      window.tileState.completedFetches = 0;
      window.tileState.decodedImages = 0;
      window.tileState.failedFetches = 0;
      window.tileState.lastDecodeTime = performance.now();
      window.tileState.hasActivity = false;
    };
    
    // Check if tiles are fully loaded and rendered
    window.areTilesReady = function() {
      var s = window.tileState;
      var now = performance.now();
      var timeSinceDecode = now - s.lastDecodeTime;
      
      // Must have activity (at least one fetch started this generation)
      if (!s.hasActivity) return false;
      
      return s.pendingFetches === 0 &&           // No pending requests
             s.completedFetches > 0 &&            // At least one tile loaded
             s.decodedImages >= s.completedFetches && // All images decoded
             timeSinceDecode >= 50;               // Brief buffer for GPU upload
    };
    
    // Get detailed status for monitoring
    window.getTileStatus = function() {
      var now = performance.now();
      return {
        pending: window.tileState.pendingFetches,
        completed: window.tileState.completedFetches,
        decoded: window.tileState.decodedImages,
        failed: window.tileState.failedFetches,
        timeSinceDecode: Math.round(now - window.tileState.lastDecodeTime),
        ready: window.areTilesReady()
      };
    };
  "))

(defn wait-for-tiles
  "Waits for satellite tiles to fully load using intelligent detection.
   
   No hardcoded delays - detects actual image decode completion:
   1. Tracks pending tile fetch requests  
   2. Monitors when image blobs are fully decoded
   3. Waits until all images are decoded + 50ms GPU buffer
   4. Falls back to max wait time if detection fails
   
   This adapts to network speed automatically - fast connection = fast capture,
   slow connection = waits as long as needed.
   
   Note: Reset happens in move-to-tile BEFORE the move, not here.
   
   Parameters in config:
   - :tile-wait-ms - Max wait time in ms (default: 5000, safety net)"
  [driver {:keys [tile-wait-ms]}]
  (let [max-wait (or tile-wait-ms 5000)    ; Safety net max wait
        check-interval 25                   ; Check every 25ms for responsiveness
        start (System/currentTimeMillis)]
    
    ;; Poll until tiles are ready or timeout
    ;; Note: reset already happened in move-to-tile
    (loop []
      (let [elapsed (- (System/currentTimeMillis) start)]
        (when (< elapsed max-wait)
          (let [ready (try
                        (e/js-execute driver "return window.areTilesReady();")
                        (catch Exception _ false))]
            (when (not ready)
              (Thread/sleep check-interval)
              (recur))))))))
