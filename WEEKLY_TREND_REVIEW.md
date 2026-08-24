# 未来一周趋势预测评审（WEEKLY_TREND_REVIEW）

> 独立评审文档，配合 `WEEKLY_TREND_PLAN.md` 与 `MODEL_CHANGES.md` 维护。
> **约定：最新评审放最前面。** 本期为 v1 代码评审（对应 `MODEL_CHANGES.md` v14：周趋势上线，盘中信号 + 竞价预测下线）。

## v1 代码评审（最新）

> 验证基准：全量 154 用例通过（`gradlew :app:testDebugUnitTest`）；`assembleDebug` 构建通过。

### 1. 声明与代码一致性（全部通过，无虚报）

| v14 声明 | 代码证据 | 结果 |
|---------|----------|------|
| 周趋势仅 A 股、目标未来 5 日方向、±1% 震荡带 | `WeeklyTrend.kt` `isAStock` / `HORIZON=5` / `FLAT_BAND_PCT=1.0` / `outcome5d` | ✅ |
| 日K接口与解析 | `StockApi.fetchDailyKline` + `parseDailyKline`（qfqday 正则，括号深度取数组） | ✅ |
| 特征只用 `[0,i]`、阈值只用前推前缀（out-of-sample） | `featuresAt`/`scoreAt` 滚动窗口 = `[i-119, i]`；`walkForward` 每折 `curveThreshold(前缀)` | ✅ |
| F1 阈值 / 邻域信心 / 时间衰减 | `curveThreshold`（候选 t 现场重分类）/ `confidence` / `decayWeight` | ✅ |
| 界面展示方向+分数+信心+回测命中率 | `WeeklyTrendCard`/`TrendResultBanner`/`WeeklyTrendPanel` | ✅ |
| 旧两套模型与相关 Worker/通知/UI 下线 | 删除 14 个主文件 + 7 个测试；`MainActivity` 去除调度；Manifest 去除通知权限；移除 `androidx.work` | ✅ |
| 分时图保留、仅去信号横幅 | `StockViewModel.loadIntraday` 仅拉分钟线；`IntradayDialog` 去 `signal` | ✅ |

### 2. 独立复核补充

- **结构正确性**：日K解析按括号深度取 `qfqday` 数组整段，避免 `qt`/`market`/`mx_price` 其它数组误匹配；行正则限定 `yyyy-MM-dd` 开头，无重复。
- **无偷看未来**：`walkForward` 从 `MIN_BARS` 开始、到 `size-1-HORIZON` 止；第 i 折阈值由前缀拟合；`forecast` 只用最后一根 K 线之前的数据。
- **异常路径**：K线 <120 根 → FLAT + "数据积累中" + 无回测；指数缺失 → `indexRS20=null` 降级（`hasIndexData=false`），不阻塞出预测。
- **UI 集成**：首页卡 + 总览"周趋势"标签 + 展开详情，均复用既有卡片/列表风格；不再有 30 秒自动刷新（多日维度）。
- **遗留**：权重为经验值未做逐因子权重搜索（阈值已标定）；"匀速上涨"下动量 z 偏中性属相对动量口径；均以回测数据为准，待真机观察。

### 3. 结论

v14 声明与代码一致；旧模型下线干净（无悬挂引用）；154 用例全绿；`assembleDebug` 通过。周趋势模型可交付真机验证。

---

## 旧模型退役说明

- 盘中实时信号（`INTRADAY_SIGNAL_PLAN/REVIEW.md`）与竞价预测（`MODEL_REVIEW.md`）已于 v14 下线，文档保留仅作历史存档。
- 原"买在当日高点"结构性缺陷（`aboveAvg` 在顶部取最大）随盘中信号一并移除。

---

*本评审为数据参考，不构成投资建议。*
