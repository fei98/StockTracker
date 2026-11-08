"""Run a locked, training-only search and report temporal and execution diagnostics."""

import argparse
from dataclasses import replace
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path

from backtest import json_default, money, pct, write_csv, write_json
from intraday import simulate_intraday
from intraday_data import ROOT, load_intraday
from model import Config, D
from parameter_search import (RISK_LIMIT, SPLIT, candidates, key_for, metric_pair,
                              metric_row, neighbors, rank_training, split_bars, training_rows)


BASE = Path(__file__).resolve().parent
OUTPUT = ROOT / "outputs"


def dump_result(label, result):
    for table in ("trades", "events", "marks", "daily"):
        write_csv(OUTPUT / f"{label}_{table}.csv", result[table])


def exploratory_candidates(rows, original_id):
    """Describe hindsight alternatives without changing the pre-validation selection lock."""
    feasible = [row for row in rows if all(row[f"{period}_max_drawdown_pct_{tick}tick"] <= RISK_LIMIT
                                         for period in ("full", "validation") for tick in (0, 1))]
    if not feasible:
        return {}
    original = next(row for row in rows if row["candidate"] == original_id)
    worst = lambda row, period: min(row[f"{period}_return_pct_{tick}tick"] for tick in (0, 1))
    better = [row for row in feasible if worst(row, "full") >= worst(original, "full") and
              worst(row, "validation") >= worst(original, "validation") and worst(row, "full") > 0]
    result = {"exploratory_return": max(feasible, key=lambda row: (worst(row, "full"), row["candidate"]))["candidate"]}
    if better:
        result["exploratory_balance"] = max(better, key=lambda row: (
            worst(row, "full") / max(row[f"full_max_drawdown_pct_{tick}tick"] for tick in (0, 1)),
            row["candidate"]))["candidate"]
    return result


def lock_selection(chosen, training, source_metadata):
    canonical = json.dumps(training, sort_keys=True, ensure_ascii=True, default=json_default)
    digest = hashlib.sha256(canonical.encode()).hexdigest()
    path = ROOT / "selection_lock.json"
    record = {"candidate": chosen, "training_result_sha256": digest,
              "training_window": ["2024-11-07", "2025-11-06"],
              "validation_window": [SPLIT, "2026-11-06"],
              "source_sha256": source_metadata["sha256"], "risk_limit_pct": str(RISK_LIMIT),
              "selector": "Training-only: both slippages within risk limit; at least half of immediate neighbors "
                          "risk-eligible; maximize median neighbor worst-slippage return, then own worst return, "
                          "then lower worst drawdown, then deterministic candidate ID.",
              "created_at_utc": datetime.now(timezone.utc).isoformat()}
    if path.exists():
        previous = json.loads(path.read_text(encoding="utf-8"))
        if previous["candidate"] != chosen or previous["training_result_sha256"] != digest:
            raise ValueError("The training lock changed. Use a new study version instead of replacing it after validation.")
        return previous
    write_json(path, record)
    return record


