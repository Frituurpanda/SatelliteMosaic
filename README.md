# SatelliteMosaic

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Clojure](https://img.shields.io/badge/Clojure-1.11+-green.svg)](https://clojure.org/)
[![Platform](https://img.shields.io/badge/Platform-macOS-lightgrey.svg)]()

**Capture ultra-high-resolution satellite imagery from Apple Maps and stitch it into stunning wall art prints.**

| Navigate to your target area | Select capture region |
|:---:|:---:|
| ![Init mode](docs/init_mode.png) | ![Capture mode](docs/capture_mode.png) |

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Configuration](#configuration)
- [Printing Guide](#printing-guide)
- [Viewing Large Images](#viewing-large-images)
- [Troubleshooting](#troubleshooting)
- [Technical Details](#technical-details)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- **Interactive capture** — visually select your region with a draggable overlay
- **Ultra-high resolution** — stitch 10,000+ tiles into multi-gigapixel images (15GB+)
- **Intelligent detection** — adapts to network speed, no hardcoded delays
- **Fast stitching** — uses libvips for memory-efficient processing of massive images
- **Print-ready output** — lossless PNG with real-time PPI calculations for poster printing
- **Auto-viewer** — opens results in vipsdisp instantly, even for 15GB+ files

---

## Installation

### Prerequisites

- **macOS** (tested on macOS, may work on Linux)
- **Java 11+** (`java -version`)
- **Google Chrome**

### Step 1: Install Dependencies

```bash
# Clojure CLI
brew install clojure/tools/clojure

# Image processing (stitching + viewing)
brew install vips vipsdisp

# Browser automation
brew install chromedriver
xattr -d com.apple.quarantine $(which chromedriver)
```

### Step 2: Verify Setup

The tool automatically checks ChromeDriver version compatibility on startup.

```bash
# Optional manual verification
chromedriver --version
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --version
```

**Note:** On first run, macOS may block chromedriver. Go to **System Settings → Privacy & Security** and click "Allow" when prompted.

### Step 3: Clone and Run

```bash
git clone https://github.com/Frituurpanda/SatelliteMosaic.git
cd SatelliteMosaic
clj -X main/capture-interactive
```

A browser window opens → navigate to your location → switch to Satellite view → click **Select Capture Area** → drag the green box → click **Start Capture**.

Output: `output/YYYY-MM-DD_HH-mm-ss/stitched.png`

---

## Usage

### Interactive Mode (Recommended)

```bash
clj -X main/capture-interactive
```

1. **Navigate** — Browser opens to Apple Maps. Pan/zoom to your target area
2. **Satellite View** — Click the map type control (top-right corner)
3. **Select Area** — Click "Select Capture Area", drag/resize the green box
4. **Capture** — Click "Start Capture". Progress is shown in the terminal
5. **Done** — Image opens automatically in vipsdisp

### Demo Mode

```bash
clj -X main/demo
```

Captures a small 3×3 grid to verify everything works.

### Scripted Mode

```bash
# Default 10×10 grid at maximum zoom
clj -X main/capture-map

# Custom settings
clj -X main/capture-map :city '"newyork"' :rows 5 :cols 5 :zoom 50
```

### Reproduce a Capture

After each capture, the CLI outputs a command to reproduce the exact same area:

```bash
clj -X main/capture-map :center-lat 37.78600691953786 :center-lon -122.45634944274101 :rows 8 :cols 10 :zoom 10 :map-type '"satellite"'
```

This is useful for:
- Retrying if something went wrong
- Sharing exact locations with others
- Automating repeated captures of the same area

### Stitch Existing Tiles

```bash
clj -X main/stitch-tiles :input-dir '"output/tiles"' :output-file '"output/final.png"' :rows 10 :cols 10
```

---

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| `:city` | `"sanfrancisco"` | City name for Apple Maps URL |
| `:rows` | `10` | Number of rows in grid |
| `:cols` | `10` | Number of columns in grid |
| `:zoom` | `100` | Zoom level (100 = max detail) |
| `:map-type` | `"satellite"` | Map type: `satellite`, `standard`, `hybrid` |
| `:hidpi` | `true` | 2× resolution (Retina mode) |
| `:output-dir` | `"output"` | Output directory |
| `:center-lat` | — | Override center latitude |
| `:center-lon` | — | Override center longitude |
| `:tile-wait-ms` | `5000` | Max wait per tile (ms) |

### Zoom Levels

| Zoom | Area per Tile | Best For |
|------|---------------|----------|
| 10 | ~1.1 km | City overview |
| 50 | ~220 m | Neighborhoods |
| 100 | ~200 m | Maximum detail |

**Note:** Zoom 100+ produces identical results—Apple Maps caps at ~0.00183° minimum span.

---

## Printing Guide

### Understanding DPI vs PPI

- **Printer DPI** = dots per inch (hardware capability)
- **Image PPI** = pixels per inch (your source file)

Printers use multiple ink dots per pixel for color accuracy. The optimal ratio is **~4 dots per pixel**.

### Printer DPI → Maximum Useful Source PPI

| Printer DPI | Max Useful PPI | Common Printer Types |
|-------------|----------------|---------------------|
| 300 | 75 | Basic office inkjet |
| 600 | 150 | Standard inkjet/laser |
| 720 | 180 | Entry photo printers |
| 1200 | 300 | Quality inkjet/laser |
| 1440 | 360 | Large format (common) |
| 2400 | 600 | Professional photo |
| 2880 | 720 | High-end (Epson) |
| 4800 | 1200 | Premium photo |
| 5760 | 1440 | Top-tier (Epson Pro) |
| 9600 | 2400 | Maximum consumer (Canon) |

**Formula:** `Max PPI = Printer DPI ÷ 4`

### Viewing Distance → Minimum Useful PPI

| Distance | Min PPI | Use Case |
|----------|---------|----------|
| 10 cm (magnifier) | 300+ | Close inspection |
| 30 cm (reading) | 150 | Detailed viewing |
| 1 m (arm's length) | 100 | Wall art |
| 2 m+ (across room) | 50–75 | Large posters |

**Human eye limit:** ~300 PPI at 10–20 cm. Beyond this, only magnification reveals more detail.

### Calculating Print Size

```
Print size (inches) = Pixels ÷ PPI
Print size (cm) = Pixels ÷ PPI × 2.54
```

### Quick Reference: Pixels Needed for 300 PPI

| Print Size | Pixels Required | Megapixels |
|------------|-----------------|------------|
| 50 × 50 cm | 5,906 × 5,906 | 35 MP |
| 1 × 1 m | 11,811 × 11,811 | 139 MP |
| 1.5 × 1.5 m | 17,717 × 17,717 | 314 MP |
| 2 × 2 m | 23,622 × 23,622 | 558 MP |

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
