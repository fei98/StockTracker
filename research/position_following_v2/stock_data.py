"""Freeze 002396 data and explain its adjusted history with announced corporate actions."""

from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

from backtest import D, get_url, normalize, write_csv, write_json
from backtest_two_years import START, END
from intraday_data import normalize_intraday
from stock_model import ACTIONS


ROOT = Path(__file__).resolve().parent / "stock_002396"


def validate_stock(blobs):
    raw_rows = json.loads(blobs["raw"])["data"]["sz002396"]["day"]
    qfq_rows = json.loads(blobs["qfq"])["data"]["sz002396"]["qfqday"]
    raw, adjusted = normalize(raw_rows, START, END), normalize(qfq_rows, START, END)
    if len(raw) < 450 or raw[0]["date"] != START or raw[-1]["date"] != END:
        raise ValueError("Stock daily history does not cover the two-year window.")
    if [bar["date"] for bar in raw] != [bar["date"] for bar in adjusted]:
        raise ValueError("Raw and adjusted stock dates differ.")
    secondary = {row["day"][:10]: row for row in json.loads(blobs["daily"])
                 if START <= row["day"][:10] <= END}
    if set(secondary) != {bar["date"] for bar in raw}:
        raise ValueError("Independent daily stock coverage differs.")
    max_error = D("0")
    differences = 0
    for bar, qfq in zip(raw, adjusted):
        if any(bar[key] != D(secondary[bar["date"]][key]) for key in ("open", "close", "high", "low")):
            raise ValueError("Independent stock daily OHLC sources disagree.")
        differences += any(bar[key] != qfq[key] for key in ("open", "close", "high", "low"))
        for key in ("open", "close", "high", "low"):
            expected = bar[key]
            for action in ACTIONS:
                if bar["date"] < action.ex_date:
                    expected = (expected - action.cash_reference) / (1 + action.bonus_reference)
            error = abs(expected - qfq[key])
            max_error = max(max_error, error)
            if error > D("0.001"):
                raise ValueError(f"Unexplained stock adjustment at {bar['date']}: {error}")
    minute, dropped = normalize_intraday(json.loads(blobs["30m"]), 30, raw)
    return raw, minute, {"daily_rows": len(raw), "intraday_bars": len(minute),
                          "adjusted_difference_days": differences,
                          "explained_adjustment_max_error": max_error, "daily_sources_equal": True,
                          "intraday_aggregated_ohlc_equal": True}


def load_stock(refresh=False):
    folder = ROOT / "data"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / "snapshot.json"
    if path.exists() and not refresh:
        metadata = json.loads(path.read_text(encoding="utf-8"))
        blobs = {}
        for key, item in metadata["sources"].items():
            blobs[key] = (folder / item["filename"]).read_bytes()
            if hashlib.sha256(blobs[key]).hexdigest() != item["sha256"]:
                raise ValueError(f"Frozen stock source hash changed: {key}")
        daily, minute, checks = validate_stock(blobs)
        return daily, minute, metadata
    urls = {
        "raw": f"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sz002396,day,{START},{END},1000,",
        "qfq": f"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sz002396,day,{START},{END},1000,qfq",
        "daily": ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                  "CN_MarketData.getKLineData?symbol=sz002396&scale=240&ma=no&datalen=1023"),
        "30m": ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                "CN_MarketData.getKLineData?symbol=sz002396&scale=30&ma=no&datalen=5000"),
    }
    with ThreadPoolExecutor(max_workers=4) as pool:
        blobs = dict(zip(urls, pool.map(get_url, urls.values())))
    daily, minute, checks = validate_stock(blobs)
    sources = {}
    for key, blob in blobs.items():
        filename = key + ".json"
        (folder / filename).write_bytes(blob)
        sources[key] = {"url": urls[key], "filename": filename, "sha256": hashlib.sha256(blob).hexdigest()}
    metadata = {"symbol": "002396", "name": "星网锐捷", "start": START, "end": END,
                "retrieved_at_utc": datetime.now(timezone.utc).isoformat(), "sources": sources,
                "checks": checks, "corporate_actions": [asdict(action) for action in ACTIONS]}
    write_json(path, metadata)
    write_csv(folder / "daily.csv", daily)
    return daily, minute, metadata


def load_stock_five(daily, refresh=False):
    folder = ROOT / "data"
    path, meta_path = folder / "5m.json", folder / "5m_metadata.json"
    if path.exists() and meta_path.exists() and not refresh:
        blob = path.read_bytes()
        metadata = json.loads(meta_path.read_text(encoding="utf-8"))
        if hashlib.sha256(blob).hexdigest() != metadata["sha256"]:
            raise ValueError("Frozen five-minute stock source changed.")
    else:
        url = ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
               "CN_MarketData.getKLineData?symbol=sz002396&scale=5&ma=no&datalen=5000")
        blob = get_url(url, timeout=30)
        metadata = {"url": url, "sha256": hashlib.sha256(blob).hexdigest(),
                    "retrieved_at_utc": datetime.now(timezone.utc).isoformat()}
    bars, dropped = normalize_intraday(json.loads(blob), 5, daily, drop_partial_first=True)
    metadata.update(first_time=bars[0]["time"], last_time=bars[-1]["time"], bars=len(bars),
                    complete_days=len({bar["time"][:10] for bar in bars}), dropped_days=dropped,
                    daily_ohlc_equal=True)
    path.write_bytes(blob)
    write_json(meta_path, metadata)
    return bars, metadata