def plot_results(original, chosen, validation_original, validation_chosen, rows, chosen_id):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates

    plt.rcParams.update({"font.sans-serif": ["Microsoft YaHei", "SimHei", "DejaVu Sans"],
                         "axes.unicode_minus": False, "font.size": 10})
    fig, axes = plt.subplots(3, 1, figsize=(13, 12), gridspec_kw={"height_ratios": [1, 1, 1.1]})
    for result, color, label in [(original, "#64748b", "原参数：下限1万／上限3万／3%"),
                                 (chosen, "#2563eb", f"训练锁定：{chosen_id}")]:
        days = result["daily"]
        axes[0].plot([datetime.fromisoformat(row["date"]) for row in days],
                     [float(row["equity"]) for row in days], label=label, color=color, linewidth=1.6)
    axes[0].set_title("连续两年：相同资金和起点，30分钟信号／下一根开盘成交")
    axes[0].set_ylabel("权益 / 元")
    axes[0].axvline(datetime.fromisoformat(SPLIT), color="#94a3b8", linestyle="--", label="时间切分线")
    for result, color, label in [(validation_original, "#64748b", "原参数"),
                                 (validation_chosen, "#2563eb", "训练锁定参数")]:
        days = result["daily"]
        axes[1].plot([datetime.fromisoformat(row["date"]) for row in days],
                     [float(row["equity"]) for row in days], label=label, color=color, linewidth=1.6)
    axes[1].axhline(50000, color="#94a3b8", linestyle=":")
    axes[1].set_title("独立验证期：各自从5万元和约1万元初始底仓重新开始")
    axes[1].set_ylabel("权益 / 元")
    for row in rows:
        okay = max(row[f"full_max_drawdown_pct_{tick}tick"] for tick in (0, 1)) <= RISK_LIMIT
        axes[2].scatter(float(row["full_max_drawdown_pct_0tick"]), float(row["full_return_pct_0tick"]),
                        color="#0d9488" if okay else "#f59e0b", s=35, alpha=.65)
    for candidate, marker, color, label in [(key_for(Config()), "s", "#334155", "原参数"),
                                            (chosen_id, "*", "#dc2626", "训练锁定参数")]:
        row = next(row for row in rows if row["candidate"] == candidate)
        axes[2].scatter(float(row["full_max_drawdown_pct_0tick"]), float(row["full_return_pct_0tick"]),
                        marker=marker, s=140, color=color, label=label, zorder=4)
    axes[2].axvline(20, linestyle="--", color="#dc2626", label="20%回撤筛选线")
    axes[2].set_title("45组全区间结果仅作事后观察，未用于锁定训练候选")
    axes[2].set_xlabel("30分钟收盘权益最大回撤 / %")
    axes[2].set_ylabel("两年累计收益 / %")
    for axis in axes:
        axis.grid(alpha=.16)
        axis.legend(frameon=False, fontsize=9, ncol=2)
        axis.spines[["top", "right"]].set_visible(False)
    for axis in axes[:2]:
        axis.xaxis.set_major_locator(mdates.MonthLocator(interval=3))
        axis.xaxis.set_major_formatter(mdates.DateFormatter("%Y-%m"))
    fig.suptitle("159915：盘中采样与参数检验", fontsize=16)
    fig.tight_layout(rect=[0, .02, 1, .965], h_pad=2.2)
    fig.savefig(OUTPUT / "参数检验.png", dpi=160, facecolor="white")
    plt.close(fig)


