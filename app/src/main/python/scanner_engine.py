import csv
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
from concurrent.futures import ThreadPoolExecutor, as_completed

TIMEFRAMES = ["Daily", "Weekly", "Monthly", "Quarterly", "Six Month", "Yearly"]
SCAN_WORKERS = 8
INDICATORS = [
    "Close", "EMA", "HMA", "RSI", "EMA of RSI", "MACD", "MACD Signal",
    "MACD Histogram", "Stoch RSI %K", "Stoch RSI %D", "Numeric Value",
    "Reverse RSI", "Reverse Stoch RSI %K", "Reverse Stoch RSI %D"
]
COMPARATORS = ["Equal", "Greater", "Greater Equal", "Less", "Less Equal", "Cross Above", "Cross Below"]


def load_symbols(data_dir: str) -> List[str]:
    p = Path(data_dir)
    return sorted(x.stem for x in p.glob("*.csv") if x.name != "database_index.json")


def load_symbol(data_dir: str, symbol: str) -> pd.Series:
    p = Path(data_dir) / f"{symbol}.csv"
    df = pd.read_csv(p, usecols=["Date", "Close"])
    df["Date"] = pd.to_datetime(df["Date"], errors="coerce")
    df["Close"] = pd.to_numeric(df["Close"], errors="coerce")
    df = df.dropna().drop_duplicates("Date", keep="last").sort_values("Date")
    return pd.Series(df["Close"].to_numpy(dtype=float), index=df["Date"], name="Close")


def rma(s, n):
    n = int(n)
    vals = s.to_numpy(dtype=float)
    out = np.full(len(vals), np.nan)
    valid = np.where(np.isfinite(vals))[0]
    if len(valid) < n:
        return pd.Series(out, index=s.index)
    start = valid[0]
    window = vals[start:start+n]
    if len(window) < n or not np.isfinite(window).all():
        return pd.Series(out, index=s.index)
    prev = window.mean()
    out[start+n-1] = prev
    for i in range(start+n, len(vals)):
        v = vals[i]
        if np.isfinite(v):
            prev = (prev*(n-1) + v)/n
            out[i] = prev
    return pd.Series(out, index=s.index)


def ema(s, n):
    n = int(n)
    vals = s.to_numpy(dtype=float)
    out = np.full(len(vals), np.nan)
    valid = np.where(np.isfinite(vals))[0]
    if len(valid) < n:
        return pd.Series(out, index=s.index)
    start = valid[0]
    prev = vals[start:start+n].mean()
    out[start+n-1] = prev
    alpha = 2.0/(n+1.0)
    for i in range(start+n, len(vals)):
        v = vals[i]
        if np.isfinite(v):
            prev = alpha*v + (1-alpha)*prev
            out[i] = prev
    return pd.Series(out, index=s.index)


def wma(s, n):
    weights = np.arange(1, n+1, dtype=float)
    return s.rolling(n, min_periods=n).apply(lambda x: np.dot(x, weights)/weights.sum(), raw=True)


