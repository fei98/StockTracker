"""Check metric caches, budget classification, lock preservation, and search ordering."""

from concurrent.futures import Future
import copy
import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from model import D
import stock_five_search as study
from stock_five_fast import BudgetViolation
from stock_five_parameters import Point


POINT = Point(10000, 30000, D("3.5"), 4000)


def summary(ret="12.34567890123456789012345678"):
    return {"return_pct": D(ret), "max_drawdown_pct": D("10.25"), "equity": D("56172.83945061728394506172839"),
            "fees": D("150.05"), "net_profit": D("6172.83945061728394506172839"),
            "min_cash": D("25000.01"), "end_cash": D("31000.02"), "end_qty": 800,
            "trade_count": 35, "valid": True, "cancelled_opens": {"NO_BREAKOUT": 2}}


def pair():
    return [summary(), summary("12.2")]


class MetricFingerprintTest(unittest.TestCase):
    def copied_sources(self, root):
        for name in study.METRIC_SOURCE_FILES:
            (root / name).write_text("original metric dependency: " + name, encoding="utf-8")
        (root / "stock_five_parameters.py").write_text("original ranking-only source", encoding="utf-8")

    def test_price_and_simulation_changes_invalidate_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.copied_sources(root)
            with patch.object(study, "BASE", root):
                original = study.fingerprint({"sha256": "prices-a"})
                self.assertNotEqual(original, study.fingerprint({"sha256": "prices-b"}))
                (root / "stock_model.py").write_text("changed accounting", encoding="utf-8")
                self.assertNotEqual(original, study.fingerprint({"sha256": "prices-a"}))

    def test_ranking_only_source_changes_do_not_invalidate_price_simulation_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.copied_sources(root)
            with patch.object(study, "BASE", root):
                original = study.fingerprint({"sha256": "same-prices"})
                (root / "stock_five_parameters.py").write_text("ranking-only revision", encoding="utf-8")
                with patch.object(study, "rank", side_effect=AssertionError("Ranking must not run during fingerprinting")):
                    self.assertEqual(original, study.fingerprint({"sha256": "same-prices"}))

    def test_shared_model_dependency_changes_invalidate_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.copied_sources(root)
            with patch.object(study, "BASE", root):
                original = study.fingerprint({"sha256": "same-prices"})
                (root / "model.py").write_text("changed shared rounding or grid behavior", encoding="utf-8")
                self.assertNotEqual(original, study.fingerprint({"sha256": "same-prices"}))

    def test_temporal_partition_change_invalidates_metrics_without_price_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.copied_sources(root)
            with patch.object(study, "BASE", root):
                original = study.fingerprint({"sha256": "same-prices"})
                with patch.object(study, "SPLIT", "2026-01-01"):
                    self.assertNotEqual(original, study.fingerprint({"sha256": "same-prices"}))


class CacheIntegrityTest(unittest.TestCase):
    def test_cache_roundtrip_keeps_decimal_precision_and_invalid_budget_summary(self):
        other = Point(5000, 45000, D("4"), 8000)
        metrics = {POINT: pair(), other: [{"valid": False, "budget_failure": "No extra capital."}, summary()]}
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "ROOT", Path(temporary)):
            study.save_cache("train", "current", metrics)
            loaded = study.load_cache("train", "current")
            self.assertEqual(metrics, loaded)
            self.assertIsInstance(loaded[POINT][0]["return_pct"], type(D("0")))
            self.assertIsInstance(loaded[POINT][0]["end_qty"], int)
            self.assertIs(loaded[other][0]["valid"], False)
            self.assertEqual("No extra capital.", loaded[other][0]["budget_failure"])

    def test_cache_with_stale_fingerprint_is_rejected_without_partial_use(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "ROOT", Path(temporary)):
            study.save_cache("train", "old-inputs", {POINT: pair()})
            with self.assertRaisesRegex(ValueError, "Search inputs changed"):
                study.load_cache("train", "new-inputs")

    def test_training_cache_load_does_not_open_validation_or_full_cache(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "ROOT", Path(temporary)):
            study.save_cache("train", "train-only", {POINT: pair()})
            (Path(temporary) / "validation_cache.json").write_text("not valid JSON", encoding="utf-8")
            (Path(temporary) / "full_cache.json").write_text("not valid JSON", encoding="utf-8")
            self.assertEqual({POINT: pair()}, study.load_cache("train", "train-only"))

    def test_new_scope_has_no_cached_results(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "ROOT", Path(temporary)):
            self.assertEqual({}, study.load_cache("train", "current"))


