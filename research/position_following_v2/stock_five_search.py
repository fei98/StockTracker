"""Four-dimensional search with a locked training phase and separate source checks."""

import argparse
from concurrent.futures import ProcessPoolExecutor, as_completed
from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import inspect
import json
from pathlib import Path
import time

from backtest import D, json_default, write_csv, write_json
from stock_data import load_stock, load_stock_five
from stock_five_data import ROOT, load_complete_five
from stock_five_fast import BudgetViolation, context, simulate_fast
from stock_five_parameters import (Point, SPLIT, choose_seeds, coarse_neighbors, coarse_points,
                                   fine_centers, fine_neighbors, pair_metrics, rank)
from stock_intraday import simulate_stock


OUTPUT = ROOT / "outputs"
BASE = Path(__file__).resolve().parent
WORKER_CONTEXTS = {}
METRIC_SOURCE_FILES = ("model.py", "stock_model.py", "stock_intraday.py", "stock_five_fast.py",
                       "stock_five_data.py", "stock_data.py", "intraday.py", "intraday_data.py",
                       "backtest.py", "backtest_two_years.py")
DECIMAL_FIELDS = {"equity", "net_profit", "return_pct", "max_drawdown_pct", "daily_drawdown_pct", "fees",
                  "dividends", "dividend_tax", "potential_dividend_tax_if_sold", "end_cash", "end_cost",
                  "min_cash", "max_cost", "min_cost", "net_realized", "net_unrealized"}


def initialize_worker(bars):
    global WORKER_CONTEXTS
    training = [bar for bar in bars if bar["time"][:10] < SPLIT]
    validation = [bar for bar in bars if bar["time"][:10] >= SPLIT]
    WORKER_CONTEXTS = {"train": context(training), "validation": context(validation), "full": context(bars)}


def worker(task):
    scope, point = task
    pair = []
    for tick in (0, 1):
        try:
            result = simulate_fast(WORKER_CONTEXTS[scope], point.config(tick))
            result["valid"] = True
        except BudgetViolation as error:
            result = {"valid": False, "budget_failure": str(error)}
        pair.append(result)
    return point, pair


def fingerprint(metadata):
    source = {"data": metadata["sha256"], "scenarios": [0, 1],
              "split": SPLIT,
              "point_config": hashlib.sha256(inspect.getsource(Point.config).encode()).hexdigest(),
              "worker": hashlib.sha256(inspect.getsource(worker).encode()).hexdigest(),
              "initialize_worker": hashlib.sha256(inspect.getsource(initialize_worker).encode()).hexdigest()}
    # Metric caches depend on prices and simulation, not on which points a selector later ranks.
    for name in METRIC_SOURCE_FILES:
        source[name] = hashlib.sha256((BASE / name).read_bytes()).hexdigest()
    return hashlib.sha256(json.dumps(source, sort_keys=True).encode()).hexdigest()


def load_cache(scope, digest):
    path = ROOT / f"{scope}_cache.json"
    if not path.exists():
        return {}
    saved = json.loads(path.read_text(encoding="utf-8"))
    if saved["fingerprint"] != digest:
        raise ValueError("Search inputs changed. Preserve this study and use a new version.")
    result = {}
    for row in saved["rows"]:
        p = row["point"]
        point = Point(int(p["lower"]), int(p["upper"]), D(p["step"]), int(p["amount"]))
        pair = []
        for source in row["summaries"]:
            summary = dict(source)
            for key in DECIMAL_FIELDS:
                if key in summary:
                    summary[key] = D(summary[key])
            pair.append(summary)
        result[point] = pair
    return result


def save_cache(scope, digest, metrics):
    write_json(ROOT / f"{scope}_cache.json", {"fingerprint": digest,
               "rows": [{"point": asdict(point), "summaries": metrics[point]} for point in sorted(metrics)]})


def evaluate(points, scope, metrics, pool, digest):
    missing = [point for point in points if point not in metrics]
    if not missing:
        return
    started = time.perf_counter()
    futures = [pool.submit(worker, (scope, point)) for point in missing]
    for count, future in enumerate(as_completed(futures), 1):
        point, pair = future.result()
        metrics[point] = pair
        if count % 100 == 0 or count == len(missing):
            save_cache(scope, digest, metrics)
            print(f"{scope}: {count}/{len(missing)} parameter pairs ready, {time.perf_counter()-started:.1f}s", flush=True)


