"""Summary-only stock simulator using the same accounting and execution primitives."""

from collections import Counter
from dataclasses import dataclass, field

from backtest import select_entry
from intraday import Pending, daily_bars
from model import D, ZERO, Phase, Signal, decide, round_quantity
from stock_intraday import execute_stock_bar
from stock_model import (ACTIONS, StockGrid, StockState, TaxLot, apply_action, assert_stock,
                         execution_price, record_trade, settle_day, tax_for_net_sale)


class RunningTrades(list):
    def __init__(self):
        super().__init__()
        self.fees = self.realized = self.net_realized = ZERO

    def append(self, trade):
        super().append(trade)
        self.fees += trade["fee"]
        self.realized += trade["gross_profit"]
        self.net_realized += trade["realized_net"]


class BudgetViolation(ValueError):
    pass


@dataclass
class FastState(StockState):
    trades: list = field(default_factory=RunningTrades)

    @property
    def fees(self):
        return self.trades.fees

    @property
    def realized_gross(self):
        return self.trades.realized


@dataclass
class Context:
    bars: list
    initial_index: int
    entry_date: str
    active_days: int


def context(bars, entry_date=None):
    days = daily_bars(bars)
    if entry_date is None:
        _, entry = select_entry(days)
        entry_date = days[entry]["date"]
    else:
        entry = next(i for i, row in enumerate(days) if row["date"] == entry_date)
        if entry == 0:
            raise ValueError("Explicit entry requires a prior observed day.")
    index = next(i for i, bar in enumerate(bars) if bar["time"][:10] == entry_date)
    return Context(bars, index, entry_date, len(days) - entry)


def simulate_fast(market, config, actions=ACTIONS):
    bars, index0, entry_date = market.bars, market.initial_index, market.entry_date
    first = bars[index0]
    state = FastState(config.capital)
    price = execution_price(first["open"], "BUY", config)
    qty = round_quantity(config.initial_amount, price)
    signal = Signal(bars[index0 - 1]["time"], 0, "INITIAL", "BUY", "INITIAL_POSITION", price, Phase.NORMAL)
    record_trade(state, signal, price, qty, entry_date, config, initial=True)
    state.tax_lots.append(TaxLot(entry_date, qty))
    grid = StockGrid(price, config.ratio, tick=config.tick)
    action_map = {action.ex_date: action for action in actions}
    pending = None
    current_day, opening_qty = None, 0
    peak, worst = config.capital, ZERO
    daily_peak, daily_worst = config.capital, ZERO
    minimum_cash, maximum_cost, minimum_cost = state.cash, state.cost, state.cost
    blocked, cancelled = Counter(), Counter()
    last_equity = ZERO
    for index in range(index0, len(bars)):
        bar = bars[index]
        day = bar["time"][:10]
        if day != current_day:
            if day in action_map and day > entry_date:
                apply_action(state, grid, action_map[day])
                if pending:
                    cancelled["CORPORATE_ACTION_CANCEL"] += 1
                    pending = None
            current_day, opening_qty = day, state.qty
        if pending:
            trade, block = execute_stock_bar(state, pending, bar, index, config)
            if block:
                cancelled[block] += 1
            pending = None
        end_of_day = index + 1 == len(bars) or bars[index + 1]["time"][:10] != day
        if end_of_day:
            try:
                settle_day(state, opening_qty, day)
            except AssertionError:
                if state.cash < 0:
                    raise BudgetViolation("Cash cannot cover dividend tax without additional capital.")
                raise
        crossing = grid.observe(bar["close"])
        if crossing:
            direction, target = crossing
            signal, block = decide(state, bar["time"], direction, target, bar["close"], config)
            if block:
                blocked[block] += 1
            elif index + 1 < len(bars):
                pending = Pending(signal, index, bar["time"])
        cost, held = state.cost, state.qty
        market_value = held * bar["close"]
        last_equity = state.cash + market_value
        expected = config.capital + state.realized_gross + market_value - cost - state.fees + state.dividends - state.dividend_tax
        if abs(last_equity - expected) > D("0.000001"):
            raise AssertionError("Fast simulation cash reconciliation failed.")
        peak = max(peak, last_equity)
        worst = max(worst, (peak - last_equity) / peak)
        if end_of_day:
            daily_peak = max(daily_peak, last_equity)
            daily_worst = max(daily_worst, (daily_peak - last_equity) / daily_peak)
        minimum_cash = min(minimum_cash, state.cash)
        maximum_cost, minimum_cost = max(maximum_cost, cost), min(minimum_cost, cost)
    assert_stock(state, settled_tax=True)
    unrealized_net = state.qty * bars[-1]["close"] - state.cost - sum((lot.remaining_buy_fee for lot in state.lots), ZERO)
    net = last_equity - config.capital
    if abs(net - state.trades.net_realized - unrealized_net - state.dividends + state.dividend_tax) > D("0.000001"):
        raise AssertionError("Fast simulation terminal profit reconciliation failed.")
    return {"entry_date": entry_date, "end_date": bars[-1]["time"][:10], "active_days": market.active_days,
            "active_bars": len(bars) - index0, "equity": last_equity, "net_profit": net,
            "return_pct": (last_equity / config.capital - 1) * 100, "max_drawdown_pct": worst * 100,
            "daily_drawdown_pct": daily_worst * 100, "trade_count": len(state.trades), "fees": state.fees,
            "dividends": state.dividends, "dividend_tax": state.dividend_tax,
            "potential_dividend_tax_if_sold": tax_for_net_sale(state.tax_lots, state.qty, bars[-1]["time"][:10], consume=False)[0],
            "end_cash": state.cash, "end_qty": state.qty, "end_cost": state.cost,
            "min_cash": minimum_cash, "max_cost": maximum_cost, "min_cost": minimum_cost,
            "net_realized": state.trades.net_realized, "net_unrealized": unrealized_net,
            "cancelled_opens": dict(cancelled), "blocked_closes": dict(blocked)}