class WorkerFailureBoundaryTest(unittest.TestCase):
    def test_budget_failure_is_invalid_summary_and_both_slippages_are_attempted(self):
        market = object()
        with patch.object(study, "WORKER_CONTEXTS", {"train": market}), \
             patch.object(study, "simulate_fast", side_effect=[BudgetViolation("Tax exceeds cash."), summary()]) as simulate:
            point, summaries = study.worker(("train", POINT))
            self.assertEqual(POINT, point)
            self.assertIs(summaries[0]["valid"], False)
            self.assertIn("Tax exceeds cash", summaries[0]["budget_failure"])
            self.assertIs(summaries[1]["valid"], True)
            self.assertEqual([0, 1], [call.args[1].slippage_ticks for call in simulate.call_args_list])
            self.assertTrue(all(call.args[0] is market for call in simulate.call_args_list))

    def test_accounting_assertion_is_not_swallowed_as_budget_failure(self):
        with patch.object(study, "WORKER_CONTEXTS", {"train": object()}), \
             patch.object(study, "simulate_fast", side_effect=AssertionError("Inventory mismatch.")):
            with self.assertRaisesRegex(AssertionError, "Inventory mismatch"):
                study.worker(("train", POINT))

    def test_other_value_error_is_not_swallowed_as_budget_failure(self):
        with patch.object(study, "WORKER_CONTEXTS", {"train": object()}), \
             patch.object(study, "simulate_fast", side_effect=ValueError("Unsupported source bars.")):
            with self.assertRaisesRegex(ValueError, "Unsupported source bars"):
                study.worker(("train", POINT))


class SelectionLockTest(unittest.TestCase):
    def test_same_training_reordered_in_memory_keeps_the_original_lock(self):
        other = Point(5000, 45000, D("4"), 8000)
        training = {POINT: pair(), other: pair()}
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "ROOT", Path(temporary)):
            first = study.selection_lock(POINT, [POINT], training, "metric-a", [POINT])
            repeated = study.selection_lock(POINT, [POINT], dict(reversed(list(training.items()))), "metric-a", [POINT])
            self.assertEqual(json.loads(json.dumps(first, default=study.json_default)), repeated)
            self.assertEqual(POINT.key, first["candidate_id"])
            self.assertEqual(2, first["training_evaluated_count_including_support"])

    def test_selected_id_cannot_be_replaced_after_validation(self):
        other = Point(5000, 45000, D("4"), 8000)
        training = {POINT: pair(), other: pair()}
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "ROOT", Path(temporary)):
            first = study.selection_lock(POINT, [POINT], training, "metric-a", [POINT])
            with self.assertRaisesRegex(ValueError, "training lock changed after validation"):
                study.selection_lock(other, [POINT], training, "metric-a", [POINT])
            saved = json.loads((Path(temporary) / "training_lock.json").read_text(encoding="utf-8"))
            self.assertEqual(first["candidate_id"], saved["candidate_id"])

    def test_changed_training_values_cannot_silently_replace_the_lock(self):
        training = {POINT: pair()}
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "ROOT", Path(temporary)):
            study.selection_lock(POINT, [POINT], training, "metric-a", [POINT])
            changed = copy.deepcopy(training)
            changed[POINT][1]["return_pct"] += D("0.01")
            with self.assertRaisesRegex(ValueError, "training lock changed after validation"):
                study.selection_lock(POINT, [POINT], changed, "metric-a", [POINT])


class ConcurrentEvaluationTest(unittest.TestCase):
    def test_cached_points_are_not_resubmitted_and_checkpoints_preserve_results(self):
        points = study.coarse_points()[:102]
        metrics = {points[0]: pair()}
        submitted, checkpoints = [], []

        class ImmediatePool:
            def submit(self, method, task):
                scope, point = task
                submitted.append((scope, point))
                future = Future()
                future.set_result((point, pair()))
                return future

        with patch.object(study, "save_cache", side_effect=lambda scope, digest, current: checkpoints.append(len(current))):
            study.evaluate(points, "train", metrics, ImmediatePool(), "fingerprint")
        self.assertEqual(101, len(submitted))
        self.assertNotIn(("train", points[0]), submitted)
        self.assertTrue(all(scope == "train" for scope, _ in submitted))
        self.assertEqual([101, 102], checkpoints)
        self.assertEqual(set(points), set(metrics))

    def test_worker_exception_reaches_caller_instead_of_becoming_fake_metrics(self):
        class BrokenPool:
            def submit(self, method, task):
                future = Future()
                future.set_exception(AssertionError("Worker accounting failed."))
                return future

        metrics = {}
        with self.assertRaisesRegex(AssertionError, "Worker accounting failed"):
            study.evaluate([POINT], "train", metrics, BrokenPool(), "fingerprint")
        self.assertEqual({}, metrics)


