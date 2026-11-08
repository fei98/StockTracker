"""Download a frozen market snapshot and run a next-open, close-signal experiment."""

import argparse
from collections import Counter
from dataclasses import asdict, replace
from datetime import datetime, timezone
from decimal import Decimal
import csv
import hashlib
import json
from pathlib import Path
import urllib.request

from model import Config, D, Grid, Phase, ZERO, decide, execute, initialize, round_quantity


ROOT = Path(__file__).resolve().parent
WINDOW_START = "2026-05-07"
WINDOW_END = "2026-11-06"
SOURCE_BASE = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
PHASE_NAMES = {"NORMAL": "正向", "DOWN_REDUCTION": "下跌反向减仓", "UP_FOLLOW": "上涨反向跟随"}
REASON_NAMES = {
    "INITIAL_POSITION": "初始底仓", "NORMAL_DOWN_BUY": "正向下跌买入",
    "NORMAL_UP_SELL": "正向上涨止盈", "DOWN_REDUCTION_SELL": "反向下跌减仓",
    "REBOUND_PROFIT_SELL": "减仓途中反弹止盈", "UP_FOLLOW_BUY": "反向上涨跟随",
    "PULLBACK_BUY": "跟随途中回落空格买入",
    "OCCUPIED_GRID": "格子仍有持仓", "NO_BREAKOUT": "未突破最高剩余买价",
    "INSUFFICIENT_CASH": "现金不足完整一份", "INSUFFICIENT_HOLDINGS": "剩余持仓不足完整一份",
    "INSUFFICIENT_PROFITABLE_SHARES": "盈利批次数量不足完整一份",
    "INSUFFICIENT_SETTLED_SHARES": "T+1可卖数量不足", "END_OF_SAMPLE": "样本结束未成交",
}


def json_default(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, Phase):
        return value.value
    raise TypeError(f"Unsupported JSON value: {type(value)}")


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=json_default), encoding="utf-8")


def get_url(url, timeout=20):
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def normalize(rows, window_start=WINDOW_START, window_end=WINDOW_END):
    result = []
    for row in rows:
        if window_start <= row[0] <= window_end:
            result.append({"date": row[0], "open": D(row[1]), "close": D(row[2]),
                           "high": D(row[3]), "low": D(row[4]), "volume": D(row[5])})
    dates = [bar["date"] for bar in result]
    if dates != sorted(set(dates)) or len(result) < 100:
        raise ValueError("The market snapshot has missing, duplicate, or unsorted history.")
    for bar in result:
        values = [bar[key] for key in ("open", "close", "high", "low", "volume")]
        if not all(value.is_finite() and value > 0 for value in values):
            raise ValueError("Nonfinite or nonpositive market data.")
        if not (bar["low"] <= min(bar["open"], bar["close"]) <=
                max(bar["open"], bar["close"]) <= bar["high"]):
            raise ValueError("OHLC bounds do not agree.")
    return result


