"""Stock fee, corporate-action, tax-settlement, and accounting behavior tests."""

import copy
from dataclasses import replace
import unittest

from model import D, Phase, Signal, decide, round_quantity, validate_order
from stock_model import (
    ACTIONS, CorporateAction, SettlementLot, StockConfig, StockGrid, StockLot,
    StockState, TaxLot, anniversary, apply_action, assert_stock, dividend_rate,
    exact_bonus, execution_price, record_trade, settle_day, tax_for_net_sale,
)


DAY = "2026-05-25"
OLD = "2026-04-01"


def position(qty=1000, price="20", cash="30000", acquired=OLD, phase=Phase.NORMAL):
    return StockState(cash=D(cash), phase=phase,
                      lots=[StockLot(1, 0, qty, qty, acquired, D(price) * qty, D("5"))],
                      broker_lots=[SettlementLot(acquired, qty)],
                      tax_lots=[TaxLot(acquired, qty)], next_id=2)


def signal(side, day=DAY, grid=1, reason=None):
    direction = "DOWN" if side == "BUY" else "UP"
    reason = reason or ("NORMAL_DOWN_BUY" if side == "BUY" else "NORMAL_UP_SELL")
    return Signal(day + " 10:00:00", grid, direction, side, reason, D("20"), Phase.NORMAL)


