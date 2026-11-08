"""Run the unchanged position-following model on a separate two-year snapshot."""

import argparse
from dataclasses import replace
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

from backtest import (
    Config, D, PHASE_NAMES, REASON_NAMES, SOURCE_BASE, ZERO, get_url,
    json_default, money, normalize, pct, plot, simulate, write_csv, write_json,
)


BASE = Path(__file__).resolve().parent
ROOT = BASE / "two_years"
START = "2024-11-07"
END = "2026-11-06"


def validate_sources(raw, adjusted, secondary):
    raw_rows = json.loads(raw)["data"]["sz159915"]["day"]
    qfq_rows = json.loads(adjusted)["data"]["sz159915"]["qfqday"]
    bars = normalize(raw_rows, START, END)
    if len(bars) < 450 or bars[0]["date"] != START or bars[-1]["date"] != END:
        raise ValueError("The source does not cover the requested two-year window.")
    if bars != normalize(qfq_rows, START, END):
        raise ValueError("Corporate adjustments require explicit accounting before this run.")
    secondary_rows = json.loads(secondary)
    indexed = {row["day"][:10]: row for row in secondary_rows if START <= row["day"][:10] <= END}
    if len(indexed) != len(bars) or set(indexed) != {bar["date"] for bar in bars}:
        raise ValueError("Independent data sources have different dates or coverage.")
    for bar in bars:
        row = indexed[bar["date"]]
        if any(D(str(row[key])) != bar[key] for key in ("open", "close", "high", "low")):
            raise ValueError(f"Independent sources disagree on OHLC: {bar['date']}")
    return bars


def load_snapshot(refresh=False):
    folder = ROOT / "data"
    path = folder / "snapshot.json"
    if path.exists() and not refresh:
        snapshot = json.loads(path.read_text(encoding="utf-8"))
        blobs = {}
        for key in ("raw", "qfq", "sina"):
            source = snapshot["sources"][key]
            blobs[key] = (folder / source["filename"]).read_bytes()
            if hashlib.sha256(blobs[key]).hexdigest() != source["sha256"]:
                raise ValueError(f"Frozen data hash mismatch: {key}")
        bars = validate_sources(blobs["raw"], blobs["qfq"], blobs["sina"])
        if snapshot["window_start"] != START or snapshot["window_end"] != END:
            raise ValueError("The cached window differs from the requested window.")
        return bars, snapshot
    folder.mkdir(parents=True, exist_ok=True)
    urls = {
        "raw": SOURCE_BASE + f"?param=sz159915,day,{START},{END},1000,",
        "qfq": SOURCE_BASE + f"?param=sz159915,day,{START},{END},1000,qfq",
        "sina": ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                 "CN_MarketData.getKLineData?symbol=sz159915&scale=240&ma=no&datalen=1023"),
    }
    blobs = {key: get_url(url) for key, url in urls.items()}
    bars = validate_sources(blobs["raw"], blobs["qfq"], blobs["sina"])
    sources = {}
    for key, blob in blobs.items():
        filename = f"{key}.json"
        (folder / filename).write_bytes(blob)
        sources[key] = {"url": urls[key], "filename": filename, "sha256": hashlib.sha256(blob).hexdigest()}
    snapshot = {"window_start": START, "window_end": END, "rows": len(bars),
                "retrieved_at_utc": datetime.now(timezone.utc).isoformat(),
                "raw_qfq_equal": True, "independent_ohlc_matching_days": len(bars), "sources": sources}
    write_json(path, snapshot)
    write_csv(folder / "159915_daily.csv", bars)
    return bars, snapshot


def period_returns(daily, capital):
    groups = []
    opening = capital
    first = 0
    for index, row in enumerate(daily):
        if index + 1 < len(daily) and row["date"][:4] == daily[index + 1]["date"][:4]:
            continue
        groups.append({"period": row["date"][:4], "first_date": daily[first]["date"],
                       "last_date": row["date"], "opening_equity": opening,
                       "closing_equity": row["equity"], "net_profit": row["equity"] - opening,
                       "return_pct": (row["equity"] / opening - 1) * 100})
        opening, first = row["equity"], index + 1
    return groups


