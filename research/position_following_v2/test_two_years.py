"""Verify two-year source coverage, cache integrity, and continuous yearly accounting."""

from datetime import date, timedelta
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from backtest import D, normalize
import backtest_two_years as two


def source_blobs():
    first, last = date.fromisoformat(two.START), date.fromisoformat(two.END)
    rows, secondary = [], []
    for index in range(485):
        day = (first + timedelta(days=(last - first).days * index // 484)).isoformat()
        rows.append([day, "3.000", "3.010", "3.020", "2.990", "1000"])
        secondary.append({"day": day, "open": "3.000", "close": "3.010", "high": "3.020", "low": "2.990"})
    return {
        "raw": json.dumps({"data": {"sz159915": {"day": rows}}}).encode(),
        "qfq": json.dumps({"data": {"sz159915": {"qfqday": rows}}}).encode(),
        "sina": json.dumps(secondary).encode(),
    }


def validate(blobs):
    return two.validate_sources(blobs["raw"], blobs["qfq"], blobs["sina"])


class TwoYearSourcesTest(unittest.TestCase):
    def test_explicit_window_includes_older_data_without_changing_half_year_default(self):
        rows = json.loads(source_blobs()["raw"])["data"]["sz159915"]["day"]
        self.assertEqual(two.START, normalize(rows, two.START, two.END)[0]["date"])
        self.assertGreaterEqual(normalize(rows)[0]["date"], "2026-05-07")

    def test_two_year_sources_cover_the_same_dates_and_decimal_ohlc(self):
        result = validate(source_blobs())
        self.assertEqual(485, len(result))
        self.assertEqual(two.START, result[0]["date"])
        self.assertEqual(two.END, result[-1]["date"])

    def test_adjustment_difference_blocks_an_unhandled_corporate_event(self):
        blobs = source_blobs()
        payload = json.loads(blobs["qfq"])
        payload["data"]["sz159915"]["qfqday"][0][2] = "3.011"
        blobs["qfq"] = json.dumps(payload).encode()
        with self.assertRaisesRegex(ValueError, "Corporate adjustments"):
            validate(blobs)

    def test_second_source_one_tick_difference_is_not_ignored(self):
        blobs = source_blobs()
        payload = json.loads(blobs["sina"])
        payload[10]["close"] = "3.011"
        blobs["sina"] = json.dumps(payload).encode()
        with self.assertRaisesRegex(ValueError, "disagree on OHLC"):
            validate(blobs)

    def test_second_source_missing_a_trading_date_is_rejected(self):
        blobs = source_blobs()
        blobs["sina"] = json.dumps(json.loads(blobs["sina"])[1:]).encode()
        with self.assertRaisesRegex(ValueError, "different dates or coverage"):
            validate(blobs)

    def test_truncated_history_cannot_be_presented_as_two_years(self):
        blobs = source_blobs()
        for key, field in (("raw", "day"), ("qfq", "qfqday")):
            payload = json.loads(blobs[key])
            payload["data"]["sz159915"][field] = payload["data"]["sz159915"][field][100:]
            blobs[key] = json.dumps(payload).encode()
        with self.assertRaisesRegex(ValueError, "requested two-year window"):
            validate(blobs)

    def test_each_cached_source_is_hashed_before_reuse(self):
        for modified in ("raw", "qfq", "sina"):
            with self.subTest(source=modified), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                folder = root / "data"
                folder.mkdir()
                sources = {}
                for key, blob in source_blobs().items():
                    filename = key + ".json"
                    (folder / filename).write_bytes(blob)
                    sources[key] = {"filename": filename, "sha256": hashlib.sha256(blob).hexdigest()}
                snapshot = {"window_start": two.START, "window_end": two.END, "sources": sources}
                (folder / "snapshot.json").write_text(json.dumps(snapshot), encoding="utf-8")
                with patch.object(two, "ROOT", root):
                    self.assertEqual(485, len(two.load_snapshot()[0]))
                    (folder / (modified + ".json")).write_bytes(b"tampered")
                    with self.assertRaisesRegex(ValueError, "hash mismatch"):
                        two.load_snapshot()


class ContinuousYearReturnsTest(unittest.TestCase):
    def test_years_keep_prior_equity_instead_of_resetting_capital(self):
        rows = [{"date": "2024-11-26", "equity": D("50000")},
                {"date": "2024-12-31", "equity": D("55000")},
                {"date": "2025-01-02", "equity": D("54000")},
                {"date": "2025-12-31", "equity": D("66000")},
                {"date": "2026-11-06", "equity": D("59400")}]
        result = two.period_returns(rows, D("50000"))
        self.assertEqual([D("10"), D("20"), D("-10")], [row["return_pct"] for row in result])
        self.assertEqual(D("55000"), result[1]["opening_equity"])
        self.assertEqual(D("66000"), result[2]["opening_equity"])
        self.assertEqual(D("9400"), sum(row["net_profit"] for row in result))

    def test_one_incomplete_year_is_not_fabricated_into_full_years(self):
        result = two.period_returns([{"date": "2026-06-01", "equity": D("50000")},
                                     {"date": "2026-11-06", "equity": D("52500")}], D("50000"))
        self.assertEqual(1, len(result))
        self.assertEqual("2026", result[0]["period"])
        self.assertEqual(D("5"), result[0]["return_pct"])


if __name__ == "__main__":
    unittest.main()
