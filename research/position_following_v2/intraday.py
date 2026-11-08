"""Intraday execution adapter with next-bar sequencing and calendar-day T+1."""

from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta

from backtest import hold_quantity, max_drawdown, select_entry
from model import (Config, D, ZERO, Signal, _record, decide, fill_price,
                   initialize, round_quantity, validate_order)


@dataclass(frozen=True)
class Pending:
    signal: Signal
    bar_index: int
    end_time: str


def start_time(bar):
    return (datetime.fromisoformat(bar["time"]) - timedelta(minutes=bar["minutes"])).isoformat(sep=" ")


def daily_bars(bars):
    result = []
    for bar in bars:
        day = bar["time"][:10]
        if not result or result[-1]["date"] != day:
            result.append({"date": day, "open": bar["open"], "close": bar["close"],
                           "high": bar["high"], "low": bar["low"], "volume": bar["volume"]})
        else:
            row = result[-1]
            row["close"] = bar["close"]
            row["high"] = max(row["high"], bar["high"])
            row["low"] = min(row["low"], bar["low"])
            row["volume"] += bar["volume"]
    return result


def execute_next_bar(state, pending, bar, bar_index, config):
    beginning = start_time(bar)
    if bar_index != pending.bar_index + 1 or beginning < pending.end_time:
        raise ValueError("An intraday signal must execute at the next bar open.")
    signal = pending.signal
    if signal.phase != state.phase:
        raise ValueError("The pending intraday signal has a stale phase.")
    day = bar["time"][:10]
    price = fill_price(bar["open"], signal.side, config)
    block = validate_order(state, signal, price, day, config)
    if block:
        return None, block
    # Shared accounting uses calendar dates, never intraday timestamps, for broker settlement.
    trade = _record(state, signal, price, round_quantity(config.amount, price), day, config)
    trade.update(signal_date=signal.date[:10], signal_time=pending.end_time,
                 fill_time=beginning, signal_bar=pending.bar_index, fill_bar=bar_index)
    return trade, None


