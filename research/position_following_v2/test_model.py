"""Behavioral tests for the independent position-following experiment."""

import copy
from dataclasses import replace
from decimal import Decimal
import unittest

from model import (
    BrokerLot, Config, Grid, Lot, Phase, Signal, State, assert_state,
    decide, execute, fill_price, initialize, round_quantity,
)


D = Decimal
EARLY = "2026-05-01"
SIGNAL_DATE = "2026-05-05"
FILL_DATE = "2026-05-06"


def lot(lot_id, grid, price, qty, date=EARLY, fee="0"):
    return Lot(lot_id, grid, D(price), qty, qty, date, D(fee))


def portfolio(lots, cash="10000", phase=Phase.NORMAL, broker=None):
    return State(
        cash=D(cash), phase=phase, lots=lots,
        broker_lots=broker if broker is not None else [BrokerLot(x.buy_date, x.qty) for x in lots],
        next_id=max((x.lot_id for x in lots), default=0) + 1,
    )


class ModelTest(unittest.TestCase):
    def setUp(self):
        self.config = Config(commission_rate=D("0"), minimum_commission=D("0"))

    def order(self, state, direction, grid, price, date=SIGNAL_DATE):
        signal, block = decide(state, date, direction, grid, D(price), self.config)
        self.assertIsNone(block)
        return signal

    def fill(self, state, direction, grid, price, date=FILL_DATE, signal_date=SIGNAL_DATE):
        signal = self.order(state, direction, grid, price, signal_date)
        trade, block = execute(state, signal, D(price), date, self.config)
        self.assertIsNone(block)
        self.assertIsNotNone(trade)
        return trade

    def test_initial_position_is_one_occupied_grid_without_initial_phase_switch(self):
        state, grid = initialize(FILL_DATE, D("3.9"), self.config)
        self.assertEqual(2600, state.qty)
        self.assertEqual(D("10140"), state.cost)
        self.assertEqual(Phase.NORMAL, state.phase)
        self.assertTrue(state.occupied(0))
        self.assertEqual(0, grid.visited)
        self.assertEqual(0, state.settled_qty(FILL_DATE))
        self.assertEqual(2600, state.settled_qty("2026-05-07"))
        self.assertEqual(self.config.capital, state.cash + state.cost)

        rounded_below, _ = initialize(FILL_DATE, D("4.1"), self.config)
        self.assertLess(rounded_below.cost, self.config.lower)
        self.assertEqual(Phase.NORMAL, rounded_below.phase)

    def test_whole_buy_crosses_upper_and_enters_downward_reduction(self):
        state = portfolio([lot(1, 0, "10", 2800)])
        trade = self.fill(state, "DOWN", -1, "10")
        self.assertEqual(400, trade["qty"])
        self.assertEqual(D("32000"), state.cost)
        self.assertEqual(Phase.DOWN_REDUCTION, state.phase)
        self.assertEqual("NORMAL", trade["phase_before"])
        self.assertEqual("DOWN_REDUCTION", trade["phase_after"])

    def test_reverse_reduction_removes_highest_cost_and_crosses_middle_in_full(self):
        state = portfolio([lot(1, 0, "12", 2000)], phase=Phase.DOWN_REDUCTION)
        trade = self.fill(state, "DOWN", -1, "10")
        self.assertEqual("DOWN_REDUCTION_SELL", trade["reason"])
        self.assertEqual(400, trade["qty"])
        self.assertEqual(D("19200"), state.cost)
        self.assertEqual(D("-800"), trade["gross_profit"])
        self.assertEqual(Phase.NORMAL, state.phase)
        next_signal, block = decide(state, FILL_DATE, "DOWN", -2, D("9"), self.config)
        self.assertIsNone(block)
        self.assertEqual("BUY", next_signal.side)

    def test_reduction_keeps_its_phase_while_above_middle(self):
        state = portfolio([lot(1, 0, "10", 2900)], phase=Phase.DOWN_REDUCTION)
        trade = self.fill(state, "DOWN", -1, "8")
        self.assertEqual(D("24000"), trade["cost_after"])
        self.assertEqual(Phase.DOWN_REDUCTION, state.phase)

    def test_reduction_restores_normal_at_exact_middle(self):
        state = portfolio([lot(1, 0, "10", 2400)], phase=Phase.DOWN_REDUCTION)
        self.fill(state, "DOWN", -1, "10")
        self.assertEqual(self.config.middle, state.cost)
        self.assertEqual(Phase.NORMAL, state.phase)

    def test_reverse_reduction_does_not_automatically_rebuy_newly_freed_capacity(self):
        state = portfolio([lot(1, 0, "10", 2900)], phase=Phase.DOWN_REDUCTION)
        self.fill(state, "DOWN", -1, "8")
        signal, block = decide(state, FILL_DATE, "DOWN", -2, D("7.5"), self.config)
        self.assertIsNone(block)
        self.assertEqual("SELL", signal.side)
        self.assertEqual("DOWN_REDUCTION_SELL", signal.reason)

    def test_upward_whole_sale_can_cross_lower_boundary(self):
        state = portfolio([lot(1, 0, "10", 1100)])
        trade = self.fill(state, "UP", 1, "11")
        self.assertEqual(400, trade["qty"])
        self.assertEqual(D("7000"), state.cost)
        self.assertEqual(Phase.UP_FOLLOW, state.phase)

    def test_upward_follow_continues_until_crossing_middle_then_resumes_profit_sales(self):
        state = portfolio([lot(1, 0, "10", 1700)], phase=Phase.UP_FOLLOW)
        trade = self.fill(state, "UP", 1, "11")
        self.assertEqual("UP_FOLLOW_BUY", trade["reason"])
        self.assertEqual(D("21400"), state.cost)
        self.assertEqual(Phase.NORMAL, state.phase)
        next_signal, block = decide(state, FILL_DATE, "UP", 2, D("12"), self.config)
        self.assertIsNone(block)
        self.assertEqual("SELL", next_signal.side)

    def test_upward_follow_below_middle_keeps_its_phase(self):
        state = portfolio([lot(1, 0, "10", 700)], phase=Phase.UP_FOLLOW)
        self.fill(state, "UP", 1, "11")
        self.assertEqual(D("11400"), state.cost)
        self.assertEqual(Phase.UP_FOLLOW, state.phase)

    def test_upward_follow_restores_normal_at_exact_middle(self):
        state = portfolio([lot(1, 0, "8", 2000)], phase=Phase.UP_FOLLOW)
        self.fill(state, "UP", 1, "10")
        self.assertEqual(self.config.middle, state.cost)
        self.assertEqual(Phase.NORMAL, state.phase)

    def test_rebound_without_profitable_shares_waits_and_preserves_downward_phase(self):
        state = portfolio([lot(1, 0, "12", 2000)], phase=Phase.DOWN_REDUCTION)
        snapshot = copy.deepcopy(state)
        signal, block = decide(state, SIGNAL_DATE, "UP", -1, D("10"), self.config)
        self.assertEqual("REBOUND_PROFIT_SELL", signal.reason)
        self.assertEqual("INSUFFICIENT_PROFITABLE_SHARES", block)
        self.assertEqual(snapshot, state)
        next_signal, next_block = decide(state, SIGNAL_DATE, "DOWN", -2, D("9"), self.config)
        self.assertIsNone(next_block)
        self.assertEqual("DOWN_REDUCTION_SELL", next_signal.reason)

    def test_rebound_can_sell_lowest_profitable_shares_without_resetting_reduction(self):
        state = portfolio([
            lot(1, 0, "12", 2000), lot(2, -1, "8", 1000),
        ], phase=Phase.DOWN_REDUCTION)
        trade = self.fill(state, "UP", 1, "10")
        self.assertEqual(2, trade["matched_lots"][0]["lot_id"])
        self.assertEqual(D("28800"), state.cost)
        self.assertEqual(Phase.DOWN_REDUCTION, state.phase)

    def test_pullback_in_following_stage_cannot_rebuy_an_occupied_grid(self):
        state = portfolio([lot(1, 0, "10", 1000)], phase=Phase.UP_FOLLOW)
        signal, block = decide(state, SIGNAL_DATE, "DOWN", 0, D("10"), self.config)
        self.assertEqual("BUY", signal.side)
        self.assertEqual("PULLBACK_BUY", signal.reason)
        self.assertEqual("OCCUPIED_GRID", block)
        self.assertEqual(Phase.UP_FOLLOW, state.phase)

    def test_pullback_in_following_stage_can_buy_a_genuinely_empty_grid(self):
        state = portfolio([lot(1, 0, "10", 1000)], phase=Phase.UP_FOLLOW)
        trade = self.fill(state, "DOWN", -1, "9")
        self.assertEqual("PULLBACK_BUY", trade["reason"])
        self.assertTrue(state.occupied(-1))
        self.assertEqual(Phase.UP_FOLLOW, state.phase)

    def test_partial_sale_keeps_grid_occupied_until_final_shares_are_sold(self):
        state = portfolio([lot(1, 0, "10", 1000)])
        self.fill(state, "UP", 1, "20")
        self.assertEqual(800, state.qty)
        self.assertTrue(state.occupied(0))
        _, block = decide(state, FILL_DATE, "DOWN", 0, D("10"), self.config)
        self.assertEqual("OCCUPIED_GRID", block)

        sale = Signal(FILL_DATE, 2, "UP", "SELL", "NORMAL_UP_SELL", D("5"), state.phase)
        trade, block = execute(state, sale, D("5"), "2026-05-07", self.config)
        self.assertIsNone(trade)
        self.assertEqual("INSUFFICIENT_PROFITABLE_SHARES", block)

        reduction = portfolio([lot(1, 0, "10", 400)], phase=Phase.DOWN_REDUCTION)
        self.fill(reduction, "DOWN", -1, "10")
        self.assertFalse(reduction.occupied(0))
        next_signal, block = decide(reduction, FILL_DATE, "DOWN", 0, D("9"), self.config)
        self.assertIsNone(block)
        self.assertEqual("BUY", next_signal.side)

    def test_full_sale_can_span_lots_without_releasing_a_partially_consumed_grid(self):
        state = portfolio([lot(1, -1, "5", 100), lot(2, 0, "6", 1000)])
        trade = self.fill(state, "UP", 1, "10")
        self.assertEqual([100, 300], [x["qty"] for x in trade["matched_lots"]])
        self.assertFalse(state.occupied(-1))
        self.assertTrue(state.occupied(0))
        self.assertEqual(700, state.qty)

    def test_not_enough_profitable_shares_cancels_whole_sale_instead_of_selling_losers(self):
        state = portfolio([lot(1, -1, "5", 100), lot(2, 0, "12", 1000)])
        signal, block = decide(state, SIGNAL_DATE, "UP", 1, D("10"), self.config)
        self.assertEqual("INSUFFICIENT_PROFITABLE_SHARES", block)
        snapshot = copy.deepcopy(state)
        trade, block = execute(state, signal, D("10"), FILL_DATE, self.config)
        self.assertIsNone(trade)
        self.assertEqual("INSUFFICIENT_PROFITABLE_SHARES", block)
        self.assertEqual(snapshot, state)

    def test_cash_shortfall_does_not_fake_upper_boundary_or_change_phase(self):
        state = portfolio([lot(1, 0, "10", 2600)], cash="3990")
        snapshot = copy.deepcopy(state)
        signal, block = decide(state, SIGNAL_DATE, "DOWN", -1, D("10"), self.config)
        self.assertEqual("INSUFFICIENT_CASH", block)
        trade, block = execute(state, signal, D("10"), FILL_DATE, self.config)
        self.assertIsNone(trade)
        self.assertEqual("INSUFFICIENT_CASH", block)
        self.assertEqual(snapshot, state)
        self.assertLess(state.cost, self.config.upper)
        self.assertEqual(Phase.NORMAL, state.phase)

    def test_commission_can_prevent_last_whole_buy_even_when_cost_gap_equals_amount(self):
        config = Config(capital=D("30000"))
        state = portfolio([lot(1, 0, "10", 2600)], cash="4000")
        _, block = decide(state, SIGNAL_DATE, "DOWN", -1, D("10"), config)
        self.assertEqual("INSUFFICIENT_CASH", block)
        self.assertEqual(Phase.NORMAL, state.phase)

    def test_reverse_sale_without_enough_inventory_preserves_the_entire_state(self):
        state = portfolio([lot(1, 0, "10", 100)], phase=Phase.DOWN_REDUCTION)
        signal, block = decide(state, SIGNAL_DATE, "DOWN", -1, D("8"), self.config)
        self.assertEqual("INSUFFICIENT_HOLDINGS", block)
        snapshot = copy.deepcopy(state)
        trade, block = execute(state, signal, D("8"), FILL_DATE, self.config)
        self.assertIsNone(trade)
        self.assertEqual("INSUFFICIENT_HOLDINGS", block)
        self.assertEqual(snapshot, state)

    def test_open_gap_can_cancel_previously_profitable_sale_without_mutating_state(self):
        state = portfolio([lot(1, 0, "10", 2000)])
        signal = self.order(state, "UP", 1, "11")
        snapshot = copy.deepcopy(state)
        trade, block = execute(state, signal, D("9"), FILL_DATE, self.config)
        self.assertIsNone(trade)
        self.assertEqual("INSUFFICIENT_PROFITABLE_SHARES", block)
        self.assertEqual(snapshot, state)

    def test_open_gap_can_cancel_follow_buy_when_highest_lot_is_no_longer_broken(self):
        state = portfolio([lot(1, 0, "10", 1000)], phase=Phase.UP_FOLLOW)
        signal = self.order(state, "UP", 1, "11")
        snapshot = copy.deepcopy(state)
        trade, block = execute(state, signal, D("9.9"), FILL_DATE, self.config)
        self.assertIsNone(trade)
        self.assertEqual("NO_BREAKOUT", block)
        self.assertEqual(snapshot, state)

    def test_next_open_reprices_full_amount_instead_of_using_signal_quote_quantity(self):
        state = portfolio([lot(1, 0, "10", 1000)])
        signal = self.order(state, "DOWN", -1, "10")
        trade, block = execute(state, signal, D("8"), FILL_DATE, self.config)
        self.assertIsNone(block)
        self.assertEqual(500, trade["qty"])
        self.assertEqual(D("4000"), trade["amount"])
        self.assertEqual(-1, state.lots[-1].grid)

    def test_execution_is_strictly_after_signal_date(self):
        state = portfolio([lot(1, 0, "10", 1000)])
        signal = self.order(state, "DOWN", -1, "9")
        with self.assertRaisesRegex(ValueError, "later date"):
            execute(state, signal, D("9"), SIGNAL_DATE, self.config)

    def test_stale_phase_order_is_rejected(self):
        state = portfolio([lot(1, 0, "10", 1000)])
        signal = self.order(state, "DOWN", -1, "9")
        state.phase = Phase.UP_FOLLOW
        with self.assertRaisesRegex(ValueError, "stale phase"):
            execute(state, signal, D("9"), FILL_DATE, self.config)

    def test_t1_is_an_execution_constraint_instead_of_a_close_signal_constraint(self):
        state = portfolio([lot(1, 0, "10", 1000, date=FILL_DATE)])
        signal = self.order(state, "UP", 1, "11")
        snapshot = copy.deepcopy(state)
        trade, block = execute(state, signal, D("11"), FILL_DATE, self.config)
        self.assertIsNone(trade)
        self.assertEqual("INSUFFICIENT_SETTLED_SHARES", block)
        self.assertEqual(snapshot, state)
        trade, block = execute(state, signal, D("11"), "2026-05-07", self.config)
        self.assertIsNone(block)
        self.assertEqual(400, trade["qty"])

    def test_broker_and_accounting_lots_can_differ_without_losing_sellable_quantity(self):
        state = portfolio(
            [lot(1, 0, "8", 500), lot(2, -1, "6", 500, date=FILL_DATE)],
            broker=[BrokerLot(EARLY, 500), BrokerLot(FILL_DATE, 500)],
        )
        trade = self.fill(state, "UP", 1, "10")
        self.assertEqual(2, trade["matched_lots"][0]["lot_id"])
        self.assertEqual(500, state.lots[0].qty)
        self.assertEqual(100, state.lots[1].qty)
        self.assertEqual(100, state.settled_qty(FILL_DATE))
        self.assertEqual(600, state.settled_qty("2026-05-07"))
        assert_state(state)

    def test_cash_cost_and_realized_profit_reconcile_after_buys_and_sales_with_fees(self):
        config = Config()
        state, _ = initialize("2026-05-01", D("10"), config)
        for signal_date, fill_date, direction, grid, price in [
            ("2026-05-01", "2026-05-04", "DOWN", -1, "9"),
            ("2026-05-04", "2026-05-05", "UP", 1, "11"),
            ("2026-05-05", "2026-05-06", "UP", 2, "12"),
        ]:
            signal, block = decide(state, signal_date, direction, grid, D(price), config)
            self.assertIsNone(block)
            trade, block = execute(state, signal, D(price), fill_date, config)
            self.assertIsNone(block)
            self.assertEqual(
                config.capital + state.realized_gross - state.fees,
                state.cash + state.cost,
            )
            self.assertGreaterEqual(state.cash, 0)
            assert_state(state)
        sale = state.trades[2]
        self.assertEqual(D("5.00"), sale["allocated_buy_fee"])
        self.assertEqual(D("790.00"), sale["realized_net"])
        self.assertEqual(Phase.UP_FOLLOW, state.phase)

    def test_normal_sales_require_gross_profit_but_can_have_negative_net_profit(self):
        config = Config()
        state = portfolio([lot(1, 0, "10", 1000, fee="5")])
        signal, block = decide(state, SIGNAL_DATE, "UP", 1, D("10.001"), config)
        self.assertIsNone(block)
        trade, block = execute(state, signal, D("10.001"), FILL_DATE, config)
        self.assertIsNone(block)
        self.assertGreater(trade["gross_profit"], 0)
        self.assertLess(trade["realized_net"], 0)

    def test_fee_is_etf_commission_only(self):
        config = Config()
        self.assertEqual(D("5.00"), config.fee(D("4000")))
        self.assertEqual(D("10.00"), config.fee(D("40000")))

    def test_quantity_rounds_to_nearest_hundred_and_retains_a_minimum_round_lot(self):
        self.assertEqual(800, round_quantity(D("4000"), D("5.2")))
        self.assertEqual(900, round_quantity(D("4250"), D("5")))
        self.assertEqual(100, round_quantity(D("1"), D("100")))

    def test_adverse_slippage_is_applied_in_opposite_directions(self):
        config = replace(self.config, slippage_ticks=2)
        self.assertEqual(D("10.002"), fill_price(D("10"), "BUY", config))
        self.assertEqual(D("9.998"), fill_price(D("10"), "SELL", config))

    def test_initial_purchase_cannot_borrow_money(self):
        with self.assertRaisesRegex(ValueError, "available capital"):
            initialize(FILL_DATE, D("10"), replace(self.config, capital=D("9000")))

    def test_invalid_configuration_and_quotes_are_rejected(self):
        for changes in [
            {"lower": D("30000")}, {"amount": D("0")},
            {"ratio": D("1")}, {"commission_rate": D("-1")},
            {"capital": D("NaN")}, {"slippage_ticks": -1},
        ]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                replace(self.config, **changes)
        for value in [D("0"), D("-1"), D("NaN"), D("Infinity")]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                round_quantity(D("4000"), value)


