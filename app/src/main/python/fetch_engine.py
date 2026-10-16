"""NSE daily history fetch engine; compatible with the Android app's file/result contracts."""
import calendar
import csv
import json
import math
import os
import re
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timedelta
from pathlib import Path

CHUNK_SIZE = 25
MAX_RETRIES = 1
REQUEST_TIMEOUT = 30
CONCURRENCY = 6
PAUSE_SECONDS = 0.18
NSE_EQUITY_CSV_URL = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv"
SYMBOL_UNIVERSE_FILE = "symbol_universe.json"


def _utc_now():
    return datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def _atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, separators=(",", ":"))
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def refresh_nse_symbol_universe(data_dir, fallback_symbols=None):
    """Download NSE's official EQ symbol list, using saved/bundled symbols on failure."""
    root = Path(data_dir)
    root.mkdir(parents=True, exist_ok=True)
    saved = root / SYMBOL_UNIVERSE_FILE
    try:
        req = urllib.request.Request(NSE_EQUITY_CSV_URL, headers={
            "User-Agent": "Mozilla/5.0 Chrome/131 Safari/537.36",
            "Accept": "text/csv,*/*", "Connection": "close"})
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as r:
            raw = r.read().decode("utf-8-sig", errors="replace")
        reader = csv.DictReader(raw.splitlines())
        keys = {str(k).strip().upper(): k for k in (reader.fieldnames or []) if k}
        sk, serk = keys.get("SYMBOL"), keys.get("SERIES")
        if not sk:
            raise RuntimeError("NSE CSV has no SYMBOL column")
        symbols = []
        for row in reader:
            s = str(row.get(sk) or "").strip().upper()
            series = str(row.get(serk) or "").strip().upper() if serk else "EQ"
            if s and (not serk or series == "EQ"):
                symbols.append(s)
        symbols = sorted(set(symbols))
        if not symbols:
            raise RuntimeError("NSE CSV returned no EQ symbols")
        _atomic_json(saved, {"updated_at": _utc_now(), "symbols": symbols})
        return symbols, "nse_official"
    except Exception:
        try:
            old = json.loads(saved.read_text(encoding="utf-8"))
            symbols = sorted(set(str(s).strip().upper() for s in old.get("symbols", []) if str(s).strip()))
            if symbols:
                return symbols, "saved_universe"
        except Exception:
            pass
        return sorted(set(str(s).strip().upper() for s in (fallback_symbols or []) if str(s).strip())), "bundled_fallback"


def _today():
    return date.today()


def most_recent_expected_trading_day():
    d = _today()
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d


def _safe_symbol(symbol):
    return re.sub(r"[^A-Za-z0-9_.-]", "_", str(symbol).strip().upper())


def symbol_file(data_dir, symbol):
    return Path(data_dir) / (_safe_symbol(symbol) + ".csv")


def index_file(data_dir):
    return Path(data_dir) / "database_index.json"


def progress_file(data_dir):
    return Path(data_dir) / "update_progress.json"


