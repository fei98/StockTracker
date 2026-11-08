"""Render the completed five-minute study without changing selection or trade results."""

import csv
import json
from pathlib import Path

from model import D
from stock_five_data import ROOT


OUTPUT = ROOT / "outputs"
NAMES = {"original": "原参数", "training_locked": "训练锁定", "historical_best": "全段风险约束最高（事后）",
         "posthoc_return": "后段风险筛选收益型（事后）", "posthoc_balance": "后段风险筛选均衡型（事后）"}
COLORS = {"original": "#64748b", "training_locked": "#a855f7", "historical_best": "#dc2626",
          "posthoc_return": "#d97706", "posthoc_balance": "#0d9488"}


def number(value):
    return D(str(value))


def money(value):
    return f"{number(value):,.2f}"


def pct(value):
    return f"{number(value):.2f}%"


def description(point):
    return f"下限{point['lower']}／上限{point['upper']}／步长{point['step']}%／每笔目标{point['amount']}元"


def valid(summary):
    return summary.get("valid", True)


def pair(payload, label, scope):
    return [payload["results"][f"{label}_{scope}_{tick}tick"] for tick in (0, 1)]


def render_pair(values, field):
    return " / ".join(pct(row[field]) if valid(row) else "资金约束失败" for row in values)


def risk_pass(summaries):
    return all(valid(summary) and number(summary["max_drawdown_pct"]) <= 20 for summary in summaries)


def all_checks_pass(payload, label):
    checks = pair(payload, label, "full") + pair(payload, label, "validation")
    checks += [summary for values in payload["source_checks"][label].values() for summary in values]
    return risk_pass(checks)


def draw(payload):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates
    from datetime import datetime

    plt.rcParams.update({"font.sans-serif": ["Microsoft YaHei", "SimHei", "DejaVu Sans"],
                         "axes.unicode_minus": False, "font.size": 10})
    fig, axes = plt.subplots(3, 1, figsize=(14, 13), gridspec_kw={"height_ratios": [1, 1, 1.15]})
    for label in payload["named"]:
        for index, scope in enumerate(("full", "validation")):
            path = OUTPUT / f"{label}_{scope}_0tick_daily.csv"
            if not path.exists():
                continue
            with path.open(encoding="utf-8-sig", newline="") as stream:
                rows = list(csv.DictReader(stream))
            axes[index].plot([datetime.fromisoformat(row["date"]) for row in rows],
                             [float(row["equity"]) for row in rows], color=COLORS[label],
                             label=NAMES[label], linewidth=1.4, alpha=.9)
    axes[0].set_title("完整两年5分钟：连续账户，零滑点（BaoStock来源）")
    axes[1].set_title("独立后段：从5万元及约1万元底仓重新开始，零滑点")
    for row in payload["grid"]:
        if not row["full_valid_0tick"]:
            continue
        axes[2].scatter(float(row["full_max_drawdown_pct_0tick"]), float(row["full_return_pct_0tick"]),
                        s=10, color="#94a3b8", alpha=.35)
    for label in payload["named"]:
        summary = pair(payload, label, "full")[0]
        if valid(summary):
            axes[2].scatter(float(summary["max_drawdown_pct"]), float(summary["return_pct"]),
                            s=95, color=COLORS[label], label=NAMES[label], zorder=4)
    axes[2].axvline(20, color="#dc2626", linestyle="--", linewidth=1)
    axes[2].set_xlabel("5分钟收盘权益最大回撤 / %")
    axes[2].set_ylabel("两年收益 / %")
    axes[2].set_title(f"{payload['searched_count']}组参数：全历史分布，仅作事后观察")
    for axis in axes:
        axis.grid(alpha=.16)
        axis.legend(frameon=False, ncol=2, fontsize=9)
        axis.spines[["top", "right"]].set_visible(False)
    for axis in axes[:2]:
        axis.axhline(50000, color="#94a3b8", linestyle=":", linewidth=1)
        axis.set_ylabel("权益 / 元")
        axis.xaxis.set_major_locator(mdates.MonthLocator(interval=3))
        axis.xaxis.set_major_formatter(mdates.DateFormatter("%Y-%m"))
    fig.suptitle("002396：扩大上下限、步长与每笔金额的5分钟检验", fontsize=16)
    fig.text(.06, .012, "供应商分钟价格存在差异；不混拼。成交为下一根开盘假设，收益已扣股票费税。", color="#475569")
    fig.tight_layout(rect=[0, .035, 1, .965], h_pad=2.1)
    fig.savefig(OUTPUT / "参数比较.png", dpi=160, facecolor="white")
    plt.close(fig)