def download_snapshot():
    data_dir = ROOT / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    url = SOURCE_BASE + "?param=sz159915,day,2026-04-01,2026-11-06,640,"
    qfq_url = url + "qfq"
    raw = get_url(url)
    adjusted = get_url(qfq_url)
    data_dir.joinpath("tencent_unadjusted.json").write_bytes(raw)
    data_dir.joinpath("tencent_qfq.json").write_bytes(adjusted)
    payload = json.loads(raw)
    qfq_payload = json.loads(adjusted)
    rows = payload["data"]["sz159915"]["day"]
    qfq_rows = qfq_payload["data"]["sz159915"].get("qfqday", [])
    bars, adjusted_bars = normalize(rows), normalize(qfq_rows)
    equal = all(a == b for a, b in zip(bars, adjusted_bars)) and len(bars) == len(adjusted_bars)
    if not equal:
        raise ValueError("Adjusted and raw histories differ; corporate actions need explicit accounting.")
    secondary_url = ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                     "CN_MarketData.getKLineData?symbol=sz159915&scale=240&ma=no&datalen=300")
    secondary = {"url": secondary_url}
    try:
        secondary_raw = get_url(secondary_url, timeout=12)
        data_dir.joinpath("sina_daily.json").write_bytes(secondary_raw)
        secondary_rows = json.loads(secondary_raw)
        indexed = {row["day"][:10]: row for row in secondary_rows}
        mismatches = []
        overlap = 0
        for bar in bars:
            row = indexed.get(bar["date"])
            if row is not None:
                overlap += 1
                if any(abs(D(str(row[key])) - bar[key]) > D("0.0005")
                       for key in ("open", "close", "high", "low")):
                    mismatches.append(bar["date"])
        secondary.update(overlap_days=overlap, mismatch_dates=mismatches,
                         sha256=hashlib.sha256(secondary_raw).hexdigest())
        if mismatches:
            raise ValueError("Independent sources disagree on daily OHLC.")
    except (OSError, ValueError, KeyError, TypeError) as error:
        if isinstance(error, ValueError) and str(error) == "Independent sources disagree on daily OHLC.":
            raise
        secondary["unavailable_reason"] = f"{type(error).__name__}: {error}"
    metadata = {
        "symbol": "159915", "name": "易方达创业板ETF", "window_start": WINDOW_START,
        "window_end": WINDOW_END, "rows": len(bars), "source": url, "adjusted_source": qfq_url,
        "retrieved_at_utc": datetime.now(timezone.utc).isoformat(),
        "raw_sha256": hashlib.sha256(raw).hexdigest(),
        "adjusted_sha256": hashlib.sha256(adjusted).hexdigest(),
        "raw_adjusted_ohlcv_equal": equal, "secondary": secondary,
        "fields": ["date", "open", "close", "high", "low", "volume_in_source_units"],
    }
    write_json(data_dir / "snapshot.json", {"metadata": metadata, "rows": rows})
    write_csv(data_dir / "159915_daily.csv", bars)
    return bars, metadata


def load_snapshot(refresh=False):
    path = ROOT / "data" / "snapshot.json"
    if refresh or not path.exists():
        return download_snapshot()
    snapshot = json.loads(path.read_text(encoding="utf-8"))
    raw = (ROOT / "data" / "tencent_unadjusted.json").read_bytes()
    adjusted = (ROOT / "data" / "tencent_qfq.json").read_bytes()
    if hashlib.sha256(raw).hexdigest() != snapshot["metadata"]["raw_sha256"]:
        raise ValueError("Raw data hash no longer matches the frozen snapshot.")
    if hashlib.sha256(adjusted).hexdigest() != snapshot["metadata"]["adjusted_sha256"]:
        raise ValueError("Adjusted data hash no longer matches the frozen snapshot.")
    bars = normalize(json.loads(raw)["data"]["sz159915"]["day"])
    adjusted_bars = normalize(json.loads(adjusted)["data"]["sz159915"]["qfqday"])
    if bars != adjusted_bars:
        raise ValueError("Adjusted and raw histories differ; corporate actions need explicit accounting.")
    if bars != normalize(snapshot["rows"]):
        raise ValueError("Snapshot rows disagree with the hashed raw source.")
    return bars, snapshot["metadata"]


def select_entry(bars):
    """Select the first two-close decline of 1%-6%, using only an early historical prefix."""
    for index in range(2, min(22, len(bars) - 1)):
        a, b, c = [bars[j]["close"] for j in range(index - 2, index + 1)]
        decline = 1 - c / a
        if a > b > c and D("0.01") <= decline <= D("0.06"):
            return index, index + 1
    raise ValueError("No qualifying initial decline in the first 22 observations.")


def hold_quantity(config, open_price, initial_qty=None):
    price = open_price + D("0.001") * config.slippage_ticks
    qty = initial_qty if initial_qty is not None else int(config.capital / price / 100) * 100
    while qty > 0 and price * qty + config.fee(price * qty) > config.capital:
        qty -= 100
    return qty, config.capital - price * qty - config.fee(price * qty)


def max_drawdown(values, capital):
    peak, worst = capital, ZERO
    for value in values:
        peak = max(peak, value)
        worst = max(worst, (peak - value) / peak)
    return worst * 100


