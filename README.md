<p align="center"><img src="docs/icon.png" width="120" alt="MovieLens icon"></p>

<h1 align="center">MovieLens</h1>
<p align="center">Point your phone at a movie poster, a title, or a TV catalogue and see the IMDb rating live.</p>

<p align="center"><img src="docs/tv-grid.png" width="600" alt="Pointing the app at a streaming catalogue on a TV"></p>

## What it shows

- **IMDb rating** and **vote count** (e.g. `8.8 /10 · 2.6M votes`)
- **Type** (Movie / TV Series) and runtime
- **Year**, or the years a series ran (`2019 – present`)
- **Age badge**: `ALL`, `+7`, `+10`, `+13`, `+14`, `+18`, or `NR` (not rated)
- **Genres**
- Tap the card to open the title in the IMDb app or the browser

## How it works

1. **Live text mode (free, automatic).** CameraX frames go through on-device ML Kit OCR. The largest text closest to the centre of the frame is taken as the title and looked up on [OMDb](https://www.omdbapi.com). A result is only shown if its title matches the words that were read, so fuzzy search never shows the wrong movie.
2. **✦ Identify (AI, on tap).** For posters with no readable title, the current frame is sent to Claude (`claude-opus-5-5`), which names the film. That name is then looked up on OMDb the same way.

## Setup

1. Get a free OMDb key: <https://www.omdbapi.com/apikey.aspx>
2. Optional: get a Claude API key for ✦ Identify: <https://console.anthropic.com>
3. Build with `./gradlew assembleDebug`, then put `app/build/outputs/apk/debug/app-debug.apk` on your phone.
4. Enter the keys on first launch. Long-press the top pill to change them later. Keys are stored only on the device.

Requires Android 7.0+ (API 24) and JDK 17+ to build.

## Limitations

- OMDb doesn't cover every regional release, so smaller titles may show "No IMDb match".
- OCR reads Latin script only.
- The Claude key lives on the phone. Fine for personal use; put a small proxy server in front before sharing the app widely.
