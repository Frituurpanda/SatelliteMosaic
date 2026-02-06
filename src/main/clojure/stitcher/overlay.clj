(ns stitcher.overlay
  "Interactive UI overlay for capture area selection.
   Contains CSS, HTML, and JavaScript for the browser-based UI."
  (:require
   [etaoin.api :as e]))

;; =============================================================================
;; CSS Styles
;; =============================================================================

(def overlay-css "
  #capture-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    pointer-events: none;
    z-index: 10000;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  }
  
  /* Initial floating button */
  #select-area-btn {
    position: fixed;
    bottom: 30px;
    left: 50%;
    transform: translateX(-50%);
    background: #22c55e;
    color: white;
    border: none;
    padding: 14px 28px;
    border-radius: 30px;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    pointer-events: auto;
    box-shadow: 0 4px 20px rgba(0,0,0,0.4);
    transition: all 0.2s;
  }
  
  #select-area-btn:hover {
    background: #16a34a;
    transform: translateX(-50%) scale(1.05);
  }
  
  #coach-text {
    position: fixed;
    bottom: 90px;
    left: 50%;
    transform: translateX(-50%);
    background: rgba(0, 0, 0, 0.8);
    color: white;
    padding: 12px 20px;
    border-radius: 8px;
    font-size: 14px;
    pointer-events: none;
    text-align: center;
    max-width: 400px;
  }
  
  /* Capture controls panel (hidden initially) */
  #capture-controls {
    position: fixed;
    top: 10px;
    left: 10px;
    background: rgba(0, 0, 0, 0.85);
    color: white;
    padding: 20px;
    border-radius: 12px;
    pointer-events: auto;
    min-width: 240px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.3);
    display: none;
  }
  
  #capture-controls h3 {
    margin: 0 0 15px 0;
    font-size: 16px;
    font-weight: 600;
  }
  
  #capture-controls label {
    display: block;
    margin: 12px 0 6px 0;
    font-size: 13px;
    color: #aaa;
  }
  
  #capture-controls input[type=range] {
    width: 100%;
    margin: 5px 0;
  }
  
  #capture-controls .value-display {
    font-size: 14px;
    font-weight: 500;
    color: #4ade80;
  }
  
  #capture-controls .info {
    margin-top: 15px;
    padding-top: 15px;
    border-top: 1px solid #333;
    font-size: 12px;
    color: #888;
  }
  
  #capture-controls .info div {
    margin: 4px 0;
  }
  
  .control-btn {
    width: 100%;
    margin-top: 12px;
    padding: 12px;
    color: white;
    border: none;
    border-radius: 8px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.2s;
  }
  
  #capture-btn {
    background: #22c55e;
  }
  
  #capture-btn:hover {
    background: #16a34a;
  }
  
  #cancel-btn {
    background: #6b7280;
  }
  
  #cancel-btn:hover {
    background: #4b5563;
  }
  
  /* Selection box (hidden initially) */
  #selection-box {
    position: fixed;
    border: 3px solid #22c55e;
    background: rgba(34, 197, 94, 0.15);
    pointer-events: auto;
    cursor: move;
    box-sizing: border-box;
    display: none;
  }
  
  #selection-box .resize-handle {
    position: absolute;
    width: 16px;
    height: 16px;
    background: #22c55e;
    border-radius: 3px;
  }
  
  #selection-box .resize-handle.nw { top: -8px; left: -8px; cursor: nw-resize; }
  #selection-box .resize-handle.ne { top: -8px; right: -8px; cursor: ne-resize; }
  #selection-box .resize-handle.sw { bottom: -8px; left: -8px; cursor: sw-resize; }
  #selection-box .resize-handle.se { bottom: -8px; right: -8px; cursor: se-resize; }
  
  #tile-grid {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    pointer-events: none;
  }
  
  .grid-line-v {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 2px;
    background: rgba(34, 197, 94, 0.8);
  }
  
  .grid-line-h {
    position: absolute;
    left: 0;
    right: 0;
    height: 2px;
    background: rgba(34, 197, 94, 0.8);
  }
  
  #selection-label {
    position: absolute;
    bottom: -30px;
    left: 50%;
    transform: translateX(-50%);
    background: rgba(0,0,0,0.8);
    color: #4ade80;
    padding: 4px 10px;
    border-radius: 4px;
    font-size: 12px;
    white-space: nowrap;
  }
  
  /* Status panel (used for stitching progress and completion) */
  #status-panel {
    position: fixed;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    background: rgba(0, 0, 0, 0.9);
    color: white;
    padding: 40px 50px;
    border-radius: 16px;
    text-align: center;
    pointer-events: auto;
    box-shadow: 0 8px 40px rgba(0,0,0,0.5);
    display: none;
    min-width: 300px;
  }
  
  #status-icon {
    font-size: 48px;
    margin-bottom: 15px;
  }
  
  #status-icon.loading {
    color: #60a5fa;
    animation: pulse 1.5s ease-in-out infinite;
  }
  
  #status-icon.complete {
    color: #22c55e;
  }
  
  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.5; }
  }
  
  #status-panel h3 {
    margin: 0 0 20px 0;
    font-size: 24px;
    font-weight: 600;
  }
  
  #status-message {
    font-size: 14px;
    color: #aaa;
    margin-bottom: 25px;
    word-break: break-all;
  }
  
  #status-message code {
    display: block;
    background: rgba(255,255,255,0.1);
    padding: 10px 15px;
    border-radius: 6px;
    margin-top: 8px;
    color: #4ade80;
    font-family: monospace;
  }
  
  #new-capture-btn {
    background: #22c55e;
    padding: 14px 30px;
    font-size: 16px;
    display: none;
  }
  
  #new-capture-btn:hover {
    background: #16a34a;
  }
  
  /* Legacy completion panel - hidden, using status-panel now */
  #completion-panel {
    display: none !important;
  }