def simulate(bars, config, entry_index=None):
    selection, selected_entry = select_entry(bars)
    entry_index = selected_entry if entry_index is None else entry_index
    initial = bars[entry_index]
    state, grid = initialize(initial["date"], initial["open"], config,
                             signal_date=bars[entry_index - 1]["date"])
    initial_qty = state.qty
    same_qty, same_cash = hold_quantity(config, initial["open"], initial_qty)
    full_qty, full_cash = hold_quantity(config, initial["open"])
    daily, events = [], []
    pending = None
    for index in range(entry_index, len(bars)):
        bar = bars[index]
        if pending is not None:
            trade, block = execute(state, pending, bar["open"], bar["date"], config)
            events.append({"date": bar["date"], "stage": "OPEN", "signal_date": pending.date,
                           "direction": pending.direction, "grid": pending.grid, "side": pending.side,
                           "reason": pending.reason, "phase": pending.phase.value,
                           "price": bar["open"], "status": block or "FILLED"})
            pending = None
        crossing = grid.observe(bar["close"])
        if crossing is not None:
            direction, target = crossing
            signal, block = decide(state, bar["date"], direction, target, bar["close"], config)
            events.append({"date": bar["date"], "stage": "CLOSE", "signal_date": bar["date"],
                           "direction": direction, "grid": target, "side": signal.side,
                           "reason": signal.reason, "phase": state.phase.value,
                           "price": bar["close"], "status": block or "QUEUED"})
            if not block:
                if index + 1 < len(bars):
                    pending = signal
                else:
                    events[-1]["status"] = "END_OF_SAMPLE"
        market_value = bar["close"] * state.qty
        equity = state.cash + market_value
        identity = config.capital + state.realized_gross + market_value - state.cost - state.fees
        if equity != identity:
            raise AssertionError("Equity does not reconcile with profit, inventory, and fees.")
        daily.append({"date": bar["date"], "close": bar["close"], "cash": state.cash,
                      "qty": state.qty, "position_cost": state.cost, "market_value": market_value,
                      "equity": equity, "phase": state.phase.value, "visited_grid": grid.visited,
                      "occupied_grids": len(state.lots), "fees_paid": state.fees,
                      "realized_gross": state.realized_gross, "unrealized_gross": market_value - state.cost,
                      "same_initial_hold": same_cash + same_qty * bar["close"],
                      "full_buy_hold": full_cash + full_qty * bar["close"]})
    trade_dates = [trade["date"] for trade in state.trades]
    if len(trade_dates) != len(set(trade_dates)):
        raise AssertionError("More than one fill occurred on a single trading date.")
    last = daily[-1]
    realized_net = sum((trade["realized_net"] for trade in state.trades), ZERO)
    held_buy_fees = sum((lot.buy_fee * lot.qty / lot.original_qty for lot in state.lots), ZERO)
    unrealized_net = last["market_value"] - state.cost - held_buy_fees
    if abs(realized_net + unrealized_net - (last["equity"] - config.capital)) > D("0.000001"):
        raise AssertionError("Realized and unrealized net profits do not reconcile.")
    sells = [trade for trade in state.trades if trade["side"] == "SELL"]
    realized_by_reason = {}
    for trade in sells:
        group = realized_by_reason.setdefault(trade["reason"], {"count": 0, "net_profit": ZERO})
        group["count"] += 1
        group["net_profit"] += trade["realized_net"]
    summary = {
        "config": asdict(config), "start_signal_date": bars[selection]["date"],
        "entry_date": initial["date"], "end_date": bars[-1]["date"], "active_days": len(daily),
        "initial_price": state.trades[0]["price"], "initial_qty": initial_qty,
        "initial_cost": state.trades[0]["cost_after"], "grid_anchor": grid.anchor,
        "end_price": last["close"], "equity": last["equity"],
        "net_profit": last["equity"] - config.capital,
        "return_pct": (last["equity"] / config.capital - 1) * 100,
        "max_drawdown_pct": max_drawdown([row["equity"] for row in daily], config.capital),
        "same_initial_hold_equity": last["same_initial_hold"],
        "same_initial_hold_return_pct": (last["same_initial_hold"] / config.capital - 1) * 100,
        "same_initial_hold_max_drawdown_pct": max_drawdown([row["same_initial_hold"] for row in daily], config.capital),
        "full_buy_hold_equity": last["full_buy_hold"],
        "full_buy_hold_return_pct": (last["full_buy_hold"] / config.capital - 1) * 100,
        "full_buy_hold_max_drawdown_pct": max_drawdown([row["full_buy_hold"] for row in daily], config.capital),
        "full_buy_hold_qty": full_qty, "full_buy_hold_cash": full_cash,
        "end_cash": state.cash, "end_qty": state.qty, "end_cost": state.cost,
        "end_phase": state.phase.value, "end_grid_count": len(state.lots),
        "realized_gross": state.realized_gross, "realized_net": realized_net,
        "unrealized_net": unrealized_net, "fees": state.fees,
        "trade_count": len(state.trades), "buy_count": len(state.trades) - len(sells), "sell_count": len(sells),
        "winning_sells": sum(trade["realized_net"] > 0 for trade in sells),
        "realized_by_reason": realized_by_reason,
        "average_market_value": sum((row["market_value"] for row in daily), ZERO) / len(daily),
        "max_qty": max(row["qty"] for row in daily),
        "min_cash": min(trade["cash_after"] for trade in state.trades),
        "max_cost": max(trade["cost_after"] for trade in state.trades),
        "min_cost": min(trade["cost_after"] for trade in state.trades),
        "max_upper_overshoot": max(ZERO, max(trade["cost_after"] for trade in state.trades) - config.upper),
        "max_lower_undershoot": max(ZERO, config.lower - min(trade["cost_after"] for trade in state.trades)),
        "phase_days": dict(Counter(row["phase"] for row in daily)),
        "reason_counts": dict(Counter(trade["reason"] for trade in state.trades)),
        "blocked_closes": dict(Counter(event["status"] for event in events
                                      if event["stage"] == "CLOSE" and event["status"] not in ("QUEUED", "END_OF_SAMPLE"))),
        "cancelled_opens": dict(Counter(event["status"] for event in events
                                       if event["stage"] == "OPEN" and event["status"] != "FILLED")),
        "transitions": [trade for trade in state.trades if trade["phase_before"] != trade["phase_after"]],
        "final_lots": [asdict(lot) for lot in state.lots],
    }
    return {"summary": summary, "daily": daily, "events": events, "trades": state.trades}


