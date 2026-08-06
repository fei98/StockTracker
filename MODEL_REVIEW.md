# 竞价预测模型评审报告

> 按 `MODEL_CHANGES.md` 各轮改动同步更新，与 `MODEL_CHANGES.md` 配合维护。
> **约定：最新评审放最前面（`## v6 评审` 为最新），旧评审依次后移。**

---

## 当前状态（v6）

| 维度 | v3 | v4 | v5 | v6 | 说明 |
|------|-----|-----|-----|-----|------|
| 因子质量 | 8 | 8.5 | 8.5 | 8.5 | 竞价引擎未变；盘中信号为独立规则模型 |
| 评分公式 | 7 | 8 | 8 | 8 | 竞价引擎未变 |
| 标定框架 | 9 | 9 | 9 | 9.5 | +同日去重，历史数据卫生提升 |
| 工程纪律 | 9 | 9 | 9 | 9.5 | +去重回归、并发 token 防护 |
| 性能 | 7 | 6.5 | 7.5 | 7.5 | 竞价引擎未变 |
| 可测试性 | 9 | 9 | 9 | 9.5 | 189 用例（含 IntradaySignalTest 12 例） |

---

## v6 评审（最新，对应 MODEL_CHANGES.md v6）

> 本次为**结合代码复核 `MODEL_CHANGES.md`** 的全面评审：逐项核验 v4/v5/v6 声明与代码一致性，并新发现问题。
> 验证基准：全量 189 用例通过（`gradlew :app:testDebugUnitTest`）。
> 涉及代码：`Calibration.kt`、`AuctionPredictor.kt`、`PredictionEngine.kt`、`PredictionModel.kt`、`SnapshotStore.kt`、`IntradaySignal.kt`、`PredictionViewModel.kt`、`StockViewModel.kt`、`ui/StockApp.kt`。

### 1. 声明与代码一致性核验（v4/v5/v6 全部通过，无虚报）

| MODEL_CHANGES.md 声明 | 代码证据 | 结果 |
|------------------------|----------|------|
| 8.1 多目标投票 0.5/0.25/0.25 | `Calibration.kt:46-50 VOTE_WEIGHTS`；`walkForwardVotedSeries`（Calibration.kt:244-268）逐折三目标前缀权重评分加权合成，实际结果以主目标判定；`PredictionEngine.kt:145` 加权投票 | ✅ |
| 8.2 加权广度 | `AuctionPredictor.kt:52-63`，`BREADTH_GAP_CAP=3.0`，公式与文档一致 | ✅ |
| 8.3 合成权重搜索 | `PredictionModel.kt:67 sectorW=1-targetW-indexW`；`scoreFor` 延迟重算（Calibration.kt:98-105）；约束 targetW∈[0.2,0.8]、indexW∈[0.05,0.45]、sectorW≥0.1（Calibration.kt:201-209） | ✅ |
| 8.4 交互项 | `AuctionPredictor.kt:162-163`，`interactionScale∈[0,1.5]`（Calibration.kt:200） | ✅ |
| 8.7 F1 阈值 | `Calibration.kt:277-302 curveThreshold(series)` argmax F1，调用方同步（PredictionEngine.kt:149） | ✅ |
| v5 votedSeries 缓存 | `PredictionEngine.kt:18-22,217-226`，`records.hashCode()` 签名，`runPrediction` 改用缓存序列 | ✅ |
| v5 strengthHistory 目标特定权重 | `PredictionEngine.kt:127-130` `strengthHistoryByTarget[t]` 用 `weightsByTarget[t]` | ✅ |
| v6 盘中信号 / 去重 / UI | 见 `INTRADAY_SIGNAL_REVIEW.md §1` 代码评审（结论：可合入） | ✅ |

### 2. 新发现问题（本次复核发现，按优先级）

**① 竞价 worker 迟到 → 盘中价污染历史记录（高，建议合入前修复）**
- `isObservationPhase` 只拦截 9:20 前（PredictionEngine.kt:37-42），`runPrediction` 全天任意时刻均可执行。9:25 的 `PredictWorker` 若被 Doze/国产 ROM 延迟到盘中（如 11:00），会用**盘中实时价当竞价因子、把当前价当 open 落库**，并写入当日 `DailySnapshot`（PredictionEngine.kt:174-177）→ 一条错误的"竞价记录"永久进入 walk-forward 标定与放量基线。
- v6 下线 UI 手动预测后，`PredictWorker` 是**唯一记录源**，此漏洞影响放大。
- 建议：`runPrediction`（或 `PredictWorker`）增加交易时段窗口校验（仅 9:20–9:35 可执行），迟到则跳过当天、不落记录。

