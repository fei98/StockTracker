"""Compare summary-only execution with the complete stock accounting reference."""

import copy
from dataclasses import replace
from decimal import Decimal
from pathlib import Path
import unittest
from unittest.mock import patch

from backtest import D, max_drawdown
from intraday_data import session_times
from stock_five_fast import BudgetViolation, RunningTrades, context, simulate_fast
from stock_intraday import simulate_stock
from stock_model import CorporateAction, StockConfig


PRIOR = "2026-05-21"
ENTRY = "2026-05-22"
EX_DATE = "2026-05-25"
NEXT = "2026-05-26"
LAST = "2026-05-27"


def session(day, closes, opens=None):
    if not isinstance(closes, list):
        closes = [closes] * 48
    if len(closes) != 48:
        raise ValueError("Synthetic five-minute sessions require 48 bars.")
    if opens is None:
        opens = [closes[0]] + closes[:-1]
    if not isinstance(opens, list):
        opens = [opens] * 48
    if len(opens) != 48:
        raise ValueError("Synthetic opens require 48 bars.")
    rows = []
    for clock, closing, opening in zip(session_times(5), closes, opens):
        close, start = D(str(closing)), D(str(opening))
        rows.append({"time": day + " " + clock, "minutes": 5, "open": start,
                     "close": close, "high": max(start, close) + D("0.01"),
                     "low": min(start, close) - D("0.01"), "volume": D("100000")})
    return rows


