# Architecture

## Android shell

- Jetpack Compose + Material 3 dark UI
- WorkManager for persistent scheduling and long-running background workers
- DataStore for small user settings
- Local app files for market data, saved scans, daily result files, and tracking files

## Python layer

`app/src/main/python/fetch_engine.py`
- Max-history first load
- Incremental update when last date is older than the latest expected weekday
- Atomic per-symbol writes
- One retry only
- Progress file for UI/notification polling

`app/src/main/python/scanner_engine.py`
- Daily / Weekly / Monthly / Quarterly / Six Month / Yearly
- Close / EMA / HMA / RSI / EMA of RSI
- MACD / Signal / Histogram
- Stoch RSI %K / %D
- Numeric Value
- Reverse RSI price level
- Reverse Stoch RSI %K / %D price level inversion
- Comparators including Cross Above / Cross Below

`app/src/main/python/android_bridge.py`
- Small bridge between Kotlin and Python

## Why CSV rather than Parquet on Android

The supplied Colab version stores individual Parquet files. The Android runtime has a different packaging problem: Parquet generally pushes the build toward pyarrow/native-wheel support. Keeping the same two-column daily series in per-symbol CSV files lets the app use only Android-compatible pandas/numpy wheels for the scanner and standard-library HTTP/CSV code for data collection.

Each symbol file is written as a temporary file and atomically renamed, so a process death during a write does not replace the last known-good file.
