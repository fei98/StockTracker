"""Stock accounting with corporate actions, independent settlement, and dividend-tax lots."""

import calendar
from dataclasses import dataclass, field
from datetime import date
from decimal import ROUND_HALF_UP

from model import (Config, D, ZERO, Grid, Phase, State, eligible_lots, round_quantity,
                   update_phase, validate_order)


@dataclass(frozen=True)
class StockConfig(Config):
    tick: object = D("0.01")
    stamp_rate: object = D("0.0005")
    transfer_rate: object = D("0.00001")

    def __post_init__(self):
        super().__post_init__()
        if not all(value.is_finite() and value >= 0 for value in (self.tick, self.stamp_rate, self.transfer_rate)) or self.tick <= 0:
            raise ValueError("Invalid stock fee rates or price tick.")

    def fee_parts(self, amount, side="BUY"):
        if side not in ("BUY", "SELL"):
            raise ValueError("Unknown stock trade side.")
        cents = lambda value: value.quantize(D("0.01"), rounding=ROUND_HALF_UP)
        return {"commission": super().fee(amount), "transfer_fee": cents(amount * self.transfer_rate),
                "stamp_tax": cents(amount * self.stamp_rate) if side == "SELL" else ZERO}

    def fee(self, amount, side="BUY"):
        return sum(self.fee_parts(amount, side).values(), ZERO)


@dataclass
class StockLot:
    lot_id: int
    grid: int
    original_qty: int
    qty: int
    buy_date: str
    cost: object
    remaining_buy_fee: object

    @property
    def price(self):
        return self.cost / self.qty


@dataclass
class SettlementLot:
    buy_date: str
    qty: int
    available_date: str | None = None

    def available(self, day):
        return self.buy_date < day or (self.available_date is not None and self.available_date <= day)


@dataclass
class TaxLot:
    acquired: str
    qty: int
    rights: list = field(default_factory=list)


@dataclass
class StockState(State):
    tax_lots: list = field(default_factory=list)
    cashflows: list = field(default_factory=list)
    dividends: object = ZERO
    dividend_tax: object = ZERO

    @property
    def cost(self):
        return sum((lot.cost for lot in self.lots), ZERO)

    def settled_qty(self, day):
        return sum(lot.qty for lot in self.broker_lots if lot.available(day))


@dataclass
class StockGrid(Grid):
    tick: object = D("0.01")
    scale: object = D("1")
    offset: object = ZERO

    def level(self, index):
        value = self.scale * self.anchor * self.ratio ** index + self.offset
        return value.quantize(self.tick, rounding=ROUND_HALF_UP)

    def adjust(self, cash_reference, bonus_reference):
        divisor = 1 + bonus_reference
        self.scale /= divisor
        self.offset = (self.offset - cash_reference) / divisor


@dataclass(frozen=True)
class CorporateAction:
    ex_date: str
    cash_per_share: object
    bonus_per_share: object
    cash_reference: object
    bonus_reference: object
    source: str


ACTIONS = (
    CorporateAction("2025-05-30", D("0.25"), ZERO, D("0.2487976"), ZERO,
                    "https://epaper.stcn.com/pic/202505/23/996a1d12f9f266da34b4c8d61efd3e25.pdf"),
    CorporateAction("2026-05-25", D("0.25"), D("0.3"), D("0.2487916"), D("0.2985500"),
                    "https://static.cninfo.com.cn/finalpage/2026-05-15/1225307722.PDF"),
)


def execution_price(open_price, side, config):
    value = open_price + config.tick * config.slippage_ticks * (1 if side == "BUY" else -1)
    value = value.quantize(config.tick, rounding=ROUND_HALF_UP)
    if value <= 0 or not value.is_finite():
        raise ValueError("Invalid stock execution price.")
    return value


def assert_stock(state, settled_tax=False):
    if state.cash < 0 or any(lot.qty <= 0 or lot.cost <= 0 or lot.remaining_buy_fee < 0 for lot in state.lots):
        raise AssertionError("Invalid stock cash or inventory.")
    if len({lot.grid for lot in state.lots}) != len(state.lots):
        raise AssertionError("An occupied stock grid was reused.")
    if state.qty != sum(lot.qty for lot in state.broker_lots):
        raise AssertionError("Stock settlement and accounting quantities disagree.")
    if settled_tax and state.qty != sum(lot.qty for lot in state.tax_lots):
        raise AssertionError("Daily tax settlement quantities disagree.")


