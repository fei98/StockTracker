"""Verify four-dimensional coverage, physical neighbors, and training-only ranking."""

import copy
from dataclasses import replace
import unittest

from model import D
import stock_five_parameters as search


def pair(ret="10", drawdown="10", slipped_return=None, slipped_drawdown=None):
    return [{"return_pct": D(ret), "max_drawdown_pct": D(drawdown), "valid": True},
            {"return_pct": D(slipped_return or ret),
             "max_drawdown_pct": D(slipped_drawdown or drawdown), "valid": True}]


def isolated_training(point, own=None, neighbor=None):
    training = {other: copy.deepcopy(neighbor or pair()) for other in search.coarse_neighbors(point)}
    training[point] = copy.deepcopy(own or pair())
    return training


class FourDimensionalCoverageTest(unittest.TestCase):
    def test_coarse_grid_has_1056_unique_admissible_points(self):
        points = search.coarse_points()
        self.assertEqual(1056, len(points))
        self.assertEqual(len(points), len(set(points)))
        self.assertEqual(len(points), len({point.key for point in points}))
        self.assertTrue(all(search.valid(point) for point in points))
        self.assertEqual(set(search.AMOUNTS), {point.amount for point in points})

    def test_all_three_previous_parameter_sets_remain_present(self):
        points = set(search.coarse_points())
        for values in ((10000, 30000, "3", 4000), (15000, 20000, "4", 4000),
                       (15000, 30000, "5", 4000)):
            low, high, step, amount = values
            self.assertIn(search.Point(low, high, D(step), amount), points)

    def test_order_amount_changes_without_altering_capital_or_initial_position(self):
        a = search.Point(10000, 45000, D("3.5"), 2000)
        b = replace(a, amount=8000)
        self.assertEqual(D("2000"), a.config().amount)
        self.assertEqual(D("8000"), b.config().amount)
        for config in (a.config(), b.config(tick=1)):
            self.assertEqual(D("50000"), config.capital)
            self.assertEqual(D("10000"), config.initial_amount)
            self.assertEqual(D("45000"), config.upper)
            self.assertEqual(D("1.035"), config.ratio)
        self.assertEqual(1, b.config(tick=1).slippage_ticks)

    def test_candidate_ids_keep_fractional_steps_and_amount_identity(self):
        p = search.Point(10000, 30000, D("3.50"), 3000)
        self.assertEqual("L10000_U30000_S3p5_A3000", p.key)
        self.assertEqual(p.key, replace(p, step=D("3.5")).key)
        self.assertNotEqual(p.key, replace(p, amount=4000).key)

    def test_bounds_reject_equal_cost_edges_and_out_of_range_fine_points(self):
        p = search.Point(10000, 30000, D("3"), 4000)
        invalid = [replace(p, lower=-2500), replace(p, lower=32500), replace(p, upper=7500),
                   replace(p, upper=52500), replace(p, lower=30000), replace(p, step=D("0.5")),
                   replace(p, step=D("10.5")), replace(p, amount=0), replace(p, amount=11000)]
        self.assertTrue(all(not search.valid(point) for point in invalid))
        self.assertTrue(search.valid(replace(p, amount=1000)))
        self.assertTrue(search.valid(replace(p, amount=10000)))


