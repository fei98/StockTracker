"""Integration tests for stock bars, corporate actions, daily tax, and benchmarks."""

import copy
from dataclasses import replace
from datetime import datetime
import unittest

from intraday import Pending, simulate_intraday
from intraday_data import session_times
from model import D, Signal, Phase, decide
from stock_intraday import execute_stock_bar, initial_state, simulate_stock
from stock_model import CorporateAction, StockConfig


DATES = ("2026-05-21", "2026-05-22", "2026-05-25", "2026-05-26", "2026-05-27")


def bars_for_days(specification):
    result = []
    for day, closes, opens in specification:
        if not isinstance(closes, list):
            closes = [closes] * 8
        if opens is None:
            opens = closes
        elif not isinstance(opens, list):
            opens = [opens] * 8
        for end, closing, opening in zip(session_times(30), closes, opens):
            c, o = D(str(closing)), D(str(opening))
            result.append({"time": day + " " + end, "minutes": 30, "open": o, "close": c,
                           "high": max(o, c) + D("0.01"), "low": min(o, c) - D("0.01"),
                           "volume": D("100000")})
    return result


def bonus_action():
    return CorporateAction(DATES[2], D("0.25"), D("0.3"), D("0.25"), D("0.3"), "test")


def cash_action():
    return CorporateAction(DATES[2], D("0.25"), D("0"), D("0.25"), D("0"), "test")


