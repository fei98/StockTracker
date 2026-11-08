"""Preserve different corporate-action and dividend-tax cash-flow fields in exports."""

import csv
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import backtest_stock


class StockExportTest(unittest.TestCase):
    def test_mixed_cashflow_records_keep_dividends_bonus_and_tax_details(self):
        result = {key: [] for key in ("trades", "events", "cashflows", "marks", "daily")}
        result["cashflows"] = [
            {"date": "2025-05-30", "type": "CORPORATE_ACTION", "dividend_gross": "25", "bonus_qty": 0},
            {"date": "2025-06-02", "type": "DIVIDEND_TAX_SETTLEMENT", "dividend_tax": "2.5", "fifo": [{"qty": 100}]},
            {"date": "2026-05-25", "type": "CORPORATE_ACTION", "dividend_gross": "25", "bonus_qty": 30},
        ]
        with tempfile.TemporaryDirectory() as temporary, patch.object(backtest_stock, "OUTPUT", Path(temporary)):
            backtest_stock.export("test", result)
            with (Path(temporary) / "test_cashflows.csv").open(encoding="utf-8-sig", newline="") as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual(3, len(rows))
            self.assertEqual("25", rows[0]["dividend_gross"])
            self.assertEqual("2.5", rows[1]["dividend_tax"])
            self.assertEqual("30", rows[2]["bonus_qty"])
            self.assertEqual("", rows[0]["dividend_tax"])
            self.assertEqual('[{"qty": 100}]', rows[1]["fifo"])


if __name__ == "__main__":
    unittest.main()
