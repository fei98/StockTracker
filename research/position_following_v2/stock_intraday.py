"""Thirty-minute stock strategy with action-aware grids and daily net dividend tax."""

from collections import Counter
from dataclasses import asdict

from backtest import max_drawdown, select_entry
from intraday import Pending, daily_bars, start_time
from model import D, ZERO, Phase, Signal, decide, round_quantity, validate_order
from stock_model import (ACTIONS, StockConfig, StockGrid, StockState, TaxLot, apply_action,
                         assert_stock, execution_price, record_trade, settle_day, tax_for_net_sale)


def initial_state(day, opening, config, amount, signal_time, fill_index, bars, all_in=False):
    state = StockState(config.capital)
    price = execution_price(opening, "BUY", config)
    qty = (int(config.capital / price / 100) * 100 if all_in else round_quantity(amount, price))
    while all_in and price * qty + config.fee(price * qty) > state.cash:
        qty -= 100
    if qty <= 0:
        raise ValueError("Initial stock position cannot afford one round lot.")
    signal = Signal(signal_time, 0, "INITIAL", "BUY", "INITIAL_POSITION", price, Phase.NORMAL)
    trade = record_trade(state, signal, price, qty, day, config, initial=True)
    trade.update(signal_time=signal_time, fill_time=start_time(bars[fill_index]),
                 signal_bar=fill_index - 1, fill_bar=fill_index)
    state.tax_lots.append(TaxLot(day, qty))
    assert_stock(state, settled_tax=True)
    return state, StockGrid(price, config.ratio, tick=config.tick)


def execute_stock_bar(state, pending, bar, index, config):
    beginning = start_time(bar)
    if index != pending.bar_index + 1 or beginning < pending.end_time:
        raise ValueError("A stock order must use the next bar open.")
    signal = pending.signal
    if signal.phase != state.phase:
        raise ValueError("Stock pending phase is stale.")
    day = bar["time"][:10]
    price = execution_price(bar["open"], signal.side, config)
    block = validate_order(state, signal, price, day, config)
    if block:
        return None, block
    trade = record_trade(state, signal, price, round_quantity(config.amount, price), day, config)
    trade.update(signal_time=pending.end_time, fill_time=beginning,
                 signal_bar=pending.bar_index, fill_bar=index)
    return trade, None