def hma(s, n):
    half = max(1, n//2)
    root = max(1, int(math.sqrt(n)))
    return wma(2*wma(s, half)-wma(s, n), root)


def rsi(s, n):
    d = s.diff()
    gain = d.clip(lower=0)
    loss = (-d).clip(lower=0)
    ag = rma(gain, n)
    al = rma(loss, n)
    rs = ag / al.replace(0, np.nan)
    out = 100 - 100/(1+rs)
    out = out.where(~((al == 0) & (ag > 0)), 100)
    out = out.where(~((al == 0) & (ag == 0)), 50)
    return out


def macd(s, fast_n, slow_n, signal_n):
    line = ema(s, fast_n) - ema(s, slow_n)
    sig = ema(line, signal_n)
    return line, sig, line-sig


def stoch_rsi(s, rsi_n, stoch_n, k_n, d_n):
    r = rsi(s, rsi_n)
    lo = r.rolling(stoch_n, min_periods=stoch_n).min()
    hi = r.rolling(stoch_n, min_periods=stoch_n).max()
    den = hi-lo
    raw = ((r-lo)/den.replace(0, np.nan))*100
    raw = raw.where(den != 0, 0)
    k = raw.rolling(k_n, min_periods=k_n).mean()
    d = k.rolling(d_n, min_periods=d_n).mean()
    return k, d


def reverse_rsi_price(s, target, rsi_n, smoothing_n):
    target = float(target)
    d = s.diff()
    ag = rma(d.clip(lower=0), rsi_n).shift(1)
    al = rma((-d).clip(lower=0), rsi_n).shift(1)
    prev = s.shift(1)
    rs = target/(100-target)
    cur_rs = ag / al.replace(0, np.nan)
    use_up = rs > cur_rs
    up = prev + (rsi_n-1)*(rs*al-ag)
    down = prev - (rsi_n-1)*((ag/rs)-al)
    raw = pd.Series(np.where(use_up, up, down), index=s.index)
    raw = raw.where(prev.notna() & ag.notna() & al.notna())
    return ema(raw, smoothing_n)


def reverse_stoch_price(s, target, rsi_n, stoch_n, k_n, d_n, smoothing_n, mode):
    # Invert the same Stoch-RSI smoothing chain used by stoch_rsi():
    # RSI -> raw StochRSI -> K SMA -> D SMA -> target price.
    r = rsi(s, rsi_n)
    lo = r.shift(1).rolling(stoch_n, min_periods=stoch_n).min()
    hi = r.shift(1).rolling(stoch_n, min_periods=stoch_n).max()
    den = hi-lo
    raw = ((r-lo)/den.replace(0, np.nan))*100
    raw = raw.where(den != 0, 0)

    if mode == "K":
        previous_raw_sum = raw.shift(1).rolling(k_n, min_periods=k_n).sum()
        target_raw = float(target)*k_n - previous_raw_sum
    else:
        previous_k = raw.rolling(k_n, min_periods=k_n).mean().shift(1)
        previous_k_sum = previous_k.shift(0).rolling(d_n, min_periods=d_n).sum()
        # previous_k_sum includes the K value immediately before the current bar
        # through d_n-1 earlier bars; build the exact d-window manually.
        k = raw.rolling(k_n, min_periods=k_n).mean()
        previous_k_sum = k.shift(1).rolling(d_n, min_periods=d_n).sum()
        target_k = float(target)*d_n - previous_k_sum
        previous_raw_sum = raw.shift(1).rolling(k_n, min_periods=k_n).sum()
        target_raw = target_k*k_n - previous_raw_sum

    target_raw = target_raw.clip(0.0001, 99.9999)
    implied_rsi = lo + (target_raw/100.0)*den
    implied_rsi = implied_rsi.where(den.abs() > 1e-12, r.shift(1))

    # Reverse RSI at an arbitrary target RSI, using the same Wilder recursion.
    d = s.diff()
    ag = rma(d.clip(lower=0), rsi_n).shift(1)
    al = rma((-d).clip(lower=0), rsi_n).shift(1)
    prev = s.shift(1)
    trs = implied_rsi.clip(0.0001, 99.9999)/(100.0-implied_rsi.clip(0.0001, 99.9999))
    cur = ag/al.replace(0, np.nan)
    up = prev + (rsi_n-1)*(trs*al-ag)
    down = prev - (rsi_n-1)*((ag/trs)-al)
    raw_price = pd.Series(np.where(trs > cur, up, down), index=s.index)
    raw_price = raw_price.where(prev.notna() & ag.notna() & al.notna())
    return ema(raw_price, max(1, smoothing_n))


def resample_series(s, timeframe):
    if timeframe == "Daily":
        return s
    rules = {
        "Weekly": "W-FRI",
        "Monthly": "ME",
        "Quarterly": "QE",
        "Six Month": "2QE",
        "Yearly": "YE",
    }
    rule = rules[timeframe]
    try:
        return s.resample(rule, closed="right", label="right").last().dropna()
    except Exception:
        fallback = {"Monthly": "M", "Quarterly": "Q", "Six Month": "2Q", "Yearly": "Y"}[timeframe]
        return s.resample(fallback, closed="right", label="right").last().dropna()


def indicator_series(s, spec):
    name = spec["name"]
    p = spec.get("params", {})
    if name == "Close": return s
    if name == "EMA": return ema(s, int(p["length"]))
    if name == "HMA": return hma(s, int(p["length"]))
    if name == "RSI": return rsi(s, int(p["length"]))
    if name == "EMA of RSI": return ema(rsi(s, int(p["rsi_length"])), int(p["ema_length"]))
    if name.startswith("MACD"):
        line, sig, hist = macd(s, int(p["fast_length"]), int(p["slow_length"]), int(p["signal_length"]))
        return {"MACD": line, "MACD Signal": sig, "MACD Histogram": hist}[name]
    if name in ("Stoch RSI %K", "Stoch RSI %D"):
        k, d = stoch_rsi(s, int(p["rsi_length"]), int(p["stoch_length"]), int(p["k_length"]), int(p["d_length"]))
        return k if name.endswith("%K") else d
    if name == "Numeric Value": return pd.Series(float(p["value"]), index=s.index)
    if name == "Reverse RSI": return reverse_rsi_price(s, float(p["target"]), int(p["rsi_length"]), int(p["smoothing_length"]))
    if name == "Reverse Stoch RSI %K": return reverse_stoch_price(s, float(p["target"]), int(p["rsi_length"]), int(p["stoch_length"]), int(p["k_length"]), int(p["d_length"]), int(p["smoothing_length"]), "K")
    if name == "Reverse Stoch RSI %D": return reverse_stoch_price(s, float(p["target"]), int(p["rsi_length"]), int(p["stoch_length"]), int(p["k_length"]), int(p["d_length"]), int(p["smoothing_length"]), "D")
    raise ValueError(f"Unsupported indicator: {name}")


def value_last(series):
    return float(series.iloc[-1]) if len(series) and pd.notna(series.iloc[-1]) else float("nan")


def value_prev(series):
    return float(series.iloc[-2]) if len(series) >= 2 and pd.notna(series.iloc[-2]) else float("nan")


def compare(a, b, op, aprev=float("nan"), bprev=float("nan")):
    if not math.isfinite(a) or not math.isfinite(b):
        return False
    if op == "Equal": return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-9)
    if op == "Greater": return a > b
    if op == "Greater Equal": return a >= b
    if op == "Less": return a < b
    if op == "Less Equal": return a <= b
    if op == "Cross Above": return math.isfinite(aprev) and math.isfinite(bprev) and aprev <= bprev and a > b
    if op == "Cross Below": return math.isfinite(aprev) and math.isfinite(bprev) and aprev >= bprev and a < b
    return False


