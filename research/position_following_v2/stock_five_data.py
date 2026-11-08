"""Freeze complete two-year unadjusted five-minute history from BaoStock."""

import argparse
from collections import Counter, defaultdict
import csv
from datetime import date, datetime, timedelta, timezone
import hashlib
import importlib.metadata
import json
from pathlib import Path
import socket

from backtest import D, write_csv, write_json
from backtest_two_years import START, END
from intraday_data import normalize_intraday
from stock_data import ROOT as STOCK_ROOT, load_stock, load_stock_five


ROOT = STOCK_ROOT / "fine_search_5m"
FIELDS = ["date", "time", "code", "open", "high", "low", "close", "volume", "amount", "adjustflag"]


def monthly_intervals(start=START, end=END):
    point, finish = date.fromisoformat(start), date.fromisoformat(end)
    while point <= finish:
        month = point.month
        boundary = date(point.year + (month == 12), month % 12 + 1, 1) - timedelta(days=1)
        stop = min(boundary, finish)
        yield point.isoformat(), stop.isoformat()
        point = stop + timedelta(days=1)


def convert_rows(rows):
    converted = []
    for row in rows:
        if row["code"] != "sz.002396" or row["adjustflag"] != "3":
            raise ValueError("Wrong stock symbol or adjusted minute prices.")
        if len(row["time"]) != 17 or not row["time"].isdigit() or row["time"][14:] != "000":
            raise ValueError("BaoStock timestamps must contain 17 digits and zero milliseconds.")
        stamp = datetime.strptime(row["time"][:14], "%Y%m%d%H%M%S")
        if stamp.date().isoformat() != row["date"]:
            raise ValueError("BaoStock date and timestamp disagree.")
        converted.append({"day": stamp.isoformat(sep=" "),
                          **{key: row[key] for key in ("open", "high", "low", "close", "volume")}})
    return converted


def validate_minutes(rows, daily, recent, allow_source_differences=False):
    converted = convert_rows(rows)
    grouped = defaultdict(list)
    for row in converted:
        grouped[row["day"][:10]].append(row)
    own_daily = []
    for day, values in grouped.items():
        own_daily.append({"date": day, "open": D(values[0]["open"]), "close": D(values[-1]["close"]),
                          "high": max(D(row["high"]) for row in values),
                          "low": min(D(row["low"]) for row in values),
                          "volume": sum(D(row["volume"]) for row in values)})
    # Self-aggregation checks structure only; independent market checks remain mandatory below.
    bars, dropped = normalize_intraday(converted, 5, own_daily if allow_source_differences else daily)
    if dropped or len(bars) != len(daily) * 48 or len(daily) != 485:
        raise ValueError("The complete study requires 485 full sessions and 23280 real bars.")
    if bars[0]["time"][:10] != START or bars[-1]["time"][:10] != END:
        raise ValueError("Five-minute history does not cover the full requested window.")
    indexed = {bar["time"]: bar for bar in bars}
    daily_index = {row["date"]: row for row in daily}
    if set(grouped) != set(daily_index):
        raise ValueError("Five-minute and daily trading dates differ.")
    daily_differences = Counter()
    daily_errors = defaultdict(lambda: D("0"))
    for own in own_daily:
        reference = daily_index[own["date"]]
        if own["open"] != reference["open"] or own["close"] != reference["close"]:
            raise ValueError("Daily opening or closing price differs from the independent reference.")
        if own["high"] > reference["high"] or own["low"] < reference["low"]:
            raise ValueError("Five-minute prices exceed the independent daily trading range.")
        for field in ("high", "low"):
            if own[field] != reference[field]:
                daily_differences[field] += 1
                daily_errors[field] = max(daily_errors[field], abs(own[field] - reference[field]))
    mismatches = []
    field_differences = Counter()
    field_errors = defaultdict(lambda: D("0"))
    for bar in recent:
        other = indexed.get(bar["time"])
        if other is None:
            raise ValueError("A recent independent five-minute timestamp is missing.")
        differing = [key for key in ("open", "high", "low", "close") if bar[key] != other[key]]
        for field in differing:
            field_differences[field] += 1
            field_errors[field] = max(field_errors[field], abs(bar[field] - other[field]))
        if differing:
            mismatches.append(bar["time"])
    if mismatches and not allow_source_differences:
        raise ValueError(f"BaoStock and Sina five-minute OHLC disagree: {mismatches[:5]}")
    return bars, {"complete_days": len(daily), "bars": len(bars),
                   "daily_open_close_equal": True, "daily_range_contained": True,
                   "daily_ohlc_equal": not bool(daily_differences),
                   "daily_extreme_difference_days": dict(daily_differences),
                   "daily_extreme_max_difference": dict(daily_errors),
                   "recent_sina_comparison_bars": len(recent),
                   "recent_sina_equal_bars": len(recent) - len(mismatches),
                   "recent_sina_differing_bars": len(mismatches),
                   "recent_sina_field_differences": dict(field_differences),
                   "recent_sina_max_difference": dict(field_errors),
                   "source_differences_explicitly_allowed": allow_source_differences,
                   "first_time": bars[0]["time"], "last_time": bars[-1]["time"]}


