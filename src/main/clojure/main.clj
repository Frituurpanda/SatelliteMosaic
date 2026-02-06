(ns main
  "Apple Maps tile capture and stitching tool.
   
   Entry points for CLI and REPL usage. The actual implementation
   is split across the stitcher.* namespaces:
   
   - stitcher.config   - Configuration and city centers
   - stitcher.browser  - Browser setup and map control
   - stitcher.overlay  - Interactive UI (CSS/HTML/JS)
   - stitcher.grid     - Grid calculations and tile loading
   - stitcher.capture  - Screenshots and stitching"
  (:require
   [clojure.java.io :as io]
   [etaoin.api :as e]
   [stitcher.config :as config]
   [stitcher.browser :as browser]
   [stitcher.overlay :as overlay]
   [stitcher.grid :as grid]
   [stitcher.capture :as capture]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- vipsdisp-available?
  "Checks if vipsdisp is installed."
  []
  (try
    (let [result (-> (ProcessBuilder. ["which" "vipsdisp"])
                     (.start)
                     (.waitFor))]
      (zero? result))
    (catch Exception _ false)))

(defn- open-with-vipsdisp
  "Opens an image file with vipsdisp in the background."
  [file-path]
  (when (vipsdisp-available?)
    (println "Opening in vipsdisp...")
    (-> (ProcessBuilder. ["vipsdisp" file-path])
        (.inheritIO)
        (.start))))

(defn- print-reproduce-command
  "Prints a CLI command to reproduce the exact same capture."
  [{:keys [center-lat center-lon rows cols zoom map-type]}]
  (println)
  (println "To reproduce this exact capture:")
  (println)
  (println (format "clj -X main/capture-map :center-lat %s :center-lon %s :rows %d :cols %d :zoom %d :map-type '\"%s\"'"
                   center-lat center-lon rows cols zoom (or map-type "satellite")))
  (println))

;; =============================================================================
;; Scripted Capture Mode
;; =============================================================================

(defn capture-grid
  "Captures all tiles in the grid."
  [driver config]
  (let [{:keys [rows cols output-dir]} config
        tiles-dir (str output-dir "/tiles")]
    
    ;; Ensure output directory exists
    (capture/ensure-dir tiles-dir)
    
    (println "Starting capture...")
    
    ;; Calculate all tile positions
    (let [positions (grid/calculate-grid-positions driver rows cols)
          total (count positions)
          start-time (System/currentTimeMillis)]
      
      ;; Reset timing tracker for accurate ETA
      (capture/reset-tile-timing!)
      
      ;; Capture each tile with progress bar
      (doseq [[idx pos] (map-indexed vector positions)]
        (let [{:keys [row col]} pos
              tile-start (System/currentTimeMillis)]
          (capture/print-progress (inc idx) total row col start-time)
          (grid/move-to-tile driver pos)
          (grid/wait-for-tiles driver config)
          (capture/take-tile-screenshot driver tiles-dir row col)
          ;; Track this tile's time for EMA
          (capture/update-tile-timing! (- (System/currentTimeMillis) tile-start))))
      
      (capture/print-progress-complete total (- (System/currentTimeMillis) start-time))
      tiles-dir)))

(defn capture-map
  "Main entry point for scripted map capture.
   
   Args map:
   - :city       - City name (default: 'sanfrancisco')
   - :rows       - Number of rows in the grid (default: 10)
   - :cols       - Number of columns in the grid (default: 10)
   - :zoom       - Zoom level, 100 = max detail (default: 100)
   - :hidpi      - Use 2x resolution for Retina (default: true)
   - :output-dir - Base output directory (default: 'output')
   - :headless   - Run headless (default: false)
   - :stitch     - Auto-stitch after capture (default: true)
   - :center-lat - Override center latitude
   - :center-lon - Override center longitude"
  [args]
  (let [cfg (merge config/default-config args)
        {:keys [rows cols output-dir zoom city viewport-size hidpi]} cfg
        stitch? (get cfg :stitch true)
        ;; Create timestamped output directory
        run-dir (capture/make-output-dir output-dir)
        cfg (assoc cfg :output-dir run-dir)
        ;; Calculate tile dimensions
        hidpi-mult (if hidpi 2 1)
        tile-size (* viewport-size hidpi-mult)]
    
    (println "")
    (println "=== SatelliteMosaic ===")
    (println (format "City: %s | Output: %s" city run-dir))
    
    ;; Print comprehensive stats
    (capture/print-capture-stats {:rows rows
                                  :cols cols
                                  :tile-width tile-size
                                  :tile-height tile-size
                                  :zoom zoom})
    
    (let [driver (browser/create-driver cfg)]
      (try
        ;; Setup the map
        (println "Setting up map...")
        (browser/setup-map driver cfg)
        
        ;; Set zoom level
        (println "Setting zoom level...")
        (browser/set-zoom-level driver zoom)
        (e/wait 2)
        
        ;; Setup tile detection
        (grid/setup-tile-detection driver)
        
        ;; Get actual center coordinates and map type for reproduce command
        (let [region (browser/get-current-region driver)
              actual-center-lat (:centerLat region)
              actual-center-lon (:centerLon region)
              actual-map-type (browser/get-map-type driver)]
          
          ;; Capture the grid
          (let [tiles-dir (capture-grid driver cfg)
                output-file (str run-dir "/stitched.png")]
            
            ;; Stitch if requested
            (when stitch?
              (println)
              (println "========================================")
              (println "Stitching tiles...")
              (println "========================================")
              (capture/stitch-tiles-auto {:input-dir tiles-dir
                                          :output-file output-file
                                          :rows rows
                                          :cols cols})
              
              ;; Open in vipsdisp if available
              (open-with-vipsdisp output-file))
            
            (println)
            (println "Done!")
            
            ;; Print reproduce command with actual coordinates and map type
            (print-reproduce-command {:center-lat actual-center-lat
                                      :center-lon actual-center-lon
                                      :map-type actual-map-type
                                      :rows rows
                                      :cols cols
                                      :zoom zoom})))
        
        (finally
          (e/quit driver))))))

;; =============================================================================
;; Interactive Capture Mode
;; =============================================================================

(defn capture-interactive
  "Interactive capture mode with visual area selection.
   
   Opens a browser window where you can:
   1. Pan/zoom the map to find your area
   2. Switch to Satellite view
   3. Click 'Select Capture Area' to show the selection box
   4. Drag/resize the green box to select capture region
   5. Adjust zoom level for tile detail
   6. Click 'Start Capture' to begin
   
   Args map:
   - :city       - City to load (default: 'sanfrancisco')
   - :hidpi      - Use 2x resolution (default: true)
   - :output-dir - Output directory (default: 'output')"
  ([] (capture-interactive {}))
  ([args]
   (let [cfg (merge {:city "sanfrancisco"
                     :viewport-size 1200
                     :hidpi true
                     :output-dir "output"
                     :tile-wait-ms 2000}
                    args)
         {:keys [city output-dir]} cfg]
     
     (println "")
     (println "=== SatelliteMosaic ===")
     (println "")
     (println "Browser opening... Navigate to your target area,")
     (println "switch to Satellite view, then click 'Select Capture Area'.")
     (println "")
     
     (let [driver (browser/create-driver cfg)]
       (try
         ;; Navigate to map in standard view
         (browser/setup-map-interactive driver city)
         
         ;; Inject interactive overlay
         (overlay/inject-overlay driver)
         
         ;; Main capture loop (allows multiple captures)
         (loop []
          ;; Wait for user selection
          (let [selection (overlay/wait-for-user-selection driver)
                {:keys [rows cols zoom]} selection
                ;; Create timestamped output directory
                run-dir (capture/make-output-dir output-dir)
                tiles-dir (str run-dir "/tiles")
                ;; Calculate tile dimensions (viewport-size × hidpi multiplier)
                viewport (:viewport-size cfg)
                hidpi-mult (if (:hidpi cfg) 2 1)
                tile-size (* viewport hidpi-mult)]
            
            ;; Print capture stats
            (capture/print-capture-stats {:rows rows
                                          :cols cols
                                          :tile-width tile-size
                                          :tile-height tile-size
                                          :zoom zoom})
            
            ;; Hide all UI
            (browser/hide-all-ui driver)
            (e/wait 1)
            
            ;; Ensure output directory
            (capture/ensure-dir tiles-dir)
            
            ;; Set the zoom level
            (browser/set-zoom-level driver zoom)
            (e/wait 1)
            
            ;; Setup tile detection for faster capture
            (grid/setup-tile-detection driver)
            
            ;; Calculate and capture tiles
            (let [positions (grid/calculate-grid-from-selection driver selection)
                  total (count positions)
                  start-time (System/currentTimeMillis)]
              
              ;; Reset timing tracker for accurate ETA
              (capture/reset-tile-timing!)
              
              (doseq [[idx pos] (map-indexed vector positions)]
                (let [{:keys [row col]} pos
                      tile-start (System/currentTimeMillis)]
                  (capture/print-progress (inc idx) total row col start-time)
                  (grid/move-to-tile driver pos)
                  (grid/wait-for-tiles driver cfg)
                  (capture/take-tile-screenshot driver tiles-dir row col)
                  ;; Track this tile's time for EMA
                  (capture/update-tile-timing! (- (System/currentTimeMillis) tile-start))))
              
              (capture/print-progress-complete total (- (System/currentTimeMillis) start-time))
              
              ;; Show stitching progress in browser UI
              (overlay/show-stitching driver)
              
              ;; Stitch
              (let [output-file (str run-dir "/stitched.png")]
                (println "Stitching...")
                (capture/stitch-tiles-auto {:input-dir tiles-dir
                                            :output-file output-file
                                            :rows rows
                                            :cols cols})
                
                ;; Open in vipsdisp if available
                (open-with-vipsdisp output-file)))
             
             ;; Show completion panel in browser
             (let [abs-path (.getAbsolutePath (io/file run-dir))]
               (println (format "Done! Output: %s" abs-path))
               (print-reproduce-command selection)
               (overlay/show-completion driver abs-path))
             
             ;; Wait for user to click New Capture or close browser
             (overlay/wait-for-restart driver)
             
             ;; User wants another capture - reset and loop
             (println "Ready for new capture...")
             (overlay/reset-for-new-capture driver)
             (recur)))
         
        (catch clojure.lang.ExceptionInfo e
          ;; Check if it's a "window closed" error (user closed browser)
          (let [data (ex-data e)
                msg (or (get-in data [:response :value :message]) "")]
            (if (or (.contains msg "no such window")
                    (.contains msg "target window already closed"))
              (println "\nBrowser closed. Goodbye!")
              (do
                (println "Error occurred:")
                (println (.getMessage e))))))
        
        (catch Exception e
          (println "Error occurred:")
          (println (.getMessage e)))
        
        (finally
          (try (e/quit driver) (catch Exception _))))))))

;; =============================================================================
;; REPL Helpers
;; =============================================================================

(defn demo
  "Quick demo: captures a small 3x3 grid at max quality.
   Pass opts map to override any defaults."
  ([] (demo {}))
  ([opts]
   (capture-map (merge {:city "sanfrancisco"
                        :rows 3
                        :cols 3
                        :zoom 100
                        :hidpi true
                        :output-dir "output/demo"}
                       opts))))

(defn stitch-tiles
  "Convenience wrapper for stitching existing tiles.
   
   Args map:
   - :input-dir   - Directory containing tile images
   - :output-file - Output file path
   - :rows        - Number of rows
   - :cols        - Number of columns"
  [args]
  (capture/stitch-tiles-auto args))

(comment
  ;; ============================================
  ;; INTERACTIVE MODE (recommended)
  ;; ============================================
  ;; Opens a visual interface to select your area
  (capture-interactive)
  
  ;; With options
  (capture-interactive {:city "newyork"
                        :output-dir "output/nyc"})
  
  ;; ============================================
  ;; SCRIPTED MODE (for automation)
  ;; ============================================
  
  ;; Quick demo - 3x3 grid at max quality
  (demo)
  
  ;; Full 10x10 capture at max quality
  (capture-map {:city "sanfrancisco"
                :rows 10
                :cols 10
                :zoom 100
                :hidpi true
                :output-dir "output/sf"})
  
  ;; Custom coordinates (Golden Gate Bridge)
  (capture-map {:city "sanfrancisco"
                :center-lat 37.8199
                :center-lon -122.4783
                :rows 3
                :cols 3
                :zoom 100
                :hidpi true
                :output-dir "output/golden-gate"})
  
  ;; Just stitch existing tiles
  (stitch-tiles {:input-dir "output/tiles"
                 :output-file "output/stitched.png"
                 :rows 10
                 :cols 10}))