def eval_condition_set(s, conditions):
    cache = {}
    def get(spec):
        key = json.dumps(spec, sort_keys=True)
        if key not in cache:
            cache[key] = indicator_series(s, spec)
        return cache[key]
    results = []
    for item in conditions:
        left = get(item["left"])
        right_spec = item["right"]
        if right_spec["name"] == "Numeric Value":
            right = get(right_spec)
        else:
            right = get(right_spec)
        ok = compare(value_last(left), value_last(right), item["comparator"], value_prev(left), value_prev(right))
        results.append(ok)
    if not results:
        return False, cache
    answer = results[0]
    for item, value in zip(conditions[1:], results[1:]):
        answer = (answer and value) if item.get("logic", "AND") == "AND" else (answer or value)
    return bool(answer), cache


def run_scan(data_dir, timeframe_choice, conditions, symbols=None, progress_callback=None):
    progress_path = Path(data_dir) / "scan_progress.json"
    def write_progress(payload):
        try:
            tmp = progress_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            tmp.replace(progress_path)
        except Exception:
            pass

    all_symbols = symbols or load_symbols(data_dir)
    tf_list = TIMEFRAMES if timeframe_choice == "All Timeframes" else [timeframe_choice]
    total = len(all_symbols)

    def scan_one(symbol):
        local_matches = []
        try:
            daily = load_symbol(data_dir, symbol)
            for tf in tf_list:
                series = resample_series(daily, tf)
                ok, cache = eval_condition_set(series, conditions)
                if ok:
                    values = {}
                    for spec_key, ser in cache.items():
                        try:
                            values[spec_key] = value_last(ser)
                        except Exception:
                            pass
                    label_date = series.index[-1].strftime("%Y-%m-%d") if len(series) else ""
                    local_matches.append({
                        "symbol": symbol, "timeframe": tf, "close": value_last(series),
                        "values": values, "data_date": label_date
                    })
            return local_matches, None
        except Exception as exc:
            return [], {"symbol": symbol, "error": f"{type(exc).__name__}: {exc}"}

    matches = []
    failed = []
    done = 0
    workers = min(SCAN_WORKERS, max(1, total))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(scan_one, symbol): symbol for symbol in all_symbols}
        for future in as_completed(futures):
            symbol = futures[future]
            try:
                local_matches, error = future.result()
            except Exception as exc:
                local_matches, error = [], {"symbol": symbol, "error": f"{type(exc).__name__}: {exc}"}
            matches.extend(local_matches)
            if error:
                failed.append(error)
            done += 1
            payload = {"stage":"running", "total": total, "completed": done, "symbol": symbol, "matches": len(matches)}
            write_progress(payload)
            if progress_callback:
                progress_callback(payload)

    matches.sort(key=lambda x: (x.get("symbol", ""), x.get("timeframe", "")))
    final = {"stage":"complete", "matches": matches, "failed": failed, "processed": done, "total": total}
    write_progress({"stage":"complete", "total": total, "completed": done, "matches": len(matches), "failed": len(failed)})
    return final


