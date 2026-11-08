"""Verify intraday sequencing, settlement, state persistence, and source sessions."""

import copy
from dataclasses import replace
from datetime import datetime
import unittest

from intraday import Pending, daily_bars, execute_next_bar, simulate_intraday, start_time
from intraday_data import normalize_intraday, session_times
from model import BrokerLot, Config, D, Lot, Phase, State, decide


SIGNAL_DAY = "2026-05-08"
NEXT_DAY = "2026-05-11"


def bar(time, opening="10", close=None, minutes=30):
    opening = D(str(opening))
    close = opening if close is None else D(str(close))
    return {"time": time, "minutes": minutes, "open": opening, "close": close,
            "high": max(opening, close) + D("0.01"),
            "low": min(opening, close) - D("0.01"), "volume": D("1000")}


def full_day(day, closes=None, opens=None):
    closes = ["10"] * 8 if closes is None else closes
    opens = closes if opens is None else opens
    return [bar(day + " " + time, opening, close)
            for time, opening, close in zip(session_times(30), opens, closes)]


def portfolio(day="2026-05-07", cash="10000", phase=Phase.NORMAL):
    lot = Lot(1, 0, D("10"), 1000, 1000, day, D("0"))
    return State(D(cash), phase, [lot], [BrokerLot(day, 1000)], next_id=2)


def source_day(day, minutes=30):
    return [{"day": day + " " + time, "open": "10", "close": "10",
             "high": "10.1", "low": "9.9", "volume": "1000"}
            for time in session_times(minutes)]


def source_daily(rows):
    groups = {}
    for row in rows:
        groups.setdefault(row["day"][:10], []).append(row)
    return [{"date": day, "open": D(items[0]["open"]), "close": D(items[-1]["close"]),
             "high": max(D(row["high"]) for row in items),
             "low": min(D(row["low"]) for row in items),
             "volume": sum((D(row["volume"]) for row in items), D("0"))}
            for day, items in groups.items()]