def blocks(values):
    return [value for value in values for _ in range(48 // len(values))]


def oscillating_history():
    return (session(PRIOR, "10") +
            session(ENTRY, blocks([10, 9.7, 9.4, 10.3, 10.6, 10, 9.2, 9.4])) +
            session(EX_DATE, blocks([9.4, 9.8, 10.2, 10.5, 10.8, 10, 9.6, 9.4])) +
            session(NEXT, blocks([9.4, 9.0, 8.6, 8.1, 8.5, 9.5, 10.5, 11.5])) +
            session(LAST, "11.5"))


class FastReferenceTest(unittest.TestCase):
    def compare(self, bars, config, actions=(), entry_date=ENTRY):
        market = context(bars, entry_date=entry_date)
        fast = simulate_fast(market, config, actions=actions)
        reference = simulate_stock(bars, config, actions=actions, entry_date=entry_date)
        common = set(fast) & set(reference["summary"])
        self.assertGreaterEqual(len(common), 20)
        for key in sorted(common):
            actual, expected = fast[key], reference["summary"][key]
            if isinstance(actual, Decimal):
                self.assertLess(abs(actual - expected), D("0.000000000001"), key)
            else:
                self.assertEqual(expected, actual, key)
        daily_risk = max_drawdown([row["equity"] for row in reference["daily"]], config.capital)
        self.assertEqual(daily_risk, fast["daily_drawdown_pct"])
        return fast, reference

    def test_oscillations_match_with_each_amount_slippage_and_bound_configuration(self):
        history = oscillating_history()
        for amount in (D("2000"), D("8000")):
            for slip in (0, 1):
                for lower, upper in ((D("0"), D("10000")), (D("15000"), D("40000"))):
                    with self.subTest(amount=amount, slip=slip, lower=lower, upper=upper):
                        config = replace(StockConfig(), amount=amount, slippage_ticks=slip,
                                         lower=lower, upper=upper, ratio=D("1.03"))
                        fast, reference = self.compare(history, config)
                        self.assertGreater(fast["trade_count"], 1)
                        self.assertGreaterEqual(fast["min_cash"], 0)

    def test_same_day_new_purchases_stay_frozen_in_both_engines(self):
        history = (session(PRIOR, "10") +
                   session(ENTRY, [10, 9.0, 10.5] + [10.5] * 45,
                           [10, 10, 9.0, 10.5] + [10.5] * 44) +
                   session(EX_DATE, "10.5"))
        fast, reference = self.compare(history, StockConfig())
        self.assertGreater(fast["cancelled_opens"].get("INSUFFICIENT_SETTLED_SHARES", 0), 0)
        self.assertEqual(reference["summary"]["end_qty"], fast["end_qty"])

    def test_cash_dividend_net_zero_round_trip_does_not_create_tax(self):
        action = CorporateAction(EX_DATE, D("0.25"), D("0"), D("0.25"), D("0"), "test")
        history = (session(PRIOR, "10") + session(ENTRY, "10") +
                   session(EX_DATE, [10.2, 9] + [9] * 46,
                           [9.75, 10.2, 9] + [9] * 45) + session(NEXT, "9"))
        fast, reference = self.compare(history, StockConfig(), (action,))
        today = [trade for trade in reference["trades"] if trade["date"] == EX_DATE]
        self.assertEqual(["SELL", "BUY"], [trade["side"] for trade in today])
        self.assertEqual(0, sum(trade["qty"] * (1 if trade["side"] == "BUY" else -1) for trade in today))
        self.assertEqual(D("250"), fast["dividends"])
        self.assertEqual(D("0"), fast["dividend_tax"])

    def test_net_sale_dividend_tax_is_reflected_in_streamed_cash_and_drawdown(self):
        action = CorporateAction(EX_DATE, D("0.25"), D("0"), D("0.25"), D("0"), "test")
        history = (session(PRIOR, "10") + session(ENTRY, "10") +
                   session(EX_DATE, "10.2", [9.75] + [10.2] * 47) + session(NEXT, "10.2"))
        fast, reference = self.compare(history, StockConfig(), (action,))
        self.assertEqual(D("20"), fast["dividend_tax"])
        self.assertEqual(reference["marks"][-1]["cash"], fast["end_cash"])

    def test_bonus_and_cash_actions_compose_and_preserve_zero_share_cost(self):
        action = CorporateAction(EX_DATE, D("0.25"), D("0.3"), D("0.25"), D("0.3"), "test")
        history = session(PRIOR, "20") + session(ENTRY, "20") + session(EX_DATE, "15.19") + session(NEXT, "15.19")
        for slip in (0, 1):
            with self.subTest(slip=slip):
                fast, reference = self.compare(history, replace(StockConfig(), slippage_ticks=slip), (action,))
                self.assertEqual(650, fast["end_qty"])
                self.assertEqual(reference["summary"]["initial_price"] * 500, fast["end_cost"])
                self.assertEqual(D("125"), fast["dividends"])

    def test_ex_date_entry_has_no_prior_dividend_or_bonus_entitlement(self):
        action = CorporateAction(EX_DATE, D("0.25"), D("0.3"), D("0.25"), D("0.3"), "test")
        history = session(ENTRY, "20") + session(EX_DATE, "15.19") + session(NEXT, "15.19")
        fast, reference = self.compare(history, StockConfig(), (action,), entry_date=EX_DATE)
        self.assertEqual(D("0"), fast["dividends"])
        self.assertEqual(1, fast["trade_count"])
        self.assertEqual(reference["summary"]["initial_qty"], fast["end_qty"])

    def test_pre_action_pending_is_cancelled_before_the_new_open(self):
        action = CorporateAction(EX_DATE, D("0.25"), D("0.3"), D("0.25"), D("0.3"), "test")
        history = (session(PRIOR, "20") + session(ENTRY, [20] * 47 + [20.8], [20] * 48) +
                   session(EX_DATE, "15.19") + session(NEXT, "15.19"))
        fast, _ = self.compare(history, StockConfig(), (action,))
        self.assertEqual(1, fast["cancelled_opens"].get("CORPORATE_ACTION_CANCEL"))

    def test_auto_entry_uses_the_same_decline_prefix_as_reference(self):
        history = (session("2026-05-18", "10.2") + session("2026-05-19", "10.1") +
                   session("2026-05-20", "10") + session(PRIOR, "10") +
                   session(ENTRY, "9.5") + session(EX_DATE, "10.5"))
        fast, reference = self.compare(history, StockConfig(), entry_date=None)
        self.assertEqual(PRIOR, fast["entry_date"])
        self.assertEqual(reference["summary"]["active_bars"], fast["active_bars"])

    def test_gaining_and_losing_paths_keep_the_same_net_result_sign(self):
        losing = session(PRIOR, "10") + session(ENTRY, "10") + session(EX_DATE, "7") + session(NEXT, "5")
        gaining = session(PRIOR, "10") + session(ENTRY, "10") + session(EX_DATE, "12") + session(NEXT, "15")
        low, _ = self.compare(losing, StockConfig())
        high, _ = self.compare(gaining, StockConfig())
        self.assertLess(low["net_profit"], 0)
        self.assertGreater(high["net_profit"], 0)

    def test_context_and_input_history_are_not_mutated_by_different_runs(self):
        history = oscillating_history()
        original = copy.deepcopy(history)
        market = context(history, ENTRY)
        first = simulate_fast(market, StockConfig(), actions=())
        simulate_fast(market, replace(StockConfig(), amount=D("8000")), actions=())
        repeated = simulate_fast(market, StockConfig(), actions=())
        self.assertEqual(original, history)
        self.assertEqual(first, repeated)


class RunningTradeTotalsTest(unittest.TestCase):
    def test_cached_totals_reconcile_through_buys_losses_and_gains(self):
        trades = RunningTrades()
        records = [{"fee": D("5"), "gross_profit": D("0"), "realized_net": D("0")},
                   {"fee": D("7.25"), "gross_profit": D("-350.45"), "realized_net": D("-360.20")},
                   {"fee": D("8.50"), "gross_profit": D("600.19"), "realized_net": D("585.01")}]
        for record in records:
            trades.append(record)
            self.assertEqual(sum((trade["fee"] for trade in trades), D("0")), trades.fees)
            self.assertEqual(sum((trade["gross_profit"] for trade in trades), D("0")), trades.realized)
            self.assertEqual(sum((trade["realized_net"] for trade in trades), D("0")), trades.net_realized)


class BudgetClassificationTest(unittest.TestCase):
    def test_day_end_tax_shortfall_is_classified_as_budget_violation(self):
        market = context(session(PRIOR, "10") + session(ENTRY, "10"), ENTRY)

        def unaffordable_tax(state, opening_qty, day):
            state.cash = D("-0.01")
            raise AssertionError("Tax settlement would require additional capital.")

        with patch("stock_five_fast.settle_day", side_effect=unaffordable_tax):
            with self.assertRaisesRegex(BudgetViolation, "without additional capital"):
                simulate_fast(market, StockConfig(), actions=())

    def test_nonnegative_cash_accounting_assertion_is_not_hidden_as_budget_failure(self):
        market = context(session(PRIOR, "10") + session(ENTRY, "10"), ENTRY)

        def inconsistent_inventory(state, opening_qty, day):
            self.assertGreaterEqual(state.cash, 0)
            raise AssertionError("Independent tax inventory diverged.")

        with patch("stock_five_fast.settle_day", side_effect=inconsistent_inventory):
            with self.assertRaisesRegex(AssertionError, "tax inventory diverged"):
                simulate_fast(market, StockConfig(), actions=())


class FrozenMarketRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from stock_five_data import ROOT, load_complete_five

        if not (ROOT / "data" / "snapshot.json").exists() or not (ROOT / "data" / "baostock_5m.csv").exists():
            raise unittest.SkipTest("Frozen two-year five-minute history is not available.")
        with patch("stock_data.get_url", side_effect=AssertionError("Network forbidden in regression tests")), \
             patch("stock_five_data.download_chunks", side_effect=AssertionError("Downloads forbidden in regression tests")):
            cls.bars, _ = load_complete_five(allow_source_differences=True)
        cls.market = context(cls.bars)

    def compare_real(self, config):
        fast = simulate_fast(self.market, config)
        reference = simulate_stock(self.bars, config)["summary"]
        for key in sorted(set(fast) & set(reference)):
            if isinstance(fast[key], Decimal):
                self.assertLess(abs(fast[key] - reference[key]), D("0.000000000001"), key)
            else:
                self.assertEqual(reference[key], fast[key], key)
        self.assertEqual(485 * 48, len(self.bars))
        self.assertGreater(fast["trade_count"], 50)

    def test_original_amount_and_boundaries_match_on_complete_real_history(self):
        self.compare_real(StockConfig())

    def test_larger_amount_different_boundaries_and_adverse_tick_match_real_history(self):
        self.compare_real(replace(StockConfig(), amount=D("8000"), lower=D("15000"),
                                  upper=D("40000"), ratio=D("1.04"), slippage_ticks=1))


if __name__ == "__main__":
    unittest.main()