class NeighborhoodTest(unittest.TestCase):
    def test_coarse_neighbors_move_one_declared_axis_including_amount(self):
        p = search.Point(15000, 30000, D("4"), 4000)
        nearby = search.coarse_neighbors(p)
        self.assertEqual(8, len(nearby))
        self.assertEqual(len(nearby), len(set(nearby)))
        self.assertNotIn(p, nearby)
        self.assertTrue(all(search.coarse_distance(p, other) == 1 for other in nearby))
        self.assertIn(replace(p, amount=2000), nearby)
        self.assertIn(replace(p, amount=6000), nearby)
        self.assertNotIn(replace(p, lower=10000, amount=6000), nearby)

    def test_irregular_coarse_steps_use_adjacent_declared_values(self):
        p = search.Point(15000, 40000, D("8"), 6000)
        steps = {other.step for other in search.coarse_neighbors(p) if other.step != p.step}
        self.assertEqual({D("6"), D("10")}, steps)
        self.assertNotIn(replace(p, step=D("7")), search.coarse_neighbors(p))

    def test_fine_neighbors_use_fixed_physical_offsets(self):
        p = search.Point(15000, 30000, D("4"), 4000)
        expected = {replace(p, lower=12500), replace(p, lower=17500), replace(p, upper=27500),
                    replace(p, upper=32500), replace(p, step=D("3.5")), replace(p, step=D("4.5")),
                    replace(p, amount=3000), replace(p, amount=5000)}
        self.assertEqual(expected, set(search.fine_neighbors(p)))

    def test_fine_corners_do_not_expand_search_bounds(self):
        p = search.Point(0, 50000, D("10"), 10000)
        expected = {replace(p, lower=2500), replace(p, upper=47500),
                    replace(p, step=D("9.5")), replace(p, amount=9000)}
        self.assertEqual(expected, set(search.fine_neighbors(p)))

    def test_fine_centers_cover_four_axes_and_deduplicate_shared_regions(self):
        seed = search.Point(15000, 30000, D("5"), 4000)
        centers = search.fine_centers([seed])
        self.assertEqual(81, len(centers))
        self.assertEqual(centers, search.fine_centers([seed, seed]))
        self.assertIn(replace(seed, lower=12500, upper=32500, step=D("4.5"), amount=5000), centers)
        corner = search.fine_centers([search.Point(0, 50000, D("10"), 10000)])
        self.assertEqual(16, len(corner))
        self.assertTrue(all(search.valid(point) for point in corner))

    def test_training_seed_regions_keep_coarse_distance_of_at_least_two(self):
        a = search.Point(0, 20000, D("2"), 2000)
        adjacent = replace(a, amount=4000)
        b = replace(a, amount=6000)
        adjacent_b = replace(a, amount=8000)
        c = search.Point(10000, 30000, D("4"), 4000)
        chosen = search.choose_seeds([{"point": p} for p in (a, adjacent, b, adjacent_b, c)])
        self.assertEqual([a, b, c], chosen)
        self.assertTrue(all(search.coarse_distance(left, right) >= 2
                            for index, left in enumerate(chosen) for right in chosen[index + 1:]))


class TrainingMetricsTest(unittest.TestCase):
    def test_risk_gate_uses_both_slippages_and_includes_exact_twenty(self):
        self.assertEqual((D("8"), D("20"), True), search.pair_metrics(pair("10", "19", "8", "20")))
        self.assertFalse(search.pair_metrics(pair("10", "19", "8", "20.01"))[2])
        self.assertFalse(search.pair_metrics(pair("10", "20.01", "8", "19"))[2])

    def test_budget_invalid_scenario_is_rejected_before_metrics_are_read(self):
        invalid = [{"valid": False}, {"return_pct": D("100"), "max_drawdown_pct": D("1")}]
        self.assertEqual((D("-Infinity"), D("Infinity"), False), search.pair_metrics(invalid))
        invalid.reverse()
        self.assertEqual((D("-Infinity"), D("Infinity"), False), search.pair_metrics(invalid))

    def test_missing_neighbor_training_result_is_not_silently_omitted(self):
        p = search.Point(15000, 30000, D("4"), 4000)
        with self.assertRaisesRegex(ValueError, "neighbor has no training result"):
            search.rank([p], {p: pair()}, search.fine_neighbors)

    def test_fewer_than_four_actual_neighbors_cannot_self_certify(self):
        p = search.Point(15000, 30000, D("4"), 4000)
        nearby = search.fine_neighbors(p)[:3]
        training = {other: pair("100", "1") for other in [p] + nearby}
        self.assertEqual([], search.rank([p], training, lambda point: nearby))

    def test_duplicate_or_self_neighbors_cannot_satisfy_the_minimum(self):
        p = search.Point(15000, 30000, D("4"), 4000)
        q = replace(p, amount=3000)
        training = {p: pair("100", "1"), q: pair("100", "1")}
        for nearby in ([p, p, p, p], [q, q, q, q]):
            with self.subTest(neighbors=nearby):
                with self.assertRaises(ValueError):
                    search.rank([p], training, lambda point: nearby)

    def test_no_risk_eligible_result_does_not_relax_the_gate(self):
        p = search.Point(15000, 30000, D("4"), 4000)
        training = isolated_training(p, pair("100", "25"), pair("100", "25"))
        ranked = search.rank([p], training, search.coarse_neighbors)
        self.assertEqual([], ranked)
        with self.assertRaisesRegex(ValueError, "No training seed"):
            search.choose_seeds(ranked)

    def test_amount_neighbor_can_determine_neighborhood_risk_admissibility(self):
        p = search.Point(15000, 30000, D("4"), 4000)
        nearby = search.coarse_neighbors(p)
        invalid = [{"valid": False}, {"valid": False}]
        training = {other: copy.deepcopy(invalid) for other in nearby}
        training[p] = pair()
        nonamount = [other for other in nearby if other.amount == p.amount]
        for other in nonamount[:3]:
            training[other] = pair()
        self.assertEqual([], search.rank([p], training, search.coarse_neighbors))
        training[replace(p, amount=2000)] = pair()
        chosen = search.rank([p], training, search.coarse_neighbors)
        self.assertEqual(p, chosen[0]["point"])
        self.assertEqual(D(5) / 9, chosen[0]["neighbor_pass_fraction"])