class IntradayExecutionTest(unittest.TestCase):
    def setUp(self):
        self.config = Config(commission_rate=D("0"), minimum_commission=D("0"))

    def pending(self, state, direction="UP", grid=1, quote="10.4", time=None, index=0):
        time = time or SIGNAL_DAY + " 10:00:00"
        signal, blocked = decide(state, time, direction, grid, D(quote), self.config)
        self.assertIsNone(blocked)
        return Pending(signal, index, time)

    def test_next_bar_open_may_share_the_signal_close_clock_time(self):
        state = portfolio()
        pending = self.pending(state)
        target = bar(SIGNAL_DAY + " 10:30:00", "10.4")
        trade, blocked = execute_next_bar(state, pending, target, 1, self.config)
        self.assertIsNone(blocked)
        self.assertEqual(SIGNAL_DAY + " 10:00:00", trade["signal_time"])
        self.assertEqual(trade["signal_time"], trade["fill_time"])
        self.assertEqual(0, trade["signal_bar"])
        self.assertEqual(1, trade["fill_bar"])
        self.assertEqual(SIGNAL_DAY, trade["date"])

    def test_a_signal_cannot_fill_at_its_own_bar_open(self):
        state = portfolio()
        pending = self.pending(state)
        before = copy.deepcopy(state)
        with self.assertRaisesRegex(ValueError, "next bar open"):
            execute_next_bar(state, pending, bar(SIGNAL_DAY + " 10:00:00", "10.4"), 0, self.config)
        self.assertEqual(before, state)

    def test_skipping_a_bar_or_going_back_in_time_is_rejected(self):
        for index, time in [(2, "10:30:00"), (1, "10:00:00")]:
            with self.subTest(index=index, time=time):
                state = portfolio()
                pending = self.pending(state)
                with self.assertRaisesRegex(ValueError, "next bar open"):
                    execute_next_bar(state, pending, bar(SIGNAL_DAY + " " + time, "10.4"), index, self.config)

    def test_morning_signal_executes_after_lunch_not_inside_the_break(self):
        state = portfolio()
        pending = self.pending(state, time=SIGNAL_DAY + " 11:30:00", index=3)
        trade, block = execute_next_bar(state, pending, bar(SIGNAL_DAY + " 13:30:00", "10.4"), 4, self.config)
        self.assertIsNone(block)
        self.assertEqual(SIGNAL_DAY + " 13:00:00", trade["fill_time"])

    def test_last_bar_signal_executes_at_next_trading_day_open(self):
        state = portfolio()
        pending = self.pending(state, time=SIGNAL_DAY + " 15:00:00", index=7)
        trade, block = execute_next_bar(state, pending, bar(NEXT_DAY + " 10:00:00", "10.4"), 8, self.config)
        self.assertIsNone(block)
        self.assertEqual(NEXT_DAY + " 09:30:00", trade["fill_time"])
        self.assertEqual(SIGNAL_DAY, trade["signal_date"])

    def test_same_day_buy_does_not_become_sellable_in_the_afternoon(self):
        state = portfolio(day=SIGNAL_DAY)
        pending = self.pending(state, time=SIGNAL_DAY + " 13:30:00", index=4)
        before = copy.deepcopy(state)
        trade, block = execute_next_bar(state, pending, bar(SIGNAL_DAY + " 14:00:00", "10.4"), 5, self.config)
        self.assertIsNone(trade)
        self.assertEqual("INSUFFICIENT_SETTLED_SHARES", block)
        self.assertEqual(before, state)

    def test_same_holdings_become_sellable_on_the_next_observed_date(self):
        state = portfolio(day=SIGNAL_DAY)
        pending = self.pending(state, time=SIGNAL_DAY + " 15:00:00", index=7)
        trade, block = execute_next_bar(state, pending, bar(NEXT_DAY + " 10:00:00", "10.4"), 8, self.config)
        self.assertIsNone(block)
        self.assertEqual("SELL", trade["side"])
        self.assertEqual(600, state.qty)

    def test_sale_proceeds_can_fund_an_intraday_buy_but_do_not_unfreeze_it(self):
        state = portfolio(cash="0")
        sale, block = execute_next_bar(state, self.pending(state),
                                       bar(SIGNAL_DAY + " 10:30:00", "10.4"), 1, self.config)
        self.assertIsNone(block)
        self.assertEqual(D("4160"), state.cash)
        self.assertEqual(Phase.UP_FOLLOW, state.phase)
        buy = self.pending(state, "DOWN", -1, "10", SIGNAL_DAY + " 10:30:00", 1)
        trade, block = execute_next_bar(state, buy, bar(SIGNAL_DAY + " 11:00:00", "10"), 2, self.config)
        self.assertIsNone(block)
        self.assertEqual(D("160"), state.cash)
        self.assertEqual(1000, state.qty)
        self.assertEqual(600, state.settled_qty(SIGNAL_DAY))
        self.assertEqual(SIGNAL_DAY, state.lots[-1].buy_date)
        self.assertEqual(SIGNAL_DAY, state.broker_lots[-1].buy_date)
        self.assertTrue(state.occupied(0))
        self.assertTrue(state.occupied(-1))

    def test_new_open_rechecks_profit_without_changing_order_direction(self):
        state = portfolio()
        pending = self.pending(state)
        before = copy.deepcopy(state)
        trade, block = execute_next_bar(state, pending, bar(SIGNAL_DAY + " 10:30:00", "9.9"), 1, self.config)
        self.assertIsNone(trade)
        self.assertEqual("INSUFFICIENT_PROFITABLE_SHARES", block)
        self.assertEqual(before, state)