def load_index(data_dir):
    try:
        value = json.loads(index_file(data_dir).read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def save_index(data_dir, idx):
    _atomic_json(index_file(data_dir), idx)


def _parse_csv(path):
    """Parse all valid rows, sorted and deduplicated; deliberately no candle cap."""
    path = Path(path)
    if not path.exists():
        return []
    rows = {}
    try:
        with path.open("r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            keys = {str(k).strip().lower(): k for k in (reader.fieldnames or []) if k}
            dk, ck = keys.get("date"), keys.get("close")
            if not dk or not ck:
                return []
            for row in reader:
                day = str(row.get(dk) or "").strip()[:10]
                raw = str(row.get(ck) or "").strip()
                try:
                    day = date.fromisoformat(day).isoformat()
                    close = float(raw)
                except (ValueError, TypeError):
                    continue
                if math.isfinite(close) and close > 0:
                    rows[day] = close
    except (OSError, UnicodeError, csv.Error):
        return []
    return sorted(rows.items())


def _atomic_write_csv(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Date", "Close"])
        w.writerows(rows)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def _parse_yahoo_timestamp(ts):
    return datetime.utcfromtimestamp(int(ts)).strftime("%Y-%m-%d")

def _yahoo_ticker(symbol):
    """Map plain NSE equity symbols to Yahoo Finance tickers without double suffixes."""
    value = str(symbol).strip().upper()
    if not value:
        raise ValueError("Empty stock symbol")
    if value.endswith(".NS") or value.endswith(".BO"):
        return value
    return value + ".NS"

def _download_symbol(symbol, start_epoch=None, end_epoch=None):
    """None start means period1=0 (all available Yahoo daily history)."""
    if start_epoch is None:
        start_epoch = 0
    if end_epoch is None:
        end_epoch = int(time.time()) + 86400
    ticker = urllib.parse.quote(_yahoo_ticker(symbol), safe="")
    url = ("https://query1.finance.yahoo.com/v8/finance/chart/" + ticker
           + "?period1=" + str(int(start_epoch)) + "&period2=" + str(int(end_epoch))
           + "&interval=1d&events=history&includeAdjustedClose=false")
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 Chrome/131 Safari/537.36",
        "Accept": "application/json,text/plain,*/*", "Connection": "close"})
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as r:
        payload = json.loads(r.read().decode("utf-8"))
    chart = payload.get("chart") or {}
    results = chart.get("result") or []
    if not results:
        error = chart.get("error") or {}
        raise RuntimeError(str(error.get("description") or "Yahoo returned no chart data"))
    result = results[0]
    timestamps = result.get("timestamp") or []
    blocks = ((result.get("indicators") or {}).get("quote") or [])
    closes = (blocks[0].get("close") or []) if blocks else []
    out = {}
    for ts, raw in zip(timestamps, closes):
        if raw is None:
            continue
        try:
            day, close = _parse_yahoo_timestamp(ts), float(raw)
        except (ValueError, TypeError, OverflowError, OSError):
            continue
        if math.isfinite(close) and close > 0:
            out[day] = close
    return sorted(out.items())


def _epoch_utc(day):
    return calendar.timegm(datetime(day.year, day.month, day.day).timetuple())


def _merge_rows(old, fresh):
    merged = dict(old)
    merged.update(dict(fresh))
    return sorted(merged.items())


def _update_one(symbol, data_dir, mode, last_date=None):
    path = symbol_file(data_dir, symbol)
    try:
        existing = _parse_csv(path)
        if mode == "full" or not existing:
            fresh = _download_symbol(symbol, start_epoch=None)
        else:
            # Five-day overlap repairs recent missing/changed candles and avoids local-time epoch drift.
            latest = date.fromisoformat(existing[-1][0])
            fresh = _download_symbol(symbol, start_epoch=_epoch_utc(latest - timedelta(days=5)))
        merged = _merge_rows(existing, fresh)
        if not merged:
            raise RuntimeError("Yahoo returned no usable historical candles")
        _atomic_write_csv(path, merged)
        return {"symbol": symbol, "ok": True, "last_date": merged[-1][0], "rows": len(merged), "error": ""}
    except Exception as exc:
        return {"symbol": symbol, "ok": False, "last_date": last_date or "", "rows": 0,
                "error": type(exc).__name__ + ": " + str(exc)}


def _classify(symbols, data_dir, idx, expected):
    full, inc, already = [], [], []
    for symbol in symbols:
        path = symbol_file(data_dir, symbol)
        if not path.exists():
            full.append(symbol)
            continue
        meta = idx.get(symbol, {})
        last = meta.get("last_date") if isinstance(meta, dict) else None
        if last:
            try:
                last = date.fromisoformat(str(last)[:10]).isoformat()
            except ValueError:
                last = None
        if not last:
            rows = _parse_csv(path)
            last = rows[-1][0] if rows else None
        if not last:
            full.append(symbol)
        elif last < expected:
            inc.append(symbol)
        else:
            already.append(symbol)
    return full, inc, already


def update(symbols, data_dir, progress_callback=None):
    """Run full/incremental updates and return the established app-facing result schema."""
    root = Path(data_dir)
    root.mkdir(parents=True, exist_ok=True)
    symbols = list(dict.fromkeys(str(s).strip().upper() for s in (symbols or []) if str(s).strip()))
    idx = load_index(root)
    expected = most_recent_expected_trading_day().isoformat()
    full, incremental, already = _classify(symbols, root, idx, expected)
    total, completed = len(symbols), len(already)
    failed, retry_count = [], 0
    retry_total, retry_completed = 0, 0
    mode_counts = {"full": len(full), "incremental": len(incremental), "already": len(already)}

    def emit(stage, symbol="", last_date="", error=""):
            payload = {"stage": stage, "total": total, "completed": completed,
                   "failed": len(failed), "symbol": symbol, "last_date": last_date,
                   "expected": expected, "full": len(full), "incremental": len(incremental),
                   "already": len(already), "retry_count": retry_count,
                   "retry_total": retry_total,
                   "retry_completed": retry_completed,
                   "error": error}
        try:
            _atomic_json(progress_file(root), payload)
        except Exception:
            pass
        if progress_callback:
            try:
                progress_callback(payload)
            except Exception:
                pass

    emit("starting")
    successes_since_save = 0
    for mode, group in (("full", full), ("incremental", incremental)):
        if not group:
            continue
        with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
            jobs = {pool.submit(_update_one, s, root, mode,
                                (idx.get(s, {}) or {}).get("last_date")): s for s in group}
            for future in as_completed(jobs):
                symbol = jobs[future]
                try:
                    result = future.result()
                except Exception as exc:
                    result = {"symbol": symbol, "ok": False, "last_date": "", "rows": 0,
                              "error": type(exc).__name__ + ": " + str(exc)}
                completed += 1
                result["mode"] = mode
                if result["ok"]:
                    idx[symbol] = {"last_date": result["last_date"], "row_count": result["rows"],
                                   "updated_at": _utc_now()}
                    successes_since_save += 1
                    if successes_since_save >= CHUNK_SIZE:
                        try:
                            save_index(root, idx)
                        except Exception:
                            pass
                        successes_since_save = 0
                else:
                    failed.append(result)
                emit(mode, symbol, result.get("last_date", ""), result.get("error", ""))
                time.sleep(PAUSE_SECONDS)
        try:
            save_index(root, idx)
        except Exception:
            pass

    # Exactly one retry per failed symbol. Retries do not increment completed.
    if failed:
        first_failures, failed = list(failed), []
        retry_total = len(first_failures)
        retry_completed = 0

        for position, previous in enumerate(first_failures, 1):
            retry_count += 1
            symbol, mode = previous["symbol"], previous.get("mode", "full")
            meta = idx.get(symbol, {})
            result = _update_one(symbol, root, mode, meta.get("last_date") if isinstance(meta, dict) else None)
            result["mode"] = mode
            if result["ok"]:
                idx[symbol] = {"last_date": result["last_date"], "row_count": result["rows"],
                               "updated_at": _utc_now()}
                try:
                    save_index(root, idx)
                except Exception:
                    pass
            else:
                failed.append(result)
                retry_completed = position
            emit("retry {}/{}".format(position, len(first_failures)), symbol,
                 result.get("last_date", ""), result.get("error", ""))
            time.sleep(PAUSE_SECONDS)

    try:
        save_index(root, idx)
    except Exception:
        pass
    dates = [idx[s]["last_date"] for s in symbols
             if isinstance(idx.get(s), dict) and idx[s].get("last_date")]
    market_through = min(dates) if dates else None
        final_stage = "complete"
    failed_symbols = [
        {"symbol": item["symbol"], "error": item.get("error", "")}
        for item in failed
    ]
    failed_names = [item["symbol"] for item in failed_symbols]

    message = (
        "Update complete; failed stocks: " + ", ".join(failed_names)
        if failed_names else "Update complete"
    )

    final_payload = {
        "stage": final_stage,
        "process_complete": True,
        "success": not failed,
        "total": total,
        "completed": completed,
        "failed": len(failed),
        "expected": expected,
        "market_data_through": market_through,
        "retry_count": retry_count,
        "retry_total": retry_total,
        "retry_completed": retry_completed,
        "failed_symbols": failed_symbols,
        "failed_names": failed_names,
        "message": message
    }
    try:
        _atomic_json(progress_file(root), final_payload)
    except Exception:
        pass
    if progress_callback:
        try:
            progress_callback(final_payload)
        except Exception:
            pass
    return {"success": not failed, "total": total, "completed": completed,
            "failed": len(failed), "failed_symbols": failed_symbols, "expected": expected,
            "market_data_through": market_through, "mode_counts": mode_counts,
            "retry_count": retry_count}