class TrainingOrderingTest(unittest.TestCase):
    def setUp(self):
        self.a = search.Point(0, 20000, D("1"), 2000)
        self.b = search.Point(30000, 50000, D("10"), 8000)

    def rank(self, own_a=None, own_b=None, neighbors_a=None, neighbors_b=None, points=None):
        training = isolated_training(self.a, own_a, neighbors_a)
        training.update(isolated_training(self.b, own_b, neighbors_b))
        return search.rank(points or [self.a, self.b], training, search.coarse_neighbors), training

    def test_stable_neighborhood_beats_an_isolated_high_return(self):
        ranked, _ = self.rank(pair("100", "5"), pair("8", "5"), pair("1", "5"), pair("8", "5"))
        self.assertEqual(self.b, ranked[0]["point"])

    def test_own_worst_slippage_return_breaks_equal_neighbor_medians(self):
        ranked, _ = self.rank(pair("50", "5", "20", "5"), pair("40", "5", "30", "5"))
        self.assertEqual(D("10"), ranked[0]["neighbor_median_worst_return"])
        self.assertEqual(self.b, ranked[0]["point"])
        self.assertEqual(D("30"), ranked[0]["worst_return"])

    def test_lower_worst_drawdown_breaks_equal_returns_and_neighborhoods(self):
        ranked, _ = self.rank(pair("20", "5", "20", "11"), pair("20", "5", "20", "10"))
        self.assertEqual(self.b, ranked[0]["point"])

    def test_point_order_breaks_complete_tie_deterministically(self):
        forward, _ = self.rank()
        backward, _ = self.rank(points=[self.b, self.a])
        self.assertEqual([self.a, self.b], [row["point"] for row in forward])
        self.assertEqual(forward, backward)

    def test_validation_and_full_period_values_cannot_change_training_rank(self):
        ranked, training = self.rank(pair("20", "5"), pair("25", "5"))
        before = copy.deepcopy(training)
        for point, summaries in training.items():
            for summary in summaries:
                summary["validation_return_pct"] = D("9999") if point == self.a else D("-9999")
                summary["full_return_pct"] = D("9999") if point == self.a else D("-9999")
        self.assertEqual(ranked, search.rank([self.a, self.b], training, search.coarse_neighbors))
        for point in before:
            self.assertEqual(before[point][0]["return_pct"], training[point][0]["return_pct"])

    def test_ranking_does_not_mutate_training_metrics(self):
        _, training = self.rank(pair("20", "5"), pair("25", "5"))
        before = copy.deepcopy(training)
        search.rank([self.a, self.b], training, search.coarse_neighbors)
        self.assertEqual(before, training)


if __name__ == "__main__":
    unittest.main()