class StockIntradayTest(unittest.TestCase):
    def test_without_stock_fees_or_actions_matches_existing_etf_adapter(self):
        history = bars_for_days([(DATES[0], "10", None),
                                 (DATES[1], ["10", "9.7", "9.4", "10.3", "10.6", "10", "9.2", "9.4"], None),
                                 (DATES[2], ["9.4", "9.8", "10.2", "10.5", "10.8", "10", "9.6", "9.4"], None),
                                 (DATES[3], "10.2", None)])
        config = StockConfig(tick=D("0.001"), stamp_rate=D("0"), transfer_rate=D("0"))
        stock = simulate_stock(history, config, actions=(), entry_date=DATES[1])
        etf = simulate_intraday(history, config, entry_date=DATES[1])
        for key in ("equity", "net_profit", "return_pct", "max_drawdown_pct", "fees", "end_qty", "end_cash"):
            self.assertEqual(etf["summary"][key], stock["summary"][key], key)
        keys = ("date", "side", "grid", "price", "qty", "phase_before", "phase_after", "fill_time", "fill_bar")
        self.assertEqual([{key: t[key] for key in keys} for t in etf["trades"]],
                         [{key: t[key] for key in keys} for t in stock["trades"]])

    def test_price_adjustment_does_not_fabricate_a_large_downward_trade(self):
        history = bars_for_days([(DATES[0], "20", None), (DATES[1], "20", None),
                                 (DATES[2], "15.19", None), (DATES[3], "15.19", None)])
        result = simulate_stock(history, actions=(bonus_action(),), entry_date=DATES[1])
        self.assertEqual(1, result["summary"]["trade_count"])
        self.assertEqual(650, result["summary"]["end_qty"])
        self.assertEqual(D("125"), result["summary"]["dividends"])
        self.assertEqual(D("10000"), result["summary"]["end_cost"])
        self.assertTrue(all(mark["grid"] == 0 for mark in result["marks"]))
        self.assertEqual(1, len([flow for flow in result["cashflows"] if flow["type"] == "CORPORATE_ACTION"]))

    def test_entry_on_ex_date_does_not_receive_preentry_entitlements(self):
        history = bars_for_days([(DATES[1], "20", None), (DATES[2], "15.19", None), (DATES[3], "15.19", None)])
        result = simulate_stock(history, actions=(bonus_action(),), entry_date=DATES[2])
        self.assertEqual(D("0"), result["summary"]["dividends"])
        self.assertEqual(result["summary"]["initial_qty"], result["summary"]["end_qty"])
        self.assertEqual(1, result["summary"]["trade_count"])
        self.assertTrue(all(mark["grid_scale"] == 1 and mark["grid_offset"] == 0 for mark in result["marks"]))
        self.assertEqual(D("0"), result["summary"]["benchmark_dividends"]["same_initial"])
        self.assertEqual(D("0"), result["summary"]["benchmark_dividends"]["full"])

    def test_old_pending_order_is_cancelled_on_ex_date_by_fixed_policy(self):
        history = bars_for_days([(DATES[0], "20", None),
                                 (DATES[1], ["20"] * 7 + ["20.8"], ["20"] * 8),
                                 (DATES[2], "15.19", None), (DATES[3], "15.19", None)])
        result = simulate_stock(history, actions=(bonus_action(),), entry_date=DATES[1])
        self.assertEqual(1, result["summary"]["cancelled_opens"]["CORPORATE_ACTION_CANCEL"])
        cancelled = next(e for e in result["events"] if e["status"] == "CORPORATE_ACTION_CANCEL")
        self.assertEqual(DATES[2] + " 09:30:00", cancelled["time"])
        self.assertFalse(any(t["fill_bar"] == cancelled["fill_bar"] for t in result["trades"]))

    def test_daily_round_trip_does_not_charge_tax_on_gross_intraday_sales(self):
        history = bars_for_days([(DATES[0], "10", None), (DATES[1], "10", None),
                                 (DATES[2], ["10.2", "9"] + ["9"] * 6,
                                  ["9.75", "10.2", "9"] + ["9"] * 5),
                                 (DATES[3], "9", None)])
        result = simulate_stock(history, actions=(cash_action(),), entry_date=DATES[1])
        activity = [t for t in result["trades"] if t["date"] == DATES[2]]
        self.assertEqual(["SELL", "BUY"], [t["side"] for t in activity])
        self.assertEqual([400, 400], [t["qty"] for t in activity])
        self.assertEqual(D("250"), result["summary"]["dividends"])
        self.assertEqual(D("0"), result["summary"]["dividend_tax"])

    def test_net_sale_tax_is_included_in_the_same_close_equity(self):
        history = bars_for_days([(DATES[0], "10", None), (DATES[1], "10", None),
                                 (DATES[2], "10.2", ["9.75"] + ["10.2"] * 7),
                                 (DATES[3], "10.2", None)])
        result = simulate_stock(history, actions=(cash_action(),), entry_date=DATES[1])
        self.assertEqual(D("20"), result["summary"]["dividend_tax"])
        last_today = [mark for mark in result["marks"] if mark["date"] == DATES[2]][-1]
        self.assertEqual(D("20"), last_today["dividend_tax"])
        self.assertTrue(any(flow["type"] == "DIVIDEND_TAX_SETTLEMENT" and
                            flow["net_trade_qty"] == -400 and flow["dividend_tax"] == 20 for flow in result["cashflows"]))

    def test_both_benchmarks_receive_actual_dividends_and_bonus_shares(self):
        history = bars_for_days([(DATES[0], "20", None), (DATES[1], "20", None),
                                 (DATES[2], "15.19", None), (DATES[3], "15.19", None)])
        config = StockConfig()
        result = simulate_stock(history, config, actions=(bonus_action(),), entry_date=DATES[1])
        summary = result["summary"]
        self.assertEqual(D("125"), summary["benchmark_dividends"]["same_initial"])
        self.assertEqual(D("600"), summary["benchmark_dividends"]["full"])
        same_cash = D("50000") - 500 * D("20") - config.fee(D("10000")) + D("125")
        full_cash = D("50000") - 2400 * D("20") - config.fee(D("48000")) + D("600")
        self.assertEqual(same_cash + 650 * D("15.19"), summary["same_initial_hold_equity"])
        self.assertEqual(full_cash + 3120 * D("15.19"), summary["full_buy_hold_equity"])

    def test_all_stock_fills_use_the_next_bar_and_bar_has_at_most_one_fill(self):
        history = bars_for_days([(DATES[0], "10", None),
                                 (DATES[1], ["10", "9.7", "9.4", "10.3", "10.6", "10", "9.2", "9.4"], None),
                                 (DATES[2], "10.2", None)])
        result = simulate_stock(history, actions=(), entry_date=DATES[1])
        self.assertGreater(len(result["trades"]), 1)
        self.assertEqual(len(result["trades"]), len({t["fill_bar"] for t in result["trades"]}))
        for trade in result["trades"]:
            self.assertEqual(trade["signal_bar"] + 1, trade["fill_bar"])
            self.assertGreaterEqual(trade["fill_time"], trade["signal_time"])

    def test_same_bar_execution_is_rejected_even_if_clock_comparison_would_pass(self):
        history = bars_for_days([(DATES[0], "10", None), (DATES[1], "10", None)])
        state, _ = initial_state(DATES[1], D("10"), StockConfig(), D("10000"),
                                 history[7]["time"], 8, history)
        sig = Signal(history[8]["time"], -1, "DOWN", "BUY", "NORMAL_DOWN_BUY", D("9"), Phase.NORMAL)
        with self.assertRaisesRegex(ValueError, "next bar"):
            execute_stock_bar(state, Pending(sig, 8, history[8]["time"]), history[8], 8, StockConfig())

    def test_future_prices_do_not_change_prior_fills_or_equity(self):
        history = bars_for_days([(DATES[0], "10", None), (DATES[1], ["10", "9.7", "9.4", "10.3", "10.6", "10", "9.2", "9.4"], None),
                                 (DATES[2], "10.2", None), (DATES[3], "9", None), (DATES[4], "10", None)])
        changed = copy.deepcopy(history)
        cutoff = DATES[2] + " 15:00:00"
        for bar in changed:
            if bar["time"] > cutoff:
                for key in ("open", "close", "high", "low"):
                    bar[key] *= 2
        before = simulate_stock(history, actions=(), entry_date=DATES[1])
        after = simulate_stock(changed, actions=(), entry_date=DATES[1])
        self.assertEqual([t for t in before["trades"] if t["fill_time"] <= cutoff],
                         [t for t in after["trades"] if t["fill_time"] <= cutoff])
        self.assertEqual([r for r in before["marks"] if r["time"] <= cutoff],
                         [r for r in after["marks"] if r["time"] <= cutoff])

    def test_final_close_signal_does_not_get_a_fictional_fill(self):
        history = bars_for_days([(DATES[0], "10", None), (DATES[1], ["10"] * 7 + ["9"], ["10"] * 8)])
        result = simulate_stock(history, actions=(), entry_date=DATES[1])
        self.assertEqual(1, result["summary"]["trade_count"])
        self.assertEqual("END_OF_SAMPLE", result["events"][-1]["status"])

    def test_every_mark_and_terminal_net_profit_reconcile_with_cashflows(self):
        history = bars_for_days([(DATES[0], "20", None), (DATES[1], ["20", "19.4", "19.2", "20.8", "20", "20", "20", "20"], None),
                                 (DATES[2], "15.19", None), (DATES[3], ["15.19", "16", "16.5", "16.8", "17", "17", "17", "17"], None)])
        result = simulate_stock(history, actions=(bonus_action(),), entry_date=DATES[1])
        for mark in result["marks"]:
            self.assertEqual(mark["cash"] + mark["qty"] * mark["close"], mark["equity"])
            expected = D("50000") + mark["realized_gross"] + mark["unrealized_gross"] - mark["fees"] + mark["dividends"] - mark["dividend_tax"]
            self.assertLess(abs(expected - mark["equity"]), D("0.000001"))
        s = result["summary"]
        expected = s["net_realized"] + s["net_unrealized"] + s["dividends"] - s["dividend_tax"]
        self.assertLess(abs(expected - s["net_profit"]), D("0.000001"))


if __name__ == "__main__":
    unittest.main()