class IntradaySimulationTest(unittest.TestCase):
    def history(self):
        return (full_day("2026-05-07") + full_day(SIGNAL_DAY) +
                full_day(NEXT_DAY, ["10.4", "10.9", "10.9", "10.9", "10.9", "10.9", "10.9", "10.9"],
                         ["10", "10.4", "10.9", "10.9", "10.9", "10.9", "10.9", "10.9"]))

    def test_same_day_can_have_multiple_fills_with_at_most_one_per_bar(self):
        result = simulate_intraday(self.history(), Config(), entry_date=SIGNAL_DAY)
        fills = [trade for trade in result["trades"] if trade["date"] == NEXT_DAY]
        self.assertEqual(["SELL", "BUY"], [trade["side"] for trade in fills])
        self.assertEqual(len(result["trades"]), len({trade["fill_bar"] for trade in result["trades"]}))
        self.assertEqual("UP_FOLLOW", fills[0]["phase_after"])
        self.assertEqual("UP_FOLLOW", fills[1]["phase_before"])

    def test_explicit_entry_is_identical_across_grid_parameters(self):
        history = self.history()
        a = simulate_intraday(history, Config(ratio=D("1.02")), entry_date=SIGNAL_DAY)
        b = simulate_intraday(history, Config(ratio=D("1.06")), entry_date=SIGNAL_DAY)
        for key in ("fill_time", "price", "qty", "amount", "fee"):
            self.assertEqual(a["trades"][0][key], b["trades"][0][key])
        self.assertEqual(SIGNAL_DAY + " 09:30:00", a["trades"][0]["fill_time"])
        self.assertEqual("2026-05-07 15:00:00", a["trades"][0]["signal_time"])

    def test_explicit_entry_requires_a_prior_observed_day(self):
        for entry in ("2026-05-07", "2026-05-12"):
            with self.subTest(entry=entry), self.assertRaisesRegex(ValueError, "prior observed"):
                simulate_intraday(self.history(), entry_date=entry)

    def test_default_entry_only_uses_prior_daily_closes(self):
        history = (full_day("2026-05-07", ["10.2"] * 8) +
                   full_day(SIGNAL_DAY, ["10.1"] * 8) +
                   full_day(NEXT_DAY, ["10"] * 8) +
                   full_day("2026-05-12", ["9.9"] * 8))
        result = simulate_intraday(history)
        self.assertEqual("2026-05-12", result["summary"]["entry_date"])
        self.assertEqual(NEXT_DAY + " 15:00:00", result["trades"][0]["signal_time"])
        self.assertEqual("2026-05-12 09:30:00", result["trades"][0]["fill_time"])

    def test_future_price_changes_do_not_modify_prior_fills_or_marks(self):
        history = self.history() + full_day("2026-05-12", ["10.8"] * 8)
        changed = copy.deepcopy(history)
        for candle in changed[-8:]:
            for key in ("open", "close", "high", "low"):
                candle[key] *= 2
        a = simulate_intraday(history, entry_date=SIGNAL_DAY)
        b = simulate_intraday(changed, entry_date=SIGNAL_DAY)
        self.assertEqual([trade for trade in a["trades"] if trade["date"] <= NEXT_DAY],
                         [trade for trade in b["trades"] if trade["date"] <= NEXT_DAY])
        self.assertEqual([mark for mark in a["marks"] if mark["date"] <= NEXT_DAY],
                         [mark for mark in b["marks"] if mark["date"] <= NEXT_DAY])

    def test_each_fill_uses_exactly_the_next_bar_open(self):
        history = self.history()
        result = simulate_intraday(history, entry_date=SIGNAL_DAY)
        for trade in result["trades"]:
            self.assertEqual(trade["signal_bar"] + 1, trade["fill_bar"])
            self.assertEqual(history[trade["fill_bar"]]["open"], trade["price"])
            self.assertEqual(start_time(history[trade["fill_bar"]]), trade["fill_time"])

    def test_every_bar_and_terminal_net_profit_reconcile(self):
        result = simulate_intraday(self.history(), entry_date=SIGNAL_DAY)
        for mark in result["marks"]:
            self.assertEqual(mark["equity"], mark["cash"] + mark["qty"] * mark["close"])
            self.assertEqual(mark["equity"], D("50000") + mark["realized_gross"] +
                             mark["unrealized_gross"] - mark["fees_paid"])
            self.assertGreaterEqual(mark["cash"], 0)
        summary = result["summary"]
        self.assertLess(abs(summary["net_profit"] - summary["realized_net"] - summary["unrealized_net"]), D("0.000001"))

    def test_bar_close_drawdown_captures_a_loss_hidden_by_the_day_close(self):
        history = (full_day("2026-05-07") +
                   full_day(SIGNAL_DAY, ["10", "9", "10", "10", "10", "10", "10", "10"], ["10"] * 8))
        result = simulate_intraday(history, Config(commission_rate=D("0"), minimum_commission=D("0")), entry_date=SIGNAL_DAY)
        self.assertEqual(D("2"), result["summary"]["max_drawdown_pct"])
        self.assertEqual(D("0"), result["summary"]["daily_drawdown_pct"])

    def test_final_signal_does_not_receive_an_unobserved_future_fill(self):
        history = full_day("2026-05-07") + full_day(SIGNAL_DAY, ["10"] * 7 + ["9"])
        result = simulate_intraday(history, entry_date=SIGNAL_DAY)
        self.assertEqual(1, len(result["trades"]))
        self.assertEqual("END_OF_SAMPLE", result["events"][-1]["status"])


