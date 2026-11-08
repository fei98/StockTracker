"""Predeclared training-only selection for a small, auditable parameter grid."""

from dataclasses import replace
from statistics import median

from model import Config, D
from intraday import simulate_intraday


LOWERS = (5000, 10000, 15000)
UPPERS = (20000, 30000, 40000)
STEPS = (2, 3, 4, 5, 6)
SPLIT = "2025-11-07"
RISK_LIMIT = D("20")


def key_for(config):
    return f"L{config.lower:.0f}_U{config.upper:.0f}_S{(config.ratio - 1) * 100:.0f}"


def candidates():
    return [replace(Config(), lower=D(lower), upper=D(upper), ratio=1 + D(step) / 100)
            for lower in LOWERS for upper in UPPERS for step in STEPS]


def coordinates(row):
    return (LOWERS.index(int(row["lower"])), UPPERS.index(int(row["upper"])), STEPS.index(int(row["step"])))


def neighbors(left, right):
    return sum(abs(a - b) for a, b in zip(coordinates(left), coordinates(right))) == 1


def metric_pair(bars, config):
    return [simulate_intraday(bars, replace(config, slippage_ticks=tick))["summary"] for tick in (0, 1)]


def metric_row(config, pair, prefix):
    row = {"candidate": key_for(config), "lower": config.lower, "upper": config.upper,
           "step": (config.ratio - 1) * 100}
    for tick, summary in enumerate(pair):
        for field in ("return_pct", "max_drawdown_pct", "trade_count", "fees", "equity"):
            row[f"{prefix}_{field}_{tick}tick"] = summary[field]
    return row


def training_rows(bars):
    return [metric_row(config, metric_pair(bars, config), "train") for config in candidates()]


def rank_training(rows):
    """Use only training columns, with no access to validation or full-period outcomes."""
    if len({row["candidate"] for row in rows}) != len(rows) or not rows:
        raise ValueError("Training candidates must be nonempty and unique.")
    scored = []
    for source in rows:
        row = dict(source)
        row["train_worst_return"] = min(D(row[f"train_return_pct_{tick}tick"]) for tick in (0, 1))
        row["train_worst_drawdown"] = max(D(row[f"train_max_drawdown_pct_{tick}tick"]) for tick in (0, 1))
        row["train_risk_eligible"] = row["train_worst_drawdown"] <= RISK_LIMIT
        scored.append(row)
    for row in scored:
        nearby = [other for other in scored if other["candidate"] == row["candidate"] or neighbors(row, other)]
        row["neighbor_count_including_self"] = len(nearby)
        row["train_neighbor_median_worst_return"] = median(other["train_worst_return"] for other in nearby)
        row["train_neighbor_risk_pass_fraction"] = D(sum(other["train_risk_eligible"] for other in nearby)) / len(nearby)
        row["train_selector_eligible"] = (row["train_risk_eligible"] and
                                           row["train_neighbor_risk_pass_fraction"] >= D("0.5"))
    eligible = [row for row in scored if row["train_selector_eligible"]]
    if not eligible:
        raise ValueError("No training candidate satisfies the declared risk and neighborhood rules.")
    eligible.sort(key=lambda row: (-row["train_neighbor_median_worst_return"], -row["train_worst_return"],
                                   row["train_worst_drawdown"], row["candidate"]))
    return eligible[0]["candidate"], scored


def split_bars(bars):
    training = [bar for bar in bars if bar["time"][:10] < SPLIT]
    validation = [bar for bar in bars if bar["time"][:10] >= SPLIT]
    if not training or not validation:
        raise ValueError("Both temporal partitions need data.")
    return training, validation
