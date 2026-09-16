import csv
import json
import math
import os
import re
import time
import traceback
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timedelta
from pathlib import Path

MAX_CANDLES = 1500
CHUNK_SIZE = 25
MAX_RETRIES = 1
REQUEST_TIMEOUT = 30
CONCURRENCY = 6
PAUSE_SECONDS = 0.18

NSE_EQUITY_CSV_URL = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv"
SYMBOL_UNIVERSE_FILE = "symbol_universe.json"


def refresh_nse_symbol_universe(data_dir, fallback_symbols=None):
    """Fetch NSE's official equity-security CSV; fall back to the last good list."""
    data_path = Path(data_dir)
    data_path.mkdir(parents=True, exist_ok=True)
    universe_path = data_path / SYMBOL_UNIVERSE_FILE
    fallback_symbols = list(fallback_symbols or [])

    try:
        req = urllib.request.Request(
            NSE_EQUITY_CSV_URL,
            headers={
                "User-Agent": "Mozilla/5.0 (Android 11; Mobile) AppleWebKit/537.36 Chrome/131 Safari/537.36",
                "Accept": "text/csv,*/*",
                "Connection": "close",
            },
        )
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as response:
            raw = response.read().decode("utf-8-sig", errors="replace")

        reader = csv.DictReader(raw.splitlines())
        field_map = {str(k).strip().upper(): k for k in (reader.fieldnames or []) if k is not None}
        symbol_key = field_map.get("SYMBOL")
        series_key = field_map.get("SERIES")
        if not symbol_key:
            raise RuntimeError("NSE equity CSV has no SYMBOL column")

        symbols = []
        for row in reader:
            symbol = str(row.get(symbol_key) or "").strip().upper()
            series = str(row.get(series_key) or "").strip().upper() if series_key else "EQ"
            if symbol and (not series_key or series == "EQ"):
                symbols.append(symbol)

        symbols = sorted(set(symbols))
        if not symbols:
            raise RuntimeError("NSE equity CSV returned no EQ symbols")

        tmp = universe_path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"updated_at": datetime.utcnow().isoformat(), "symbols": symbols}, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, universe_path)
        return symbols, "nse_official"
    except Exception:
        try:
            saved = json.loads(universe_path.read_text(encoding="utf-8"))
            symbols = [str(x).strip().upper() for x in saved.get("symbols", []) if str(x).strip()]
            if symbols:
                return sorted(set(symbols)), "saved_universe"
        except Exception:
            pass
        return sorted(set(str(x).strip().upper() for x in fallback_symbols if str(x).strip())), "bundled_fallback"


def _today():
    return date.today()


def most_recent_expected_trading_day():
    d = _today()
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d


def _safe_symbol(symbol):
    return re.sub(r"[^A-Za-z0-9_.-]", "_", symbol.strip().upper())


def symbol_file(data_dir, symbol):
    return Path(data_dir) / f"{_safe_symbol(symbol)}.csv"


def index_file(data_dir):
    return Path(data_dir) / "database_index.json"


def load_index(data_dir):
    p = index_file(data_dir)
    if not p.exists():
        return {}
    try:
        with p.open("r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_index(data_dir, idx):
    p = index_file(data_dir)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(".json.tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(idx, f, ensure_ascii=False, indent=0)
    os.replace(tmp, p)


def _parse_csv(path):
    rows = []
    if not path.exists():
        return rows
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for r in reader:
            d = (r.get("Date") or "").strip()
            c = (r.get("Close") or "").strip()
            if not d or not c:
                continue
            try:
                float(c)
                rows.append((d, float(c)))
            except Exception:
                continue
    rows.sort(key=lambda x: x[0])
    out = []
    seen = set()
    for d, c in rows:
        if d not in seen:
            out.append((d, c))
            seen.add(d)
    return out[-MAX_CANDLES:]


def _atomic_write_csv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Date", "Close"])
        w.writerows(rows)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def _parse_yahoo_timestamp(ts):
    return datetime.utcfromtimestamp(int(ts)).strftime("%Y-%m-%d")


def _download_symbol(symbol, start_epoch=None, end_epoch=None):
    if end_epoch is None:
        end_epoch = int(time.time()) + 86400
    if start_epoch is None:
        start_epoch = 0
    q = urllib.parse.quote(symbol, safe="")
    url = (
        f"https://query1.finance.yahoo.com/v8/finance/chart/{q}"
        f"?period1={int(start_epoch)}&period2={int(end_epoch)}"
        f"&interval=1d&events=history&includeAdjustedClose=false"
    )
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Android 11; Mobile) AppleWebKit/537.36 Chrome/131 Safari/537.36",
            "Accept": "application/json,text/plain,*/*",
            "Connection": "close",
        },
    )
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as response:
        raw = response.read().decode("utf-8")
    payload = json.loads(raw)
    result = payload.get("chart", {}).get("result")
    if not result:
        err = payload.get("chart", {}).get("error") or {}
        raise RuntimeError(str(err.get("description") or "Yahoo returned no data"))
    r0 = result[0]
    timestamps = r0.get("timestamp") or []
    quote = ((r0.get("indicators") or {}).get("quote") or [{}])[0]
    closes = quote.get("close") or []
    rows = []
    for ts, close in zip(timestamps, closes):
        if close is None:
            continue
        try:
            value = float(close)
        except Exception:
            continue
        if math.isfinite(value) and value > 0:
            rows.append((_parse_yahoo_timestamp(ts), value))
    return rows