class IntradaySourceTest(unittest.TestCase):
    def test_complete_thirty_minute_sessions_aggregate_to_daily_ohlc(self):
        rows = source_day(SIGNAL_DAY) + source_day(NEXT_DAY)
        normalized, dropped = normalize_intraday(rows, 30, source_daily(rows))
        self.assertEqual(16, len(normalized))
        self.assertEqual([], dropped)
        self.assertEqual(source_daily(rows), daily_bars(normalized))

    def test_missing_bar_duplicate_or_invalid_session_is_rejected(self):
        original = source_day(SIGNAL_DAY)
        daily = source_daily(original)
        missing = original[:2] + original[3:]
        duplicate = original[:2] + [copy.deepcopy(original[1])] + original[2:]
        invalid = copy.deepcopy(original)
        invalid[3]["day"] = SIGNAL_DAY + " 12:00:00"
        for rows in (missing, duplicate, invalid):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                normalize_intraday(rows, 30, daily)

    def test_unsorted_rows_and_invalid_prices_are_rejected(self):
        original = source_day(SIGNAL_DAY)
        daily = source_daily(original)
        for mutation in (lambda rows: rows.reverse(),
                         lambda rows: rows[0].__setitem__("low", "11"),
                         lambda rows: rows[0].__setitem__("open", "NaN"),
                         lambda rows: rows[0].__setitem__("volume", "-1")):
            rows = copy.deepcopy(original)
            mutation(rows)
            with self.assertRaises(ValueError):
                normalize_intraday(rows, 30, daily)

    def test_daily_ohlc_disagreement_blocks_intraday_history(self):
        rows = source_day(SIGNAL_DAY)
        daily = source_daily(rows)
        daily[0]["high"] = D("10.2")
        with self.assertRaisesRegex(ValueError, "daily OHLC disagree"):
            normalize_intraday(rows, 30, daily)

    def test_entire_missing_trading_day_is_not_hidden_by_complete_other_days(self):
        complete = source_day("2026-05-07") + source_day(SIGNAL_DAY) + source_day(NEXT_DAY)
        rows = complete[:8] + complete[16:]
        with self.assertRaisesRegex(ValueError, "skips a daily trading date"):
            normalize_intraday(rows, 30, source_daily(complete))

    def test_thirty_minute_history_cannot_omit_a_daily_window_endpoint(self):
        complete = source_day(SIGNAL_DAY) + source_day(NEXT_DAY)
        with self.assertRaisesRegex(ValueError, "entire two-year daily window"):
            normalize_intraday(complete[8:], 30, source_daily(complete))

    def test_first_partial_five_minute_day_is_removed_before_comparison(self):
        first = source_day(SIGNAL_DAY, 5)
        second = source_day(NEXT_DAY, 5)
        normalized, dropped = normalize_intraday(first[-9:] + second, 5, source_daily(first + second), drop_partial_first=True)
        self.assertEqual([SIGNAL_DAY], dropped)
        self.assertEqual(48, len(normalized))
        self.assertEqual(NEXT_DAY + " 09:35:00", normalized[0]["time"])
        with self.assertRaises(ValueError):
            normalize_intraday(first[-9:] + second, 5, source_daily(first + second))

    def test_a_later_partial_five_minute_day_cannot_be_silently_dropped(self):
        complete = source_day(SIGNAL_DAY, 5) + source_day(NEXT_DAY, 5)
        with self.assertRaisesRegex(ValueError, "session bars"):
            normalize_intraday(complete[:-1], 5, source_daily(complete), drop_partial_first=True)

    def test_partial_drop_does_not_hide_a_full_first_day_with_an_invalid_session(self):
        complete = source_day(SIGNAL_DAY, 5) + source_day(NEXT_DAY, 5)
        corrupted = copy.deepcopy(complete)
        corrupted[23]["day"] = SIGNAL_DAY + " 12:00:00"
        with self.assertRaisesRegex(ValueError, "session bars"):
            normalize_intraday(corrupted, 5, source_daily(complete), drop_partial_first=True)


if __name__ == "__main__":
    unittest.main()
