"""Tests for signal timing, fair benchmarks, historical integrity, and accounting."""

import copy
from dataclasses import replace
from datetime import date, timedelta
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import backtest
from backtest import D, Config, max_drawdown, normalize, select_entry, simulate


def bars(closes, opens=None):
    opens = opens or closes
    result = []
    for index, (close, opening) in enumerate(zip(closes, opens)):
        c, o = D(str(close)), D(str(opening))
        result.append({"date": (date(2026, 5, 7) + timedelta(days=index)).isoformat(),
                       "open": o, "close": c, "high": max(o, c) + D("0.01"),
                       "low": min(o, c) - D("0.01"), "volume": D("100000")})
    return result


def raw_rows():
    return [[(date(2026, 5, 7) + timedelta(days=i)).isoformat(),
             "3.000", "3.010", "3.020", "2.990", "1000"] for i in range(110)]


class BacktestTimingTest(unittest.TestCase):
    def setUp(self):
        self.config = Config()

    def test_entry_uses_the_first_qualifying_prefix(self):
        history = bars([10.2, 10.1, 10.0, 10.0, 8.0, 9.0])
        self.assertEqual((2, 3), select_entry(history))
        changed = history[:3] + bars([5, 2, 99])
        self.assertEqual((2, 3), select_entry(changed))

    def test_entry_does_not_choose_a_later_more_profitable_decline(self):
        history = bars([10.2, 10.1, 10, 9.9, 9.8, 100])
        self.assertEqual((2, 3), select_entry(history))

    def test_no_qualifying_decline_fails_instead_of_cherry_picking(self):
        with self.assertRaisesRegex(ValueError, "No qualifying"):
            select_entry(bars([10, 11, 12, 13, 14]))

    def test_no_entry_when_a_large_crash_is_mislabeled_small(self):
        with self.assertRaisesRegex(ValueError, "No qualifying"):
            select_entry(bars([10, 9, 8, 8, 8]))

    def test_close_signals_fill_exactly_at_next_observed_open(self):
        history = bars([10.2, 10.1, 10, 9.7, 9.3, 9.0, 9.5],
                       [10.2, 10.1, 10, 10, 9.6, 9.2, 9.4])
        result = simulate(history, self.config)
        by_date = {bar["date"]: index for index, bar in enumerate(history)}
        self.assertGreater(len(result["trades"]), 1)
        for trade in result["trades"]:
            index = by_date[trade["date"]]
            self.assertEqual(index - 1, by_date[trade["signal_date"]])
            self.assertEqual(history[index]["open"], trade["price"])

    def test_final_close_signal_never_gets_a_fictional_fill(self):
        result = simulate(bars([10.2, 10.1, 10, 10, 9]), self.config)
        self.assertEqual(1, len(result["trades"]))
        self.assertEqual("END_OF_SAMPLE", result["events"][-1]["status"])

    def test_multi_grid_gaps_still_fill_at_most_once_per_day(self):
        result = simulate(bars([10.2, 10.1, 10, 10, 8, 7, 6, 5]), self.config)
        dates = [trade["date"] for trade in result["trades"]]
        self.assertEqual(len(dates), len(set(dates)))
        self.assertGreater(abs(result["events"][0]["grid"]), 1)

    def test_changing_future_history_does_not_change_prior_fills(self):
        history = bars([10.2, 10.1, 10, 9.7, 9.3, 9.0, 9.5, 10, 11, 8],
                       [10.2, 10.1, 10, 10, 9.6, 9.2, 9.4, 9.6, 10.2, 8.5])
        changed = copy.deepcopy(history)
        for bar in changed[7:]:
            for key in ("open", "close", "high", "low"):
                bar[key] *= 2
        before = simulate(history, self.config)
        after = simulate(changed, self.config)
        cutoff = history[6]["date"]
        self.assertEqual([x for x in before["trades"] if x["date"] <= cutoff],
                         [x for x in after["trades"] if x["date"] <= cutoff])

    def test_daily_high_and_low_do_not_drive_signal_order(self):
        history = bars([10.2, 10.1, 10, 9.7, 9.3, 9, 9.5])
        changed = copy.deepcopy(history)
        for bar in changed:
            bar["high"] *= 5
            bar["low"] /= 5
        self.assertEqual(simulate(history, self.config), simulate(changed, self.config))

    def test_waiting_and_open_cancellation_are_separate_events(self):
        history = bars([10.2, 10.1, 10, 10.4, 10.4], [10.2, 10.1, 10, 10, 9.5])
        result = simulate(history, self.config)
        self.assertEqual(1, result["summary"]["cancelled_opens"]["INSUFFICIENT_PROFITABLE_SHARES"])
        self.assertEqual(1, result["summary"]["trade_count"])