def create_report(payload):
    meta = payload["metadata"]["checks"]
    passed = [label for label in ("posthoc_balance", "posthoc_return", "original")
              if label in payload["named"] and all_checks_pass(payload, label)]
    lines = ["# 002396：完整两年5分钟、四维参数搜索", "",
             "> 研究日期：2026年11月7日。本金入金上限50000元，收益与历史回撤兼顾，回撤筛选线20%。", "",
             "## 1. 数据质量先说明", "",
             "本轮取得2024年11月7日至2026年11月6日的 **23280根真实供应商5分钟记录、485个完整交易日**，"
             "没有将30分钟拆分、插值成5分钟，也没有混拼供应商价格。1分钟公开来源仅覆盖很短区间，不足以做两年搜索。", "",
             "**分钟数据存在明显供应商差异，不能因周期更细就认为回测更接近真实成交。** "
             f"BaoStock与独立日线的每日开盘、收盘485天均一致；但{meta['daily_extreme_difference_days'].get('high',0)}天聚合最高价、"
             f"{meta['daily_extreme_difference_days'].get('low',0)}天聚合最低价不同（可能重叠），差值最大分别为"
             f"{money(meta['daily_extreme_max_difference'].get('high',0))}／{money(meta['daily_extreme_max_difference'].get('low',0))}元，"
             "均在独立日线真实区间内。", "",
             f"最近新浪{meta['recent_sina_comparison_bars']}根中，{meta['recent_sina_differing_bars']}根至少一个OHLC字段不同"
             f"（约{number(meta['recent_sina_differing_bars']) / number(meta['recent_sina_comparison_bars']) * 100:.2f}%）；"
             f"开盘／收盘最大差{money(meta['recent_sina_max_difference'].get('open',0))}／"
             f"{money(meta['recent_sina_max_difference'].get('close',0))}元。这不是可忽略的分位舍入差。", "",
             "因此：两年主研究使用完整BaoStock5分钟；候选另用同日期、同起点的新浪5分钟核验。"
             "来源差异保留在快照质量字段，不修改原价让它们看起来一致。结论只适合作为继续研究的候选，不能称已确认最佳参数。", "",
             "## 2. 搜索范围与资金约束", "",
             "| 维度 | 粗搜索 | 局部细化 |", "|---|---|---|",
             "| 成本下限 | 0、5000、10000、15000、20000、25000、30000元 | 训练种子±2500元 |",
             "| 成本上限 | 10000、20000、30000、40000、45000、50000元 | 训练种子±2500元 |",
             "| 百分比步长 | 1%、2%、3%、4%、5%、6%、8%、10% | ±0.5个百分点 |",
             "| 每笔目标金额 | 2000、4000、6000、8000元 | ±1000元 |", "",
             f"过滤下限＜上限后粗搜{payload['coarse_count']}组；细化中心{payload['fine_centers']}组，"
             f"合计{payload['searched_count']}组不同候选。训练近邻支持点另计，不把它们当成已在验证／全段搜索过的候选。", "",
             "细化边界固定为下限0—30000、上限10000—50000、步长1%—10%、每笔目标1000—10000元。"
             "初始底仓仍约10000元，不随下限或每笔金额变化。中线始终是上下限平均。", "",
             "**50000元限制的是本金净投入，不是累计买入成交额。** 不追加资金、不借款，现金不得为负；盈利和分红留在账户内可再用。"
             "资金不足时取消完整订单，不用模型外现金补足。上限是阶段阈值，按此前确认的完整一份规则可跨过；"
             "获利再投入时，账面成本或市值可能超过50000元，这不表示追加了本金。", "",
             "费用、T+1、分红转增、网格仿射除权、日终净减股数红利税FIFO均沿用上一轮股票模型。"
             "一档滑点为每侧0.01元，主表分别列零／一档；回撤为5分钟收盘权益回撤，不是逐笔最大回撤。", "",
             "## 3. 先锁定再检验", "",
             "训练2024年11月7日—2025年11月6日；验证2025年11月7日—2026年11月6日，独立从相同本金与底仓初始化。", "",
             "1. 粗筛在训练期同时要求两档滑点回撤≤20%；按固定粗网格邻域最差收益中位数排序，邻域含自身至少一半风险合格。",
             "2. 依次取粗网格距离至少2的前三个种子区域，四维各±一档生成细中心，补齐其实际物理近邻训练结果。",
             "3. 细筛要求至少4个唯一且非自身的实际近邻；固定±2500／±2500／±0.5%／±1000，不因插入新点改变邻居定义。",
             "4. 仍仅用训练结果锁定一个候选，随后才计算验证及全区间。锁定失败不换成验证赢家。", "",
             f"锁定为 **{description(payload['named']['training_locked'])}**。"
             "完整两年和独立后段账户不能拼接；002396的后段此前已经看过，这仍是时间切分检验，不是真正未见行情。", "",
             "## 4. 主要结果", "",
             "下表0／1档均已扣交易费税和已发生红利税，含分红转增；期末不强制清仓。", "",
             "| 性质 | 参数 | 两年收益0／1档 | 两年回撤0／1档 | 后段收益0／1档 | 后段回撤0／1档 | 全部展示风险检查≤20% |",
             "|---|---|---:|---:|---:|---:|---|"]
    for label, point in payload["named"].items():
        full, validation = pair(payload,label,"full"), pair(payload,label,"validation")
        lines.append(f"| {NAMES[label]} | {description(point)} | {render_pair(full,'return_pct')} | "
                     f"{render_pair(full,'max_drawdown_pct')} | {render_pair(validation,'return_pct')} | "
                     f"{render_pair(validation,'max_drawdown_pct')} | {'是' if all_checks_pass(payload,label) else '否'} |")
    lines += ["", "‘全部展示风险检查’同时检查两年、独立后段及近期两来源，在零／一档滑点下均≤20%。"
              "这只是本次有限历史的筛选，不能保证未来回撤。", "",
              "‘全段风险约束最高’指两档全段回撤均≤20%时，按两档较低收益选出的最高者，不是完全不限制风险的最高收益。", "",
              "**训练锁定候选不通过验证时，不构成优化成功。** ‘全段风险约束最高’使用了整段历史；"
              "‘后段风险筛选’两组更使用了后段结果，都是事后探索，不是独立验证赢家。", "",
              ("本轮展示候选中通过全部风险检查的有：" + "、".join(NAMES[label] for label in passed) + "。" if passed else
               "本轮展示候选没有通过全部风险检查的组合，不应直接替换现有设置。"), "",
              "![参数比较](参数比较.png)", "", "## 5. 实际每笔金额与资金占用", "",
              "目标金额会折成最接近的100股整手，最低100股。股价较高时，即使目标1000／2000元，"
              "实际也可能是2000—4000元，多个名义金额可能产生同一交易行为。以下不含初始建仓。", "",
              "| 参数性质，零滑点完整两年 | 每笔目标 | 实际最小／中位／最大 | 成交数含建仓 | 费用 | 最低现金 | 最大原始成本 |",
              "|---|---:|---:|---:|---:|---:|---:|"]
    for label, point in payload["named"].items():
        summary = pair(payload,label,"full")[0]
        if not valid(summary):
            continue
        amounts = summary.get("actual_order_amounts")
        actual = "—" if amounts is None else " / ".join(money(amounts[key]) for key in ("minimum","median","maximum"))
        lines.append(f"| {NAMES[label]} | {point['amount']} | {actual} | {summary['trade_count']} | "
                     f"{money(summary['fees'])} | {money(summary['min_cash'])} | {money(summary['max_cost'])} |")
    lines += ["", "## 6. 相同日期的供应商核验", "",
              f"共同建仓日 **{payload['source_common_entry']}**；两来源都从50000元、约10000元初始底仓重新开始，"
              "参数不因该核验再次调优。该区间仅为近期完整5分钟的覆盖，不替代两年结果。", "",
              "| 参数性质 | 来源 | 收益0／1档 | 回撤0／1档 |", "|---|---|---:|---:|"]
    for label, providers in payload["source_checks"].items():
        for provider, summaries in providers.items():
            lines.append(f"| {NAMES[label]} | {provider} | {render_pair(summaries,'return_pct')} | "
                         f"{render_pair(summaries,'max_drawdown_pct')} |")
    lines += ["", "近期来源差异和起点变化都可能改变路径。滑点后收益更高不代表滑点有利，"
              "而是锚点、整手取整、占格及阶段切换不同；不得只挑收益较高的一档展示。", "",
              "## 7. 数据、模型与复现", "",
              "- 原始逐月BaoStock CSV、总快照与SHA-256保存在data目录；明确使用不复权`adjustflag=3`。",
              "- BaoStock不支持本次所需两年1分钟；公开东方财富1分钟仅近5日且部分开盘字段缺失，未据此伪造长期精细成交。",
              "- 校验完整交易时段、17位时间格式、485×48根、日开收一致及价格范围；来源差异模式须显式开启。",
              "- 每根5分钟结束判断，下一根开盘执行，每根最多一笔，不能还原一根内多次穿格。",
              "- 快速搜索与原完整账本引擎逐字段回归；所有展示候选再用完整引擎复算并导出实际成交和税务现金流。",
              "- 旧股票及ETF报告、JSON未覆盖；新邻居防御校验未改变任何合法邻域或选择。"
              "缓存格式与依赖补齐的逐行不变证明见[首次迁移](../cache_scheme_migration.json)及"
              "[依赖补齐迁移](../cache_dependency_migration.json)，原训练锁定点与训练摘要哈希保持相同。", "",
              "```powershell", "python -m unittest discover -s research/position_following_v2 -p 'test_*.py' -v",
              "python research/position_following_v2/stock_five_search.py --workers 4", "```", "",
              "- `parameter_grid.csv`：全部候选四维参数与训练、验证、全段的两档结果。",
              "- `results.json`：展示候选、全部结果和跨源核验。",
              "- `../training_lock.json`：查看验证之前的锁定点、种子、训练摘要哈希及原时间。",
              "- `*_trades.csv`、`*_cashflows.csv`、`*_marks.csv`、`*_daily.csv`：完整可审计台账。", ""]
    (OUTPUT / "报告.md").write_text("\n".join(lines), encoding="utf-8")
    draw(payload)


if __name__ == "__main__":
    payload = json.loads((OUTPUT / "results.json").read_text(encoding="utf-8"))
    create_report(payload)