class StockFeeTest(unittest.TestCase):
    def test_stock_fees_charge_transfer_both_sides_and_stamp_on_sales_only(self):
        config = StockConfig()
        self.assertEqual({"commission": D("5"), "transfer_fee": D("0.04"), "stamp_tax": D("0")},
                         config.fee_parts(D("4000"), "BUY"))
        self.assertEqual(D("5.04"), config.fee(D("4000"), "BUY"))
        self.assertEqual(D("7.04"), config.fee(D("4000"), "SELL"))
        self.assertEqual(D("76"), config.fee(D("100000"), "SELL"))

    def test_fee_components_round_to_cents_and_unknown_side_is_rejected(self):
        config = StockConfig()
        self.assertEqual(D("0.04"), config.fee_parts(D("4499"))["transfer_fee"])
        self.assertEqual(D("2.25"), config.fee_parts(D("4499"), "SELL")["stamp_tax"])
        with self.assertRaises(ValueError):
            config.fee_parts(D("4000"), "OTHER")

    def test_stock_tick_and_adverse_slippage_are_one_cent(self):
        config = StockConfig(slippage_ticks=1)
        self.assertEqual(D("20.01"), execution_price(D("20"), "BUY", config))
        self.assertEqual(D("19.99"), execution_price(D("20"), "SELL", config))
        self.assertEqual(D("20.24"), execution_price(D("20.235"), "BUY", StockConfig()))

    def test_invalid_stock_tick_and_fee_rates_are_rejected(self):
        for kwargs in ({"tick": D("0")}, {"tick": D("NaN")}, {"stamp_rate": D("-1")},
                       {"transfer_rate": D("Infinity")}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                StockConfig(**kwargs)


class CorporateGridTest(unittest.TestCase):
    def test_successive_actions_transform_every_level_affinely_and_preserve_ids(self):
        grid = StockGrid(D("20"), D("1.04"), visited=-3)
        for action in ACTIONS:
            grid.adjust(action.cash_reference, action.bonus_reference)
        for index in (-8, -3, 0, 4, 12):
            expected = ((D("20") * D("1.04") ** index - D("0.2487976") - D("0.2487916")) /
                        D("1.2985500")).quantize(D("0.01"))
            self.assertEqual(expected, grid.level(index))
        self.assertEqual(-3, grid.visited)
        self.assertEqual(D("20"), grid.anchor)
        self.assertEqual(D("1.04"), grid.ratio)

    def test_cash_adjustment_does_not_rebuild_an_incorrect_geometric_grid(self):
        grid = StockGrid(D("20"), D("1.04"))
        grid.adjust(D("1"), D("0"))
        self.assertEqual(D("19"), grid.level(0))
        self.assertEqual(D("19.80"), grid.level(1))
        self.assertNotEqual((D("19") * D("1.04")).quantize(D("0.01")), grid.level(1))


class CorporateActionTest(unittest.TestCase):
    def test_cash_dividend_adds_income_without_reducing_original_position_cost(self):
        state = position()
        flow = apply_action(state, StockGrid(D("20")), ACTIONS[0])
        self.assertEqual(D("30250"), state.cash)
        self.assertEqual(D("250"), state.dividends)
        self.assertEqual(D("20000"), state.cost)
        self.assertEqual(1000, state.qty)
        self.assertEqual(0, flow["bonus_qty"])
        self.assertEqual(D("250"), state.tax_lots[0].rights[0]["gross"])

    def test_bonus_preserves_cost_and_buy_fees_while_changing_quantity_and_unit_price(self):
        state = position()
        grid = StockGrid(D("20"), visited=-2)
        apply_action(state, grid, ACTIONS[1])
        self.assertEqual(1300, state.qty)
        self.assertEqual(1300, state.lots[0].original_qty)
        self.assertEqual(D("20000"), state.cost)
        self.assertEqual(D("5"), state.lots[0].remaining_buy_fee)
        self.assertEqual(D("20000") / 1300, state.lots[0].price)
        self.assertEqual(-2, grid.visited)
        self.assertTrue(state.occupied(0))
        assert_stock(state, settled_tax=True)

    def test_action_does_not_change_trading_phase_or_reuse_grid(self):
        state = position(phase=Phase.DOWN_REDUCTION)
        apply_action(state, StockGrid(D("20")), ACTIONS[1])
        self.assertEqual(Phase.DOWN_REDUCTION, state.phase)
        self.assertEqual(0, state.lots[0].grid)

    def test_bonus_is_sellable_on_listing_day_but_same_day_purchase_is_frozen(self):
        state = position(qty=100, price="10", cash="50000", acquired="2026-05-22")
        config = StockConfig()
        apply_action(state, StockGrid(D("10")), ACTIONS[1])
        self.assertEqual(130, state.settled_qty(DAY))
        record_trade(state, signal("BUY", grid=1), D("10"), 100, DAY, config)
        self.assertEqual(230, state.qty)
        self.assertEqual(130, state.settled_qty(DAY))
        before = copy.deepcopy(state)
        with self.assertRaises(ValueError):
            record_trade(state, signal("SELL", grid=2), D("11"), 200, DAY, config)
        self.assertEqual(before, state)
        self.assertEqual(230, state.settled_qty("2026-05-26"))

    def test_new_bonus_tax_lot_has_no_historical_cash_dividend_rights(self):
        state = position()
        state.tax_lots[0].rights.append({"ex_date": "2025-05-30", "gross": D("250")})
        apply_action(state, StockGrid(D("20")), ACTIONS[1])
        self.assertEqual(2, len(state.tax_lots))
        self.assertEqual(1000, state.tax_lots[0].qty)
        self.assertEqual(2, len(state.tax_lots[0].rights))
        self.assertEqual(300, state.tax_lots[1].qty)
        self.assertEqual([], state.tax_lots[1].rights)
        self.assertEqual(DAY, state.tax_lots[1].acquired)

    def test_bonus_odd_lot_remains_occupied_after_partial_sale(self):
        state = position(qty=100, price="10", cash="50000")
        apply_action(state, StockGrid(D("10")), ACTIONS[1])
        trade = record_trade(state, signal("SELL", grid=1), D("10"), 100, DAY, StockConfig())
        self.assertEqual(30, state.qty)
        self.assertTrue(state.occupied(0))
        self.assertEqual(100, trade["matched_lots"][0]["qty"])
        self.assertAlmostEqual(float(D("1000") * 30 / 130), float(state.cost), places=9)
        with self.assertRaises(AssertionError):
            record_trade(state, signal("BUY", grid=0), D("10"), 100, DAY, StockConfig())

    def test_fractional_bonus_requires_an_explicit_rule_instead_of_silent_rounding(self):
        self.assertEqual(30, exact_bonus(100, D("0.3")))
        with self.assertRaises(ValueError):
            exact_bonus(101, D("0.3"))

    def test_zero_position_still_adjusts_grid_without_creating_dividend_or_shares(self):
        state = StockState(cash=D("50000"))
        grid = StockGrid(D("20"), visited=2)
        apply_action(state, grid, ACTIONS[1])
        self.assertEqual(D("50000"), state.cash)
        self.assertEqual(0, state.qty)
        self.assertEqual([], state.tax_lots)
        self.assertEqual(2, grid.visited)
        self.assertLess(grid.level(2), D("20") * D("1.03") ** 2)


class DividendTaxTest(unittest.TestCase):
    def test_natural_month_and_year_boundary_are_inclusive(self):
        self.assertEqual(D("0.20"), dividend_rate("2025-01-08", "2025-02-08"))
        self.assertEqual(D("0.10"), dividend_rate("2025-01-08", "2025-02-09"))
        self.assertEqual(D("0.10"), dividend_rate("2025-01-08", "2026-01-08"))
        self.assertEqual(D("0"), dividend_rate("2025-01-08", "2026-01-09"))

    def test_short_month_clamps_month_end_instead_of_using_thirty_days(self):
        self.assertEqual("2025-02-28", anniversary("2025-01-31", months=1).isoformat())
        self.assertEqual(D("0.20"), dividend_rate("2025-01-31", "2025-02-28"))
        self.assertEqual(D("0.10"), dividend_rate("2025-01-31", "2025-03-01"))
        self.assertEqual("2025-02-28", anniversary("2024-02-29", years=1).isoformat())

    def test_tax_fifo_consumes_oldest_shares_with_distinct_rights(self):
        lots = [TaxLot("2024-01-01", 100, [{"ex_date": "2025-05-30", "gross": D("25")}]),
                TaxLot("2025-04-20", 100, [{"ex_date": "2025-05-30", "gross": D("25")}]),
                TaxLot("2025-05-25", 100, [{"ex_date": "2025-05-30", "gross": D("25")}])]
        tax, details = tax_for_net_sale(lots, 250, "2025-06-01")
        self.assertEqual(D("5"), tax)
        self.assertEqual([100, 100, 50], [x["qty"] for x in details])
        self.assertEqual([0, 0, 50], [x.qty for x in lots])
        self.assertEqual(D("12.5"), lots[2].rights[0]["gross"])

    def test_partial_sale_preserves_proportional_dividend_rights_for_remaining_shares(self):
        lots = [TaxLot("2026-05-01", 1000, [{"ex_date": DAY, "gross": D("250")}])]
        first_tax, _ = tax_for_net_sale(lots, 400, DAY)
        second_tax, _ = tax_for_net_sale(lots, 100, DAY)
        self.assertEqual(D("20"), first_tax)
        self.assertEqual(D("5"), second_tax)
        self.assertEqual(500, lots[0].qty)
        self.assertEqual(D("125"), lots[0].rights[0]["gross"])

    def test_potential_end_tax_does_not_mutate_tax_lots(self):
        lots = [TaxLot("2026-05-01", 1000, [{"ex_date": DAY, "gross": D("250")}])]
        before = copy.deepcopy(lots)
        tax, _ = tax_for_net_sale(lots, 1000, "2026-06-02", consume=False)
        self.assertEqual(D("25"), tax)
        self.assertEqual(before, lots)

    def test_same_day_round_trip_has_no_net_reduction_and_no_dividend_tax(self):
        state = position(qty=1000, price="10", cash="50000")
        state.tax_lots[0].rights.append({"ex_date": "2026-05-01", "gross": D("250")})
        original = copy.deepcopy(state.tax_lots)
        config = StockConfig()
        record_trade(state, signal("SELL", grid=1), D("11"), 400, DAY, config)
        record_trade(state, signal("BUY", grid=-1), D("10"), 400, DAY, config)
        cash = state.cash
        self.assertEqual(D("0"), settle_day(state, 1000, DAY))
        self.assertEqual(cash, state.cash)
        self.assertEqual(original, state.tax_lots)

    def test_net_daily_sale_taxes_only_reduction_and_keeps_bought_back_shares_old(self):
        state = position(qty=1000, price="10", cash="50000", acquired="2026-05-01")
        state.tax_lots[0].rights.append({"ex_date": DAY, "gross": D("250")})
        config = StockConfig()
        record_trade(state, signal("SELL", grid=1), D("11"), 400, DAY, config)
        record_trade(state, signal("BUY", grid=-1), D("10"), 200, DAY, config)
        before_cash = state.cash
        self.assertEqual(D("10"), settle_day(state, 1000, DAY))
        self.assertEqual(before_cash - 10, state.cash)
        self.assertEqual(800, state.tax_lots[0].qty)
        self.assertEqual("2026-05-01", state.tax_lots[0].acquired)
        self.assertEqual(D("200"), state.tax_lots[0].rights[0]["gross"])

    def test_net_daily_buy_adds_only_new_net_shares_without_old_dividend_rights(self):
        state = position(qty=1000, price="10", cash="50000")
        config = StockConfig()
        record_trade(state, signal("BUY", grid=-1), D("10"), 400, DAY, config)
        record_trade(state, signal("SELL", grid=1), D("11"), 200, DAY, config)
        self.assertEqual(D("0"), settle_day(state, 1000, DAY))
        self.assertEqual([1000, 200], [x.qty for x in state.tax_lots])
        self.assertEqual(DAY, state.tax_lots[-1].acquired)
        self.assertEqual([], state.tax_lots[-1].rights)


class StockTradeTest(unittest.TestCase):
    def test_shared_decision_and_whole_buy_cross_upper_then_change_phase(self):
        state = position(qty=1400, price="20", cash="30000")
        config = StockConfig()
        order, block = decide(state, DAY, "DOWN", -1, D("20"), config)
        self.assertIsNone(block)
        qty = round_quantity(config.amount, D("20"))
        self.assertEqual(200, qty)
        trade = record_trade(state, order, D("20"), qty, DAY, config)
        self.assertEqual(D("32000"), state.cost)
        self.assertEqual(Phase.DOWN_REDUCTION, state.phase)
        self.assertEqual("DOWN_REDUCTION", trade["phase_after"])

    def test_shared_buy_validation_includes_stock_transfer_fee(self):
        state = StockState(cash=D("4005"))
        order, block = decide(state, DAY, "DOWN", -1, D("20"), StockConfig())
        self.assertEqual("INSUFFICIENT_CASH", block)
        self.assertEqual("INSUFFICIENT_CASH", validate_order(state, order, D("20"), DAY, StockConfig()))

    def test_partial_then_full_sale_preserves_then_releases_grid(self):
        state = position(qty=200, price="10", cash="50000")
        config = StockConfig()
        first = record_trade(state, signal("SELL", grid=1), D("11"), 100, DAY, config)
        self.assertTrue(state.occupied(0))
        self.assertEqual(D("1000"), state.cost)
        self.assertEqual(D("2.5"), first["allocated_buy_fee"])
        record_trade(state, signal("SELL", grid=2), D("11"), 100, DAY, config)
        self.assertFalse(state.occupied(0))
        self.assertEqual(D("0"), state.cost)

    def test_stock_price_ordering_matches_actual_original_cost_after_bonus(self):
        state = position(qty=200, price="20", cash="50000")
        state.lots.append(StockLot(2, 1, 100, 100, OLD, D("1000"), D("5")))
        state.broker_lots.append(SettlementLot(OLD, 100))
        state.tax_lots.append(TaxLot(OLD, 100))
        state.next_id = 3
        apply_action(state, StockGrid(D("20")), ACTIONS[1])
        trade = record_trade(state, signal("SELL", grid=2), D("16"), 100, DAY, StockConfig())
        self.assertEqual(2, trade["matched_lots"][0]["lot_id"])
        self.assertEqual(30, next(x for x in state.lots if x.lot_id == 2).qty)

    def test_cash_cost_profit_and_dividend_tax_reconcile(self):
        config = StockConfig()
        state = StockState(cash=config.capital)
        first = record_trade(state, signal("BUY", day="2026-05-01", grid=0), D("20"), 500,
                             "2026-05-01", config, initial=True)
        state.tax_lots.append(TaxLot("2026-05-01", 500))
        apply_action(state, StockGrid(D("20")), ACTIONS[1])
        opening_qty = state.qty
        sell = record_trade(state, signal("SELL", grid=1), D("17"), 200, DAY, config)
        tax = settle_day(state, opening_qty, DAY)
        mark = D("16")
        equity = state.cash + state.qty * mark
        expected = (config.capital + sell["gross_profit"] + state.qty * mark - state.cost -
                    first["fee"] - sell["fee"] + state.dividends - tax)
        self.assertEqual(expected, equity)
        self.assertGreater(tax, 0)
        self.assertEqual(first["fee"] + sell["fee"], state.fees)
        assert_stock(state, settled_tax=True)


if __name__ == "__main__":
    unittest.main()
