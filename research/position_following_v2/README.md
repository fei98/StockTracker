# 仓位跟随模型研究 v2

本目录是独立的 Python 研究实现，不修改 Android App 的仓位跟随或周趋势预测代码。

模型依据作者原始草稿及本次讨论确定：正向下跌买、上涨卖；触及成本上下限进入反向；反向运行至中线恢复正向；同一格只要有剩余份额就不能重复买入。完整可复用规则见项目根目录 `POSITION_FOLLOWING_MODEL.md`。

本轮参数：159915，成本下限10000元、上限30000元、中线20000元，价格网格比例1.03，每笔约4000元，总资金50000元，初始底仓约10000元。用户选择完整一份成交并允许越过成本边界。日线收盘判断、下一交易日开盘成交。

## 阅读顺序

1. [回测报告与完整规则](outputs/报告.md)
2. [回测曲线](outputs/回测曲线.png)
3. [全部成交明细](outputs/trades.csv)、[跨格与等待事件](outputs/events.csv)
4. [纯模型实现](model.py)、[回测及数据快照程序](backtest.py)
5. [近两年独立回测报告](two_years/outputs/报告.md)、[两年回测曲线](two_years/outputs/回测曲线.png)
6. [盘中回测及45组参数检验](intraday_study/outputs/报告.md)、[完整参数表](intraday_study/outputs/parameter_grid.csv)
7. [星网锐捷002396固定参数迁移检验](stock_002396/outputs/报告.md)、[股票回测曲线](stock_002396/outputs/回测曲线.png)
8. [002396完整两年5分钟四维参数搜索](stock_002396/fine_search_5m/outputs/报告.md)、[1179组参数明细](stock_002396/fine_search_5m/outputs/parameter_grid.csv)

## 复现

需要 Python 3.10+。模型及单元测试只使用标准库；运行图表需要本机已有的 `matplotlib`。

在项目根目录执行：

```powershell
python -m unittest discover -s research/position_following_v2 -p 'test_*.py' -v
python research/position_following_v2/backtest.py
python research/position_following_v2/backtest_two_years.py
python research/position_following_v2/optimize_intraday.py
python research/position_following_v2/backtest_stock.py
python research/position_following_v2/stock_five_search.py --workers 4
```

本地已有冻结快照时默认复用并核验SHA-256；新检出源码时会按脚本从来源下载。仓库保留源码、测试、摘要报告、图表与参数表，原始行情、任务缓存及大批逐bar台账保留本地。只有明确需要重拉时才使用 `--refresh-data`；供应商修订或历史覆盖变化可能影响复现，完整快照应另行存档。

## 数据与结果边界

- 行情窗口为2026年5月7日至11月6日；按预先定义的小幅下跌条件，于5月19日识别、5月20日开盘建仓。
- 两年回测使用2024年11月7日至2026年11月6日的独立快照，保持全部模型参数与执行规则，保存于`two_years/`；原半年结果保留。
- 盘中研究使用相同窗口的30分钟K线，下一根开盘成交；45组参数按训练期锁定、后段独立初始化检验，并加入最近完整区间5分钟复核。资料保存在`intraday_study/`，训练锁定与事后探索严格分开记录。
- 002396使用原三组参数，不重新搜索；`stock_002396/`独立保存真实未复权数据、公司分红转增、复合仿射网格、股票费税和日终净红利税FIFO账，详见报告假设及核验记录。
- `stock_002396/fine_search_5m/`使用完整两年23280根BaoStock5分钟，搜索上下限、百分比步长和每笔目标金额，保留训练锁及独立后段结果。与近期新浪分钟报价存在明显差异，不能当作已逐bar核实的真实路径；差异未修平，候选另做同窗口跨源核验。具体质量统计及结果性质以本轮报告为准。
- 原始行情、前复权对照和第二供应商响应均保存在 `data/`。资金核算采用未复权价格。
- 单元测试覆盖纯状态机、次日成交、无未来信息、每日最多一笔、数据完整性与资金守恒。
- 这是交易规则研究，不是价格预测算法更新，不修改 `WeeklyTrend.kt` 或其模型评审记录；本目录及报告承担本次研究的规则与结果记录。
- CSV表头使用稳定的英文程序字段；报告解释资金和规则含义，`matched_lots`记录实际抵扣的批次、格子、份额及买价。
- 不将回测收益等同于未来收益；下一轮修改规则后应另保存版本或明确更新研究版本，再重跑全部检查。