**② 结果回填迟到 → outcome 误标（中）**
- `recordOutcome30m`（PredictionEngine.kt:182-188）若 10:00 任务延迟，会用更晚的实时价判定"开盘 30 分钟方向"（如 11:00 跑 → 实际是 2 小时方向）；`recordOutcomeClose`（190-200）同理。误标 outcome 会污染三目标标定。
- 建议：回填前校验时间窗口（30 分钟回填取 10:00–10:15 区间价；收盘回填取 ≥15:00 且临近收盘的价）。

**③ F1 阈值曲线的 predicted 标签不随候选阈值重算（中低）**
- `curveThreshold`（Calibration.kt:277-302）对每个候选 t 直接使用 `series[i].predicted`，而该值在 `walkForwardVotedSeries` 中固定按 `REF_THRESHOLD=2.0` 分类（Calibration.kt:263）。当候选 t<2.0 时，|score|∈[t,2.0) 的记录 predicted=FLAT 却计入 precision 分母 → precision 被系统性低估 → 阈值选择偏向 ≥2.0。
- 引擎运行时 `classify(score, threshold)` 用的是选出的阈值，若选出 t<2.0，标定与实际分类不一致。文档"对每个候选 t 的方向预测"与实现不符。
- 建议：对每个候选 t 现场 `classify(score, t)` 重算 predicted；或候选域限定 t≥2.0。

**④ 评分名义范围"±13"偏保守（低）**
- 极端同向（volAmp=+0.5、交互、广度、外围）理论可达 ±19；`capDecision` 返回 null（未触发）时极端分不封顶。影响仅封顶触发与信心邻域稀疏性。

**⑤ 首折单样本跑坐标下降（低/性能）**
- `walkForwardSeries`/`walkForwardVotedSeries` 首折 prefix 仅 1 条，`refine` 仍执行粗搜+细搜，纯噪音。建议 prefix 不足 `MIN_CAL_SAMPLES` 时直接 DEFAULT。

**⑥ 文档一致性（低）**
- `MODEL_CHANGES.md` v5 段"全量 147 用例"与配套改动段"146 用例"不一致（当前全量 189）；建议统一为实测值。
- `MODEL_REVIEW.md` §6 迭代记录原缺 v6 行（本次已补）。

### 3. v6 已评审问题的复核（结论不变，详见 `INTRADAY_SIGNAL_REVIEW.md §1`）

- 代码正确性高、可合入；去重修复正确且必要。
- 模型盲区：**顶部反转盲区**（VWAP 滞后，见 INTRADAY §1.2①）建议加固（mom15>0 确认 + 回落禁 BUY）。
- 测试缺口：AFTERNOON（位置≥151）分支未测（INTRADAY §1.4⑪）；无顶部反转用例（⑫）。
- 其他：非 A 股基准错配、`signedPct` 死代码、指数接口真机待验证。

### 4. 结论

- `MODEL_CHANGES.md` 声明的 v4/v5/v6 改动与代码**一致，无虚报**。
- 最高优先级待修：**① 竞价 worker 迟到污染记录**（建议合入 v7 前修复）；**② 回填窗口校验**次之。
- ③④⑤⑥ 顺带处理；⑦ 竞价详情/命中率展示下线导致"可靠性可视性退化"（产品取舍，提醒）。

---

## 1. 模型架构