class BacktestAccountingTest(unittest.TestCase):
    def test_same_initial_hold_and_all_in_hold_use_the_same_entry_and_fees(self):
        config = Config()
        result = simulate(bars([10.2, 10.1, 10, 10, 10]), config)
        summary = result["summary"]
        self.assertEqual(D("49995"), summary["equity"])
        self.assertEqual(summary["equity"], summary["same_initial_hold_equity"])
        self.assertEqual(4900, summary["full_buy_hold_qty"])
        self.assertEqual(D("49987.75"), summary["full_buy_hold_equity"])
        self.assertEqual(D("987.75"), summary["full_buy_hold_cash"])

    def test_slippage_applies_equally_to_benchmarks_and_initial_strategy_buy(self):
        result = simulate(bars([10.2, 10.1, 10, 10, 10]), Config(slippage_ticks=1))
        self.assertEqual(D("10.001"), result["summary"]["initial_price"])
        self.assertEqual(result["summary"]["equity"], result["summary"]["same_initial_hold_equity"])

    def test_max_drawdown_includes_initial_capital_and_later_high_watermark(self):
        self.assertEqual(D("25"), max_drawdown([D("100"), D("120"), D("90")], D("100")))
        self.assertEqual(D("1"), max_drawdown([D("99")], D("100")))

    def test_daily_and_terminal_profit_reconcile_with_cash_and_fees(self):
        result = simulate(bars([10.2, 10.1, 10, 10.4, 10.8, 10.1, 9, 8, 7, 6, 8, 10]), Config())
        for row in result["daily"]:
            self.assertEqual(row["equity"], row["cash"] + row["qty"] * row["close"])
            self.assertEqual(row["equity"], D("50000") + row["realized_gross"] +
                             row["unrealized_gross"] - row["fees_paid"])
            self.assertGreaterEqual(row["cash"], 0)
        s = result["summary"]
        self.assertLess(abs(s["net_profit"] - s["realized_net"] - s["unrealized_net"]), D("0.000001"))

    def test_default_primary_capital_is_fifty_thousand(self):
        self.assertEqual(D("50000"), Config().capital)
        result = simulate(bars([10.2, 10.1, 10, 10, 10]), Config())
        diagnostic = simulate(bars([10.2, 10.1, 10, 10, 10]), replace(Config(), capital=D("30000")))
        self.assertEqual(D("20000"), result["summary"]["equity"] - diagnostic["summary"]["equity"])


class DataIntegrityTest(unittest.TestCase):
    def test_normalization_preserves_raw_decimal_prices_and_window(self):
        normalized = normalize(raw_rows())
        self.assertEqual(110, len(normalized))
        self.assertEqual(D("3.010"), normalized[0]["close"])

    def test_duplicate_or_unsorted_history_is_rejected(self):
        for mutation in (lambda rows: rows.__setitem__(1, rows[0]), lambda rows: rows.reverse()):
            rows = raw_rows()
            mutation(rows)
            with self.assertRaises(ValueError):
                normalize(rows)

    def test_invalid_ohlc_volume_and_nonfinite_values_are_rejected(self):
        for field, value in [(3, "2"), (4, "4"), (1, "NaN"), (5, "0")]:
            rows = raw_rows()
            rows[0][field] = value
            with self.assertRaises(ValueError):
                normalize(rows)

    def cached_snapshot(self, root):
        folder = root / "data"
        folder.mkdir()
        rows = raw_rows()
        raw = json.dumps({"data": {"sz159915": {"day": rows}}}).encode()
        adjusted = json.dumps({"data": {"sz159915": {"qfqday": rows}}}).encode()
        (folder / "tencent_unadjusted.json").write_bytes(raw)
        (folder / "tencent_qfq.json").write_bytes(adjusted)
        snapshot = {"rows": rows, "metadata": {
            "raw_sha256": hashlib.sha256(raw).hexdigest(),
            "adjusted_sha256": hashlib.sha256(adjusted).hexdigest()}}
        (folder / "snapshot.json").write_text(json.dumps(snapshot), encoding="utf-8")
        return folder, snapshot

    def test_cache_uses_frozen_raw_data_and_detects_raw_tampering(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder, _ = self.cached_snapshot(root)
            with patch.object(backtest, "ROOT", root):
                self.assertEqual(110, len(backtest.load_snapshot()[0]))
                (folder / "tencent_unadjusted.json").write_bytes(b"modified")
                with self.assertRaisesRegex(ValueError, "Raw data hash"):
                    backtest.load_snapshot()

    def test_cache_detects_modified_normalized_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder, snapshot = self.cached_snapshot(root)
            snapshot["rows"][0][2] = "3.011"
            (folder / "snapshot.json").write_text(json.dumps(snapshot), encoding="utf-8")
            with patch.object(backtest, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "Snapshot rows"):
                    backtest.load_snapshot()

    def test_cache_blocks_adjustment_events_even_with_valid_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder, snapshot = self.cached_snapshot(root)
            adjusted_rows = raw_rows()
            adjusted_rows[0][2] = "3.011"
            adjusted = json.dumps({"data": {"sz159915": {"qfqday": adjusted_rows}}}).encode()
            (folder / "tencent_qfq.json").write_bytes(adjusted)
            snapshot["metadata"]["adjusted_sha256"] = hashlib.sha256(adjusted).hexdigest()
            (folder / "snapshot.json").write_text(json.dumps(snapshot), encoding="utf-8")
            with patch.object(backtest, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "corporate actions"):
                    backtest.load_snapshot()


if __name__ == "__main__":
    unittest.main()
