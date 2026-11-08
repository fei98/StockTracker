"""Cross-stock transfer check of three previously fixed parameter sets, without retuning."""

import argparse
from dataclasses import replace
import hashlib
import json
from pathlib import Path

from backtest import D, json_default, money, pct, write_csv, write_json
from backtest_two_years import period_returns
from stock_data import ROOT, load_stock, load_stock_five
from stock_intraday import simulate_stock
from stock_model import ACTIONS, StockConfig


BASE = Path(__file__).resolve().parent
OUTPUT = ROOT / "outputs"
PARAMETERS = {
    "original": StockConfig(),
    "balance": replace(StockConfig(), lower=D("15000"), upper=D("20000"), ratio=D("1.04")),
    "return": replace(StockConfig(), lower=D("15000"), upper=D("30000"), ratio=D("1.05")),
}
NAMES = {"original": "原参数10000／30000／3%", "balance": "均衡候选15000／20000／4%",
         "return": "收益候选15000／30000／5%"}


def export(label, result):
    for table in ("trades", "events", "cashflows", "marks", "daily"):
        rows = result[table]
        fields = list(dict.fromkeys(key for row in rows for key in row))
        normalized = [{key: row.get(key, "") for key in fields} for row in rows]
        write_csv(OUTPUT / f"{label}_{table}.csv", normalized)


def draw(results):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates
    from datetime import datetime

    plt.rcParams.update({"font.sans-serif": ["Microsoft YaHei", "SimHei", "DejaVu Sans"],
                         "axes.unicode_minus": False, "font.size": 10})
    fig, axes = plt.subplots(3, 1, figsize=(13, 12))
    colors = {"original": "#2563eb", "balance": "#0d9488", "return": "#9333ea"}
    for label in PARAMETERS:
        for slip in (0, 1):
            result = results[f"{label}_full_{slip}tick"]
            days = result["daily"]
            axes[0].plot([datetime.fromisoformat(row["date"]) for row in days],
                         [float(row["equity"]) for row in days], color=colors[label],
                         linestyle="--" if slip else "-", alpha=.6 if slip else 1,
                         label=NAMES[label] if not slip else None)
        result = results[f"{label}_validation_0tick"]
        axes[1].plot([datetime.fromisoformat(row["date"]) for row in result["daily"]],
                     [float(row["equity"]) for row in result["daily"]], color=colors[label], label=NAMES[label])
    days = results["original_full_0tick"]["daily"]
    for key, color, label in [("equity", "#2563eb", "原参数策略"),
                               ("same_initial_hold", "#d97706", "相同底仓＋现金持有"),
                               ("full_buy_hold", "#64748b", "全仓持有（含分红转增）")]:
        axes[2].plot([datetime.fromisoformat(row["date"]) for row in days],
                     [float(row[key]) for row in days], color=color, label=label)
    axes[0].set_title("连续两年；实线零滑点，虚线每侧0.01元不利滑点")
    axes[1].set_title("独立后段检验：2025年11月起，各自从5万元重新建仓（零滑点）")
    axes[2].set_title("原参数与持有基准：同日起点、同资金、同分红转增处理")
    for axis in axes:
        axis.axhline(50000, color="#94a3b8", linestyle=":", linewidth=1)
        axis.set_ylabel("权益 / 元")
        axis.grid(alpha=.16)
        axis.legend(frameon=False, ncol=2, fontsize=9)
        axis.spines[["top", "right"]].set_visible(False)
        axis.xaxis.set_major_locator(mdates.MonthLocator(interval=3))
        axis.xaxis.set_major_formatter(mdates.DateFormatter("%Y-%m"))
    fig.suptitle("星网锐捷002396：固定参数迁移检验", fontsize=16)
    fig.text(.06, .012, "30分钟收盘信号／下一根开盘成交；股票费用、现金分红、转增及日终红利税已计入。", color="#475569")
    fig.tight_layout(rect=[0, .035, 1, .965], h_pad=2)
    fig.savefig(OUTPUT / "回测曲线.png", dpi=160, facecolor="white")
    plt.close(fig)