def simulate_intraday(bars, config=Config(), entry_date=None):
    days = daily_bars(bars)
    first = {}
    for index, bar in enumerate(bars):
        first.setdefault(bar["time"][:10], index)
    if entry_date is None:
        selection, entry = select_entry(days)
        entry_date = days[entry]["date"]
        signal_day = days[selection]["date"]
    else:
        entry = next((index for index, day in enumerate(days) if day["date"] == entry_date), None)
        if entry is None or entry == 0:
            raise ValueError("An explicit entry requires a prior observed trading day.")
        signal_day = days[entry - 1]["date"]
    initial_index = first[entry_date]
    initial = bars[initial_index]
    state, grid = initialize(entry_date, initial["open"], config, signal_date=signal_day)
    initial_qty = state.qty
    state.trades[0].update(signal_time=bars[initial_index - 1]["time"],
                           fill_time=start_time(initial), signal_bar=initial_index - 1, fill_bar=initial_index)
    same_qty, same_cash = hold_quantity(config, initial["open"], initial_qty)
    full_qty, full_cash = hold_quantity(config, initial["open"])
    fees, realized = state.fees, ZERO
    marks, events = [], []
    pending = None
    for index in range(initial_index, len(bars)):
        bar = bars[index]
        if pending is not None:
            trade, block = execute_next_bar(state, pending, bar, index, config)
            events.append({"time": start_time(bar), "stage": "OPEN", "signal_time": pending.end_time,
                           "direction": pending.signal.direction, "grid": pending.signal.grid,
                           "side": pending.signal.side, "reason": pending.signal.reason,
                           "status": block or "FILLED", "signal_bar": pending.bar_index, "fill_bar": index})
            if trade:
                fees += trade["fee"]
                realized += trade["gross_profit"]
            pending = None
        crossing = grid.observe(bar["close"])
        if crossing:
            direction, target = crossing
            signal, block = decide(state, bar["time"], direction, target, bar["close"], config)
            status = block or ("QUEUED" if index + 1 < len(bars) else "END_OF_SAMPLE")
            events.append({"time": bar["time"], "stage": "CLOSE", "signal_time": bar["time"],
                           "direction": direction, "grid": target, "side": signal.side,
                           "reason": signal.reason, "status": status, "signal_bar": index, "fill_bar": ""})
            if status == "QUEUED":
                pending = Pending(signal, index, bar["time"])
        cost = state.cost
        market_value = state.qty * bar["close"]
        equity = state.cash + market_value
        if equity != config.capital + realized + market_value - cost - fees:
            raise AssertionError("Intraday equity does not reconcile with cash, inventory, and fees.")
        marks.append({"date": bar["time"][:10], "time": bar["time"], "close": bar["close"],
                      "cash": state.cash, "qty": state.qty, "position_cost": cost,
                      "market_value": market_value, "equity": equity, "phase": state.phase.value,
                      "fees_paid": fees, "realized_gross": realized, "unrealized_gross": market_value - cost,
                      "same_initial_hold": same_cash + same_qty * bar["close"],
                      "full_buy_hold": full_cash + full_qty * bar["close"],
                      "grid": grid.visited, "occupied_grids": len(state.lots)})
    daily = []
    for mark in marks:
        if daily and daily[-1]["date"] == mark["date"]:
            daily[-1] = mark
        else:
            daily.append(mark)
    net_realized = sum((trade["realized_net"] for trade in state.trades), ZERO)
    held_buy_fees = sum((lot.buy_fee * lot.qty / lot.original_qty for lot in state.lots), ZERO)
    unrealized = marks[-1]["market_value"] - state.cost - held_buy_fees
    if abs(net_realized + unrealized - (marks[-1]["equity"] - config.capital)) > D("0.000001"):
        raise AssertionError("Terminal intraday net profits do not reconcile.")
    if len(state.trades) != len({trade["fill_bar"] for trade in state.trades}):
        raise AssertionError("An intraday bar contains more than one fill.")
    summary = {
        "config": asdict(config), "entry_date": entry_date, "signal_day": signal_day,
        "end_date": marks[-1]["date"], "active_days": len(daily), "active_bars": len(marks),
        "bar_minutes": initial["minutes"], "initial_price": state.trades[0]["price"],
        "initial_qty": initial_qty, "equity": marks[-1]["equity"],
        "net_profit": marks[-1]["equity"] - config.capital,
        "return_pct": (marks[-1]["equity"] / config.capital - 1) * 100,
        "max_drawdown_pct": max_drawdown([mark["equity"] for mark in marks], config.capital),
        "daily_drawdown_pct": max_drawdown([mark["equity"] for mark in daily], config.capital),
        "same_initial_hold_return_pct": (marks[-1]["same_initial_hold"] / config.capital - 1) * 100,
        "full_buy_hold_return_pct": (marks[-1]["full_buy_hold"] / config.capital - 1) * 100,
        "fees": fees, "realized_net": net_realized, "unrealized_net": unrealized,
        "trade_count": len(state.trades), "buy_count": sum(t["side"] == "BUY" for t in state.trades),
        "sell_count": sum(t["side"] == "SELL" for t in state.trades),
        "end_cash": state.cash, "end_qty": state.qty, "end_cost": state.cost,
        "min_cash": min(t["cash_after"] for t in state.trades),
        "min_cost": min(t["cost_after"] for t in state.trades),
        "max_cost": max(t["cost_after"] for t in state.trades),
        "transitions": sum(t["phase_before"] != t["phase_after"] for t in state.trades),
        "blocked_closes": dict(Counter(e["status"] for e in events if e["stage"] == "CLOSE" and
                                      e["status"] not in ("QUEUED", "END_OF_SAMPLE"))),
        "cancelled_opens": dict(Counter(e["status"] for e in events if e["stage"] == "OPEN" and e["status"] != "FILLED")),
        "final_lots": [asdict(lot) for lot in state.lots],
    }
    return {"summary": summary, "trades": state.trades, "events": events, "marks": marks, "daily": daily}
