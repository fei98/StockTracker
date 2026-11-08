"""Tests for predeclared parameter coverage and training-only robust selection."""

import copy
import unittest
from unittest.mock import patch

from model import Config, D
from parameter_search import (
    candidates, key_for, metric_pair, neighbors, rank_training, split_bars, training_rows,
)


def row(lower=10000, upper=30000, step=3, gain="10", slip_gain=None, dd="10", slip_dd=None):
    return {
        "candidate": f"L{lower}_U{upper}_S{step}", "lower": D(lower), "upper": D(upper), "step": D(step),
        "train_return_pct_0tick": D(gain), "train_return_pct_1tick": D(slip_gain if slip_gain is not None else gain),
        "train_max_drawdown_pct_0tick": D(dd),
        "train_max_drawdown_pct_1tick": D(slip_dd if slip_dd is not None else dd),
    }


def grid_rows():
    return [row(int(config.lower), int(config.upper), int((config.ratio - 1) * 100)) for config in candidates()]


class ParameterCoverageTest(unittest.TestCase):
    def test_declared_grid_has_45_unique_combinations_and_preserves_fixed_parameters(self):
        configs = candidates()
        self.assertEqual(45, len(configs))
        self.assertEqual(45, len({key_for(config) for config in configs}))
        self.assertIn(Config(), configs)
        for config in configs:
            self.assertEqual(D("50000"), config.capital)
            self.assertEqual(D("10000"), config.initial_amount)
            self.assertEqual(D("4000"), config.amount)
            self.assertEqual(Config().commission_rate, config.commission_rate)
            self.assertEqual(Config().minimum_commission, config.minimum_commission)

    def test_neighbors_are_one_axis_one_declared_step_not_diagonal_or_self(self):
        center = row(step=4)
        self.assertTrue(neighbors(center, row(lower=5000, step=4)))
        self.assertTrue(neighbors(center, row(upper=40000, step=4)))
        self.assertTrue(neighbors(center, row(step=5)))
        self.assertFalse(neighbors(center, center))
        self.assertFalse(neighbors(center, row(lower=5000, step=3)))
        self.assertFalse(neighbors(center, row(step=2)))

    def test_each_metric_pair_exercises_zero_and_one_tick_with_identical_other_settings(self):
        bars = [{"time": "2025-01-02 10:00:00"}]
        config = Config()
        with patch("parameter_search.simulate_intraday", side_effect=lambda bars, config: {
            "summary": {"slippage": config.slippage_ticks}}) as simulate:
            results = metric_pair(bars, config)
        self.assertEqual([0, 1], [result["slippage"] for result in results])
        self.assertEqual(2, simulate.call_count)
        for index, call in enumerate(simulate.call_args_list):
            actual_bars, actual = call.args
            self.assertIs(bars, actual_bars)
            self.assertEqual(index, actual.slippage_ticks)
            self.assertEqual(config.lower, actual.lower)
            self.assertEqual(config.upper, actual.upper)
            self.assertEqual(config.ratio, actual.ratio)
            self.assertEqual(config.capital, actual.capital)

    def test_training_table_does_not_skip_unfavorable_candidates(self):
        bars = [{"time": "2024-12-02 10:00:00"}]
        def simulate(history, config):
            self.assertIs(history, bars)
            return {"summary": {"return_pct": D("-5"), "max_drawdown_pct": D("21"),
                                "trade_count": 7, "fees": D("35"), "equity": D("47500")}}
        with patch("parameter_search.simulate_intraday", side_effect=simulate) as simulate_mock:
            results = training_rows(bars)
        self.assertEqual(45, len(results))
        self.assertEqual(90, simulate_mock.call_count)
        self.assertTrue(all(result["train_return_pct_0tick"] == D("-5") for result in results))