")

;; =============================================================================
;; HTML Structure
;; =============================================================================

(def overlay-html "
  <div id='capture-overlay'>
    <!-- Initial state: coach text and button -->
    <div id='coach-text'>
      <strong>Step 1:</strong> Navigate to your target area<br>
      <strong>Step 2:</strong> Switch to Satellite view (top right menu)<br>
      <strong>Step 3:</strong> Click the button below to select capture area
    </div>
    <button id='select-area-btn'>Select Capture Area</button>
    
    <!-- Status panel (for stitching progress and completion) -->
    <div id='status-panel'>
      <div id='status-icon' class='loading'>⟳</div>
      <h3 id='status-title'>Processing...</h3>
      <div id='status-message'></div>
      <button id='new-capture-btn' class='control-btn'>New Capture</button>
    </div>
    
    <!-- Legacy completion panel (keeping for compatibility) -->
    <div id='completion-panel'></div>
    
    <!-- Capture controls (hidden initially) -->
    <div id='capture-controls'>
      <h3>Capture Settings</h3>
      
      <label>Tile Detail Level</label>
      <input type='range' id='zoom-slider' min='1' max='100' value='10'>
      <div class='value-display'>Zoom: <span id='zoom-value'>10</span></div>
      
      <div class='info'>
        <div>Tiles: <span id='info-tiles'>-</span></div>
        <div>Area: <span id='info-area'>-</span></div>
        <div>Resolution: <span id='info-resolution'>-</span></div>
        <div>PPI @ 1m edge: <span id='info-ppi'>-</span></div>
        <div>Est. file size: <span id='info-filesize'>-</span></div>
        <div>Est. capture time: <span id='info-time'>-</span></div>
        <div id='size-warning' style='color: #f87171; margin-top: 8px; display: none;'></div>
      </div>
      
      <button id='capture-btn' class='control-btn'>Start Capture</button>
      <button id='cancel-btn' class='control-btn'>Cancel</button>
    </div>
    
    <!-- Selection box (hidden initially) -->
    <div id='selection-box'>
      <div class='resize-handle nw'></div>
      <div class='resize-handle ne'></div>
      <div class='resize-handle sw'></div>
      <div class='resize-handle se'></div>
      <div id='tile-grid'></div>
      <div id='selection-label'>-</div>
    </div>
  </div>
")

;; =============================================================================
;; JavaScript Logic
;; =============================================================================

(def overlay-js "
  window.captureState = {
    zoom: 10,
    boxLeft: 100,
    boxTop: 100,
    boxWidth: 400,
    boxHeight: 400,
    ready: false,
    confirmed: false,
    selectionActive: false,
    initialMapType: null,
    initialRegion: null
  };
  
  // Capture initial state when overlay loads
  window.captureState.initialMapType = map.mapType;
  window.captureState.initialRegion = {
    lat: map.region.center.latitude,
    lon: map.region.center.longitude,
    spanLat: map.region.span.latitudeDelta,
    spanLon: map.region.span.longitudeDelta
  };
  
  function updateTileGrid() {
    const box = document.getElementById('selection-box');
    const grid = document.getElementById('tile-grid');
    const state = window.captureState;
    
    if (!state.selectionActive) return;
    
    // Calculate tiles based on box size and current map span
    const region = map.region;
    const mapEl = document.querySelector('.mk-map-view') || document.body;
    const mapRect = mapEl.getBoundingClientRect();
    
    // How many degrees per pixel at current zoom
    const degPerPixelLat = region.span.latitudeDelta / mapRect.height;
    const degPerPixelLon = region.span.longitudeDelta / mapRect.width;
    
    // Box size in degrees
    const boxDegreesLat = state.boxHeight * degPerPixelLat;
    const boxDegreesLon = state.boxWidth * degPerPixelLon;
    
    // Tile size in degrees (based on zoom level)
    const tileSpan = Math.max(0.0001, 0.1 / state.zoom);
    
    // How many tiles fit
    const tilesX = Math.max(1, Math.ceil(boxDegreesLon / tileSpan));
    const tilesY = Math.max(1, Math.ceil(boxDegreesLat / tileSpan));
    
    state.tilesX = tilesX;
    state.tilesY = tilesY;
    
    // Update grid lines - only show if reasonable number of tiles
    grid.innerHTML = '';
    const totalTiles = tilesX * tilesY;
    const showGrid = totalTiles <= 400; // Only show grid for 20x20 or less
    
    if (showGrid) {
      // Vertical lines
      for (let i = 1; i < tilesX; i++) {
        const line = document.createElement('div');
        line.className = 'grid-line-v';
        line.style.left = (i * 100 / tilesX) + '%';
        grid.appendChild(line);
      }
      
      // Horizontal lines
      for (let i = 1; i < tilesY; i++) {
        const line = document.createElement('div');
        line.className = 'grid-line-h';
        line.style.top = (i * 100 / tilesY) + '%';
        grid.appendChild(line);
      }
    }
    
    // Update label
    const label = tilesX + ' x ' + tilesY + ' = ' + totalTiles.toLocaleString() + ' tiles';
    document.getElementById('selection-label').textContent = label;
    
    // Update info panel
    document.getElementById('info-tiles').textContent = tilesX + ' x ' + tilesY + ' = ' + totalTiles.toLocaleString() + ' tiles';
    document.getElementById('info-area').textContent = 
      (boxDegreesLat * 111).toFixed(2) + ' x ' + (boxDegreesLon * 85).toFixed(2) + ' km';
    
    // Calculate resolution based on actual viewport size (HiDPI = 2x)
    const hidpiMultiplier = 2;
    const tilePixelsX = Math.round(mapRect.width * hidpiMultiplier);
    const tilePixelsY = Math.round(mapRect.height * hidpiMultiplier);
    const resX = tilesX * tilePixelsX;
    const resY = tilesY * tilePixelsY;
    document.getElementById('info-resolution').textContent = 
      resX.toLocaleString() + ' x ' + resY.toLocaleString() + ' px';
    
    // Calculate PPI if shorter edge printed at 1m
    const meterInches = 39.37;
    const smallerEdge = Math.min(resX, resY);
    const ppi = Math.round(smallerEdge / meterInches);
    document.getElementById('info-ppi').textContent = ppi.toLocaleString();
    
    // Estimate file size (satellite PNG ~1.5 bytes/pixel after compression)
    const totalPixels = resX * resY;
    const estimatedBytes = totalPixels * 1.5;
    let sizeStr;
    if (estimatedBytes >= 1024 * 1024 * 1024) {
      sizeStr = (estimatedBytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
    } else if (estimatedBytes >= 1024 * 1024) {
      sizeStr = (estimatedBytes / (1024 * 1024)).toFixed(0) + ' MB';
    } else {
      sizeStr = (estimatedBytes / 1024).toFixed(0) + ' KB';
    }
    document.getElementById('info-filesize').textContent = '~' + sizeStr;
    
    // Hide warning - ImageMagick handles any size
    document.getElementById('size-warning').style.display = 'none';
    
    // Estimate capture time (~800ms per tile based on benchmarks)
    const msPerTile = 800;
    // Stitch time: ~0.06s per megapixel
    const megapixels = (resX * resY) / 1000000;
    const stitchingMs = megapixels * 60;
    const totalMs = (totalTiles * msPerTile) + stitchingMs;
    let timeStr;
    if (totalMs >= 3600000) {
      const hours = Math.floor(totalMs / 3600000);
      const mins = Math.round((totalMs % 3600000) / 60000);
      timeStr = hours + 'h ' + mins + 'm';
    } else if (totalMs >= 60000) {
      const mins = Math.floor(totalMs / 60000);
      const secs = Math.round((totalMs % 60000) / 1000);
      timeStr = mins + 'm ' + secs + 's';
    } else {
      timeStr = Math.round(totalMs / 1000) + 's';
    }
    document.getElementById('info-time').textContent = '~' + timeStr;
  }
  
  function showSelection() {
    const state = window.captureState;
    const box = document.getElementById('selection-box');
    const controls = document.getElementById('capture-controls');
    const selectBtn = document.getElementById('select-area-btn');
    const coachText = document.getElementById('coach-text');
    
    state.selectionActive = true;
    
    // Hide initial UI
    selectBtn.style.display = 'none';
    coachText.style.display = 'none';
    
    // Show selection box and controls
    box.style.display = 'block';
    controls.style.display = 'block';
    
    // Position box in center
    state.boxLeft = (window.innerWidth - state.boxWidth) / 2;
    state.boxTop = (window.innerHeight - state.boxHeight) / 2;
    box.style.left = state.boxLeft + 'px';
    box.style.top = state.boxTop + 'px';
    box.style.width = state.boxWidth + 'px';
    box.style.height = state.boxHeight + 'px';
    
    // Set zoom slider to max
    document.getElementById('zoom-slider').value = state.zoom;
    document.getElementById('zoom-value').textContent = state.zoom;
    
    updateTileGrid();
  }
  
  function hideSelection() {
    const state = window.captureState;
    const box = document.getElementById('selection-box');
    const controls = document.getElementById('capture-controls');
    const selectBtn = document.getElementById('select-area-btn');
    const coachText = document.getElementById('coach-text');
    
    state.selectionActive = false;
    
    // Show initial UI
    selectBtn.style.display = 'block';
    coachText.style.display = 'block';
    
    // Hide selection box and controls
    box.style.display = 'none';
    controls.style.display = 'none';
  }
  
  function initOverlay() {
    const box = document.getElementById('selection-box');
    const state = window.captureState;
    
    // Drag functionality
    let isDragging = false;
    let isResizing = false;
    let resizeHandle = null;
    let startX, startY, startLeft, startTop, startWidth, startHeight;
    
    box.addEventListener('mousedown', (e) => {
      if (e.target.classList.contains('resize-handle')) {
        isResizing = true;
        resizeHandle = e.target.classList[1];
      } else {
        isDragging = true;
      }
      startX = e.clientX;
      startY = e.clientY;
      startLeft = state.boxLeft;
      startTop = state.boxTop;
      startWidth = state.boxWidth;
      startHeight = state.boxHeight;
      e.preventDefault();
    });
    
    document.addEventListener('mousemove', (e) => {
      if (isDragging) {
        state.boxLeft = startLeft + (e.clientX - startX);
        state.boxTop = startTop + (e.clientY - startY);
        box.style.left = state.boxLeft + 'px';
        box.style.top = state.boxTop + 'px';
      } else if (isResizing) {
        const dx = e.clientX - startX;
        const dy = e.clientY - startY;
        
        if (resizeHandle.includes('e')) {
          state.boxWidth = Math.max(100, startWidth + dx);
        }
        if (resizeHandle.includes('w')) {
          state.boxWidth = Math.max(100, startWidth - dx);
          state.boxLeft = startLeft + dx;
        }
        if (resizeHandle.includes('s')) {
          state.boxHeight = Math.max(100, startHeight + dy);
        }
        if (resizeHandle.includes('n')) {
          state.boxHeight = Math.max(100, startHeight - dy);
          state.boxTop = startTop + dy;
        }
        
        box.style.left = state.boxLeft + 'px';
        box.style.top = state.boxTop + 'px';
        box.style.width = state.boxWidth + 'px';
        box.style.height = state.boxHeight + 'px';
        updateTileGrid();
      }
    });
    
    document.addEventListener('mouseup', () => {
      isDragging = false;
      isResizing = false;
      resizeHandle = null;
    });
    
    // Select Area button
    document.getElementById('select-area-btn').addEventListener('click', showSelection);
    
    // Cancel button
    document.getElementById('cancel-btn').addEventListener('click', hideSelection);
    
    // Zoom slider (now 1-100)
    document.getElementById('zoom-slider').addEventListener('input', (e) => {
      state.zoom = parseInt(e.target.value);
      document.getElementById('zoom-value').textContent = state.zoom;
      updateTileGrid();
    });
    
    // Capture button
    document.getElementById('capture-btn').addEventListener('click', () => {
      // Save original view for restoration later
      state.originalRegion = {
        lat: map.region.center.latitude,
        lon: map.region.center.longitude,
        spanLat: map.region.span.latitudeDelta,
        spanLon: map.region.span.longitudeDelta,
        mapType: map.mapType
      };
      
      // Calculate the geographic bounds of the selection box
      const mapEl = document.querySelector('.mk-map-view') || document.body;
      const mapRect = mapEl.getBoundingClientRect();
      const region = map.region;
      
      const degPerPixelLat = region.span.latitudeDelta / mapRect.height;
      const degPerPixelLon = region.span.longitudeDelta / mapRect.width;
      
      // Box bounds in screen coords relative to map
      const boxCenterX = state.boxLeft + state.boxWidth / 2 - mapRect.left;
      const boxCenterY = state.boxTop + state.boxHeight / 2 - mapRect.top;
      
      // Convert to lat/lon
      const mapCenterX = mapRect.width / 2;
      const mapCenterY = mapRect.height / 2;
      
      state.centerLat = region.center.latitude - (boxCenterY - mapCenterY) * degPerPixelLat;
      state.centerLon = region.center.longitude + (boxCenterX - mapCenterX) * degPerPixelLon;
      
      // Box size in degrees
      state.spanLat = state.boxHeight * degPerPixelLat;
      state.spanLon = state.boxWidth * degPerPixelLon;
      
      state.confirmed = true;
      
      // Hide overlay
      document.getElementById('capture-overlay').style.display = 'none';
    });
    
    state.ready = true;
    
    // Update grid when map moves (only if selection is active)
    map.addEventListener('region-change-end', () => {
      if (state.selectionActive) updateTileGrid();
    });
    
    // New Capture button
    document.getElementById('new-capture-btn').addEventListener('click', () => {
      // Hide status panel
      document.getElementById('status-panel').style.display = 'none';
      
      // Show the initial UI (coach text and button)
      document.getElementById('select-area-btn').style.display = 'block';
      document.getElementById('coach-text').style.display = 'block';
      
      // Reset state for new capture
      state.confirmed = false;
      state.selectionActive = false;
      state.restartRequested = true;
    });
  }
  
  // Helper to restore map view
  function restoreMapView() {
    var state = window.captureState;
    
    // Restore the view from when capture started
    if (state.originalRegion) {
      var orig = state.originalRegion;
      var center = new mapkit.Coordinate(orig.lat, orig.lon);
      var span = new mapkit.CoordinateSpan(orig.spanLat, orig.spanLon);
      map.region = new mapkit.CoordinateRegion(center, span);
    }
    
    // Restore the map type from when capture started
    if (state.originalRegion && state.originalRegion.mapType) {
      map.mapType = state.originalRegion.mapType;
    } else if (state.initialMapType) {
      map.mapType = state.initialMapType;
    }
    
    // Show Apple Maps UI controls again
    map.showsZoomControl = true;
    map.showsMapTypeControl = true;
    map.showsCompass = true;
    
    // Remove the hide-ui CSS
    var hideStyle = document.getElementById('hide-ui-style');
    if (hideStyle) hideStyle.remove();
  }
  
  // Function to show stitching in progress (called from Clojure)
  window.showStitching = function() {
    restoreMapView();
    
    // Show overlay
    document.getElementById('capture-overlay').style.display = 'block';
    document.getElementById('selection-box').style.display = 'none';
    document.getElementById('capture-controls').style.display = 'none';
    document.getElementById('select-area-btn').style.display = 'none';
    document.getElementById('coach-text').style.display = 'none';
    
    // Show status panel with loading state
    var panel = document.getElementById('status-panel');
    var icon = document.getElementById('status-icon');
    var title = document.getElementById('status-title');
    var message = document.getElementById('status-message');
    var btn = document.getElementById('new-capture-btn');
    
    icon.textContent = '⟳';
    icon.className = 'loading';
    title.textContent = 'Stitching...';
    message.innerHTML = 'Assembling tiles into final image.<br>This may take a moment.';
    btn.style.display = 'none';
    panel.style.display = 'block';
  };
  
  // Function to show completion (called from Clojure)
  window.showCompletion = function(outputPath) {
    restoreMapView();
    
    // Show overlay
    document.getElementById('capture-overlay').style.display = 'block';
    document.getElementById('selection-box').style.display = 'none';
    document.getElementById('capture-controls').style.display = 'none';
    document.getElementById('select-area-btn').style.display = 'none';
    document.getElementById('coach-text').style.display = 'none';
    
    // Show status panel with complete state
    var panel = document.getElementById('status-panel');
    var icon = document.getElementById('status-icon');
    var title = document.getElementById('status-title');
    var message = document.getElementById('status-message');
    var btn = document.getElementById('new-capture-btn');
    
    icon.textContent = '✓';
    icon.className = 'complete';
    title.textContent = 'Stitch Complete!';
    message.innerHTML = 'Output saved to:<code>' + outputPath + '</code>';
    btn.style.display = 'block';
    panel.style.display = 'block';
  };
  
  // Initialize after a short delay
  setTimeout(initOverlay, 500);
")

;; =============================================================================
;; Injection Functions
;; =============================================================================

(defn inject-overlay
  "Injects the interactive capture UI overlay into the page."
  [driver]
  ;; Inject CSS
  (e/js-execute driver (str "
    var style = document.createElement('style');
    style.textContent = `" overlay-css "`;
    document.head.appendChild(style);
  "))
  
  ;; Inject HTML
  (e/js-execute driver (str "
    var overlay = document.createElement('div');
    overlay.innerHTML = `" overlay-html "`;
    document.body.appendChild(overlay.firstElementChild);
  "))
  
  ;; Inject JS
  (e/js-execute driver overlay-js))

(defn wait-for-user-selection
  "Waits for the user to confirm their selection. Returns the capture config."
  [driver]
  (loop []
    (Thread/sleep 500)
    (let [confirmed (e/js-execute driver "return window.captureState.confirmed;")]
      (if confirmed
        (let [state (e/js-execute driver "return window.captureState;")
              map-type (e/js-execute driver "return map.mapType;")]
          (println "User confirmed capture!")
          {:center-lat (:centerLat state)
           :center-lon (:centerLon state)
           :span-lat (:spanLat state)
           :span-lon (:spanLon state)
           :zoom (:zoom state)
           :rows (:tilesY state)
           :cols (:tilesX state)
           :map-type map-type})
        (recur)))))

(defn show-stitching
  "Shows the stitching in progress panel in the browser."
  [driver]
  (e/js-execute driver "showStitching();"))

(defn show-completion
  "Shows the completion panel in the browser with the output path."
  [driver output-path]
  (e/js-execute driver (str "showCompletion('" output-path "');")))

(defn reset-for-new-capture
  "Resets the overlay state for a new capture."
  [driver]
  (e/js-execute driver "
    window.captureState.confirmed = false;
    window.captureState.restartRequested = false;
    document.getElementById('status-panel').style.display = 'none';
    document.getElementById('select-area-btn').style.display = 'block';
    document.getElementById('coach-text').style.display = 'block';
  "))

(defn wait-for-restart
  "Waits for user to click 'New Capture' button."
  [driver]
  (loop []
    (Thread/sleep 500)
    (let [restart (e/js-execute driver "return window.captureState.restartRequested || false;")]
      (when-not restart
        (recur)))))