def report(payload):
    rows = payload["grid"]
    locked_id = payload["selection_lock"]["candidate"]
    original_id = key_for(Config())
    selected = next(row for row in rows if row["candidate"] == locked_id)
    original = next(row for row in rows if row["candidate"] == original_id)
    feasible_full = [row for row in rows if max(row[f"full_max_drawdown_pct_{tick}tick"] for tick in (0, 1)) <= RISK_LIMIT]
    hindsight = max(feasible_full, key=lambda row: min(row[f"full_return_pct_{tick}tick"] for tick in (0, 1)))
    validated = max(selected[f"validation_max_drawdown_pct_{tick}tick"] for tick in (0, 1)) <= RISK_LIMIT
    meta, meta5 = payload["data_30m"], payload["data_5m"]
    chosen_config = payload["selected_config"]
    verdict = ("锁定候选在验证期两种滑点下都满足20%回撤限制；仍应结合收益及5分钟复核判断。" if validated else
               "锁定候选未能在验证期两种滑点下都满足20%回撤限制，因此本轮不能将它作为符合目标的替代参数。未按验证收益另换赢家。")
    lines = ["# 159915：盘中回测及45组参数检验", "",
             "> 研究日期：2026年11月7日。目标：历史最大回撤不超过20%，兼顾收益及执行稳定性。", "",
             "## 1. 结论与边界", "",
             f"训练期预先锁定候选：**下限{chosen_config['lower']}元、上限{chosen_config['upper']}元、"
             f"步长{(chosen_config['ratio'] - 1) * 100}%**。中线随上下限取平均，其他参数保持相同。", "", verdict, "",
             "全部收益均已扣佣金。这里的回撤指30分钟收盘权益回撤，不是逐笔盘中真实最大回撤，也不保证未来回撤。", "",
             "## 2. 日线与盘中有何差异", "",
             "| 执行方式，原参数 | 两年收益 | 最大回撤 | 成交笔数 | 佣金 |", "|---|---:|---:|---:|---:|"]
    daily = payload["previous_daily"]["main"]
    base = payload["detailed"]["original_full"]["summary"]
    lines += [f"| 日线收盘／次日开盘 | {pct(D(daily['return_pct']))} | {pct(D(daily['max_drawdown_pct']))}（日收盘） | {daily['trade_count']} | {money(D(daily['fees']))} |",
              f"| 30分钟收盘／下一根开盘 | {pct(base['return_pct'])} | {pct(base['max_drawdown_pct'])}（30分钟收盘） | {base['trade_count']} | {money(base['fees'])} |", "",
              "两者共用起点选择规则、总资金和初始底仓。30分钟允许同日多笔，但每根最多一笔；"
              "收盘信号在下一根开盘执行，午休及隔夜按真实下一个bar处理。"
              "不使用同根高低价安排更有利的成交顺序，也不声称每个实际触价都成交。", "",
              "## 3. 搜索空间及预先固定的选择规则", "",
              "| 项目 | 设定 |", "|---|---|",
              "| 下限 | 5000、10000、15000元 |", "| 上限 | 20000、30000、40000元 |",
              "| 步长 | 2%、3%、4%、5%、6%；固定比例网格 |",
              "| 合计 | 45组，包含原参数10000／30000／3% |",
              "| 保持相同 | 总资金50000、初始底仓约10000、每笔约4000、100份整手、完整订单越界、T+1、双边万2.5最低5元 |",
              "| 训练期 | 2024-11-07—2025-11-06 |", "| 验证期 | 2025-11-07—2026-11-06 |", "",
              "初始底仓始终约10000元，调整下限不会同步改变起始投入；所以下限15000元时，初始成本可能低于下限。"
              "沿用原初始化正向规则，只有后续常规成交才触发阶段切换。上下限仍是阶段阈值，不是严格仓位硬边界。", "",
              "选择步骤在读取验证结果之前执行并写入[selection_lock.json](../selection_lock.json)：", "",
              "1. 训练期零滑点及双边各0.001元不利滑点两种场景，回撤均不得超过20%。",
              "2. 近邻定义为只移动一个候选档位、其他两项相同；将自身也纳入，至少一半近邻满足风险要求。",
              "3. 比较该邻域每组最差滑点收益的中位数，优先选整体较好的区域；再比自身最差收益、较低回撤及固定ID。",
              "4. 锁定一个候选后才查看验证。若失败，明确报告，不换成验证期赢家。", "",
              "训练与验证各自用相同50000元和约10000元底仓独立初始化；两年连续账户另行计算。"
              "独立验证收益不能与训练收益直接拼接。2026此前已经看过，属于时间切分检验，不是真正未见样本。", "",
              "## 4. 原参数与锁定候选", "",
              "| 参数 | 训练收益0／1档滑点 | 验证收益0／1档滑点 | 验证回撤0／1档滑点 | 两年收益0／1档滑点 | 两年回撤0／1档滑点 |",
              "|---|---:|---:|---:|---:|---:|"]
    for row in (original, selected):
        pair = lambda field: " / ".join(pct(row[f"{field}_{tick}tick"]) for tick in (0, 1))
        lines.append(f"| {row['candidate']} | {pair('train_return_pct')} | {pair('validation_return_pct')} | "
                     f"{pair('validation_max_drawdown_pct')} | {pair('full_return_pct')} | {pair('full_max_drawdown_pct')} |")
    lines += ["", "![参数检验](参数检验.png)", "", "### 邻域稳定性", "",
              f"锁定候选的训练邻域最差滑点收益中位数为{pct(selected['train_neighbor_median_worst_return'])}，"
              f"邻域含自身共{selected['neighbor_count_including_self']}组，训练风险达标比例"
              f"{pct(selected['train_neighbor_risk_pass_fraction'] * 100)}。", "",
              "| 邻近参数 | 训练最差滑点收益 | 验证最差滑点收益 | 验证最坏回撤 | 两年最差滑点收益 |",
              "|---|---:|---:|---:|---:|"]
    for row in rows:
        if row["candidate"] == locked_id or neighbors(selected, row):
            lines.append(f"| {row['candidate']} | {pct(row['train_worst_return'])} | "
                         f"{pct(min(row[f'validation_return_pct_{tick}tick'] for tick in (0,1)))} | "
                         f"{pct(max(row[f'validation_max_drawdown_pct_{tick}tick'] for tick in (0,1)))} | "
                         f"{pct(min(row[f'full_return_pct_{tick}tick'] for tick in (0,1)))} |")
    lines += ["", "### 事后全区间观察，不作为锁定候选", "",
              f"45组中，在两年两种滑点回撤均≤20%的前提下，按最差滑点收益最高者为`{hindsight['candidate']}`，"
              f"两年零滑点／一档滑点收益分别为{pct(hindsight['full_return_pct_0tick'])}／"
              f"{pct(hindsight['full_return_pct_1tick'])}。它使用了整段历史，只是样本内观察，不能冒充独立验证推荐。", "",
              "### 二次探索候选（已使用验证结果，不算独立验证成功）", "",
              "锁定候选失败后，以下只用于提出后续研究假设，不修改原锁定结果。"
              "筛选同时要求全区间及验证期在两种滑点下回撤≤20%。"
              "收益型取全区间最差滑点收益较高者；均衡型还要求收益不低于原参数，按最差收益／最坏回撤比选择。"
              "这不是夏普比率，也不是预先锁定的推荐。", "",
              "| 事后候选 | 参数 | 两年收益0／1档 | 两年回撤0／1档 | 验证收益0／1档 | 验证回撤0／1档 |",
              "|---|---|---:|---:|---:|---:|"]
    for label, candidate in payload["exploratory"].items():
        row = next(row for row in rows if row["candidate"] == candidate)
        pair = lambda field: " / ".join(pct(row[f"{field}_{tick}tick"]) for tick in (0, 1))
        lines.append(f"| {'收益型' if label == 'exploratory_return' else '均衡型'} | {candidate} | "
                     f"{pair('full_return_pct')} | {pair('full_max_drawdown_pct')} | "
                     f"{pair('validation_return_pct')} | {pair('validation_max_drawdown_pct')} |")
    lines += ["", "如果后续需要选择观察对象，均衡型历史回撤余量更大；"
              "但它已用本轮数据筛选，必须等待新增未用行情或其他未用标的验证，不能重新包装成样本外成功。", "",
              "## 5. 最近完整区间的5分钟复核", "",
              f"5分钟源提供最近5001根，首日不完整，剔除{','.join(meta5['dropped_partial_days']) or '无'}。"
              f"保留{meta5['complete_days']}个完整交易日，{meta5['first_time']}—{meta5['last_time']}，"
              "每日48根；与30分钟使用共同日期、共同起点和资金。原参数和训练锁定候选在5分钟检验前已确定，"
              "另列的探索候选来自事后筛选；均不利用5分钟结果重新调参。", "",
              "| 性质 | 参数 | 采样 | 实际建仓日 | 收益0／1档滑点 | 回撤0／1档滑点 |", "|---|---|---|---|---:|---:|"]
    for row in payload["five_minute_check"]:
        roles = {"original": "原参数", "selected": "训练锁定", "exploratory_return": "事后收益型", "exploratory_balance": "事后均衡型"}
        lines.append(f"| {roles[row['role']]} | {row['candidate']} | {row['minutes']}分钟 | {row['entry_date']} | "
                     f"{pct(row['return_0tick'])} / {pct(row['return_1tick'])} | "
                     f"{pct(row['drawdown_0tick'])} / {pct(row['drawdown_1tick'])} |")
    lines += ["", "该区间较短且行情不同，不能用其收益代替两年结果；5分钟也不是逐笔触价回测。", "",
              "## 6. 执行、资金与数据核验", "",
              f"- [30分钟原始来源]({meta['url']})：{meta['bars']}根、{meta['complete_days']}个完整交易日，每天8根。",
              f"- [5分钟原始来源]({meta5['url']})：完整日与原两年日线逐日核对OHLC一致。",
              "- 原始响应、SHA-256及时间范围保存在data目录；两年日线已做腾讯与新浪双来源核对。",
              "- 信号记录bar序号及结束时间，成交记录下一根序号及开始时间。相邻bar交界可同一钟点，"
              "但绝不在产生该信号的同一根bar开盘成交。",
              "- 券商成熟持仓只按日历日比较；当天买入不能因为经过半小时就变成可卖。记账匹配与券商批次独立。",
              "- 每bar核对现金＋市值＝初始资金＋已实现毛利＋浮动毛利−全部已付费用；期末再核对净盈亏守恒。",
              "- 未模拟买卖盘口、集合竞价排队、涨跌停封单；下一根开盘可成交是一项假设。",
              "- 不改动既有日线及半年回测文件，不改App代码。", "",
              "## 7. 复现及完整产物", "", "```powershell",
              "python -m unittest discover -s research/position_following_v2 -p 'test_*.py' -v",
              "python research/position_following_v2/optimize_intraday.py", "```", "",
              "- `../selection_lock.json`：查看验证结果之前写入的候选、训练结果哈希及预定规则。",
              "- `parameter_grid.csv`：全部45组训练、验证、连续两年结果，零滑点及一档滑点都保留。",
              "- `study_results.json`：完整机器可读汇总。",
              "- `original_full_*`、`selected_full_*`、`original_validation_*`、`selected_validation_*`："
              "成交、事件、每bar权益、逐日权益；文件名后缀`1tick`为一档滑点。",
              "- `five_minute_*`：共同区间的高低频核验成交与权益。", ""]
    (OUTPUT / "报告.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh-data", action="store_true")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    protected = [BASE / "outputs" / name for name in ("报告.md", "results.json", "trades.csv")]
    protected += [BASE / "two_years" / "outputs" / name for name in ("报告.md", "results.json", "trades.csv")]
    hashes = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in protected}
    bars, metadata = load_intraday(30, args.refresh_data)
    training, validation = split_bars(bars)
    raw_training = training_rows(training)
    chosen, scored = rank_training(raw_training)
    lock = lock_selection(chosen, raw_training, metadata)
    write_json(OUTPUT / "training_results.json", scored)
    print(f"Training candidate locked before validation: {chosen}", flush=True)
    configs = {key_for(config): config for config in candidates()}
    rows = []
    for row in scored:
        config = configs[row["candidate"]]
        for label, period in (("validation", validation), ("full", bars)):
            row.update(metric_row(config, metric_pair(period, config), label))
        rows.append(row)
    write_csv(OUTPUT / "parameter_grid.csv", rows)
    exploratory = exploratory_candidates(rows, key_for(Config()))
    named_configs = [("original", Config()), ("selected", configs[chosen])]
    named_configs += [(label, configs[candidate]) for label, candidate in exploratory.items()]
    detailed = {}
    for label, config in named_configs:
        for period_name, period in (("full", bars), ("validation", validation)):
            for tick in (0, 1):
                key = f"{label}_{period_name}" + ("_1tick" if tick else "")
                result = simulate_intraday(period, replace(config, slippage_ticks=tick))
                dump_result(key, result)
                detailed[key] = result
    bars5, metadata5 = load_intraday(5, args.refresh_data)
    first_day = bars5[0]["time"][:10]
    common30 = [bar for bar in bars if bar["time"][:10] >= first_day]
    common_entry = simulate_intraday(common30, Config())["summary"]["entry_date"]
    check5 = []
    for label, config in named_configs:
        for minutes, history in ((30, common30), (5, bars5)):
            pair = []
            for tick in (0, 1):
                result = simulate_intraday(history, replace(config, slippage_ticks=tick), entry_date=common_entry)
                dump_result(f"five_minute_{label}_{minutes}m_{tick}tick", result)
                pair.append(result["summary"])
            check5.append({"candidate": key_for(config), "role": label, "minutes": minutes, "entry_date": common_entry,
                           "return_0tick": pair[0]["return_pct"], "return_1tick": pair[1]["return_pct"],
                           "drawdown_0tick": pair[0]["max_drawdown_pct"], "drawdown_1tick": pair[1]["max_drawdown_pct"]})
    payload = {"data_30m": metadata, "data_5m": metadata5, "selection_lock": lock,
               "selected_config": configs[chosen].__dict__, "grid": rows,
               "exploratory": exploratory,
               "detailed": {key: {"summary": result["summary"]} for key, result in detailed.items()},
               "five_minute_check": check5,
               "previous_daily": json.loads((BASE / "two_years" / "outputs" / "results.json").read_text(encoding="utf-8"))}
    write_json(OUTPUT / "study_results.json", payload)
    plot_results(detailed["original_full"], detailed["selected_full"],
                 detailed["original_validation"], detailed["selected_validation"], rows, chosen)
    report(payload)
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path, digest in hashes.items()):
        raise AssertionError("An earlier daily backtest artifact changed.")
    chosen_row = next(row for row in rows if row["candidate"] == chosen)
    original_row = next(row for row in rows if row["candidate"] == key_for(Config()))
    print(json.dumps({"chosen": chosen_row, "original": original_row,
                      "five_minute_check": check5, "output": str(OUTPUT)}, ensure_ascii=True, default=json_default))


if __name__ == "__main__":
    main()