def report(payload):
    summaries = payload["results"]
    initial = summaries["original_full_0tick"]
    lines = ["# 星网锐捷002396：固定三组参数的两年迁移回测", "",
             "> 研究日期：2026年11月7日。本轮未对002396重新搜索参数；三组均来自此前159915研究。", "",
             f"窗口2024年11月7日至2026年11月6日，485个交易日、3880根30分钟K线。"
             f"按相同小幅下跌起点规则于 **{initial['entry_date']}** 建仓，实际运行{initial['active_days']}个交易日。", "",
             "## 1. 连续两年结果", "",
             "总资金50000元，初始底仓约10000元，每笔约4000元；改变下限不改变初始投入。"
             "初始仍从正向阶段开始，完整一份成交允许跨过成本上下限和中线。", "",
             "| 参数 | 期末权益，零滑点 | 收益0／1档滑点 | 回撤0／1档滑点 | 成交数，零滑点 | 交易费税，零滑点 |",
             "|---|---:|---:|---:|---:|---:|"]
    for label in PARAMETERS:
        a, b = [summaries[f"{label}_full_{tick}tick"] for tick in (0, 1)]
        lines.append(f"| {NAMES[label]} | {money(a['equity'])} | {pct(a['return_pct'])} / {pct(b['return_pct'])} | "
                     f"{pct(a['max_drawdown_pct'])} / {pct(b['max_drawdown_pct'])} | {a['trade_count']} | {money(a['fees'])} |")
    lines += ["", "所有收益均含已到账分红、已实扣红利税和交易费税，期末持仓按收盘计价、不强制卖出。"
              "回撤是30分钟收盘权益回撤，不能等同逐笔盘中真实最大回撤。股票一档为0.01元。", "",
              "**滑点后收益可能更高，不代表滑点有利。** 初始成交锚点、格子触发、整数股数和后续阶段均依赖成交价格；"
              "这会改变订单路径。原参数与均衡候选两种滑点的收益差较大，应视为路径敏感性，不能只展示较高的一档。", "",
              "![回测曲线](回测曲线.png)", "", "## 2. 独立后段检验", "",
              "后段窗口为2025年11月7日至2026年11月6日，各组都重新从50000元和约10000元底仓开始，"
              "使用同样的起点条件，不能与连续两年收益拼接。参数没有根据该股票后段结果改选。", "",
              "| 参数 | 建仓日 | 收益0／1档 | 回撤0／1档 | 两档回撤均≤20% |", "|---|---|---:|---:|---|"]
    for label in PARAMETERS:
        a, b = [summaries[f"{label}_validation_{tick}tick"] for tick in (0, 1)]
        passed = max(a["max_drawdown_pct"], b["max_drawdown_pct"]) <= 20
        lines.append(f"| {NAMES[label]} | {a['entry_date']} | {pct(a['return_pct'])} / {pct(b['return_pct'])} | "
                     f"{pct(a['max_drawdown_pct'])} / {pct(b['max_drawdown_pct'])} | {'是' if passed else '否'} |")
    lines += ["", "这只股票上，完整区间收益最高不等于后段风险符合要求；收益候选5%组的后段回撤超过20%。"
              "原参数在两档滑点的后段检验中均低于20%，且后段收益高于另两组。"
              "这是跨标的研究证据，不是未来回撤保证，也不能证明单一参数适合所有股票。", "",
              "## 3. 与159915相同参数对照", "",
              "| 参数 | 159915两年收益／回撤，零滑点 | 002396两年收益／回撤，零滑点 |", "|---|---:|---:|"]
    for label, reference in payload["etf_reference"].items():
        s = summaries[f"{label}_full_0tick"]
        lines.append(f"| {NAMES[label]} | {pct(reference['return_pct'])} / {pct(reference['max_drawdown_pct'])} | "
                     f"{pct(s['return_pct'])} / {pct(s['max_drawdown_pct'])} |")
    lines += ["", "股票使用0.01元报价、股票税费；ETF使用0.001元报价及ETF费用。"
              "两者遵循相同起点条件，但实际建仓日期不同：002396为2024年11月15日，159915为11月26日。"
              "所以这是参数迁移观察，不是严格同时建仓的标的排名；标的也不是随机抽样，不能据此认定002396未来更适合。", "",
              "## 4. 买入持有基准", "",
              "| 原参数对应基准 | 期末权益 | 两年收益 | 30分钟收盘回撤 |", "|---|---:|---:|---:|",
              f"| 策略 | {money(initial['equity'])} | {pct(initial['return_pct'])} | {pct(initial['max_drawdown_pct'])} |",
              f"| 相同底仓＋现金持有 | {money(initial['same_initial_hold_equity'])} | {pct(initial['same_initial_hold_return_pct'])} | {pct(initial['same_initial_hold_drawdown_pct'])} |",
              f"| 50000元尽量全仓持有 | {money(initial['full_buy_hold_equity'])} | {pct(initial['full_buy_hold_return_pct'])} | {pct(initial['full_buy_hold_drawdown_pct'])} |", "",
              "两个基准也按同日开盘买入、相同买入费用，接收现金分红及转增股份；不将复权价当实际资金成交价。"
              "全仓暴露更高，收益及回撤应同时看。", "",
              "## 5. 分年表现（连续账户，零滑点）", "",
              "| 参数 | 年份 | 净盈亏 | 年内收益率 |", "|---|---|---:|---:|"]
    for label, years in payload["yearly"].items():
        for year in years:
            lines.append(f"| {NAMES[label]} | {year['period']} | {money(year['net_profit'])} | {pct(year['return_pct'])} |")
    lines += ["", "2024年和2026年均非完整年度；以该段开始前的账户权益为分母，不逐年重置资金或持仓。", "",
              "## 6. 分红、转增、网格与红利税", "",
              "| 事件 | 实际股东权益 | 价格参考参数 |", "|---|---|---|",
              f"| [2025-05-30]({ACTIONS[0].source}) | 每股现金0.25元；登记日05-29 | 每股除息参考0.2487976元 |",
              f"| [2026-05-25]({ACTIONS[1].source}) | 每股现金0.25元，10转3；登记日05-22 | 参考现金0.2487916，参考转增0.2985500 |", "",
              "实际到账比例与价格参考比例不同，原因是公司回购专户不参与分配。"
              "持仓股数按实际1.3倍增加、原始批次成本保持相同，每股成本相应下降；现金分红计入现金和收益，不再减买入成本。", "",
              "每个固定格子按公告公式整体变换：2025年P'=P−0.2487976；2026年P'=(P−0.2487916)/1.2985500。"
              "采用scale×原网格＋offset的复合仿射变换，保留格子编号和阶段，不只改锚点重建等比网格。"
              "除权日前已排队的信号按预定规则取消，等调整后的新bar再判断，不按当天行情择优保留。"
              "若恰好除权日才新建仓，则不获得之前登记权益，也不再次调整已除权的初始锚点。", "",
              "转增股到账日可卖，与普通当天买入的T+1冻结分开。转增会产生30、60、90股零股，"
              "只要格子仍有股票就占用；常规买卖仍按100股整手及完整每份规则。"
              "本轮转增前持仓均为整手，新增数量均为整数股，没有不足1股的随机分配问题。", "",
              "红利税另设FIFO账，与记账高低价匹配、券商可卖批次分开。派息先到账，按每日日终净减股数结算："
              "持有≤1个自然月税率20%，超过1个月但≤1个自然年10%，超过1年免税，周年当日仍在含当天的边界。"
              "同日卖出又买回、净持仓未减少，不按盘中卖出逐笔补扣。"
              "税额在当日收盘扣除，比券商后续实际划扣时刻略保守。", "",
              "新增转增股份单列为到账日取得的税务批次，不附带原股的历史现金分红权利。"
              "该新增股份税务取得日未找到足够明确的权威细则，列为假设；取得日可能影响FIFO顺序，"
              "进而影响原有股份历史红利税的补扣。不能因其后没有新分红就断言税额不受影响。"
              "本轮固定成交路径的替代排序核验见[验证记录](验证记录.md)。"
              "期末未卖出的潜在税额另报，不冒充已实际扣税。", "",
              "| 参数（零滑点） | 分红到账 | 已扣红利税 | 期末若全卖潜在红利税 | 佣金 | 印花税 | 过户费 |",
              "|---|---:|---:|---:|---:|---:|---:|"]
    for label in PARAMETERS:
        s = summaries[f"{label}_full_0tick"]
        lines.append(f"| {NAMES[label]} | {money(s['dividends'])} | {money(s['dividend_tax'])} | "
                     f"{money(s['potential_dividend_tax_if_sold'])} | {money(s['commission'])} | {money(s['stamp_tax'])} | {money(s['transfer_fee'])} |")
    lines += ["", "股票交易费用口径：双边佣金万2.5、每笔最低5元；双边过户费万0.1；卖出印花税0.05%。"
              "各费用分项按分四舍五入，实际券商结算可能有分位差异。", "",
              "规则资料：[财税2015-101号](https://www.chinatax.gov.cn/chinatax/n810341/n810765/n1465977/n1466017/c1967339/content.html)、"
              "[中国结算FAQ转载](https://www.csrc.gov.cn/shenzhen/c105614/c1575308/content.shtml)、"
              "[印花税减半公告](https://www.mof.gov.cn/jrttts/202308/t20230828_3904235.htm)。", "",
              "## 7. 近期5分钟复核", "",
              f"最近完整区间{payload['five_minute_metadata']['first_time']}—{payload['five_minute_metadata']['last_time']}，"
              f"共{payload['five_minute_metadata']['complete_days']}天；首个不完整日已剔除。"
              "30分钟与5分钟使用共同日期、共同起点及资金，仍不重新选参数。", "",
              "| 参数 | 采样 | 建仓日 | 收益0／1档 | 回撤0／1档 |", "|---|---|---|---:|---:|"]
    for row in payload["five_minute"]:
        a, b = row["summaries"]
        lines.append(f"| {NAMES[row['label']]} | {row['minutes']}分钟 | {a['entry_date']} | "
                     f"{pct(a['return_pct'])} / {pct(b['return_pct'])} | {pct(a['max_drawdown_pct'])} / {pct(b['max_drawdown_pct'])} |")
    lines += ["", "这一短区间不能代替两年，也不能等同逐笔触价成交。", "",
              "## 8. 数据核验和适用边界", "",
              "- 两年日线由腾讯未复权与新浪逐日核对；30分钟每天8根，聚合OHLC与日线完全一致。",
              f"- 原始与前复权有{payload['metadata']['checks']['adjusted_difference_days']}天差异，"
              f"按两次公告价格公式解释全部差异，最大价格误差{payload['metadata']['checks']['explained_adjustment_max_error']}元，小于0.001元。",
              "- 使用真实未复权价格成交，前复权只用于独立核对，不把转增造成的原价跳变当作网格下跌。",
              "- 未模拟盘口、封板无法成交、停牌排队或冲击成本；下一根开盘可成交是一项假设。",
              "- 每bar核对权益＝初资＋交易已实现毛利＋浮动毛利−交易费税＋分红−已扣红利税。",
              "- 三组参数未在002396上重新搜索，不能根据表现再改称此前独立训练的赢家；本轮也只是一只额外股票。", "",
              "## 9. 复现与产物", "", "```powershell",
              "python -m unittest discover -s research/position_following_v2 -p 'test_*.py' -v",
              "python research/position_following_v2/backtest_stock.py", "```", "",
              "默认冻结快照，`--refresh-data`才重新下载。`results.json`保留全部汇总，"
              "`*_trades.csv`含股票费税及实际批次，`*_cashflows.csv`含分红、转增、日终净红利税和FIFO明细，"
              "`*_marks.csv`为每bar权益，`yearly.csv`为连续账户的分年表现。", ""]
    (OUTPUT / "报告.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh-data", action="store_true")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    protected = [BASE / folder / "outputs" / filename for folder in ("two_years", "intraday_study")
                 for filename in ("报告.md", "results.json" if folder == "two_years" else "study_results.json")]
    before = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in protected}
    daily, bars, metadata = load_stock(args.refresh_data)
    validation = [bar for bar in bars if bar["time"][:10] >= "2025-11-07"]
    results = {}
    yearly = {}
    for label, config in PARAMETERS.items():
        for period_name, period in (("full", bars), ("validation", validation)):
            for tick in (0, 1):
                key = f"{label}_{period_name}_{tick}tick"
                result = simulate_stock(period, replace(config, slippage_ticks=tick))
                results[key] = result
                export(key, result)
        yearly[label] = period_returns(results[f"{label}_full_0tick"]["daily"], config.capital)
    five, metadata5 = load_stock_five(daily, args.refresh_data)
    common = [bar for bar in bars if bar["time"][:10] >= five[0]["time"][:10]]
    entry_date = simulate_stock(common)["summary"]["entry_date"]
    checks = []
    for label, config in PARAMETERS.items():
        for minutes, data in ((30, common), (5, five)):
            pair = []
            for tick in (0, 1):
                result = simulate_stock(data, replace(config, slippage_ticks=tick), entry_date=entry_date)
                export(f"recent_{label}_{minutes}m_{tick}tick", result)
                pair.append(result["summary"])
            checks.append({"label": label, "minutes": minutes, "summaries": pair})
    old = json.loads((BASE / "intraday_study" / "outputs" / "study_results.json").read_text(encoding="utf-8"))
    etf = {}
    for label, role in (("original", "original"), ("balance", "exploratory_balance"), ("return", "exploratory_return")):
        source = old["detailed"][f"{role}_full"]["summary"]
        etf[label] = {"return_pct": D(source["return_pct"]), "max_drawdown_pct": D(source["max_drawdown_pct"])}
    payload = {"metadata": metadata, "results": {key: value["summary"] for key, value in results.items()},
               "yearly": yearly, "etf_reference": etf, "five_minute_metadata": metadata5, "five_minute": checks}
    write_json(OUTPUT / "results.json", payload)
    write_csv(OUTPUT / "yearly.csv", [dict(parameter=label, **row) for label, years in yearly.items() for row in years])
    draw(results)
    report(payload)
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path, digest in before.items()):
        raise AssertionError("An earlier research artifact changed.")
    print(json.dumps({"results": {key: {field: s[field] for field in
                      ("entry_date", "equity", "return_pct", "max_drawdown_pct", "trade_count", "dividends", "dividend_tax")}
                      for key, s in payload["results"].items()},
                      "recent": [{"label": row["label"], "minutes": row["minutes"],
                                  "returns": [s["return_pct"] for s in row["summaries"]],
                                  "drawdowns": [s["max_drawdown_pct"] for s in row["summaries"]]}
                                 for row in checks],
                      "output": str(OUTPUT)}, ensure_ascii=True, default=json_default))


if __name__ == "__main__":
    main()