```
行情数据（腾讯 qt.gtimg.cn）
    │
    ▼
┌─────────────── 因子层 (AuctionPredictor) ───────────────────────────┐
│  合成强度(三权重可搜索) │ 末端上移 │ 竞价放量 │ 板块广度(幅度加权)   │
│  隔夜美股(三桶) │ 5日动量 │ 昨涨跌幅(动量置信度) │ 连阳(比例缩放)    │
│  20日线偏差(趋势情境) │ 交互项(强度×动量共振)                       │
└─────────────────────────────────────────────────────────────────────┘
    │ 标准化（滚动z / 比例缩放 / 三桶条件概率 / 延迟强度重算）
    ▼
┌─────────────── 评分公式 ────────────────────────────────────────────┐
│  score = (强度 + 末端 + 动量 + 连阳) × (1+volAmp·趋势Ctx)            │
│         + 交互项 + 广度 + 外围                                       │
│  交互项 = interactionScale × tanh(强度/2) × tanh(动量/2) × 2        │
└─────────────────────────────────────────────────────────────────────┘
    │ 三目标各自评分加权投票：0.5×CLOSE_VS_OPEN + 0.25×OPEN30M + 0.25×DAY_VS_PREV
    ▼
┌─────────────── 标定层 (Calibrator) ─────────────────────────────────┐
│  16 预设网格 → 坐标下降粗搜±1.5 → 细搜±0.3（10个字段）              │
│  时间衰减（半衰期30）应用于所有权重评估                              │
│  翻转护栏 → 相邻折预设不一致 → 回落默认                             │
│  F1 最大化经验阈值曲线 → 自适应封顶 {8/6/5}                         │
│  信心等级 = 邻域衰减加权命中率（高/中/低）                           │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────── 编排层 (PredictionEngine) ───────────────────────────┐
│  每股票独立基线/记录/回测                                            │
│  wfCache：walk-forward 统计缓存                                      │
│  votedCache：投票前推序列缓存（v5 新增，records.hashCode 签名）      │
│  定时任务：9:18意图 → 9:20基准 → 9:25预测                          │
│  结果回填：10:00 开盘30分 / 15:05 收盘                              │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 因子层

### 2.1 因子清单

| 因子 | 标准化方式 | 状态 |
|------|-----------|------|
| 合成强度（三权重可搜索） | 滚动 z / 延迟重算（strengthRaw+Mean+Std）| ✅ v4 |
| 末端上移（9:20→9:25） | 滚动 z，null→贡献归零 | ✅ |
| 竞价放量（成交额 z-score） | 滚动 40 天窗口，乘法放大器 ×(1+趋势Ctx) | ✅ |
| 板块广度（幅度加权） | Σ(sign·min(\|g\|,3)) / Σ max(\|g\|,1) | ✅ v4 |
| 隔夜美股（三桶条件概率） | ±0.5%分桶，衰减加权 P(涨\|桶)−P(跌\|桶) | ✅ |
| 5 日动量 | 滚动 z，不足 ÷3.0 | ✅ |
| 昨涨跌幅（动量置信度调制） | momentumZ × (1+0.5·tanh(昨涨跌/1.0)) | ✅ (语义为波动置信度) |
| 连阳天数 | 比例缩放 streak/5.0 | ✅ |
| 20 日线偏差（趋势情境） | 调制放量放大器 ±50% via tanh | ✅ |
| 交互项 | interactionScale × tanh(强度/2) × tanh(动量/2) × 2 | ✅ v4 |
| 板块联动 | 皮尔逊相关 ≥10天，TopK=6，决策链完整 | ✅ |

### 2.2 评分公式

```
score = (strengthC + endC + momentumC + streakC) × (1 + volAmp)
      + interC + breadthC + extC