class GridTest(unittest.TestCase):
    def test_percentage_levels_have_stable_identity_and_etf_precision(self):
        grid = Grid(D("10"))
        self.assertEqual(D("10.300"), grid.level(1))
        self.assertEqual(D("10.609"), grid.level(2))
        self.assertEqual(D("9.709"), grid.level(-1))
        self.assertEqual(("DOWN", -1), grid.observe(D("9.709")))
        self.assertEqual(("UP", 0), grid.observe(D("10")))
        self.assertEqual(D("10"), grid.anchor)

    def test_inside_adjacent_levels_does_not_invent_a_grid_event(self):
        grid = Grid(D("10"))
        self.assertIsNone(grid.observe(D("10.299")))
        self.assertIsNone(grid.observe(D("9.710")))
        self.assertEqual(0, grid.visited)

    def test_large_close_gap_reports_only_the_farthest_crossed_grid(self):
        grid = Grid(D("10"))
        self.assertEqual(("UP", 3), grid.observe(D("11")))
        self.assertEqual(3, grid.visited)
        self.assertIsNone(grid.observe(D("11")))
        self.assertEqual(("DOWN", -3), grid.observe(D("9")))
        self.assertEqual(-3, grid.visited)

    def test_waiting_at_price_still_allows_later_reversal_to_emit_a_new_grid(self):
        grid = Grid(D("10"))
        self.assertEqual(("DOWN", -3), grid.observe(D("9")))
        self.assertEqual(("UP", -1), grid.observe(D("9.8")))
        self.assertEqual(("DOWN", -2), grid.observe(D("9.4")))


if __name__ == "__main__":
    unittest.main()