def _safe_filename(name):
    return "".join(ch if ch.isalnum() or ch in "-_ ." else "_" for ch in name).strip() or "scan"


def run_saved_scan_and_track(data_dir, scan_name, timeframe, conditions, result_dir, tracking_dir):
    result = run_scan(data_dir, timeframe, conditions)
    now = pd.Timestamp.now().strftime("%Y-%m-%d")
    result_file = Path(result_dir) / f"{_safe_filename(scan_name)}_{now}.json"
    result_file.parent.mkdir(parents=True, exist_ok=True)
    result_file.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    track_file = Path(tracking_dir) / f"{_safe_filename(scan_name)}.json"
    try:
        active = json.loads(track_file.read_text(encoding="utf-8")) if track_file.exists() else []
    except Exception:
        active = []
    active_map = {f"{x.get('symbol')}|{x.get('timeframe')}": x for x in active if x.get("status") == "ACTIVE"}
    current = {}
    for m in result.get("matches", []):
        key = f"{m['symbol']}|{m['timeframe']}"
        current[key] = m
        market_date = m.get("data_date") or now
        if key not in active_map:
            active_map[key] = {
                "symbol": m["symbol"], "timeframe": m["timeframe"],
                "entry_date": market_date, "entry_close": m["close"],
                "last_date": market_date, "last_close": m["close"],
                "exit_date": None, "exit_close": None, "pnl_pct": None,
                "status": "ACTIVE"
            }
        else:
            x = active_map[key]
            x["last_date"] = market_date
            x["last_close"] = m["close"]
    for key in list(active_map.keys()):
        if key not in current:
            x = active_map[key]
            symbol = x["symbol"]
            try:
                s = load_symbol(data_dir, symbol)
                exit_close = value_last(s)
                exit_date = s.index[-1].strftime("%Y-%m-%d") if len(s) else now
            except Exception:
                exit_close = x.get("last_close")
                exit_date = now
            entry = float(x.get("entry_close") or 0)
            pnl = ((float(exit_close)-entry)/entry*100.0) if entry else None
            x["exit_date"] = exit_date
            x["exit_close"] = exit_close
            x["pnl_pct"] = pnl
            x["status"] = "CLOSED"

    combined = list(active_map.values())
    track_file.parent.mkdir(parents=True, exist_ok=True)
    track_file.write_text(json.dumps(combined, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"scan_name": scan_name, "result_file": str(result_file), "tracking_file": str(track_file), "active": sum(1 for x in combined if x.get("status") == "ACTIVE"), "closed": sum(1 for x in combined if x.get("status") == "CLOSED"), "matches": len(result.get("matches", []))}
