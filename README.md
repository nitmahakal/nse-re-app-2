# NSE Market Research Android App

A dark-mode NSE research app designed around the supplied Python data-fetch and scanner logic.

## Intended flow

1. **Update**
   - First run downloads maximum available daily history for every symbol.
   - Data is saved atomically per symbol as `Date,Close` CSV.
   - A small `database_index.json` records each symbol's last date and row count.
   - If an update is interrupted, already-complete symbols become incremental candidates; missing symbols are fetched from scratch.
   - Every failed symbol gets exactly one retry.
   - Manual update is visible with an in-app progress counter and an ongoing Android notification.
   - Completion shows `Updated data upto dd.mm.yy` when the stored market-data floor is known.

2. **Custom Scanner**
   - Select timeframe.
   - Select first indicator and all parameters.
   - Select comparator.
   - Select second indicator and parameters.
   - Add another condition: YES -> choose AND/OR and add another condition; NO -> run.
   - Scanner runs in a background worker and shows progress.
   - Results display as Symbol / Timeframe / Close and can be saved.

3. **Saved Scans & Tracking**
   - A saved scan may be marked AUTO.
   - After a successful data update, AUTO scans run automatically.
   - Daily result JSON is saved using scan name + date.
   - Active matches are tracked until their condition no longer appears in the scan.
   - Closed matches keep entry/exit prices and percentage P&L.

## Stock list

The app fetches the current NSE equity universe from NSE's official equity CSV at update time and keeps the last good universe locally as a fallback. The bundled `symbols.txt` file is only a fallback/diagnostic file and is not required for the first update.

## Build

The project intentionally does **not** include a Gradle wrapper. GitHub Actions installs Gradle 9.1.0 and builds the debug APK.

Chaquopy embeds Python 3.13 for the scanner/fetch engine. Android storage deliberately uses CSV rather than Parquet to avoid a pyarrow/native-wheel requirement on the phone while preserving the exact `Date,Close` data model.

## Important current-data note

The fetcher talks to Yahoo Finance's public chart endpoint directly rather than embedding the full yfinance dependency in the APK. yfinance itself currently requires additional networking/native dependencies such as `curl_cffi`; avoiding that dependency keeps the Android build smaller and more predictable. The app remains provider-isolatable because the fetch engine is a separate Python module.