def simulate_stock(bars, config=StockConfig(), actions=ACTIONS, entry_date=None):
    days = daily_bars(bars)
    if entry_date is None:
        selection, entry = select_entry(days)
        entry_date = days[entry]["date"]
    else:
        entry = next(i for i, day in enumerate(days) if day["date"] == entry_date)
        if entry == 0:
            raise ValueError("An explicit stock entry needs an earlier observed day.")
    index0 = next(i for i, bar in enumerate(bars) if bar["time"][:10] == entry_date)
    initial, signal_time = bars[index0], bars[index0 - 1]["time"]
    state, grid = initial_state(entry_date, initial["open"], config, config.initial_amount,
                                signal_time, index0, bars)
    same, same_grid = initial_state(entry_date, initial["open"], config, config.initial_amount,
                                    signal_time, index0, bars)
    full, full_grid = initial_state(entry_date, initial["open"], config, config.capital,
                                    signal_time, index0, bars, all_in=True)
    action_map = {action.ex_date: action for action in actions}
    marks, events = [], []
    pending = None
    current_day = None
    opening_qty = 0
    for index in range(index0, len(bars)):
        bar = bars[index]
        day = bar["time"][:10]
        if day != current_day:
            if day in action_map and day > entry_date:
                action = action_map[day]
                for portfolio, reference in ((state, grid), (same, same_grid), (full, full_grid)):
                    apply_action(portfolio, reference, action)
                if pending:
                    events.append({"time": start_time(bar), "stage": "OPEN", "reason": pending.signal.reason,
                                   "status": "CORPORATE_ACTION_CANCEL", "grid": pending.signal.grid,
                                   "signal_bar": pending.bar_index, "fill_bar": index})
                    pending = None
            current_day, opening_qty = day, state.qty
        if pending:
            trade, block = execute_stock_bar(state, pending, bar, index, config)
            events.append({"time": start_time(bar), "stage": "OPEN", "reason": pending.signal.reason,
                           "status": block or "FILLED", "grid": pending.signal.grid,
                           "signal_bar": pending.bar_index, "fill_bar": index})
            pending = None
        end_of_day = index + 1 == len(bars) or bars[index + 1]["time"][:10] != day
        if end_of_day:
            # Settle net share changes at the close; this is a conservative cash-timing approximation.
            settle_day(state, opening_qty, day)
        crossing = grid.observe(bar["close"])
        if crossing:
            direction, target = crossing
            signal, block = decide(state, bar["time"], direction, target, bar["close"], config)
            status = block or ("QUEUED" if index + 1 < len(bars) else "END_OF_SAMPLE")
            events.append({"time": bar["time"], "stage": "CLOSE", "reason": signal.reason,
                           "status": status, "grid": target, "signal_bar": index, "fill_bar": ""})
            if status == "QUEUED":
                pending = Pending(signal, index, bar["time"])
        market_value = state.qty * bar["close"]
        equity = state.cash + market_value
        expected = (config.capital + state.realized_gross + market_value - state.cost - state.fees +
                    state.dividends - state.dividend_tax)
        if abs(equity - expected) > D("0.000001"):
            raise AssertionError("Stock equity fails cash-flow reconciliation.")
        marks.append({"date": day, "time": bar["time"], "close": bar["close"], "cash": state.cash,
                      "qty": state.qty, "cost": state.cost, "market_value": market_value, "equity": equity,
                      "phase": state.phase.value, "fees": state.fees, "dividends": state.dividends,
                      "dividend_tax": state.dividend_tax, "realized_gross": state.realized_gross,
                      "unrealized_gross": market_value - state.cost,
                      "same_initial_hold": same.cash + same.qty * bar["close"],
                      "full_buy_hold": full.cash + full.qty * bar["close"],
                      "grid_scale": grid.scale, "grid_offset": grid.offset,
                      "grid": grid.visited, "occupied_grids": len(state.lots)})
    daily = []
    for row in marks:
        if daily and daily[-1]["date"] == row["date"]:
            daily[-1] = row
        else:
            daily.append(row)
    net_realized = sum((trade["realized_net"] for trade in state.trades), ZERO)
    net_unrealized = marks[-1]["market_value"] - state.cost - sum((lot.remaining_buy_fee for lot in state.lots), ZERO)
    net = marks[-1]["equity"] - config.capital
    if abs(net - (net_realized + net_unrealized + state.dividends - state.dividend_tax)) > D("0.000001"):
        raise AssertionError("Stock terminal net profits disagree.")
    if len(state.trades) != len({trade["fill_bar"] for trade in state.trades}):
        raise AssertionError("More than one stock fill used a single bar.")
    potential_tax, _ = tax_for_net_sale(state.tax_lots, state.qty, marks[-1]["date"], consume=False)
    summary = {"config": asdict(config), "entry_date": entry_date, "end_date": marks[-1]["date"],
               "active_days": len(daily), "active_bars": len(marks), "initial_price": state.trades[0]["price"],
               "initial_qty": state.trades[0]["qty"], "equity": marks[-1]["equity"], "net_profit": net,
               "return_pct": (marks[-1]["equity"] / config.capital - 1) * 100,
               "max_drawdown_pct": max_drawdown([r["equity"] for r in marks], config.capital),
               "trade_count": len(state.trades), "buy_count": sum(t["side"] == "BUY" for t in state.trades),
               "sell_count": sum(t["side"] == "SELL" for t in state.trades), "fees": state.fees,
               "commission": sum((t["commission"] for t in state.trades), ZERO),
               "transfer_fee": sum((t["transfer_fee"] for t in state.trades), ZERO),
               "stamp_tax": sum((t["stamp_tax"] for t in state.trades), ZERO),
               "dividends": state.dividends, "dividend_tax": state.dividend_tax,
               "potential_dividend_tax_if_sold": potential_tax,
               "net_realized": net_realized, "net_unrealized": net_unrealized,
               "end_cash": state.cash, "end_qty": state.qty, "end_cost": state.cost,
               "max_cost": max(r["cost"] for r in marks), "min_cost": min(r["cost"] for r in marks),
               "min_cash": min(r["cash"] for r in marks),
               "transitions": sum(t["phase_before"] != t["phase_after"] for t in state.trades),
               "cancelled_opens": dict(Counter(e["status"] for e in events if e["stage"] == "OPEN" and e["status"] != "FILLED")),
               "blocked_closes": dict(Counter(e["status"] for e in events if e["stage"] == "CLOSE" and e["status"] not in ("QUEUED", "END_OF_SAMPLE"))),
               "same_initial_hold_equity": marks[-1]["same_initial_hold"],
               "same_initial_hold_return_pct": (marks[-1]["same_initial_hold"] / config.capital - 1) * 100,
               "same_initial_hold_drawdown_pct": max_drawdown([r["same_initial_hold"] for r in marks], config.capital),
               "full_buy_hold_equity": marks[-1]["full_buy_hold"],
               "full_buy_hold_return_pct": (marks[-1]["full_buy_hold"] / config.capital - 1) * 100,
               "full_buy_hold_drawdown_pct": max_drawdown([r["full_buy_hold"] for r in marks], config.capital),
               "benchmark_dividends": {"same_initial": same.dividends, "full": full.dividends},
               "final_lots": [dict(asdict(lot), price=lot.price) for lot in state.lots]}
    return {"summary": summary, "trades": state.trades, "events": events,
            "cashflows": state.cashflows, "marks": marks, "daily": daily}