def point_row(point, scopes):
    row = {"candidate": point.key, **asdict(point)}
    for scope, metrics in scopes.items():
        pair = metrics[point]
        for tick, summary in enumerate(pair):
            row[f"{scope}_valid_{tick}tick"] = summary.get("valid", True)
            row[f"{scope}_budget_failure_{tick}tick"] = summary.get("budget_failure", "")
            for key in ("return_pct", "max_drawdown_pct", "equity", "trade_count", "fees", "min_cash", "max_cost"):
                row[f"{scope}_{key}_{tick}tick"] = summary.get(key, "")
    return row


def export(label, result):
    for table in ("trades", "events", "cashflows", "marks", "daily"):
        rows = result[table]
        fields = list(dict.fromkeys(key for row in rows for key in row))
        write_csv(OUTPUT / f"{label}_{table}.csv", [{key: row.get(key, "") for key in fields} for row in rows])


def selection_lock(chosen, seeds, training, digest, fine):
    canonical = [{"point": asdict(point), "summaries": training[point]} for point in sorted(training)]
    training_hash = hashlib.sha256(json.dumps(canonical, sort_keys=True, default=json_default).encode()).hexdigest()
    lock = {"candidate": asdict(chosen), "candidate_id": chosen.key, "seeds": [asdict(point) for point in seeds],
            "coarse_count": len(coarse_points()), "fine_center_count": len(fine),
            "training_evaluated_count_including_support": len(training), "training_sha256": training_hash,
            "fingerprint": digest, "risk_limit_pct": "20", "training_end": "2025-11-06",
            "validation_start": SPLIT, "created_at_utc": datetime.now(timezone.utc).isoformat(),
            "selection_rule": "Both slippages within 20% on training; at least four real physical neighbors; "
                              "at least half of neighbors including self risk-eligible; maximize median worst "
                              "neighbor return, then own worst return, lower drawdown, deterministic point."}
    path = ROOT / "training_lock.json"
    if path.exists():
        previous = json.loads(path.read_text(encoding="utf-8"))
        if previous["training_sha256"] != training_hash or previous["candidate_id"] != chosen.key:
            raise ValueError("The training lock changed after validation. Use a new study version.")
        return previous
    write_json(path, lock)
    return lock


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--no-report", action="store_true")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    protected = [BASE / folder / "outputs" / filename for folder, filename in (
        ("two_years", "results.json"), ("intraday_study", "study_results.json"),
        ("stock_002396", "results.json"), ("stock_002396", "报告.md"))]
    prior_hashes = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in protected}
    bars, metadata = load_complete_five(allow_source_differences=True)
    digest = fingerprint(metadata)
    coarse = coarse_points()
    training = load_cache("train", digest)
    with ProcessPoolExecutor(max_workers=args.workers, initializer=initialize_worker, initargs=(bars,)) as pool:
        evaluate(coarse, "train", training, pool, digest)
        coarse_ranked = rank(coarse, training, coarse_neighbors)
        seeds = choose_seeds(coarse_ranked)
        centers = fine_centers(seeds)
        support = sorted(set(centers) | {neighbor for point in centers for neighbor in fine_neighbors(point)})
        evaluate(support, "train", training, pool, digest)
        fine_ranked = rank(centers, training, fine_neighbors)
        if not fine_ranked:
            raise ValueError("No fine-search candidate satisfies the predeclared training rules.")
        chosen = fine_ranked[0]["point"]
        lock = selection_lock(chosen, seeds, training, digest, centers)
        print(f"Candidate locked before validation: {chosen.key}", flush=True)
        all_points = sorted(set(coarse) | set(centers))
        validation = load_cache("validation", digest)
        full = load_cache("full", digest)
        evaluate(all_points, "validation", validation, pool, digest)
        evaluate(all_points, "full", full, pool, digest)
    scopes = {"train": training, "validation": validation, "full": full}
    rows = [point_row(point, scopes) for point in all_points]
    write_csv(OUTPUT / "parameter_grid.csv", rows)
    for title, ranked in (("coarse_training_rank", coarse_ranked), ("fine_training_rank", fine_ranked)):
        write_json(OUTPUT / f"{title}.json", [dict(row, point=asdict(row["point"])) for row in ranked])
    original = Point(10000, 30000, D("3"), 4000)
    feasible = [point for point in all_points if all(pair_metrics(scopes[scope][point])[2] for scope in ("full", "validation"))]
    historical = max((point for point in all_points if pair_metrics(full[point])[2]),
                     key=lambda point: (pair_metrics(full[point])[0], point))
    balanced = (max(feasible, key=lambda point: (pair_metrics(full[point])[0] /
                 max(pair_metrics(full[point])[1], D("0.01")), point)) if feasible else None)
    named = {"original": original, "training_locked": chosen, "historical_best": historical}
    if feasible:
        named["posthoc_return"] = max(feasible, key=lambda point: (pair_metrics(full[point])[0], point))
    if balanced:
        named["posthoc_balance"] = balanced
    detailed = {}
    parts = {"full": bars, "validation": [bar for bar in bars if bar["time"][:10] >= SPLIT]}
    for label, point in named.items():
        for scope, period in parts.items():
            for tick in (0, 1):
                reference = scopes[scope][point][tick]
                if not reference.get("valid", True):
                    detailed[f"{label}_{scope}_{tick}tick"] = reference
                    continue
                result = simulate_stock(period, point.config(tick))
                for field in ("equity", "return_pct", "max_drawdown_pct", "fees", "dividends", "dividend_tax"):
                    if abs(result["summary"][field] - reference[field]) > D("0.000001"):
                        raise AssertionError("Fast and reference engines disagree on a reported candidate.")
                export(f"{label}_{scope}_{tick}tick", result)
                summary = dict(result["summary"])
                amounts = sorted(trade["amount"] for trade in result["trades"][1:])
                if amounts:
                    from statistics import median
                    summary["actual_order_amounts"] = {"minimum": amounts[0], "median": median(amounts),
                                                       "maximum": amounts[-1], "target": D(point.amount)}
                detailed[f"{label}_{scope}_{tick}tick"] = summary
    daily, _, _ = load_stock()
    sina, sina_metadata = load_stock_five(daily)
    recent_bao = [bar for bar in bars if bar["time"][:10] >= sina[0]["time"][:10]]
    common_entry = context(recent_bao).entry_date
    source_checks = {}
    for label, point in named.items():
        source_checks[label] = {}
        for provider, period in (("baostock", recent_bao), ("sina", sina)):
            source_checks[label][provider] = []
            for tick in (0, 1):
                try:
                    simulate_fast(context(period, entry_date=common_entry), point.config(tick))
                except BudgetViolation as error:
                    source_checks[label][provider].append({"valid": False, "budget_failure": str(error)})
                    continue
                result = simulate_stock(period, point.config(tick), entry_date=common_entry)
                export(f"source_{label}_{provider}_{tick}tick", result)
                source_checks[label][provider].append(result["summary"])
    payload = {"metadata": metadata, "sina_metadata": sina_metadata, "training_lock": lock,
               "coarse_count": len(coarse), "fine_centers": len(centers), "searched_count": len(all_points),
               "named": {key: asdict(point) for key, point in named.items()}, "results": detailed,
               "source_checks": source_checks, "source_common_entry": common_entry, "grid": rows,
               "locked_rank": {key: value for key, value in fine_ranked[0].items() if key != "point"}}
    write_json(OUTPUT / "results.json", payload)
    if not args.no_report:
        from stock_five_report import create_report
        create_report(payload)
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path, digest in prior_hashes.items()):
        raise AssertionError("An earlier research artifact changed.")
    print(json.dumps({"named": payload["named"], "searched_count": len(all_points),
                      "results": {key: {field: summary.get(field) for field in
                                  ("return_pct", "max_drawdown_pct", "equity", "trade_count")}
                                  for key, summary in detailed.items()}}, default=json_default, ensure_ascii=True))


if __name__ == "__main__":
    main()