def record_trade(state, signal, price, qty, day, config, initial=False):
    before = state.phase
    parts = config.fee_parts(price * qty, signal.side)
    fee = sum(parts.values(), ZERO)
    gross = allocated_fee = ZERO
    matches = []
    if signal.side == "BUY":
        if state.occupied(signal.grid):
            raise AssertionError("Cannot buy an occupied stock grid.")
        if price * qty + fee > state.cash:
            raise ValueError("Stock buy exceeds available cash.")
        state.cash -= price * qty + fee
        state.lots.append(StockLot(state.next_id, signal.grid, qty, qty, day, price * qty, fee))
        state.broker_lots.append(SettlementLot(day, qty))
        state.next_id += 1
    else:
        remaining = qty
        if state.settled_qty(day) < qty or sum(lot.qty for lot in eligible_lots(state, signal, price)) < qty:
            raise ValueError("Stock sale lacks eligible or settled inventory.")
        for lot in eligible_lots(state, signal, price):
            take = min(lot.qty, remaining)
            removed_cost = lot.cost * take / lot.qty
            buy_fee = lot.remaining_buy_fee * take / lot.qty
            matches.append({"lot_id": lot.lot_id, "grid": lot.grid, "qty": take,
                            "buy_price": str(lot.price), "removed_cost": str(removed_cost)})
            gross += price * take - removed_cost
            allocated_fee += buy_fee
            lot.cost -= removed_cost
            lot.remaining_buy_fee -= buy_fee
            lot.qty -= take
            remaining -= take
            if remaining == 0:
                break
        state.lots = [lot for lot in state.lots if lot.qty]
        remaining = qty
        for lot in state.broker_lots:
            if not lot.available(day):
                continue
            take = min(lot.qty, remaining)
            lot.qty -= take
            remaining -= take
            if remaining == 0:
                break
        state.broker_lots = [lot for lot in state.broker_lots if lot.qty]
        state.cash += price * qty - fee
    if not initial:
        update_phase(state, signal.side, config)
    trade = {"date": day, "signal_date": signal.date[:10], "side": signal.side,
             "direction": signal.direction, "reason": signal.reason, "grid": signal.grid,
             "signal_quote": signal.quote, "price": price, "qty": qty, "amount": price * qty,
             "fee": fee, **parts, "gross_profit": gross, "allocated_buy_fee": allocated_fee,
             "realized_net": gross - allocated_fee - fee if signal.side == "SELL" else ZERO,
             "phase_before": before.value, "phase_after": state.phase.value,
             "cash_after": state.cash, "cost_after": state.cost, "qty_after": state.qty,
             "matched_lots": matches}
    state.trades.append(trade)
    assert_stock(state)
    return trade


def exact_bonus(qty, ratio):
    value = qty * ratio
    if value != int(value):
        raise ValueError("Fractional bonus allocation needs an explicit account-level rule.")
    return int(value)


def apply_action(state, grid, action):
    assert_stock(state, settled_tax=True)
    before_qty, before_cost, before_cash = state.qty, state.cost, state.cash
    dividend = action.cash_per_share * before_qty
    for lot in state.tax_lots:
        lot.rights.append({"ex_date": action.ex_date, "gross": action.cash_per_share * lot.qty})
    bonus = exact_bonus(before_qty, action.bonus_per_share)
    for lot in state.lots:
        lot.qty += exact_bonus(lot.qty, action.bonus_per_share)
        lot.original_qty += exact_bonus(lot.original_qty, action.bonus_per_share)
    if bonus:
        # Bonus shares are available on their announced listing date, unlike a same-day buy.
        state.broker_lots.append(SettlementLot(action.ex_date, bonus, available_date=action.ex_date))
        # New shares receive no historical cash-dividend rights. This tax acquisition date is disclosed.
        state.tax_lots.append(TaxLot(action.ex_date, bonus))
    state.cash += dividend
    state.dividends += dividend
    grid.adjust(action.cash_reference, action.bonus_reference)
    flow = {"date": action.ex_date, "type": "CORPORATE_ACTION", "cash_before": before_cash,
            "cash_after": state.cash, "qty_before": before_qty, "qty_after": state.qty,
            "cost_before": before_cost, "cost_after": state.cost, "dividend_gross": dividend,
            "bonus_qty": bonus, "cash_reference": action.cash_reference,
            "bonus_reference": action.bonus_reference, "grid_scale": grid.scale, "grid_offset": grid.offset}
    state.cashflows.append(flow)
    if state.cost != before_cost:
        raise AssertionError("Bonus shares must preserve historical position cost.")
    assert_stock(state, settled_tax=True)
    return flow


def anniversary(point, months=0, years=0):
    value = date.fromisoformat(point)
    serial = value.year * 12 + value.month - 1 + months + years * 12
    year, month = divmod(serial, 12)
    return date(year, month + 1, min(value.day, calendar.monthrange(year, month + 1)[1]))


def dividend_rate(acquired, sale_day):
    sold = date.fromisoformat(sale_day)
    if sold <= anniversary(acquired, months=1):
        return D("0.20")
    if sold <= anniversary(acquired, years=1):
        return D("0.10")
    return ZERO


def tax_for_net_sale(tax_lots, qty, day, consume=True):
    remaining, total = qty, ZERO
    details = []
    for lot in tax_lots:
        if remaining <= 0:
            break
        take = min(lot.qty, remaining)
        if take <= 0:
            continue
        rate = dividend_rate(lot.acquired, day)
        taxable = ZERO
        for right in lot.rights:
            consumed = right["gross"] * take / lot.qty
            taxable += consumed
            if consume:
                right["gross"] -= consumed
        total += taxable * rate
        details.append({"acquired": lot.acquired, "qty": take, "dividend_gross": str(taxable), "rate": str(rate)})
        if consume:
            lot.qty -= take
        remaining -= take
    if remaining:
        raise AssertionError("Dividend-tax FIFO lacks enough shares.")
    return total.quantize(D("0.01"), rounding=ROUND_HALF_UP), details


def settle_day(state, opening_qty, day):
    change = state.qty - opening_qty
    tax, details = ZERO, []
    if change > 0:
        state.tax_lots.append(TaxLot(day, change))
    elif change < 0:
        tax, details = tax_for_net_sale(state.tax_lots, -change, day)
        state.tax_lots = [lot for lot in state.tax_lots if lot.qty]
    state.cash -= tax
    state.dividend_tax += tax
    if tax or change:
        state.cashflows.append({"date": day, "type": "DIVIDEND_TAX_SETTLEMENT", "net_trade_qty": change,
                                "dividend_tax": tax, "cash_after": state.cash, "fifo": details})
    assert_stock(state, settled_tax=True)
    return tax
