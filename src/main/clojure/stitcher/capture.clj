(ns stitcher.capture
  "Screenshot capture and image stitching functions."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [etaoin.api :as e])
  (:import
   [java.awt.image BufferedImage]
   [javax.imageio ImageIO]))

;; =============================================================================
;; File System Utilities
;; =============================================================================

(defn ensure-dir
  "Creates directory if it doesn't exist."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    path))

(defn timestamp-str
  "Returns a timestamp string for folder naming: YYYY-MM-DD_HH-MM-SS"
  []
  (let [now (java.time.LocalDateTime/now)
        fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")]
    (.format now fmt)))

(defn make-output-dir
  "Creates a timestamped output directory under the base path.
   Returns the full path to the new directory."
  [base-path]
  (let [ts (timestamp-str)
        full-path (str base-path "/" ts)]
    (ensure-dir full-path)
    full-path))

;; =============================================================================
;; Progress Display
;; =============================================================================

;; Default estimate based on real-world data with intelligent detection:
;; - 9 tiles in 6s = ~800ms per tile (M2 Pro with intelligent detection)
;; - City tiles with detail may take slightly longer
;; - Using 800ms as baseline (no hardcoded delays, actual decode detection)
(def default-ms-per-tile 800)

;; Exponential moving average smoothing factor (0-1)
;; Higher = more weight on recent tiles, faster adaptation
;; Lower = smoother but slower to adapt
(def ^:private ema-alpha 0.3)

;; Atom to track timing across tiles for EMA calculation
(def ^:private tile-timing (atom {:last-tile-ms nil
                                   :ema-ms nil}))

(defn reset-tile-timing!
  "Resets the tile timing tracker. Call at start of capture."
  []
  (reset! tile-timing {:last-tile-ms nil
                       :ema-ms nil}))

(defn update-tile-timing!
  "Updates the exponential moving average with a new tile time."
  [tile-ms]
  (swap! tile-timing
         (fn [{:keys [ema-ms]}]
           {:last-tile-ms tile-ms
            :ema-ms (if ema-ms
                      ;; EMA: new = alpha * current + (1-alpha) * previous
                      (+ (* ema-alpha tile-ms) (* (- 1 ema-alpha) ema-ms))
                      ;; First tile - use actual time
                      tile-ms)})))

(defn progress-bar
  "Returns a string progress bar like: [████████░░░░░░░░] 50%"
  [current total width]
  (let [pct (double (/ current total))
        filled (int (* pct width))
        empty (- width filled)
        bar (str (apply str (repeat filled "█"))
                 (apply str (repeat empty "░")))]
    (format "[%s] %3d%%" bar (int (* pct 100)))))

(defn format-time
  "Formats milliseconds as a human-readable time string."
  [ms]
  (let [sec (int (/ ms 1000))]
    (cond
      (>= sec 3600) (format "%dh %dm" (quot sec 3600) (mod (quot sec 60) 60))
      (>= sec 60) (format "%dm %ds" (quot sec 60) (mod sec 60))
      :else (format "%ds" sec))))

(defn format-bytes
  "Formats bytes as human-readable size (KB, MB, GB)."
  [bytes]
  (cond
    (>= bytes 1073741824) (format "%.1f GB" (/ bytes 1073741824.0))
    (>= bytes 1048576) (format "%.1f MB" (/ bytes 1048576.0))
    (>= bytes 1024) (format "%.1f KB" (/ bytes 1024.0))
    :else (format "%d bytes" bytes)))

(defn format-pixels
  "Formats pixel count as human-readable (millions, billions)."
  [pixels]
  (cond
    (>= pixels 1000000000) (format "%.2f billion" (/ pixels 1000000000.0))
    (>= pixels 1000000) (format "%.1f million" (/ pixels 1000000.0))
    (>= pixels 1000) (format "%,d" pixels)
    :else (str pixels)))

(defn estimate-png-size
  "Estimates PNG file size based on resolution.
   PNG compression varies wildly by content:
   - Dense urban satellite: ~1.5-2 bytes per pixel
   - Mixed/water areas: ~0.8-1.2 bytes per pixel
   - Using 1.2 bytes/pixel based on real-world benchmarks:
     14.12B pixels → 15.4 GB = 1.09 bytes/pixel"
  [width height]
  (long (* width height 1.2)))

(def ^:private box-width
  "Total width of the stats box (including borders)."
  55)

(defn- format-row
  "Formats a row for the stats box with proper alignment.
   Uses fixed-width formatting to ensure right edge aligns."
  [label value]
  ;; Box inner width = box-width - 2 (for │ on each side) = 53
  ;; Format: "│  label          value                            │"
  ;; We use %-14s for label (14 chars) and pad value to fill rest
  (let [inner-width (- box-width 2)  ; 53 chars inside
        prefix "  "                   ; 2 spaces after left │
        suffix " "                    ; 1 space before right │
        label-width 14
        ;; Available for value = inner - prefix(2) - label(14) - suffix(1) = 36
        value-width (- inner-width (count prefix) label-width (count suffix))
        formatted-label (format "%-14s" label)
        formatted-value (format (str "%-" value-width "s") value)]
    (str "│" prefix formatted-label formatted-value suffix "│")))

(defn- box-line
  "Creates a horizontal box line (top, middle, or bottom)."
  [left middle right]
  (str left (apply str (repeat (- box-width 2) middle)) right))

(defn- print-box-header
  "Prints the box header with title centered."
  [title]
  (let [inner-width (- box-width 2)
        title-len (count title)
        left-pad (quot (- inner-width title-len) 2)
        right-pad (- inner-width title-len left-pad)]
    (println (box-line "┌" "─" "┐"))
    (println (str "│" (apply str (repeat left-pad " ")) title (apply str (repeat right-pad " ")) "│"))
    (println (box-line "├" "─" "┤"))))

(defn print-capture-stats
  "Prints comprehensive capture statistics before starting.
   
   Args:
   - rows/cols: grid dimensions
   - tile-width/tile-height: individual tile size in pixels  
   - zoom: zoom level"
  [{:keys [rows cols tile-width tile-height zoom]}]
  (let [total-tiles (* rows cols)
        final-width (* tile-width cols)
        final-height (* tile-height rows)
        total-pixels (* (long final-width) (long final-height))
        estimated-size (estimate-png-size final-width final-height)
        estimated-time-ms (* total-tiles default-ms-per-tile)]
    (println)
    (print-box-header "CAPTURE SUMMARY")
    (println (format-row "Grid:" (format "%d x %d tiles (%,d total)" cols rows total-tiles)))
    (println (format-row "Zoom level:" (str zoom)))
    (println (format-row "Tile size:" (format "%,d x %,d px" tile-width tile-height)))
    (println (box-line "├" "─" "┤"))
    (println (format-row "Final image:" (format "%,d x %,d px" final-width final-height)))
    (println (format-row "Total pixels:" (format-pixels total-pixels)))
    (println (format-row "Est. size:" (str "~" (format-bytes estimated-size))))
    (println (box-line "├" "─" "┤"))
    (println (format-row "Est. time:" (str "~" (format-time estimated-time-ms))))
    (println (box-line "└" "─" "┘"))
    (println)))

(defn estimate-ms-per-tile
  "Estimates time per tile using exponential moving average.
   Weights recent tiles more heavily for faster adaptation to changing conditions
   (e.g., transitioning from fast ocean tiles to slow detailed city tiles)."
  [elapsed-ms current]
  (let [{:keys [ema-ms]} @tile-timing
        ;; Use EMA if we have enough data, otherwise fall back to average
        avg-ms (if (pos? current) (/ elapsed-ms current) default-ms-per-tile)]
    (cond
      ;; Use EMA if available (adapts faster to recent tile times)
      ema-ms ema-ms
      ;; Fall back to running average if we have some data
      (pos? current) avg-ms
      ;; Default for first tile
      :else default-ms-per-tile)))

(defn print-progress
  "Prints a dynamic progress line that updates in place.
   Shows: [████░░░░] 25% | Tile 5/20 (r1,c2) | ~15s left
   
   Uses exponential moving average for better ETA estimation when
   tile complexity varies (ocean vs city)."
  [current total row col start-time-ms]
  (let [elapsed-ms (- (System/currentTimeMillis) start-time-ms)
        ms-per-tile (estimate-ms-per-tile elapsed-ms current)
        remaining-tiles (- total current)
        remaining-ms (* remaining-tiles ms-per-tile)
        time-str (format-time remaining-ms)
        bar (progress-bar current total 20)
        ;; Use ANSI escape to clear to end of line: \033[K
        line (format "\r  %s | Tile %d/%d (r%d,c%d) | ~%s left\u001b[K" 
                     bar current total row col time-str)]
    (print line)
    (flush)))

(defn print-progress-complete
  "Prints the final progress line when complete."
  [total elapsed-ms]
  (let [time-str (format-time elapsed-ms)
        bar (progress-bar total total 20)
        avg-ms (/ elapsed-ms total)]
    ;; \u001b[K clears to end of line
    (print (format "\r  %s | Done! %d tiles in %s (avg %.1fs/tile)\u001b[K\n" 
                   bar total time-str (/ avg-ms 1000.0)))
    (flush)))

;; =============================================================================
;; Screenshot Capture
;; =============================================================================

(defn tile-filename
  "Generates a filename for a tile: tile_r00_c00.png"
  [row col]
  (format "tile_r%02d_c%02d.png" row col))

(defn take-tile-screenshot
  "Takes a screenshot of the current viewport and saves it."
  [driver output-dir row col]
  (let [filename (tile-filename row col)
        filepath (str output-dir "/" filename)]
    (e/screenshot driver filepath)
    filepath))

(defn take-tile-screenshot-verbose
  "Takes a screenshot with console output."
  [driver output-dir row col]
  (let [filepath (take-tile-screenshot driver output-dir row col)]
    (println (format "  Captured tile [%d, %d] -> %s" row col (tile-filename row col)))
    filepath))

;; =============================================================================
;; Image Stitching
;; =============================================================================

(defn load-image
  "Loads a PNG image from disk."
  [path]
  (ImageIO/read (io/file path)))

(def max-java-pixels
  "Maximum pixels for Java BufferedImage (~2GB limit with 4 bytes/pixel).
   We use 500 million as a safe limit (~2GB memory)."
  500000000)

(defn imagemagick-available?
  "Checks if ImageMagick is available on the system."
  []
  (try
    (let [result (shell/sh "which" "magick")]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn vips-available?
  "Checks if libvips (vips command) is available on the system.
   vips is 7-8x faster than ImageMagick for large image operations."
  []
  (try
    (let [result (shell/sh "which" "vips")]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn validate-stitch-size
  "Validates that the stitched image won't exceed limits.
   Returns {:ok true :method :java|:imagemagick} or {:ok false :error \"message\"}."
  [tile-width tile-height rows cols]
  (let [total-width (* tile-width cols)
        total-height (* tile-height rows)
        total-pixels (* (long total-width) (long total-height))
        memory-gb (/ (* total-pixels 4) 1024 1024 1024.0)
        has-imagemagick (imagemagick-available?)]
    (cond
      ;; Small enough for Java - use it (faster)
      (<= total-pixels max-java-pixels)
      {:ok true :method :java :memory-gb memory-gb}
      
      ;; Too big for Java but ImageMagick is available
      has-imagemagick
      {:ok true :method :imagemagick :memory-gb memory-gb
       :note "Using ImageMagick for large image (streams to disk)"}
      
      ;; Too big and no ImageMagick
      :else
      {:ok false
       :error (format "Image too large for Java (%,d pixels, ~%.1f GB). Install ImageMagick: brew install imagemagick"
                      total-pixels memory-gb)})))

(defn stitch-tiles
  "Stitches all tiles into a single large image.
   
   Args map:
   - :input-dir  - Directory containing tile images
   - :output-file - Output file path
   - :rows - Number of rows in the grid
   - :cols - Number of columns in the grid"
  [{:keys [input-dir output-file rows cols]}]
  (println (format "Stitching %dx%d tiles from %s..." rows cols input-dir))
  
  ;; Load first tile to get dimensions
  (let [first-tile (load-image (str input-dir "/" (tile-filename 0 0)))
        tile-width (.getWidth first-tile)
        tile-height (.getHeight first-tile)
        validation (validate-stitch-size tile-width tile-height rows cols)]
    
    (if-not (:ok validation)
      (do
        (println (str "\n  ERROR: " (:error validation)))
        (println "  Tiles are saved - you can stitch them manually with other tools.")
        nil)
      
      (let [total-width (* tile-width cols)
            total-height (* tile-height rows)
            _ (println (format "  Tile size: %dx%d, Final size: %dx%d (~%.1f GB)"
                               tile-width tile-height total-width total-height (:memory-gb validation)))
            output-image (BufferedImage. total-width total-height BufferedImage/TYPE_INT_RGB)
            graphics (.createGraphics output-image)]
        
        ;; Draw each tile at its position
        (doseq [row (range rows)
                col (range cols)]
          (let [tile-path (str input-dir "/" (tile-filename row col))
                tile-file (io/file tile-path)]
            (if (.exists tile-file)
              (let [tile (load-image tile-path)
                    x (* col tile-width)
                    y (* row tile-height)]
                (.drawImage graphics tile x y nil)
                (print "."))
              (println (format "\n  Warning: Missing tile %s" tile-path)))))
        
        (.dispose graphics)
        
        ;; Ensure output directory exists
        (ensure-dir (.getParent (io/file output-file)))
        
        ;; Save the final image
        (ImageIO/write output-image "PNG" (io/file output-file))
        (println)
        (println (format "Saved stitched image to: %s" output-file))
        (println (format "Final resolution: %dx%d pixels" total-width total-height))
        output-file))))

(defn- estimate-stitch-time
  "Estimates stitching time based on total pixels.
   Based on benchmarks: ~0.5-2 seconds per million pixels for montage,
   depending on disk speed and compression. Using 1.0s/Mpx as estimate
   for M1/M2 Macs with fast SSDs."
  [total-pixels]
  (let [megapixels (/ total-pixels 1000000.0)
        seconds-per-mpx 1.0]
    (* megapixels seconds-per-mpx 1000)))  ; Return milliseconds

(defn- stitch-progress-bar
  "Returns a progress bar for stitching phase.
   Shows actual file completion percentage."
  [phase current-size estimated-size elapsed-ms estimated-time-ms width]
  (let [;; Calculate progress based on actual file size
        pct (case phase
              :processing 0  ; No file yet, show 0%
              :writing (* 100 (/ current-size (max 1 estimated-size)))
              0)
        pct (min 99 (max 0 pct))  ; Clamp 0-99 until truly done
        filled (int (* (/ pct 100) width))
        empty (- width filled)
        bar (str (apply str (repeat filled "█"))
                 (apply str (repeat empty "░")))]
    (format "[%s] %3d%%" bar (int pct))))

(defn- monitor-stitch-progress
  "Monitors the output file growth and prints progress bar.
   Returns a future that can be cancelled when stitching is done."
  [output-file estimated-size-bytes estimated-time-ms start-time-ms]
  (future
    (try
      (loop [last-size 0
             phase :processing
             write-start-ms nil]  ; Track when writing actually started
        (Thread/sleep 1000)  ; Check every second
        (let [file (io/file output-file)
              current-size (if (.exists file) (.length file) 0)
              now-ms (System/currentTimeMillis)
              elapsed-ms (- now-ms start-time-ms)
              
              ;; Update phase and track when writing started
              current-phase (if (pos? current-size) :writing phase)
              current-write-start (if (and (= current-phase :writing) (nil? write-start-ms))
                                    now-ms
                                    write-start-ms)
              
              ;; Calculate ETA based on actual progress
              eta-ms (case current-phase
                       :processing 
                       (max 0 (- estimated-time-ms elapsed-ms))
                       
                       :writing
                       (if (and (pos? current-size) current-write-start)
                         (let [write-elapsed (- now-ms current-write-start)
                               ;; Bytes per millisecond based on actual write speed
                               write-rate (if (pos? write-elapsed)
                                            (/ current-size write-elapsed)
                                            0)
                               remaining-bytes (max 0 (- estimated-size-bytes current-size))]
                           (if (pos? write-rate)
                             (long (/ remaining-bytes write-rate))
                             estimated-time-ms))
                         estimated-time-ms)
                       
                       estimated-time-ms)
              
              eta-str (format-time (max 0 eta-ms))
              bar (stitch-progress-bar current-phase current-size estimated-size-bytes 
                                       elapsed-ms estimated-time-ms 20)
              
              ;; Build status line based on phase
              status (case current-phase
                       :processing (format "\r  %s | Processing tiles... | ~%s left\u001b[K"
                                           bar eta-str)
                       :writing (format "\r  %s | Writing %s / ~%s | ~%s left\u001b[K"
                                        bar
                                        (format-bytes current-size)
                                        (format-bytes estimated-size-bytes)
                                        eta-str))]
          (print status)
          (flush)
          (recur current-size current-phase current-write-start)))
      (catch InterruptedException _
        ;; Normal exit when cancelled
        nil))))

(defn stitch-tiles-imagemagick
  "Stitches tiles using ImageMagick's montage command.
   Handles very large images by streaming to disk instead of loading into RAM.
   Shows progress updates during the potentially long stitching process."
  [{:keys [input-dir output-file rows cols]}]
  
  ;; Load first tile to get dimensions for estimates
  (let [first-tile (load-image (str input-dir "/" (tile-filename 0 0)))
        tile-width (.getWidth first-tile)
        tile-height (.getHeight first-tile)
        total-tiles (* rows cols)
        final-width (* tile-width cols)
        final-height (* tile-height rows)
        total-pixels (* (long final-width) (long final-height))
        estimated-size (estimate-png-size final-width final-height)
        estimated-time-ms (estimate-stitch-time total-pixels)]
    
    (println)
    (print-box-header "STITCHING SUMMARY")
    (println (format-row "Tiles:" (format "%,d (%d x %d grid)" total-tiles cols rows)))
    (println (format-row "Final size:" (format "%,d x %,d px" final-width final-height)))
    (println (format-row "Total pixels:" (format-pixels total-pixels)))
    (println (format-row "Est. size:" (str "~" (format-bytes estimated-size))))
    (println (format-row "Est. time:" (str "~" (format-time estimated-time-ms))))
    (println (box-line "└" "─" "┘"))
    (println)
    
    ;; Build the list of tile files in correct order (row by row)
    (let [tile-files (for [row (range rows)
                           col (range cols)]
                       (str input-dir "/" (tile-filename row col)))
          missing (filter #(not (.exists (io/file %))) tile-files)]
      
      (if (seq missing)
        (do
          (println (format "  ERROR: Missing %d tiles" (count missing)))
          nil)
        
        (do
          (ensure-dir (.getParent (io/file output-file)))
          
          ;; Start progress monitor
          (let [start-time (System/currentTimeMillis)
                monitor (monitor-stitch-progress output-file estimated-size estimated-time-ms start-time)
                
                ;; Run montage
                result (apply shell/sh
                              "magick" "montage"
                              (concat tile-files
                                      ["-mode" "concatenate"
                                       "-tile" (str cols "x")
                                       output-file]))]
            
            ;; Stop the monitor
            (future-cancel monitor)
            
            (let [elapsed-ms (- (System/currentTimeMillis) start-time)
                  final-size (.length (io/file output-file))
                  bar (progress-bar 100 100 20)]
              
              (if (zero? (:exit result))
                (do
                  ;; Success - show completion bar like capture phase
                  (println (format "\r  %s | Done! Stitched in %s\u001b[K" bar (format-time elapsed-ms)))
                  (println)
                  (println (format "  Output:     %s" output-file))
                  (println (format "  File size:  %s" (format-bytes final-size)))
                  ;; Get final dimensions
                  (let [identify (shell/sh "magick" "identify" "-format" "%wx%h" output-file)]
                    (when (zero? (:exit identify))
                      (println (format "  Resolution: %s pixels" (clojure.string/trim (:out identify))))))
                  output-file)
                (do
                  (println (format "\r  %s | ERROR after %s\u001b[K" bar (format-time elapsed-ms)))
                  (println (format "  ImageMagick failed: %s" (:err result)))
                  nil)))))))))

;; =============================================================================
;; VIPS Stitching (7-8x faster than ImageMagick)
;; =============================================================================

(defn- estimate-vips-stitch-time
  "Estimates stitching time for vips (much faster than ImageMagick).
   Based on real benchmarks:
   - 14.12B pixels in 829s = 0.059s per million pixels
   - Using 0.06s/Mpx for estimate"
  [total-pixels]
  (let [megapixels (/ total-pixels 1000000.0)
        seconds-per-mpx 0.06]
    (* megapixels seconds-per-mpx 1000)))

(defn stitch-tiles-vips
  "Stitches tiles using libvips arrayjoin - 7-8x faster than ImageMagick.
   vips uses demand-driven processing and streams to disk efficiently."
  [{:keys [input-dir output-file rows cols]}]
  
  ;; Load first tile to get dimensions for estimates
  (let [first-tile (load-image (str input-dir "/" (tile-filename 0 0)))
        tile-width (.getWidth first-tile)
        tile-height (.getHeight first-tile)
        total-tiles (* rows cols)
        final-width (* tile-width cols)
        final-height (* tile-height rows)
        total-pixels (* (long final-width) (long final-height))
        estimated-size (estimate-png-size final-width final-height)
        estimated-time-ms (estimate-vips-stitch-time total-pixels)]
    
    (println)
    (print-box-header "STITCHING SUMMARY")
    (println (format-row "Tiles:" (format "%,d (%d x %d grid)" total-tiles cols rows)))
    (println (format-row "Final size:" (format "%,d x %,d px" final-width final-height)))
    (println (format-row "Total pixels:" (format-pixels total-pixels)))
    (println (format-row "Est. size:" (str "~" (format-bytes estimated-size))))
    (println (format-row "Est. time:" (str "~" (format-time estimated-time-ms))))
    (println (box-line "└" "─" "┘"))
    (println)
    
    ;; Build the list of tile files in correct order (row by row)
    (let [tile-files (for [row (range rows)
                           col (range cols)]
                       (str input-dir "/" (tile-filename row col)))
          missing (filter #(not (.exists (io/file %))) tile-files)]
      
      (if (seq missing)
        (do
          (println (format "  ERROR: Missing %d tiles" (count missing)))
          nil)
        
        (do
          (ensure-dir (.getParent (io/file output-file)))
          
          ;; Start progress monitor
          (let [start-time (System/currentTimeMillis)
                monitor (monitor-stitch-progress output-file estimated-size estimated-time-ms start-time)
                
                ;; Build space-separated file list for vips arrayjoin
                file-list (clojure.string/join " " tile-files)
                
                ;; Run vips arrayjoin
                ;; arrayjoin takes images and arranges them in a grid
                ;; --across sets how many images per row
                result (shell/sh "vips" "arrayjoin" file-list output-file
                                 "--across" (str cols))]
            
            ;; Stop the monitor
            (future-cancel monitor)
            
            (let [elapsed-ms (- (System/currentTimeMillis) start-time)
                  final-size (.length (io/file output-file))
                  bar (progress-bar 100 100 20)]
              
              (if (zero? (:exit result))
                (do
                  ;; Success
                  (println (format "\r  %s | Done! Stitched in %s\u001b[K" bar (format-time elapsed-ms)))
                  (println)
                  (println (format "  Output:     %s" output-file))
                  (println (format "  File size:  %s" (format-bytes final-size)))
                  ;; Get final dimensions using vips
                  (let [identify (shell/sh "vips" "image-get" output-file "width")]
                    (when (zero? (:exit identify))
                      (let [width-result (shell/sh "vips" "image-get" output-file "width")
                            height-result (shell/sh "vips" "image-get" output-file "height")]
                        (when (and (zero? (:exit width-result)) (zero? (:exit height-result)))
                          (println (format "  Resolution: %sx%s pixels" 
                                           (clojure.string/trim (:out width-result))
                                           (clojure.string/trim (:out height-result))))))))
                  output-file)
                (do
                  (println (format "\r  %s | ERROR after %s\u001b[K" bar (format-time elapsed-ms)))
                  (println (format "  vips failed: %s" (:err result)))
                  ;; Try falling back to ImageMagick
                  (println "  Falling back to ImageMagick...")
                  nil)))))))))

(defn stitch-tiles-auto
  "Stitches tiles using the best available tool.
   Prefers vips (7-8x faster) over ImageMagick."
  [{:keys [input-dir output-file rows cols] :as args}]
  (let [first-tile-path (str input-dir "/" (tile-filename 0 0))
        use-vips (vips-available?)
        use-imagemagick (imagemagick-available?)]
    
    (cond
      (not (.exists (io/file first-tile-path)))
      (do
        (println "  ERROR: No tiles found to stitch")
        nil)
      
      ;; Prefer vips - it's 7-8x faster
      use-vips
      (or (stitch-tiles-vips args)
          ;; Fall back to ImageMagick if vips fails
          (when use-imagemagick
            (stitch-tiles-imagemagick args)))
      
      ;; Fall back to ImageMagick
      use-imagemagick
      (stitch-tiles-imagemagick args)
      
      :else
      (do
        (println "  ERROR: No stitching tool found!")
        (println "  Install one of:")
        (println "    brew install vips        (recommended, 7-8x faster)")
        (println "    brew install imagemagick")
        nil))))