各项 = 2.0 × tanh(z / weight_scale)，名义范围约 ±13
interC = interactionScale × tanh(strengthC/2) × tanh(momentumC/2) × 2  // v4
volAmp = 0.5·tanh(volZ/1.5) × (1 + 0.5·tanh(trendDev/3.0))              // 趋势情境
momentumZ = momentumRaw × (1 + 0.5·tanh(prevDayPct/1.0))                 // 昨日调制
```

---

## 3. 标定层

### 3.1 权重搜索

| 环节 | 配置 |
|------|------|
| 预设网格 | 16 组 = 4 基础 × 4 动量/连阳组合 |
| 搜索字段 | strengthScale, endScale, breadthScale, extUpScale, extDownScale, momentumScale, streakScale, interactionScale, targetW, indexW |
| 粗搜 | ±1.5 步长 0.5，迭代至收敛 |
| 细搜 | ±0.3 步长 0.1，2 轮 |
| 合成权重约束 | targetW∈[0.2,0.8], indexW∈[0.05,0.45], sectorW≥0.1 |
| 交互项约束 | interactionScale∈[0, 1.5] |
| 下界保护 | MIN_SCALE=0.4 |
| 翻转护栏 | 相邻折基础预设不一致 → 回落默认 |

### 3.2 时间衰减

- 指数衰减，半衰期 30 样本
- 应用范围：sample hit、外部条件概率、阈值曲线、信心映射、分段统计、封顶决策

### 3.3 多目标投票

```
实时评分 = 0.5 × score_CLOSE_VS_OPEN + 0.25 × score_OPEN30M + 0.25 × score_DAY_VS_PREV
```

- 每个目标用各自前缀数据独立标定权重（含各自的翻转护栏与外围桶条件概率）
- 阈值/信心/封顶用 `walkForwardVotedSeries()` 标定
- 三目标结果仍每天 10:00/15:05 回填

### 3.4 阈值曲线（F1 最大化）

- 候选阈值：1.0..6.0 步长 0.5
- 改为 `argmax F1`（precision = 命中/入选，recall = 命中/全部方向样本）
- 对涨跌样本不均衡更稳健；F1 平值段取最低阈值以保留 recall

### 3.5 封顶决策

- 候选集 {8.0, 6.0, 5.0}，从高到低检查
- 尾段衰减加权命中率比中段 [2,6) 低 ≥10pp → 封顶
- 最高候选段可靠 → 不封顶

### 3.6 信心等级

- 邻域 |score 差| ≤ 1.0
- 邻域衰减加权命中率 → 高(≥70%)/中(≥60%)/低(其他)

---

## 4. 编排层

### 4.1 实时预测流程

1. 观察区拦截（9:20 前不出评分）
2. 非 A 股跳过
3. 行情抓取 → 因子计算 → 三目标独立标定 → 加权投票评分 → 分类 → 建议 → 落库
4. 阈值/信心/封顶用缓存投票序列标定

### 4.2 缓存

| 缓存 | 签名 | 内容 | 用途 |
|------|------|------|------|
| `wfCache` | records.hashCode() | Map<TargetType, WalkForwardStats> | 详情弹窗回测统计 |
| `votedCache` | records.hashCode() | List<WFPoint> | 阈值/信心/封顶标定 (v5) |

记录变化（结果回填）自动失效重算。

### 4.3 数据流与持久化

```
SharedPreferences (JSON)
  ├── snapshots      → DailySnapshot[]（放量基线 + 收盘回填）
  ├── stage          → AuctionStageSnapshot（9:18/9:20 竞价快照）
  ├── records        → PredictionRecord[]（历史预测，≤200 条）
  ├── lastResult     → PredictionResult（最新预测）
  ├── universe       → UniverseDailySnapshot[]（候选池联动学习基线）
  └── userSector     → 手选行业配置
