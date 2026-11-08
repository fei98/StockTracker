"""Check monthly downloads, raw timestamp identity, cross-source prices, and frozen caches."""

import csv
from datetime import date, datetime, timedelta
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from backtest import D
import stock_five_data as five


def raw_row(day="2024-11-07", clock="09:35:00"):
    stamp = datetime.fromisoformat(day + " " + clock).strftime("%Y%m%d%H%M%S") + "000"
    return {"date": day, "time": stamp, "code": "sz.002396", "open": "10.00", "high": "11.00",
            "low": "9.00", "close": "10.00", "volume": "100", "amount": "1000", "adjustflag": "3"}


def full_history():
    """Build independent, complete reference sessions without external market downloads."""
    start, finish = date.fromisoformat(five.START), date.fromisoformat(five.END)
    days = [(start + timedelta(days=(finish - start).days * index // 484)).isoformat() for index in range(485)]
    clocks = []
    for session in ("09:30:00", "13:00:00"):
        point = datetime.fromisoformat("2000-01-01 " + session)
        clocks.extend((point + timedelta(minutes=offset)).strftime("%H:%M:%S")
                      for offset in range(5, 121, 5))
    rows = [raw_row(day, clock) for day in days for clock in clocks]
    daily = [{"date": day, "open": D("10"), "high": D("11"), "low": D("9"),
              "close": D("10"), "volume": D("4800")} for day in days]
    return rows, daily


class MonthlyIntervalsTest(unittest.TestCase):
    def test_clips_both_partial_month_endpoints_across_year(self):
        self.assertEqual([
            ("2024-11-07", "2024-11-30"), ("2024-12-01", "2024-12-31"),
            ("2025-01-01", "2025-01-09"),
        ], list(five.monthly_intervals("2024-11-07", "2025-01-09")))

    def test_leap_february_keeps_the_twenty_ninth(self):
        self.assertEqual([
            ("2024-02-28", "2024-02-29"), ("2024-03-01", "2024-03-02"),
        ], list(five.monthly_intervals("2024-02-28", "2024-03-02")))

    def test_nonleap_february_and_single_day_are_not_extended(self):
        self.assertEqual([("2025-02-27", "2025-02-28"), ("2025-03-01", "2025-03-01")],
                         list(five.monthly_intervals("2025-02-27", "2025-03-01")))
        self.assertEqual([("2026-11-06", "2026-11-06")],
                         list(five.monthly_intervals("2026-11-06", "2026-11-06")))

    def test_requested_two_years_have_no_overlap_or_calendar_gaps(self):
        intervals = list(five.monthly_intervals())
        self.assertEqual((five.START, five.END), (intervals[0][0], intervals[-1][1]))
        flattened = []
        for first, last in intervals:
            point, stop = date.fromisoformat(first), date.fromisoformat(last)
            while point <= stop:
                flattened.append(point)
                point += timedelta(days=1)
        start, stop = date.fromisoformat(five.START), date.fromisoformat(five.END)
        expected = [start + timedelta(days=offset) for offset in range((stop - start).days + 1)]
        self.assertEqual(expected, flattened)

    def test_reversed_interval_yields_no_requests(self):
        self.assertEqual([], list(five.monthly_intervals("2026-11-07", "2026-11-06")))


class RawIdentityTest(unittest.TestCase):
    def test_standard_timestamp_preserves_raw_price_strings(self):
        converted = five.convert_rows([raw_row()])
        self.assertEqual("2024-11-07 09:35:00", converted[0]["day"])
        self.assertEqual("10.00", converted[0]["open"])
        self.assertEqual("100", converted[0]["volume"])

    def test_other_stock_or_adjusted_data_is_rejected(self):
        for field, value in (("code", "sz.000001"), ("code", "sh.002396"), ("adjustflag", "1"), ("adjustflag", "2")):
            with self.subTest(field=field, value=value):
                row = raw_row()
                row[field] = value
                with self.assertRaises(ValueError):
                    five.convert_rows([row])

    def test_date_cannot_disagree_with_embedded_timestamp(self):
        row = raw_row()
        row["date"] = "2024-11-08"
        with self.assertRaisesRegex(ValueError, "date and timestamp disagree"):
            five.convert_rows([row])

    def test_calendar_invalid_timestamp_is_rejected(self):
        row = raw_row()
        row["time"] = "20240230093500000"
        with self.assertRaises(ValueError):
            five.convert_rows([row])

    def test_timestamp_requires_all_seventeen_numeric_characters(self):
        for value in ("20241107093500BAD", "20241107093500", "202411070935000000", "20241107093500500"):
            with self.subTest(timestamp=value):
                row = raw_row()
                row["time"] = value
                with self.assertRaises(ValueError):
                    five.convert_rows([row])


class CompleteMinuteValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows, cls.daily = full_history()

    def test_complete_sessions_and_recent_prices_are_reconciled(self):
        recent = [{"time": "2026-11-06 15:00:00", "open": D("10"), "high": D("11"),
                   "low": D("9"), "close": D("10")}]
        bars, checks = five.validate_minutes(self.rows, self.daily, recent)
        self.assertEqual(23280, len(bars))
        self.assertEqual(485, checks["complete_days"])
        self.assertEqual(1, checks["recent_sina_equal_bars"])
        self.assertEqual("2024-11-07 09:35:00", checks["first_time"])
        self.assertEqual("2026-11-06 15:00:00", checks["last_time"])

    def test_missing_intraday_bar_is_not_reconstructed(self):
        rows = self.rows[:10] + self.rows[11:]
        with self.assertRaisesRegex(ValueError, "session bars"):
            five.validate_minutes(rows, self.daily, [])

    def test_missing_entire_session_is_not_accepted_as_two_years(self):
        rows, daily = self.rows[48:], self.daily[1:]
        with self.assertRaisesRegex(ValueError, "485 full sessions"):
            five.validate_minutes(rows, daily, [])

    def test_source_daily_reference_date_coverage_must_agree(self):
        daily = [dict(row) for row in self.daily]
        daily[10]["high"] = D("12")
        with self.assertRaisesRegex(ValueError, "daily OHLC disagree"):
            five.validate_minutes(self.rows, daily, [])

    def test_one_cent_recent_intraday_difference_is_rejected(self):
        recent = [{"time": "2026-11-06 14:55:00", "open": D("10"), "high": D("11"),
                   "low": D("9"), "close": D("10.01")}]
        with self.assertRaisesRegex(ValueError, "BaoStock and Sina"):
            five.validate_minutes(self.rows, self.daily, recent)

    def test_recent_timestamp_absent_from_download_is_rejected(self):
        recent = [{"time": "2026-11-06 15:01:00", "open": D("10"), "high": D("11"),
                   "low": D("9"), "close": D("10")}]
        for allowed in (False, True):
            with self.subTest(allow_source_differences=allowed):
                with self.assertRaisesRegex(ValueError, "timestamp is missing"):
                    five.validate_minutes(self.rows, self.daily, recent, allow_source_differences=allowed)

    def test_source_differences_need_explicit_opt_in(self):
        daily = [dict(row) for row in self.daily]
        daily[0]["high"] = D("11.10")
        daily[0]["low"] = D("8.90")
        with self.assertRaisesRegex(ValueError, "daily OHLC disagree"):
            five.validate_minutes(self.rows, daily, [])

    def test_allowed_source_differences_are_counted_without_replacing_prices(self):
        daily = [dict(row) for row in self.daily]
        daily[0]["high"] = D("11.10")
        daily[0]["low"] = D("8.90")
        recent = [{"time": "2026-11-06 14:55:00", "open": D("10"), "high": D("11"),
                   "low": D("9"), "close": D("10.01")},
                  {"time": "2026-11-06 15:00:00", "open": D("10"), "high": D("11"),
                   "low": D("9"), "close": D("10")}]
        bars, checks = five.validate_minutes(self.rows, daily, recent, allow_source_differences=True)
        self.assertTrue(checks["source_differences_explicitly_allowed"])
        self.assertTrue(checks["daily_open_close_equal"])
        self.assertTrue(checks["daily_range_contained"])
        self.assertFalse(checks["daily_ohlc_equal"])
        self.assertEqual({"high": 1, "low": 1}, checks["daily_extreme_difference_days"])
        self.assertEqual({"high": D("0.10"), "low": D("0.10")}, checks["daily_extreme_max_difference"])
        self.assertEqual(2, checks["recent_sina_comparison_bars"])
        self.assertEqual(1, checks["recent_sina_equal_bars"])
        self.assertEqual(1, checks["recent_sina_differing_bars"])
        self.assertEqual({"close": 1}, checks["recent_sina_field_differences"])
        self.assertEqual({"close": D("0.01")}, checks["recent_sina_max_difference"])
        self.assertEqual(D("11"), bars[0]["high"])
        self.assertEqual(D("9"), bars[0]["low"])
        self.assertEqual(D("10"), bars[-2]["close"])
        self.assertEqual("10.00", self.rows[-2]["close"])

    def test_allowing_differences_cannot_relax_daily_open_or_close(self):
        for field, value in (("open", D("10.01")), ("close", D("9.99"))):
            with self.subTest(field=field):
                daily = [dict(row) for row in self.daily]
                daily[0][field] = value
                with self.assertRaisesRegex(ValueError, "opening or closing price differs"):
                    five.validate_minutes(self.rows, daily, [], allow_source_differences=True)

    def test_allowing_differences_cannot_accept_prices_outside_daily_range(self):
        for field, value in (("high", D("10.99")), ("low", D("9.01"))):
            with self.subTest(field=field):
                daily = [dict(row) for row in self.daily]
                daily[0][field] = value
                with self.assertRaisesRegex(ValueError, "exceed the independent daily trading range"):
                    five.validate_minutes(self.rows, daily, [], allow_source_differences=True)

    def test_allowing_differences_does_not_allow_a_missing_intraday_bar(self):
        rows = self.rows[:10] + self.rows[11:]
        with self.assertRaisesRegex(ValueError, "session bars"):
            five.validate_minutes(rows, self.daily, [], allow_source_differences=True)

    def test_full_count_does_not_allow_wrong_window_endpoints(self):
        bars = [{"time": "2024-11-08 09:35:00"}] * 23279 + [{"time": "2026-11-06 15:00:00"}]
        with patch.object(five, "normalize_intraday", return_value=(bars, [])):
            with self.assertRaisesRegex(ValueError, "full requested window"):
                five.validate_minutes([raw_row()], self.daily, [])


class FrozenMinuteCacheTest(unittest.TestCase):
    def create_snapshot(self, root):
        folder = root / "data"
        folder.mkdir()
        path = folder / "baostock_5m.csv"
        with path.open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=five.FIELDS)
            writer.writeheader()
            writer.writerow(raw_row())
        metadata = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "provider": "BaoStock"}
        (folder / "snapshot.json").write_text(json.dumps(metadata), encoding="utf-8")
        return folder, metadata

    def test_corrupt_complete_cache_fails_without_downloading_replacement(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder, _ = self.create_snapshot(root)
            (folder / "baostock_5m.csv").write_bytes(b"tampered")
            with patch.object(five, "ROOT", root), patch.object(five, "load_stock", return_value=([], [], {})), \
                    patch.object(five, "load_stock_five", return_value=([], {})), \
                    patch.object(five, "download_chunks", side_effect=AssertionError("Network must not run")) as download:
                with self.assertRaisesRegex(ValueError, "snapshot hash changed"):
                    five.load_complete_five()
                download.assert_not_called()

    def test_valid_complete_cache_is_parsed_and_revalidated_without_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, metadata = self.create_snapshot(root)
            expected = [{"time": "2024-11-07 09:35:00"}]
            with patch.object(five, "ROOT", root), patch.object(five, "load_stock", return_value=(["daily"], [], {})), \
                    patch.object(five, "load_stock_five", return_value=(["recent"], {})), \
                    patch.object(five, "download_chunks", side_effect=AssertionError("Network must not run")) as download, \
                    patch.object(five, "validate_minutes", return_value=(expected, {})) as validate:
                bars, returned = five.load_complete_five()
                self.assertEqual(expected, bars)
                self.assertEqual(dict(metadata, checks={}), returned)
                self.assertEqual(raw_row(), validate.call_args.args[0][0])
                self.assertEqual(["daily"], validate.call_args.args[1])
                self.assertEqual(["recent"], validate.call_args.args[2])
                download.assert_not_called()

    def test_cached_quality_checks_are_recomputed_and_allowance_is_not_silent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder, metadata = self.create_snapshot(root)
            metadata["checks"] = {"daily_ohlc_equal": True, "source_differences_explicitly_allowed": False}
            (folder / "snapshot.json").write_text(json.dumps(metadata), encoding="utf-8")
            updated = {"daily_ohlc_equal": False, "daily_open_close_equal": True,
                       "source_differences_explicitly_allowed": True, "recent_sina_differing_bars": 7}
            with patch.object(five, "ROOT", root), patch.object(five, "load_stock", return_value=(["daily"], [], {})), \
                    patch.object(five, "load_stock_five", return_value=(["recent"], {})), \
                    patch.object(five, "download_chunks", side_effect=AssertionError("Network must not run")) as download, \
                    patch.object(five, "validate_minutes", return_value=(["validated-bars"], updated)) as validate:
                bars, returned = five.load_complete_five(allow_source_differences=True)
                self.assertEqual(["validated-bars"], bars)
                self.assertEqual(updated, returned["checks"])
                self.assertEqual(metadata["sha256"], returned["sha256"])
                self.assertEqual(True, validate.call_args.args[3])
                download.assert_not_called()

    def test_strict_cached_read_does_not_inherit_a_previous_allowance(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder, metadata = self.create_snapshot(root)
            metadata["checks"] = {"source_differences_explicitly_allowed": True}
            (folder / "snapshot.json").write_text(json.dumps(metadata), encoding="utf-8")
            with patch.object(five, "ROOT", root), patch.object(five, "load_stock", return_value=(["daily"], [], {})), \
                    patch.object(five, "load_stock_five", return_value=(["recent"], {})), \
                    patch.object(five, "download_chunks", side_effect=AssertionError("Network must not run")) as download, \
                    patch.object(five, "validate_minutes", side_effect=ValueError("strict source mismatch")) as validate:
                with self.assertRaisesRegex(ValueError, "strict source mismatch"):
                    five.load_complete_five()
                self.assertEqual(False, validate.call_args.args[3])
                download.assert_not_called()

    def test_chunk_hash_failure_never_queries_server_and_still_logs_out(self):
        fake = SimpleNamespace(login=Mock(return_value=SimpleNamespace(error_code="0", error_msg="success")),
                               logout=Mock(), query_history_k_data_plus=Mock(side_effect=AssertionError("Network must not run")))
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            first, last = "2024-11-07", "2024-11-30"
            path = folder / f"baostock_{first}_{last}.csv"
            path.write_bytes(b"tampered")
            path.with_suffix(".json").write_text(json.dumps({"sha256": "not-the-content-hash"}), encoding="utf-8")
            with patch.dict("sys.modules", {"baostock": fake}), patch.object(five.socket, "setdefaulttimeout"), \
                    patch.object(five, "monthly_intervals", return_value=[(first, last)]):
                with self.assertRaisesRegex(ValueError, "chunk changed"):
                    five.download_chunks(folder)
            fake.query_history_k_data_plus.assert_not_called()
            fake.logout.assert_called_once()


if __name__ == "__main__":
    unittest.main()
