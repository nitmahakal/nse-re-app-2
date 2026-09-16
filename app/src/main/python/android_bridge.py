import json
from fetch_engine import update as fetch_update, refresh_nse_symbol_universe
from scanner_engine import run_scan as scanner_run, run_saved_scan_and_track


def update_market_data(symbols, data_dir):
    resolved, source = refresh_nse_symbol_universe(data_dir, list(symbols))
    result = fetch_update(resolved, data_dir)
    result["symbol_universe_source"] = source
    result["symbol_count"] = len(resolved)
    return json.dumps(result, ensure_ascii=False)


def run_scan(data_dir, timeframe, conditions_json):
    conditions = json.loads(conditions_json)
    return json.dumps(scanner_run(data_dir, timeframe, conditions), ensure_ascii=False)


def run_saved_and_track(data_dir, name, timeframe, conditions_json, result_dir, tracking_dir):
    conditions = json.loads(conditions_json)
    return json.dumps(run_saved_scan_and_track(data_dir, name, timeframe, conditions, result_dir, tracking_dir), ensure_ascii=False)