def write_csv(path, rows):
    if not rows:
        return
    with path.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        for row in rows:
            writer.writerow({key: json.dumps(value, ensure_ascii=False, default=json_default)
                             if isinstance(value, (list, dict)) else value for key, value in row.items()})


def plot(result, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates

    plt.rcParams.update({"font.sans-serif": ["Microsoft YaHei", "SimHei", "DejaVu Sans"],
                         "axes.unicode_minus": False, "font.size": 10})
    rows, trades = result["daily"], result["trades"]
    dates = [datetime.fromisoformat(row["date"]) for row in rows]
    fig, axes = plt.subplots(4, 1, figsize=(13, 14), sharex=True,
                             gridspec_kw={"height_ratios": [1.15, 1.2, 1, 1]})
    fig.suptitle("159915 仓位跟随回测 | 3%网格 · 成本1万—3万元 · 总资金5万元", fontsize=16, y=.985)
    axes[0].plot(dates, [float(row["close"]) for row in rows], color="#334155", linewidth=1.5, label="收盘价")
    for side, marker, color, label in [("BUY", "^", "#dc2626", "买入（次日开盘）"),
                                        ("SELL", "v", "#16a34a", "卖出（次日开盘）")]:
        subset = [trade for trade in trades if trade["side"] == side]
        axes[0].scatter([datetime.fromisoformat(trade["date"]) for trade in subset],
                        [float(trade["price"]) for trade in subset], marker=marker,
                        s=38, color=color, label=label, zorder=3)
    axes[0].set_ylabel("价格 / 元")
    for key, color, label in [("equity", "#2563eb", "新模型"),
                               ("same_initial_hold", "#d97706", "相同初始底仓＋现金持有"),
                               ("full_buy_hold", "#64748b", "全仓买入持有")]:
        axes[1].plot(dates, [float(row[key]) for row in rows], color=color, label=label, linewidth=1.7)
    axes[1].axhline(50000, linestyle=":", color="#94a3b8", linewidth=1)
    axes[1].set_ylabel("总权益 / 元")
    axes[2].plot(dates, [float(row["position_cost"]) for row in rows], color="#7c3aed", label="剩余原始买入成本")
    for value, label, color in [(10000, "下限1万", "#94a3b8"), (20000, "中线2万", "#0d9488"),
                                 (30000, "上限3万", "#dc2626")]:
        axes[2].axhline(value, linestyle="--", linewidth=1, color=color, label=label)
    for phase, color in [("DOWN_REDUCTION", "#fee2e2"), ("UP_FOLLOW", "#dbeafe")]:
        axes[2].fill_between(dates, 0, 1, where=[row["phase"] == phase for row in rows],
                             transform=axes[2].get_xaxis_transform(), color=color, alpha=.55,
                             label=PHASE_NAMES[phase])
    axes[2].set_ylabel("成本 / 元")
    axes[3].plot(dates, [float(row["cash"]) for row in rows], color="#0891b2", label="可用现金")
    axes[3].set_ylabel("现金 / 元")
    twin = axes[3].twinx()
    twin.step(dates, [row["qty"] for row in rows], where="post", color="#9333ea", alpha=.7, label="持仓份额")
    twin.set_ylabel("持仓 / 份")
    twin.legend(loc="upper right", frameon=False)
    for axis in axes:
        axis.grid(alpha=.16)
        axis.legend(loc="upper left", frameon=False, fontsize=9, ncol=2)
        axis.spines[["top", "right"]].set_visible(False)
    month_interval = 3 if (dates[-1] - dates[0]).days > 366 else 1
    axes[-1].xaxis.set_major_locator(mdates.MonthLocator(interval=month_interval))
    axes[-1].xaxis.set_major_formatter(mdates.DateFormatter("%Y-%m"))
    fig.text(.06, .012, "收盘产生信号，下一交易日开盘成交；每日最多一笔。权益含现金与未卖出持仓，已扣佣金。", color="#475569")
    fig.tight_layout(rect=[0, .035, 1, .965], h_pad=1.7)
    fig.savefig(path, dpi=160, facecolor="white")
    plt.close(fig)


def money(value):
    return f"{value:,.2f}"


def pct(value):
    return f"{value:.2f}%"


def write_report(result, scenarios, metadata):
    s = result["summary"]
    lines = ["# 159915 正向／反向仓位跟随：独立模型与半年回测", "",
             "> 研究日期：2026年11月7日。主参数及阶段规则先确定，再运行行情；未用结果挑参数。App源码未改动。", "",
             "## 1. 先看主回测结果", "",
             f"在 {s['entry_date']} 建仓至 {s['end_date']} 的 {s['active_days']} 个交易日内，"
             f"50000元变为 **{money(s['equity'])}元**，净盈亏 **{money(s['net_profit'])}元**，"
             f"收益率 **{pct(s['return_pct'])}**，按每日收盘权益计算的最大回撤 **{pct(s['max_drawdown_pct'])}**。", "",
             "| 方案 | 期末权益 | 收益率 | 最大回撤 |", "|---|---:|---:|---:|"]
    for name, equity, ret, dd in [
        ("新模型", s["equity"], s["return_pct"], s["max_drawdown_pct"]),
        ("相同初始底仓＋现金不动", s["same_initial_hold_equity"], s["same_initial_hold_return_pct"], s["same_initial_hold_max_drawdown_pct"]),
        ("相同总资金全仓买入持有", s["full_buy_hold_equity"], s["full_buy_hold_return_pct"], s["full_buy_hold_max_drawdown_pct"])]:
        lines.append(f"| {name} | {money(equity)}元 | {pct(ret)} | {pct(dd)} |")
    lines += ["", "这两个基准分别回答：主动加减仓是否优于保留同样底仓，以及是否优于一开始全部投入。"
              "全仓基准的风险暴露更高，不能只凭跑赢全仓基准就认定网格有效。", "",
              "![回测曲线](回测曲线.png)", "", "## 2. 已确认参数与执行口径", "",
              "| 项目 | 主回测设定 |", "|---|---|",
              "| 标的 | 159915，易方达创业板ETF |",
              f"| 数据窗口 | {WINDOW_START}—{WINDOW_END}，{metadata['rows']}根真实日线 |",
              "| 初始资金／底仓 | 50000元／约10000元，之后不追加资金 |",
              f"| 实际建仓 | {s['entry_date']} 开盘，{s['initial_price']}元 × {s['initial_qty']}份，裸成本{money(s['initial_cost'])}元 |",
              "| 成本下限／中线／上限 | 10000／20000／30000元；成本为剩余原始买入价×份额 |",
              "| 每次交易 | 约4000元，按实际开盘成交价折成最接近的100份整数倍，最低100份 |",
              "| 边界 | 整笔成交，允许跨过上限、下限和中线；资金／盈利批次／可卖量不足则取消整笔 |",
              "| 价格网格 | 固定锚点×1.03ⁿ，0.001元四舍五入，不随成交偏差重置 |",
              "| 信号／成交 | 收盘到达新格子后判断，下一交易日开盘；每天最多一笔 |",
              "| 主场景费用 | 双边佣金万2.5，每笔最低5元；无印花税及股票过户费；滑点0 |",
              "| T+1 | 实际卖出量不能超过此前交易日买入的券商可卖量 |",
              "| 期末 | 按最后收盘价计价，不强制卖出，不预扣未发生的清仓佣金 |", "",
              "固定比例网格上行相邻档为3%，下行相邻档为1−1/1.03≈2.9126%。"
              "这样上涨和下跌使用相同格子编号；若改用成交价每次±3%，格子会漂移，需另定义占格规则。", "",
              "## 3. 起点怎样选，怎样避免偷看未来", "",
              "预先规定：数据窗口前22根日线中，首次出现连续两日收盘下降，且这两日累计跌幅在1%—6%之间，"
              "就在下一交易日开盘建仓。找不到则报告无法满足起点，不另挑盈利日期。", "",
              f"本次首次识别日为 **{s['start_signal_date']}**，建仓日为 **{s['entry_date']}**。"
              "规则只查看识别日及之前收盘，不把事后局部最高点或最低点当作已知起点。", "",
              "初始约10000元作为一个特殊底仓批次占用0号格；即使整手取整后略低于下限，初始化仍从正向阶段开始。"
              "初始信号不是一次常规网格买入。", "",
              "## 4. 模型状态机", "",
              "| 当前阶段 | 下跌到新格 | 上涨到新格 | 实际成交后的切换 |",
              "|---|---|---|---|",
              "| 正向 | 空格买入 | 最低买价盈利批次优先卖出 | 买后成本≥30000转下跌减仓；卖后成本≤10000转上涨跟随 |",
              "| 下跌反向减仓 | 最高买价批次优先卖出，可亏损 | 有足够盈利批次才止盈，否则等待 | 成本≤20000恢复正向 |",
              "| 上涨反向跟随 | 真正空格可买，已占格等待，不因回落减仓 | 突破最高剩余买价且格子为空才买 | 成本≥20000恢复正向 |", "",
              "阶段只在实际成交后改变。等待及报价反弹不会自行清除原阶段。"
              "到中线的那笔成交完成后才切换，不在同一天同一个信号再做一笔相反交易。", "",
              "格子剩余数量>0即占用；部分卖出不释放，全部卖完才能再次买入。"
              "每笔卖出按当前价格折算约4000元，可依次消耗多个符合条件的批次，也可只消耗某批次的一部分。"
              "盈利只指卖价高于原买价，净利润另扣费用。", "",
              "收盘跨越多格只在最远到达格产生一个候选信号，不按日高低价编造盘中先后。"
              "即使等待，行情访问格也会推进，从而能识别反弹后重新下跌。"
              "次日开盘按已确定的方向执行，并重新检查现金、占格、盈利批次或突破条件；条件失效则取消，不改做另一方向。", "",
              "## 5. 实际交易与阶段切换", "",
              f"共{ s['trade_count'] }笔成交，含初始底仓：买入{s['buy_count']}笔，卖出{s['sell_count']}笔；"
              f"已付佣金{money(s['fees'])}元。", "",
              "| 操作原因 | 成交笔数 |", "|---|---:|"]
    for reason, count in s["reason_counts"].items():
        lines.append(f"| {REASON_NAMES[reason]} | {count} |")
    lines += ["", "| 切换日期 | 操作 | 成交价／份额 | 成交后成本 | 阶段变化 |", "|---|---|---|---:|---|"]
    for trade in s["transitions"]:
        lines.append(f"| {trade['date']} | {REASON_NAMES[trade['reason']]} | {trade['price']} × {trade['qty']} | "
                     f"{money(trade['cost_after'])} | {PHASE_NAMES[trade['phase_before']]} → {PHASE_NAMES[trade['phase_after']]} |")
    if not s["transitions"]:
        lines.append("| — | 样本未触发阶段切换 | — | — | — |")
    lines += ["", "### 等待及撤单", "",
              "| 环节 | 原因 | 次数 |", "|---|---|---:|"]
    for stage, key in [("收盘未发单", "blocked_closes"), ("次日开盘撤单", "cancelled_opens")]:
        for reason, count in s[key].items():
            lines.append(f"| {stage} | {REASON_NAMES.get(reason, reason)} | {count} |")
    lines += ["", "## 6. 期末资金与盈亏核对", "",
              "| 项目 | 数值 |", "|---|---:|",
              f"| 现金 | {money(s['end_cash'])}元 |",
              f"| 持仓 | {s['end_qty']}份，{s['end_grid_count']}个占用格 |",
              f"| 剩余原始买入成本 | {money(s['end_cost'])}元 |",
              f"| 期末市值 | {money(s['end_qty'] * s['end_price'])}元 |",
              f"| 已实现净盈亏（已扣卖出及所配买入费） | {money(s['realized_net'])}元 |",
              f"| 剩余持仓浮动净盈亏（已扣剩余买入费） | {money(s['unrealized_net'])}元 |",
              f"| 最低现金余额 | {money(s['min_cash'])}元 |",
              f"| 持仓成本范围 | {money(s['min_cost'])}—{money(s['max_cost'])}元 |",
              f"| 最多越过上限／低于下限 | {money(s['max_upper_overshoot'])}／{money(s['max_lower_undershoot'])}元 |",
              f"| 期末阶段 | {PHASE_NAMES[s['end_phase']]} |", "",
              "逐日核对：权益＝现金＋持仓市值＝初始资金＋已实现毛盈亏＋浮动毛盈亏−已付全部佣金。"
              "期末还核对已实现净盈亏＋剩余浮动净盈亏＝总净盈亏，避免只报告卖出盈利而遗漏浮亏。", "",
              "## 7. 本轮暴露的策略取舍", "",
              f"**整体账户没有盈利。** 跑赢全仓持有约{pct(s['return_pct'] - s['full_buy_hold_return_pct'])}个百分点，"
              f"但比相同初始底仓＋现金持有少{money(s['same_initial_hold_equity'] - s['equity'])}元。"
              "这段行情先涨后大幅下跌，主动增加持仓带来了更多下跌敞口；不能把较低初始仓位带来的优势全部归于网格。", "",
              "### 已实现损益按操作原因拆分", "",
              "| 卖出类型 | 笔数 | 已实现净盈亏 |", "|---|---:|---:|"]
    for reason, group in s["realized_by_reason"].items():
        lines.append(f"| {REASON_NAMES[reason]} | {group['count']} | {money(group['net_profit'])}元 |")
    lines += ["", "反向减仓把部分浮亏转成已实现亏损，同时减少后续持仓并回收现金。"
              "仅凭这几笔亏损不能断言减仓更差；若要归因，需要另做关闭反向规则、其他条件相同的完整权益对照。"
              "本次没有为改善结果修改任何规则。", "",
              "### 完整一份越界会明显改变底仓", "",
              f"初始成本{money(s['initial_cost'])}元，第一次上涨卖出约4000元后，成本降到6232元，"
              "立即进入上涨反向跟随。也就是说，这套整份规则中的10000元是切换阈值，不能被理解为任何时候都保留10000元底仓。"
              "本轮最低成本低于下限3768元；如果目标是严格保底，下一轮需要修改整份成交与边界的优先级。", "",
              "### 反向回中线仍可能反复补仓", "",
              f"本轮持仓最高达到{s['max_qty']}份，日均市值约{money(s['average_market_value'])}元。"
              "反向减仓到中线后恢复下跌买入，后续继续跌就可能再走一次上限—中线循环。"
              "这保证了持续操作能力，但并未自动控制累计亏损或判断底部；成本区间控制不能代替总权益回撤控制。", "",
              "### 盈利批次不保证扣费后盈利", "",
              "2026-08-18的反弹卖出毛利9.90元，扣买卖费用后净亏0.10元。"
              "这是当前采用裸价盈利条件的直接结果；若改为净盈利门槛，应作为下一版明确规则重新回测。", "",
              "## 8. 执行成本与资金敏感性（未调整模型参数寻找最优）", "",
              "| 场景 | 初始资金 | 期末权益 | 收益率 | 最大回撤 | 成交数 |", "|---|---:|---:|---:|---:|---:|"]
    for label, scenario in [("主场景", result)] + list(scenarios.items()):
        v = scenario["summary"]
        lines.append(f"| {label} | {money(v['config']['capital'])} | {money(v['equity'])} | {pct(v['return_pct'])} | "
                     f"{pct(v['max_drawdown_pct'])} | {v['trade_count']} |")
    lines += ["", "资金30000元仅保留为诊断对照，主方案仍为用户最后确认的50000元。"
              "不同初始资金的收益率分母不同，不能直接据此认定资金多或少的策略更优。", "",
              "## 9. 数据来源与局限", "",
              f"- [腾讯未复权日线]({metadata['source']})：成交和资金核算使用未复权OHLC，原始响应已保存并记录SHA-256。",
              f"- [腾讯前复权交叉检查]({metadata['adjusted_source']})：样本内原始与前复权每日OHLCV一致，未检测到复权差异。"
              "这不是对所有基金公告的逐份审计；若未来数据存在分红或份额折算，程序会拒绝运行，需先显式处理。",
              "- [深交所ETF交易说明](https://investor.szse.cn/warning/activities/fundIntroduction/t20210104_584079.html)：100份整手、"
              "0.001元报价、只计佣金而不计股票印花税。实际券商ETF佣金可能不同。"]
    secondary = metadata["secondary"]
    if "overlap_days" in secondary:
        lines.append(f"- [新浪独立日线]({secondary['url']})：重叠{secondary['overlap_days']}个交易日，"
                     f"OHLC不一致日期{len(secondary['mismatch_dates'])}个；原始响应同样保留。")
    else:
        lines.append(f"- 新浪第二来源本次未取得有效覆盖：`{secondary.get('unavailable_reason', 'unknown')}`；"
                     "不能声称已完成独立数据商核验。")
    lines += ["- 日线收盘采样会漏掉盘中触格后回撤；因此这是所确认的日线执行版本，不能等同盘中实时网格收益。",
              "- 默认次日开盘价可成交，未模拟集合竞价排队、涨跌停封单、停牌或成交冲击；1个最小价位滑点场景仅做粗略压力检查。",
              "- 只有约半年、一个ETF、一个预先定义的起点，不能据此证明长期有效，也不能判断未来低点。",
              "- 成本是记账指标；选择最高／最低价批次不会改变同数量同价卖出的现金流，却会改变剩余成本与后续阶段。",
              "- 成本上下限允许整份越过，实际超出幅度取决于卖出时消耗的原始成本，不保证只偏差几元。", "",
              "## 10. 复现与产物", "", "```powershell",
              "python -m unittest discover -s research/position_following_v2 -p 'test_*.py' -v",
              "python research/position_following_v2/backtest.py",
              "# Re-download only when intentionally refreshing the frozen snapshot:",
              "python research/position_following_v2/backtest.py --refresh-data", "```", "",
              "- `model.py`：纯状态机及格子、费用、T+1、资金核对。",
              "- `backtest.py`：数据快照、确定起点、逐日执行、基准、图表与本报告。",
              "- `data/`：原始行情、规范化日线和数据来源元信息。",
              "- `outputs/trades.csv`：全部成交，含信号日、成交日、格子、匹配批次及阶段前后。",
              "- `outputs/events.csv`：全部跨格事件、等待原因及开盘撤单。",
              "- `outputs/daily.csv`：逐日现金、股数、成本、权益及两个基准。",
              "- `outputs/results.json`：主结果与诊断场景的完整数字。", ""]
    (ROOT / "outputs" / "报告.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh-data", action="store_true")
    args = parser.parse_args()
    bars, metadata = load_snapshot(args.refresh_data)
    config = Config()
    result = simulate(bars, config)
    scenarios = {
        "双边各1个最小价位滑点": simulate(bars, replace(config, slippage_ticks=1)),
        "无最低佣金、费率仍为万2.5": simulate(bars, replace(config, minimum_commission=ZERO)),
        "总资金30000元诊断": simulate(bars, replace(config, capital=D("30000"))),
    }
    output = ROOT / "outputs"
    output.mkdir(parents=True, exist_ok=True)
    for key in ("daily", "trades", "events"):
        write_csv(output / f"{key}.csv", result[key])
    write_json(output / "results.json", {"metadata": metadata, "main": result["summary"],
                                        "scenarios": {label: value["summary"] for label, value in scenarios.items()}})
    for index, value in enumerate(scenarios.values(), 1):
        write_csv(output / f"scenario_{index}_trades.csv", value["trades"])
    plot(result, output / "回测曲线.png")
    write_report(result, scenarios, metadata)
    print(json.dumps({"entry_date": result["summary"]["entry_date"],
                      "main_summary": result["summary"], "output_directory": str(output)},
                     ensure_ascii=True, default=json_default))


if __name__ == "__main__":
    main()