def _update_one(symbol, data_dir, mode, last_date, progress=None):
    path = symbol_file(data_dir, symbol)
    try:
        if mode == "full":
            fresh = _download_symbol(symbol)
            merged = fresh
        else:
            if last_date:
                d = datetime.strptime(last_date, "%Y-%m-%d").date() + timedelta(days=1)
                start_epoch = int(datetime(d.year, d.month, d.day).timestamp())
            else:
                start_epoch = 0
            fresh = _download_symbol(symbol, start_epoch=start_epoch)
            existing = _parse_csv(path)
            if not fresh and existing:
                # No new Yahoo candles is a valid no-op for an already-listed symbol.
                # This is important on NSE holidays/weekends and for symbols which did not trade.
                merged = existing
            else:
                merged_map = {d: c for d, c in existing}
                for d, c in fresh:
                    merged_map[d] = c
                merged = sorted(merged_map.items(), key=lambda x: x[0])[-MAX_CANDLES:]
        if not merged:
            raise RuntimeError("No market data returned")
        _atomic_write_csv(path, merged)
        new_last = merged[-1][0]
        return {"symbol": symbol, "ok": True, "last_date": new_last, "rows": len(merged), "error": ""}
    except Exception as exc:
        return {"symbol": symbol, "ok": False, "last_date": last_date or "", "rows": 0, "error": f"{type(exc).__name__}: {exc}"}


def _classify(symbols, data_dir, idx, expected):
    full, inc, skipped = [], [], []
    for s in symbols:
        meta = idx.get(s, {})
        path = symbol_file(data_dir, s)
        last = meta.get("last_date")
        if path.exists() and not last:
            rows = _parse_csv(path)
            last = rows[-1][0] if rows else None
        if not path.exists() or not last:
            full.append(s)
        elif last < expected:
            inc.append(s)
        else:
            skipped.append(s)
    return full, inc, skipped


def update(symbols, data_dir, progress_callback=None):
    Path(data_dir).mkdir(parents=True, exist_ok=True)
    progress_path = Path(data_dir) / "update_progress.json"
    def write_progress(payload):
        try:
            tmp = progress_path.with_suffix(".tmp")
            with tmp.open("w", encoding="utf-8") as pf:
                json.dump(payload, pf, ensure_ascii=False)
            os.replace(tmp, progress_path)
        except Exception:
            pass
    idx = load_index(data_dir)
    expected = most_recent_expected_trading_day().isoformat()
    full, incremental, already = _classify(symbols, data_dir, idx, expected)

    total = len(symbols)
    completed = len(already)
    failed = []
    mode_counts = {"full": len(full), "incremental": len(incremental), "already": len(already)}

    def emit(stage, symbol="", last_date="", error=""):
        payload = {
                "stage": stage,
                "total": total,
                "completed": completed,
                "symbol": symbol,
                "last_date": last_date,
                "expected": expected,
                "error": error,
                "full": len(full),
                "incremental": len(incremental),
                "already": len(already),
            }
        write_progress(payload)
        if progress_callback:
            progress_callback(payload)

    emit("starting")

    # Full history first. This makes a first install finish the database before incremental work.
    for group_name, group in (("full", full), ("incremental", incremental)):
        if not group:
            continue
        with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
            futures = {
                pool.submit(_update_one, s, data_dir, group_name, idx.get(s, {}).get("last_date"), None): s
                for s in group
            }
            for fut in as_completed(futures):
                s = futures[fut]
                try:
                    result = fut.result()
                except Exception as exc:
                    result = {"symbol": s, "ok": False, "last_date": "", "rows": 0, "error": str(exc)}
                completed += 1
                if result["ok"]:
                    idx[s] = {"last_date": result["last_date"], "row_count": result["rows"], "updated_at": datetime.utcnow().isoformat()}
                    # Persist after every successful symbol, so an interrupted run resumes cleanly.
                    save_index(data_dir, idx)
                else:
                    failed.append(result)
                emit(group_name, s, result.get("last_date", ""), result.get("error", ""))
                time.sleep(PAUSE_SECONDS)

    # Exactly one retry, one symbol at a time, only for failures.
    retry_results = []
    if failed:
        pending = list(failed)
        failed = []
        for item in pending:
            s = item["symbol"]
            retry = _update_one(s, data_dir, "full", idx.get(s, {}).get("last_date"), None)
            if retry["ok"]:
                idx[s] = {"last_date": retry["last_date"], "row_count": retry["rows"], "updated_at": datetime.utcnow().isoformat()}
                save_index(data_dir, idx)
            else:
                failed.append(retry)
            retry_results.append(retry)
            emit("retry", s, retry.get("last_date", ""), retry.get("error", ""))

    save_index(data_dir, idx)
    write_progress({"stage":"complete","total":total,"completed":completed,"expected":expected,"market_data_through":min(final_dates) if (final_dates := [idx.get(s, {}).get("last_date") for s in symbols if idx.get(s, {}).get("last_date")]) else None})
    final_dates = [idx.get(s, {}).get("last_date") for s in symbols if idx.get(s, {}).get("last_date")]
    market_through = min(final_dates) if final_dates else None
    return {
        "success": len(failed) == 0,
        "total": total,
        "completed": completed,
        "failed": len(failed),
        "failed_symbols": [{"symbol": x["symbol"], "error": x.get("error", "")} for x in failed],
        "expected": expected,
        "market_data_through": market_through,
        "mode_counts": mode_counts,
        "retry_count": len(retry_results),
    }