class TrainingSelectorTest(unittest.TestCase):
    def test_risk_limit_requires_both_execution_scenarios_and_includes_exact_twenty(self):
        rows = grid_rows()
        unsafe = rows[0]
        unsafe.update(train_return_pct_0tick=D("100"), train_return_pct_1tick=D("100"),
                      train_max_drawdown_pct_0tick=D("15"), train_max_drawdown_pct_1tick=D("20.01"))
        exact = rows[-1]
        exact.update(train_max_drawdown_pct_0tick=D("20"), train_max_drawdown_pct_1tick=D("20"))
        selected, scored = rank_training(rows)
        by_key = {item["candidate"]: item for item in scored}
        self.assertFalse(by_key[unsafe["candidate"]]["train_risk_eligible"])
        self.assertTrue(by_key[exact["candidate"]]["train_risk_eligible"])
        self.assertNotEqual(unsafe["candidate"], selected)

    def test_isolated_high_return_does_not_beat_a_stable_neighborhood(self):
        rows = grid_rows()
        center = next(item for item in rows if item["candidate"] == "L10000_U30000_S4")
        center.update(train_return_pct_0tick=D("100"), train_return_pct_1tick=D("100"))
        for other in rows:
            if neighbors(center, other):
                other.update(train_return_pct_0tick=D("0"), train_return_pct_1tick=D("0"))
        selected, scored = rank_training(rows)
        center_score = next(item for item in scored if item["candidate"] == center["candidate"])
        self.assertEqual(D("0"), center_score["train_neighbor_median_worst_return"])
        self.assertNotEqual(center["candidate"], selected)

    def test_neighborhood_requires_at_least_half_risk_passes_including_self(self):
        center = row(step=4, gain="20")
        safe_neighbor = row(lower=5000, step=4, gain="10")
        unsafe_neighbors = [row(lower=15000, step=4, gain="40", dd="21"),
                            row(upper=20000, step=4, gain="40", dd="21")]
        selected, scored = rank_training([center, safe_neighbor] + unsafe_neighbors)
        by_key = {item["candidate"]: item for item in scored}
        self.assertEqual(D("0.5"), by_key[center["candidate"]]["train_neighbor_risk_pass_fraction"])
        self.assertTrue(by_key[center["candidate"]]["train_selector_eligible"])
        self.assertEqual(center["candidate"], selected)
        selected, scored = rank_training([center, safe_neighbor] + unsafe_neighbors +
                                         [row(upper=40000, step=4, gain="40", dd="21")])
        by_key = {item["candidate"]: item for item in scored}
        self.assertEqual(D("0.4"), by_key[center["candidate"]]["train_neighbor_risk_pass_fraction"])
        self.assertFalse(by_key[center["candidate"]]["train_selector_eligible"])
        self.assertEqual(safe_neighbor["candidate"], selected)

    def test_worst_slippage_return_is_used_instead_of_optimistic_zero_tick_return(self):
        rows = grid_rows()
        optimistic = rows[0]
        optimistic.update(train_return_pct_0tick=D("100"), train_return_pct_1tick=D("-100"))
        selected, scored = rank_training(rows)
        observed = next(item for item in scored if item["candidate"] == optimistic["candidate"])
        self.assertEqual(D("-100"), observed["train_worst_return"])
        self.assertNotEqual(optimistic["candidate"], selected)

    def test_own_worst_return_breaks_equal_neighborhood_medians(self):
        weaker = row(step=2, gain="10")
        stronger = row(step=3, gain="20")
        selected, scored = rank_training([weaker, stronger])
        self.assertEqual([D("15"), D("15")], [item["train_neighbor_median_worst_return"] for item in scored])
        self.assertEqual(stronger["candidate"], selected)

    def test_lower_drawdown_then_stable_key_break_remaining_ties(self):
        low_risk = row(step=2, dd="5")
        high_risk = row(step=3, dd="10")
        self.assertEqual(low_risk["candidate"], rank_training([high_risk, low_risk])[0])
        tied = grid_rows()
        first = rank_training(tied)[0]
        self.assertEqual(min(item["candidate"] for item in tied), first)
        self.assertEqual(first, rank_training(list(reversed(tied)))[0])

    def test_validation_and_full_period_outcomes_cannot_change_selection(self):
        rows = grid_rows()
        before = copy.deepcopy(rows)
        selected = rank_training(rows)[0]
        for index, item in enumerate(rows):
            item["validation_return_pct_0tick"] = D("10000") if item["candidate"] != selected else D("-10000")
            item["validation_max_drawdown_pct_0tick"] = D(index)
            item["full_return_pct_1tick"] = D(100000 - index)
        self.assertEqual(selected, rank_training(rows)[0])
        for old, current in zip(before, rows):
            for key in old:
                self.assertEqual(old[key], current[key])

    def test_scoring_does_not_mutate_source_metrics(self):
        rows = grid_rows()
        original = copy.deepcopy(rows)
        rank_training(rows)
        self.assertEqual(original, rows)

    def test_no_eligible_candidate_fails_without_loosening_risk_limit(self):
        with self.assertRaisesRegex(ValueError, "No training candidate"):
            rank_training([row(dd="20.001")])
        with self.assertRaisesRegex(ValueError, "nonempty and unique"):
            rank_training([])
        with self.assertRaisesRegex(ValueError, "nonempty and unique"):
            rank_training([row(), row()])


class TemporalPartitionTest(unittest.TestCase):
    def test_split_boundary_and_future_changes_preserve_training_history(self):
        rows = [{"time": "2024-11-07 10:00:00", "close": D("2")},
                {"time": "2025-11-06 15:00:00", "close": D("3")},
                {"time": "2025-11-07 10:00:00", "close": D("4")},
                {"time": "2026-11-06 15:00:00", "close": D("5")}]
        training, validation = split_bars(rows)
        self.assertEqual(rows[:2], training)
        self.assertEqual(rows[2:], validation)
        modified = copy.deepcopy(rows)
        for item in modified[2:]:
            item["close"] *= 100
        modified.append({"time": "2030-01-02 10:00:00", "close": D("0.01")})
        self.assertEqual(training, split_bars(modified)[0])

    def test_empty_temporal_partition_is_rejected(self):
        for rows in ([], [{"time": "2025-11-06 15:00:00"}], [{"time": "2025-11-07 10:00:00"}]):
            with self.assertRaisesRegex(ValueError, "Both temporal partitions"):
                split_bars(rows)


if __name__ == "__main__":
    unittest.main()
