"""Pure position-following state machine for an independent ETF experiment."""

from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum


D = Decimal
ZERO = D("0")
TICK = D("0.001")


class Phase(str, Enum):
    NORMAL = "NORMAL"
    DOWN_REDUCTION = "DOWN_REDUCTION"
    UP_FOLLOW = "UP_FOLLOW"


@dataclass(frozen=True)
class Config:
    lower: Decimal = D("10000")
    upper: Decimal = D("30000")
    amount: Decimal = D("4000")
    initial_amount: Decimal = D("10000")
    capital: Decimal = D("50000")
    ratio: Decimal = D("1.03")
    commission_rate: Decimal = D("0.00025")
    minimum_commission: Decimal = D("5")
    slippage_ticks: int = 0

    def __post_init__(self):
        values = [self.lower, self.upper, self.amount, self.initial_amount,
                  self.capital, self.ratio, self.commission_rate, self.minimum_commission]
        if not all(x.is_finite() for x in values):
            raise ValueError("Configuration values must be finite.")
        if not (ZERO <= self.lower < self.upper and self.amount > 0 and
                self.initial_amount > 0 and self.capital > 0 and self.ratio > 1 and
                self.commission_rate >= 0 and self.minimum_commission >= 0 and
                self.slippage_ticks >= 0):
            raise ValueError("Invalid configuration bounds.")

    @property
    def middle(self):
        return (self.lower + self.upper) / 2

    def fee(self, amount):
        return max(self.minimum_commission, amount * self.commission_rate).quantize(
            D("0.01"), rounding=ROUND_HALF_UP)


def round_quantity(amount, price):
    if not price.is_finite() or price <= 0:
        raise ValueError("Price must be finite and positive.")
    return max(1, int((amount / price / 100).quantize(D("1"), rounding=ROUND_HALF_UP))) * 100


@dataclass
class Lot:
    lot_id: int
    grid: int
    price: Decimal
    original_qty: int
    qty: int
    buy_date: str
    buy_fee: Decimal = ZERO


@dataclass
class BrokerLot:
    buy_date: str
    qty: int


@dataclass(frozen=True)
class Signal:
    date: str
    grid: int
    direction: str
    side: str
    reason: str
    quote: Decimal
    phase: Phase


@dataclass
class State:
    cash: Decimal
    phase: Phase = Phase.NORMAL
    lots: list[Lot] = field(default_factory=list)
    broker_lots: list[BrokerLot] = field(default_factory=list)
    trades: list[dict] = field(default_factory=list)
    next_id: int = 1

    @property
    def cost(self):
        return sum((lot.price * lot.qty for lot in self.lots), ZERO)

    @property
    def qty(self):
        return sum(lot.qty for lot in self.lots)

    @property
    def fees(self):
        return sum((trade["fee"] for trade in self.trades), ZERO)

    @property
    def realized_gross(self):
        return sum((trade["gross_profit"] for trade in self.trades), ZERO)

    def occupied(self, grid):
        return any(lot.grid == grid and lot.qty > 0 for lot in self.lots)

    def settled_qty(self, date):
        return sum(lot.qty for lot in self.broker_lots if lot.buy_date < date)


@dataclass
class Grid:
    anchor: Decimal
    ratio: Decimal = D("1.03")
    visited: int = 0

    def __post_init__(self):
        if not self.anchor.is_finite() or self.anchor <= 0 or self.ratio <= 1:
            raise ValueError("Invalid grid anchor or ratio.")

    def level(self, index):
        return (self.anchor * self.ratio ** index).quantize(TICK, rounding=ROUND_HALF_UP)

    def observe(self, price):
        """Visit the farthest crossed level; never invent several fills from one daily bar."""
        if not price.is_finite() or price <= 0:
            raise ValueError("Quote must be finite and positive.")
        direction = None
        if price >= self.level(self.visited + 1):
            direction = "UP"
            for _ in range(1000):
                if price < self.level(self.visited + 1):
                    break
                self.visited += 1
            else:
                raise ValueError("Grid observation exceeded the safety bound.")
        elif price <= self.level(self.visited - 1):
            direction = "DOWN"
            for _ in range(1000):
                if price > self.level(self.visited - 1):
                    break
                self.visited -= 1
            else:
                raise ValueError("Grid observation exceeded the safety bound.")
        return (direction, self.visited) if direction else None


def fill_price(open_price, side, config):
    adjustment = TICK * config.slippage_ticks * (1 if side == "BUY" else -1)
    result = (open_price + adjustment).quantize(TICK, rounding=ROUND_HALF_UP)
    if not result.is_finite() or result <= 0:
        raise ValueError("Invalid execution price.")
    return result


def eligible_lots(state, signal, price):
    if signal.side != "SELL":
        return []
    if signal.reason == "DOWN_REDUCTION_SELL":
        return sorted(state.lots, key=lambda lot: (-lot.price, lot.lot_id))
    return sorted((lot for lot in state.lots if lot.price < price),
                  key=lambda lot: (lot.price, lot.lot_id))


def validate_order(state, signal, price, date, config, enforce_t1=True):
    """Reject an unfillable whole order instead of silently resizing it."""
    qty = round_quantity(config.amount, price)
    if signal.side == "BUY":
        if state.occupied(signal.grid):
            return "OCCUPIED_GRID"
        if signal.reason == "UP_FOLLOW_BUY" and state.lots:
            if price <= max(lot.price for lot in state.lots):
                return "NO_BREAKOUT"
        if price * qty + config.fee(price * qty) > state.cash:
            return "INSUFFICIENT_CASH"
    else:
        available = sum(lot.qty for lot in eligible_lots(state, signal, price))
        if available < qty:
            return ("INSUFFICIENT_HOLDINGS" if signal.reason == "DOWN_REDUCTION_SELL"
                    else "INSUFFICIENT_PROFITABLE_SHARES")
        if enforce_t1 and state.settled_qty(date) < qty:
            return "INSUFFICIENT_SETTLED_SHARES"
    return None