```

---

## 5. 测试覆盖

| 测试文件 | 覆盖内容 |
|----------|---------|
| `AuctionPredictorTest.kt` | 合成强度、加权广度、末端、滚动z、回退除数、动量/连阳/昨日调制/趋势情境、交互项、评分、分类、封顶、建议、结果判定、合成权重参数化 |
| `CalibrationTest.kt` | 外围三桶、权重网格、坐标下降、时间衰减、walk-forward 严格性、翻转护栏、F1 阈值曲线(含不均衡样本)、信心映射、自适应封顶、合成权重搜索边界、scoreFor 恒等性、投票序列 |
| `PredictionEngineTest.kt` | 观察区拦截、行情缺失、非A股、预测落库、末端上移、结果回填、动量因子计算、多股票隔离、投票序列缓存(一致性+失效重算) |
| `StockViewModelTest.kt` | 买入/卖出匹配、盈亏计算、T+1 规则、均价计算 |
| `StockPersistenceTest.kt` | 数据保存与恢复 |
| `MarketDataTest.kt` | 行情字段解析 |
| `SectorLearnerTest.kt` | 皮尔逊相关、自动推荐、关键词匹配 |

> 全量 189 用例，0 失败（含 IntradaySignalTest 12 例、同日去重回归 1 例）。

---

## 6. 迭代记录

| 轮次 | 改动 | 对应评审 |
|------|------|---------|
| v1 | 初始：6 因子 + 4 预设 + walk-forward + 封顶 | — |
| v2 | 动量因子(4项)、16预设、坐标下降、时间衰减、情境判断 | — |
| v3 | 连阳比例缩放、prevDayPct评分、封顶自适应、坐标下降先粗后细、分段衰减加权、wfCache | — |
| v4 | 多目标投票、加权广度、合成权重搜索、交互项、F1阈值 | 见 v4 评审 |
| v5 | votedSeries缓存、strengthHistory目标特定权重 | 见 v5 评审 |
| v6 | 盘中实时信号重构、同日去重修复、UI 下线竞价详情 | 见 v6 评审（顶部） |

---

## 7. 剩余待办

| 优先级 | 事项 | 状态 |
|--------|------|------|
| 高 | 竞价 worker/结果回填 增加交易时段窗口校验（v6 评审 ① ②） | ⏳ 待修 |
| 低 | 8.5 硬编码常数参数化 (VOL_Z_DIV / STREAK_DIV 等) | ⏳ 待定 |
| 低 | 8.6 存储层升级 SharedPreferences → Room | ⏳ 待定 |

---

## v4 评审（对应 MODEL_CHANGES.md v4）

### 改动列表

| # | 改动 | 涉及文件 |
|---|------|---------|
| 8.1 | 多目标加权投票 | PredictionEngine.kt, Calibration.kt |
| 8.2 | 板块广度加权化 | AuctionPredictor.kt |
| 8.3 | 合成强度权重纳入搜索 | PredictionModel.kt, AuctionPredictor.kt, Calibration.kt, SnapshotStore.kt |
| 8.4 | 因子交互项 | AuctionPredictor.kt, Calibration.kt |
| 8.7 | 阈值经验曲线改用 F1 最大化 | Calibration.kt, PredictionEngine.kt |

### 逐项审计

**8.1 多目标加权投票** ✅

- `VOTE_WEIGHTS` = 0.5/0.25/0.25，三目标各自 `liveWeights()` + `externalContribs()` + `standardize()`
- `walkForwardVotedSeries()` 每折对三目标独立评分加权合成，实际结果以 CLOSE_VS_OPEN 判定
- 冷启动期：OPEN30M 和 DAY_VS_PREV 的 outcome 回填不足时，`inSampleHit` 跳过 → 回退默认权重

**8.2 板块广度加权化** ✅

- `weightedBreadth = Σ(sign(g)×min(|g|,3)) / Σ max(|g|,1)`
- `BREADTH_GAP_CAP = 3.0`，分母 `max(|g|,1)` 防止微小波动放大

**8.3 合成强度权重纳入搜索** ✅

- `CalibratedWeights` 新增 `targetW`/`indexW`（`sectorW = 1-targetW-indexW`）
- `StandardizedFactors` 改为 `strengthRaw+Mean+Std`（延迟重算）
- `scoreFor()`：`base - baseStrengthC + strengthC(recomputed)`，补丁策略保证 OOS 正确性
- 搜索约束：`withValue()` 中 targetW+indexW ≤ 0.9（sectorW ≥ 0.1）
- 序列化兼容：`optDouble("interactionScale", 0.5)` / `optDouble("targetW", 0.5)` / `optDouble("indexW", 0.25)`

**8.4 因子交互项** ✅

- `interC = interactionScale × tanh(strengthC/2) × tanh(momentumC/2) × 2`
- 同向共振加分，反向扣分；独立于 volAmp 乘法
- `interactionScale ∈ [0, 1.5]`，最大贡献约 ±1.16 vs 主项 ±8

**8.7 F1 最大化阈值** ✅

- `curveThreshold(series)` → `argmax F1`（precision + recall 衰减加权）
- 签名变更，调用方 (`PredictionEngine.kt:139`) 同步

### 发现的问题（v4 评审）

1. **性能**：`walkForwardVotedSeries()` 每次预测 O(n³)，已在 v5 修复
2. **统计偏差**：`strengthHistory` 用 DEFAULT 权重，已在 v5 修复

---

## v5 评审（对应 MODEL_CHANGES.md v5）

### 改动列表

| # | 改动 | 涉及文件 |
|---|------|---------|
| 1 | votedSeries 缓存 | PredictionEngine.kt |
| 2 | strengthHistory 目标特定权重 | PredictionEngine.kt |

### 逐项审计

**1. votedSeries 缓存** ✅

- `votedCache`：`Map<stockCode, Pair<records.hashCode(), List<WFPoint>>>`，与 `wfCache` 同模式
- `runPrediction()` 调用 `votedSeries()` 而非直接 `walkForwardVotedSeries()`
- 测试 (`PredictionEngineTest.kt:196-214`)：缓存命中返回相等结果；`updateOutcomes` 后 hashCode 变化 → 缓存失效重算

**2. strengthHistory 目标特定权重** ✅

```kotlin
// before:
strengthHistory = records.mapNotNull { combinedStrength(it.factors) }  // DEFAULT

// after:
strengthHistoryByTarget[t] = records.mapNotNull { combinedStrength(it.factors, weightsByTarget[t]!!) }
```

- `standardize()` 传入的 `strengthHistoryByTarget[t]` 与 `weightsByTarget[t]` 一致
- 消除了 DEFAULT 权重历史 + 目标特定权重当前值之间的统计基线偏差

### 发现的问题（v5 评审）

无。

---

*本报告为数据参考，不构成投资建议。*
