"""Freeze intraday source responses and reconcile complete sessions with daily OHLC."""

from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path

from backtest import D, get_url, write_json
from backtest_two_years import END, START, load_snapshot as load_daily


ROOT = Path(__file__).resolve().parent / "intraday_study"


def session_times(minutes):
    times = []
    for begin in ("09:30", "13:00"):
        point = datetime.fromisoformat("2000-01-01 " + begin)
        for offset in range(minutes, 121, minutes):
            times.append((point + timedelta(minutes=offset)).strftime("%H:%M:%S"))
    return times


def normalize_intraday(rows, minutes, daily, drop_partial_first=False):
    indexed = {bar["date"]: bar for bar in daily}
    selected = [row for row in rows if START <= row["day"][:10] <= END]
    times = [row["day"] for row in selected]
    if times != sorted(set(times)) or not selected:
        raise ValueError("Intraday timestamps must be unique and strictly ordered.")
    groups = defaultdict(list)
    for row in selected:
        groups[row["day"][:10]].append(row)
    expected = session_times(minutes)
    dropped = []
    first_day = next(iter(groups))
    first_times = [row["day"][11:] for row in groups[first_day]]
    if drop_partial_first and first_times != expected:
        if 0 < len(first_times) < len(expected) and first_times == expected[-len(first_times):]:
            dropped.append(first_day)
            del groups[first_day]
        else:
            raise ValueError(f"Invalid first-day session bars: {first_day}")
    bars = []
    for day, items in groups.items():
        if [row["day"][11:] for row in items] != expected:
            raise ValueError(f"Missing, duplicate, or invalid session bars: {day}")
        if day not in indexed:
            raise ValueError(f"An intraday date is absent from daily history: {day}")
        for row in items:
            bar = {key: D(str(row[key])) for key in ("open", "close", "high", "low", "volume")}
            if not all(value.is_finite() and value >= 0 for value in bar.values()):
                raise ValueError("Nonfinite or negative intraday fields.")
            if not (0 < bar["low"] <= min(bar["open"], bar["close"]) <=
                    max(bar["open"], bar["close"]) <= bar["high"]):
                raise ValueError("Invalid intraday OHLC bounds.")
            bar.update(time=row["day"], minutes=minutes)
            bars.append(bar)
        aggregated = {"open": D(items[0]["open"]), "close": D(items[-1]["close"]),
                      "high": max(D(row["high"]) for row in items),
                      "low": min(D(row["low"]) for row in items)}
        if any(value != indexed[day][key] for key, value in aggregated.items()):
            raise ValueError(f"Intraday and daily OHLC disagree: {day}")
    if not bars:
        raise ValueError("No complete intraday sessions remain.")
    available = set(groups)
    required = {day for day in indexed if bars[0]["time"][:10] <= day <= bars[-1]["time"][:10]}
    if available != required:
        raise ValueError("Intraday history skips a daily trading date.")
    if minutes == 30 and available != set(indexed):
        raise ValueError("Thirty-minute history must cover the entire two-year daily window.")
    return bars, dropped


def load_intraday(minutes=30, refresh=False):
    daily, _ = load_daily()
    folder = ROOT / "data"
    folder.mkdir(parents=True, exist_ok=True)
    raw_path = folder / f"sina_{minutes}m.json"
    metadata_path = folder / f"sina_{minutes}m_metadata.json"
    if raw_path.exists() and metadata_path.exists() and not refresh:
        blob = raw_path.read_bytes()
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        if hashlib.sha256(blob).hexdigest() != metadata["sha256"]:
            raise ValueError("The frozen intraday source hash changed.")
    else:
        count = 5000 if minutes == 30 else 24000
        url = ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
               f"CN_MarketData.getKLineData?symbol=sz159915&scale={minutes}&ma=no&datalen={count}")
        blob = get_url(url)
        metadata = {"url": url, "sha256": hashlib.sha256(blob).hexdigest(),
                    "retrieved_at_utc": datetime.now(timezone.utc).isoformat(), "minutes": minutes}
    bars, dropped = normalize_intraday(json.loads(blob), minutes, daily, drop_partial_first=minutes == 5)
    metadata.update(first_time=bars[0]["time"], last_time=bars[-1]["time"], bars=len(bars),
                    complete_days=len({bar["time"][:10] for bar in bars}), dropped_partial_days=dropped,
                    daily_ohlc_match=True)
    raw_path.write_bytes(blob)
    write_json(metadata_path, metadata)
    return bars, metadata