class ExportSchemaTest(unittest.TestCase):
    def test_mixed_action_tax_and_execution_fields_survive_csv_export(self):
        tables = {key: [] for key in ("trades", "events", "cashflows", "marks", "daily")}
        tables["cashflows"] = [
            {"date": "2026-05-25", "type": "CORPORATE_ACTION", "bonus_qty": 30, "dividend_gross": D("25")},
            {"date": "2026-05-26", "type": "DIVIDEND_TAX_SETTLEMENT", "dividend_tax": D("2.5"), "fifo": [{"qty": 100}]},
        ]
        tables["events"] = [{"stage": "OPEN", "fill_bar": 12}, {"stage": "CLOSE", "signal_bar": 12}]
        with tempfile.TemporaryDirectory() as temporary, patch.object(study, "OUTPUT", Path(temporary)):
            study.export("mixed", tables)
            with (Path(temporary) / "mixed_cashflows.csv").open(encoding="utf-8-sig", newline="") as stream:
                flows = list(csv.DictReader(stream))
            self.assertEqual("30", flows[0]["bonus_qty"])
            self.assertEqual("2.5", flows[1]["dividend_tax"])
            self.assertEqual("", flows[0]["dividend_tax"])
            self.assertEqual('[{"qty": 100}]', flows[1]["fifo"])
            with (Path(temporary) / "mixed_events.csv").open(encoding="utf-8-sig", newline="") as stream:
                events = list(csv.DictReader(stream))
            self.assertEqual("12", events[0]["fill_bar"])
            self.assertEqual("12", events[1]["signal_bar"])


class MainOrderingTest(unittest.TestCase):
    def test_training_candidate_is_locked_before_validation_cache_is_read(self):
        class StopAtValidation(Exception):
            pass

        calls = []

        def cache(scope, digest):
            calls.append("load:" + scope)
            if scope != "train":
                raise StopAtValidation()
            return {}

        def evaluate(points, scope, metrics, pool, digest):
            calls.append("evaluate:" + scope)
            metrics.update({point: pair() for point in points})

        def lock(chosen, seeds, training, digest, fine):
            calls.append("lock:" + chosen.key)
            return {"candidate_id": chosen.key}

        ranked = [{"point": POINT}]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for folder, filename in (("two_years", "results.json"), ("intraday_study", "study_results.json"),
                                     ("stock_002396", "results.json"), ("stock_002396", "报告.md")):
                path = root / folder / "outputs" / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("protected", encoding="utf-8")
            with patch.object(study, "BASE", root), patch.object(study, "ROOT", root / "study"), \
                 patch.object(study, "OUTPUT", root / "study" / "outputs"), \
                 patch.object(sys, "argv", ["stock_five_search.py", "--workers", "1", "--no-report"]), \
                 patch.object(study, "load_complete_five", return_value=([], {"sha256": "prices"})), \
                 patch.object(study, "fingerprint", return_value="fingerprint"), \
                 patch.object(study, "coarse_points", return_value=[POINT]), \
                 patch.object(study, "fine_centers", return_value=[POINT]), \
                 patch.object(study, "fine_neighbors", return_value=[]), \
                 patch.object(study, "choose_seeds", return_value=[POINT]), \
                 patch.object(study, "rank", return_value=ranked), \
                 patch.object(study, "load_cache", side_effect=cache), \
                 patch.object(study, "evaluate", side_effect=evaluate), \
                 patch.object(study, "selection_lock", side_effect=lock), \
                 patch.object(study, "ProcessPoolExecutor"):
                with self.assertRaises(StopAtValidation):
                    study.main()
        self.assertEqual(["load:train", "evaluate:train", "evaluate:train", "lock:" + POINT.key,
                          "load:validation"], calls)


if __name__ == "__main__":
    unittest.main()
