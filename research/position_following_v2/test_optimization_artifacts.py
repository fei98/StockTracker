"""Protect the training lock and clearly separated hindsight exploration."""

import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from model import D
import optimize_intraday as study


def row(candidate, full_return, full_risk, validation_return, validation_risk):
    result = {"candidate": candidate}
    for tick in (0, 1):
        result[f"full_return_pct_{tick}tick"] = D(full_return)
        result[f"full_max_drawdown_pct_{tick}tick"] = D(full_risk)
        result[f"validation_return_pct_{tick}tick"] = D(validation_return)
        result[f"validation_max_drawdown_pct_{tick}tick"] = D(validation_risk)
    return result


class ArtifactBoundaryTest(unittest.TestCase):
    def test_lock_cannot_be_replaced_after_validation(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "ROOT", Path(temporary)):
            original = study.lock_selection("A", [{"train_return": D("10")}], {"sha256": "source"})
            repeated = study.lock_selection("A", [{"train_return": D("10")}], {"sha256": "source"})
            self.assertEqual(original, repeated)
            with self.assertRaisesRegex(ValueError, "training lock changed"):
                study.lock_selection("B", [{"train_return": D("10")}], {"sha256": "source"})
            with self.assertRaisesRegex(ValueError, "training lock changed"):
                study.lock_selection("A", [{"train_return": D("11")}], {"sha256": "source"})

    def test_hindsight_filters_both_periods_and_both_slippage_scenarios(self):
        rows = [row("original", "8", "15", "-7", "19"), row("safe", "12", "12", "-4", "16"),
                row("unsafe", "30", "19", "-12", "25")]
        slip_risk = row("slippage_failure", "40", "19", "-2", "18")
        slip_risk["validation_max_drawdown_pct_1tick"] = D("21")
        rows.append(slip_risk)
        before = copy.deepcopy(rows)
        result = study.exploratory_candidates(rows, "original")
        self.assertEqual({"exploratory_return": "safe", "exploratory_balance": "safe"}, result)
        self.assertEqual(before, rows)

    def test_hindsight_returns_no_candidate_when_all_risk_constraints_fail(self):
        self.assertEqual({}, study.exploratory_candidates([row("original", "8", "15", "-7", "21")], "original"))


if __name__ == "__main__":
    unittest.main()