def report(result, variants, metadata):
    s = result["summary"]
    difference = s["equity"] - s["same_initial_hold_equity"]
    years = period_returns(result["daily"], s["config"]["capital"])
    days = (datetime.fromisoformat(s["end_date"]) - datetime.fromisoformat(s["entry_date"])).days
    annualized = ((float(s["equity"] / s["config"]["capital"]) ** (365.25 / days)) - 1) * 100
    lines = ["# 159915仓位跟随：近两年回测", "",
             "> 研究日期：2026年11月7日。沿用上一轮模型和全部主参数，仅延长数据窗口。半年结果独立保留。", "",
             f"数据窗口：**{START}—{END}，{metadata['rows']}个交易日**。"
             f"按原起点规则，于{s['start_signal_date']}识别连续两日小幅下跌，{s['entry_date']}开盘建仓；"
             f"实际运行{s['active_days']}个交易日。", "",
             "## 1. 主结果", "",
             f"初始50000元，期末权益 **{money(s['equity'])}元**，净盈亏 **{money(s['net_profit'])}元**，"
             f"累计收益率 **{pct(s['return_pct'])}**，最大收盘权益回撤 **{pct(s['max_drawdown_pct'])}**。"
             f"按实际运行日历长度折算的年化收益率约{annualized:.2f}%。", "",
             "| 方案 | 期末权益 | 累计收益率 | 最大回撤 |", "|---|---:|---:|---:|"]
    for label, equity, ret, dd in [
        ("新模型", s["equity"], s["return_pct"], s["max_drawdown_pct"]),
        ("相同初始约1万元底仓＋现金持有", s["same_initial_hold_equity"], s["same_initial_hold_return_pct"], s["same_initial_hold_max_drawdown_pct"]),
        ("相同5万元全仓买入持有", s["full_buy_hold_equity"], s["full_buy_hold_return_pct"], s["full_buy_hold_max_drawdown_pct"])]:
        lines.append(f"| {label} | {money(equity)}元 | {pct(ret)} | {pct(dd)} |")
    lines += ["", f"相对初始底仓不动的期末权益差为 **{money(difference)}元**；"
              f"相对全仓持有的收益率差为 **{s['return_pct'] - s['full_buy_hold_return_pct']:.2f}个百分点**。"
              "仓位暴露不同，应结合两个基准判断，不能只选更有利的一个。", "",
              "![两年回测曲线](回测曲线.png)", "", "## 2. 参数和规则", "",
              "- 初始总资金50000元，底仓约10000元；成本下限10000、中线20000、上限30000元。",
              "- 每笔约4000元，按实际成交价折成最接近的100份整手，整份成交；允许越过成本边界。",
              "- 固定价格网格为初始成交价×1.03ⁿ，报价精度0.001元；相邻上涨3%，下降约2.9126%。",
              "- 正向下跌买、上涨卖最低买价盈利批次；买后成本≥上限转下跌反向减仓，卖后成本≤下限转上涨反向跟随。",
              "- 下跌反向阶段：下跌卖最高买价批次，反弹有足量盈利批次可卖，剩余成本≤中线恢复正向。",
              "- 上涨反向阶段：突破最高剩余买价且目标格为空才跟随买入；回落到空格可买、已占格等待；成本≥中线恢复正向。",
              "- 格子只要仍有份额就不允许重复买入；全部卖完才释放。阶段只随实际成交改变。",
              "- 收盘判断、次日开盘成交；每天最多一笔，跨多格仅处理最远到达格。等待时仍更新访问格。",
              "- 双边佣金万2.5、最低5元；主场景滑点0，无股票印花税和过户费。执行T+1及现金约束，不追加资金。",
              "- 资金／可卖／盈利份额不足取消整笔；次日开盘再次复核必要条件，不把撤单改成另一方向。",
              "- 初始底仓占0号格，初始化从正向开始；期末按收盘计价，不强制清仓。", "",
              "起点条件也没有修改：窗口前22根日线中，首次出现连续两日收盘下降，累计跌幅1%—6%，次日开盘建仓。"
              "没有使用后续收益选择日期，参数未按两年结果调优。", "",
              f"实际建仓：{s['initial_price']}元×{s['initial_qty']}份，成本{money(s['initial_cost'])}元。", "",
              "## 3. 分年表现（连续运行，不逐年重置）", "",
              "| 年份 | 实际区间 | 区间初权益 | 区间末权益 | 区间盈亏 | 区间收益率 |", "|---|---|---:|---:|---:|---:|"]
    for year in years:
        lines.append(f"| {year['period']} | {year['first_date']}—{year['last_date']} | {money(year['opening_equity'])} | "
                     f"{money(year['closing_equity'])} | {money(year['net_profit'])} | {pct(year['return_pct'])} |")
    lines += ["", "首年和末年均为不完整年度。阶段、格子、持仓、现金跨年连续保留；分年收益用上一年末权益作为分母。", "",
              "## 4. 交易与阶段", "",
              f"共{s['trade_count']}笔，包含初始底仓；买入{s['buy_count']}笔、卖出{s['sell_count']}笔。"
              f"发生{len(s['transitions'])}次阶段切换，累计佣金{money(s['fees'])}元。", "",
              "| 交易原因 | 笔数 |", "|---|---:|"]
    for reason, count in s["reason_counts"].items():
        lines.append(f"| {REASON_NAMES[reason]} | {count} |")
    lines += ["", "| 卖出类型 | 笔数 | 已实现净盈亏 |", "|---|---:|---:|"]
    for reason, group in s["realized_by_reason"].items():
        lines.append(f"| {REASON_NAMES[reason]} | {group['count']} | {money(group['net_profit'])}元 |")
    lines += ["", "反向减仓把浮亏变为已实现亏损，同时释放资金并减少后续敞口。"
              "不能仅用其已实现亏损断言减仓有害；判断增量效果还需关闭该规则的其他条件相同的权益对照。", "",
              "### 等待与开盘取消", "", "| 环节 | 原因 | 次数 |", "|---|---|---:|"]
    for stage, key in [("收盘等待", "blocked_closes"), ("次日开盘取消", "cancelled_opens")]:
        for reason, count in s[key].items():
            lines.append(f"| {stage} | {REASON_NAMES.get(reason, reason)} | {count} |")
    lines += ["", "### 全部阶段切换", "",
              "| 日期 | 操作原因 | 成交价×份额 | 成交后成本 | 阶段变化 |", "|---|---|---|---:|---|"]
    for trade in s["transitions"]:
        lines.append(f"| {trade['date']} | {REASON_NAMES[trade['reason']]} | {trade['price']}×{trade['qty']} | "
                     f"{money(trade['cost_after'])} | {PHASE_NAMES[trade['phase_before']]}→{PHASE_NAMES[trade['phase_after']]} |")
    lines += ["", "## 5. 期末与风险约束", "", "| 项目 | 数值 |", "|---|---:|",
              f"| 期末现金 | {money(s['end_cash'])}元 |",
              f"| 期末持仓 | {s['end_qty']}份，{s['end_grid_count']}个占用格 |",
              f"| 期末现价／持仓市值 | {s['end_price']}元／{money(s['end_qty'] * s['end_price'])}元 |",
              f"| 剩余原始成本 | {money(s['end_cost'])}元 |",
              f"| 已实现净盈亏 | {money(s['realized_net'])}元 |",
              f"| 未实现净盈亏（含剩余买入费） | {money(s['unrealized_net'])}元 |",
              f"| 最低现金余额 | {money(s['min_cash'])}元 |",
              f"| 成本范围 | {money(s['min_cost'])}—{money(s['max_cost'])}元 |",
              f"| 最大超上限／低于下限 | {money(s['max_upper_overshoot'])}／{money(s['max_lower_undershoot'])}元 |",
              f"| 最大持仓份额 | {s['max_qty']}份 |",
              f"| 期末阶段 | {PHASE_NAMES[s['end_phase']]} |", "",
              "成本上下限是阶段切换阈值，不是严格仓位硬限制：完整订单可以跨过边界。"
              "格子占用及反向回中线控制操作节奏，但不限制累计亏损或确认市场底部。", "",
              "## 6. 执行成本敏感性", "", "| 场景 | 期末权益 | 收益率 | 最大回撤 | 成交笔数 |", "|---|---:|---:|---:|---:|"]
    for label, value in [("主场景", result)] + list(variants.items()):
        v = value["summary"]
        lines.append(f"| {label} | {money(v['equity'])} | {pct(v['return_pct'])} | {pct(v['max_drawdown_pct'])} | {v['trade_count']} |")
    slip = next(iter(variants.values()))["summary"]
    lines += ["", f"**一档滑点使累计收益由{pct(s['return_pct'])}变为{pct(slip['return_pct'])}，"
              f"期末权益减少{money(s['equity'] - slip['equity'])}元。** "
              "滑点不仅增加实际成交成本，也改变初始成交锚点、各批次买价、阈值取整和后续操作路径；"
              "因此不能简单把它理解为从同一组订单中扣除几十元。"
              "这个结果提示模型具有路径敏感性，尚不能仅凭主场景收益判断稳定性。"
              "这些场景不是参数最优化。", "",
              "## 7. 与半年回测的关系", "",
              "半年回测从2026年5月重新建仓，本轮从2024年11月建仓并持续运行。"
              "进入2026年5月时，本轮已有不同的成本、现金、阶段和占格情况。"
              "因此两年结果不能由半年结果拼接，重叠日期的买卖也不要求相同。", "",
              "## 8. 数据与验证边界", "",
              f"- [腾讯未复权日线]({metadata['sources']['raw']['url']})：{metadata['rows']}个交易日，使用真实未复权价格核算资金。",
              f"- [腾讯前复权对照]({metadata['sources']['qfq']['url']})：样本每日OHLCV与未复权一致，未检测到调整差异。",
              f"- [新浪第二来源]({metadata['sources']['sina']['url']})：同样覆盖{metadata['independent_ohlc_matching_days']}日，日期及每日OHLC全部一致。",
              "- 三份原始响应及各自SHA-256保存在data目录，缓存重跑会重新验哈希并比较数据。",
              "- 未逐份审计基金分红／折算公告，也未独立核验交易所交易日历；若两种复权序列不一致则拒绝回测。",
              "- 使用相同起点、总资金和费用的两个持有基准；逐日权益与期末已实现／未实现净盈亏均核对守恒。",
              "- 日线版本漏掉盘中触格后回撤，不能等同盘中实时网格。开盘可成交是假设，未模拟排队或涨跌停封单。",
              "- 两年一个ETF、一个预先定义起点仍然不足以证明长期有效。", "",
              "## 9. 复现", "", "```powershell",
              "python -m unittest discover -s research/position_following_v2 -p 'test_*.py' -v",
              "python research/position_following_v2/backtest_two_years.py", "```", "",
              "默认读取本轮独立冻结快照；加`--refresh-data`才重新下载。",
              "`trades.csv`为全部成交；`events.csv`含等待与开盘撤单；`daily.csv`为逐日权益及基准；"
              "`results.json`保存完整参数、汇总及敏感性结果；`yearly.csv`为连续账户的分年表现。", ""]
    (ROOT / "outputs" / "报告.md").write_text("\n".join(lines), encoding="utf-8")
    write_csv(ROOT / "outputs" / "yearly.csv", years)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh-data", action="store_true")
    args = parser.parse_args()
    protected = [BASE / "outputs" / name for name in ("报告.md", "results.json", "trades.csv", "回测曲线.png")]
    old_hashes = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in protected if path.exists()}
    bars, metadata = load_snapshot(args.refresh_data)
    result = simulate(bars, Config())
    variants = {
        "双边各1个最小价位滑点": simulate(bars, replace(Config(), slippage_ticks=1)),
        "无最低佣金、费率仍为万2.5": simulate(bars, replace(Config(), minimum_commission=ZERO)),
    }
    output = ROOT / "outputs"
    output.mkdir(parents=True, exist_ok=True)
    for name in ("daily", "trades", "events"):
        write_csv(output / f"{name}.csv", result[name])
    for index, variant in enumerate(variants.values(), 1):
        write_csv(output / f"scenario_{index}_trades.csv", variant["trades"])
        write_csv(output / f"scenario_{index}_events.csv", variant["events"])
    write_json(output / "results.json", {"metadata": metadata, "main": result["summary"],
                                        "scenarios": {key: value["summary"] for key, value in variants.items()}})
    plot(result, output / "回测曲线.png")
    report(result, variants, metadata)
    if any(hashlib.sha256(Path(path).read_bytes()).hexdigest() != digest for path, digest in old_hashes.items()):
        raise AssertionError("An earlier half-year artifact changed during this run.")
    s = result["summary"]
    print(json.dumps({key: s[key] for key in (
        "entry_date", "end_date", "active_days", "equity", "net_profit", "return_pct", "max_drawdown_pct",
        "same_initial_hold_equity", "same_initial_hold_return_pct", "same_initial_hold_max_drawdown_pct",
        "full_buy_hold_equity", "full_buy_hold_return_pct", "full_buy_hold_max_drawdown_pct", "trade_count",
        "buy_count", "sell_count", "fees", "end_cash", "end_qty", "end_cost", "min_cash", "max_cost",
        "min_cost", "blocked_closes", "cancelled_opens", "reason_counts", "realized_by_reason")},
        ensure_ascii=True, default=json_default))
    print("Previous half-year artifacts preserved.")


if __name__ == "__main__":
    main()