def decide(state, date, direction, grid, quote, config):
    if direction not in ("UP", "DOWN"):
        raise ValueError("Unknown grid direction.")
    if direction == "DOWN":
        side = "SELL" if state.phase == Phase.DOWN_REDUCTION else "BUY"
        reason = ("DOWN_REDUCTION_SELL" if side == "SELL" else
                  "PULLBACK_BUY" if state.phase == Phase.UP_FOLLOW else "NORMAL_DOWN_BUY")
    elif state.phase == Phase.UP_FOLLOW:
        side, reason = "BUY", "UP_FOLLOW_BUY"
    else:
        side = "SELL"
        reason = "REBOUND_PROFIT_SELL" if state.phase == Phase.DOWN_REDUCTION else "NORMAL_UP_SELL"
    signal = Signal(date, grid, direction, side, reason, quote, state.phase)
    # A close signal executes on a later trading date, so T+1 is checked at execution.
    block = validate_order(state, signal, quote, date, config, enforce_t1=False)
    return signal, block


def update_phase(state, side, config):
    if state.phase == Phase.DOWN_REDUCTION:
        if state.cost <= config.middle:
            state.phase = Phase.NORMAL
    elif state.phase == Phase.UP_FOLLOW:
        if state.cost >= config.middle:
            state.phase = Phase.NORMAL
    elif side == "BUY" and state.cost >= config.upper:
        state.phase = Phase.DOWN_REDUCTION
    elif side == "SELL" and state.cost <= config.lower:
        state.phase = Phase.UP_FOLLOW


def execute(state, signal, open_price, date, config):
    if date <= signal.date:
        raise ValueError("A close signal must execute on a later date.")
    if signal.phase != state.phase:
        raise ValueError("The pending signal has a stale phase.")
    price = fill_price(open_price, signal.side, config)
    block = validate_order(state, signal, price, date, config)
    if block:
        return None, block
    qty = round_quantity(config.amount, price)
    return _record(state, signal, price, qty, date, config), None


def initialize(date, open_price, config, signal_date=None):
    if signal_date is not None and signal_date >= date:
        raise ValueError("The initial close signal must precede the entry date.")
    state = State(config.capital)
    price = fill_price(open_price, "BUY", config)
    qty = round_quantity(config.initial_amount, price)
    if price * qty + config.fee(price * qty) > state.cash:
        raise ValueError("Initial position exceeds available capital.")
    signal = Signal(signal_date or date, 0, "INITIAL", "BUY", "INITIAL_POSITION", price, Phase.NORMAL)
    _record(state, signal, price, qty, date, config, initial=True)
    return state, Grid(price, config.ratio)


def _record(state, signal, price, qty, date, config, initial=False):
    before = state.phase
    fee = config.fee(price * qty)
    gross_profit = ZERO
    allocated_buy_fee = ZERO
    matched = []
    if signal.side == "BUY":
        if state.occupied(signal.grid):
            raise AssertionError("A buy cannot reuse an occupied grid.")
        state.cash -= price * qty + fee
        state.lots.append(Lot(state.next_id, signal.grid, price, qty, qty, date, fee))
        state.broker_lots.append(BrokerLot(date, qty))
        state.next_id += 1
    else:
        remaining = qty
        for lot in eligible_lots(state, signal, price):
            take = min(lot.qty, remaining)
            if take <= 0:
                continue
            gross_profit += (price - lot.price) * take
            allocated_buy_fee += lot.buy_fee * take / lot.original_qty
            matched.append({"lot_id": lot.lot_id, "grid": lot.grid, "qty": take,
                            "buy_price": str(lot.price)})
            lot.qty -= take
            remaining -= take
            if remaining == 0:
                break
        if remaining:
            raise AssertionError("An accepted sale must be fully matched.")
        state.lots = [lot for lot in state.lots if lot.qty > 0]
        remaining = qty
        for lot in state.broker_lots:
            if lot.buy_date >= date:
                continue
            take = min(lot.qty, remaining)
            lot.qty -= take
            remaining -= take
            if remaining == 0:
                break
        if remaining:
            raise AssertionError("An accepted sale must satisfy broker T+1.")
        state.broker_lots = [lot for lot in state.broker_lots if lot.qty > 0]
        state.cash += price * qty - fee
    if not initial:
        update_phase(state, signal.side, config)
    trade = {
        "date": date, "signal_date": signal.date, "side": signal.side,
        "direction": signal.direction, "reason": signal.reason, "grid": signal.grid,
        "signal_quote": signal.quote, "price": price, "qty": qty, "amount": price * qty,
        "fee": fee, "gross_profit": gross_profit, "allocated_buy_fee": allocated_buy_fee,
        "realized_net": gross_profit - allocated_buy_fee - fee if signal.side == "SELL" else ZERO,
        "phase_before": before.value, "phase_after": state.phase.value,
        "cash_after": state.cash, "cost_after": state.cost, "qty_after": state.qty,
        "matched_lots": matched,
    }
    state.trades.append(trade)
    assert_state(state)
    return trade


def assert_state(state):
    if state.cash < 0 or any(lot.qty <= 0 or lot.qty % 100 for lot in state.lots):
        raise AssertionError("Invalid cash or accounting inventory.")
    if len({lot.grid for lot in state.lots}) != len(state.lots):
        raise AssertionError("Several live lots occupy the same grid.")
    if state.qty != sum(lot.qty for lot in state.broker_lots):
        raise AssertionError("Broker and accounting quantities diverged.")