def download_chunks(folder, refresh=False):
    import baostock as bs

    socket.setdefaulttimeout(20)
    login = bs.login()
    if login.error_code != "0":
        raise RuntimeError(f"BaoStock login failed: {login.error_code} {login.error_msg}")
    all_rows, chunks = [], []
    try:
        for first, last in monthly_intervals():
            path = folder / f"baostock_{first}_{last}.csv"
            meta_path = path.with_suffix(".json")
            if path.exists() and meta_path.exists() and not refresh:
                metadata = json.loads(meta_path.read_text(encoding="utf-8"))
                if hashlib.sha256(path.read_bytes()).hexdigest() != metadata["sha256"]:
                    raise ValueError("A cached BaoStock chunk changed.")
                with path.open(encoding="utf-8-sig", newline="") as stream:
                    rows = list(csv.DictReader(stream))
            else:
                query = bs.query_history_k_data_plus("sz.002396", ",".join(FIELDS),
                                                     start_date=first, end_date=last,
                                                     frequency="5", adjustflag="3")
                values = []
                while query.error_code == "0" and query.next():
                    values.append(query.get_row_data())
                if query.error_code != "0" or not values:
                    raise RuntimeError(f"BaoStock query failed: {query.error_code} {query.error_msg}")
                rows = [dict(zip(FIELDS, value)) for value in values]
                write_csv(path, rows)
                metadata = {"first": first, "last": last, "rows": len(rows),
                            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                            "retrieved_at_utc": datetime.now(timezone.utc).isoformat()}
                write_json(meta_path, metadata)
            all_rows.extend(rows)
            chunks.append(metadata)
            print(f"Five-minute history ready: {first} through {last}, {len(rows)} bars", flush=True)
    finally:
        bs.logout()
    return all_rows, chunks


def load_complete_five(refresh=False, allow_source_differences=False):
    daily, _, _ = load_stock()
    recent, _ = load_stock_five(daily)
    folder = ROOT / "data"
    folder.mkdir(parents=True, exist_ok=True)
    snapshot_path, csv_path = folder / "snapshot.json", folder / "baostock_5m.csv"
    if snapshot_path.exists() and csv_path.exists() and not refresh:
        metadata = json.loads(snapshot_path.read_text(encoding="utf-8"))
        if hashlib.sha256(csv_path.read_bytes()).hexdigest() != metadata["sha256"]:
            raise ValueError("The complete BaoStock snapshot hash changed.")
        with csv_path.open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        bars, checks = validate_minutes(rows, daily, recent, allow_source_differences)
        return bars, dict(metadata, checks=checks)
    rows, chunks = download_chunks(folder, refresh)
    bars, checks = validate_minutes(rows, daily, recent, allow_source_differences)
    write_csv(csv_path, rows)
    metadata = {"symbol": "002396", "provider": "BaoStock", "sdk_version": importlib.metadata.version("baostock"),
                "source_documentation": "https://www.baostock.com/mainContent?file=stockKData.md",
                "request": {"frequency": "5", "adjustflag": "3", "start": START, "end": END},
                "sha256": hashlib.sha256(csv_path.read_bytes()).hexdigest(), "chunks": chunks, "checks": checks,
                "retrieved_at_utc": datetime.now(timezone.utc).isoformat()}
    write_json(snapshot_path, metadata)
    return bars, metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-source-differences", action="store_true")
    args = parser.parse_args()
    bars, metadata = load_complete_five(allow_source_differences=args.allow_source_differences)
    print(json.dumps(metadata["checks"], ensure_ascii=True, default=str))
