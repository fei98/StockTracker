# 竞价预测模型评审报告

> 按 `MODEL_CHANGES.md` 各轮改动同步更新，与 `MODEL_CHANGES.md` 配合维护。
> 后续评审直接在末尾追加 `## vN 评审` 章节。

---

## 当前状态（v5）

| 维度 | v3 | v4 | v5 | 说明 |
|------|-----|-----|-----|------|
| 因子质量 | 8 | 8.5 | 8.5 | +加权广度、+交互项 |
| 评分公式 | 7 | 8 | 8 | +交互项、+多目标投票 |
| 标定框架 | 9 | 9 | 9 | +合成权重搜索、+F1 阈值 |
| 工程纪律 | 9 | 9 | 9 | OOS 严格，缓存到位 |
| 性能 | 7 | 6.5 | 7.5 | +votedSeries 缓存，消除 O(n³) 瓶颈 |
| 可测试性 | 9 | 9 | 9 | 147 用例 |

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

> 全量 147 用例，0 失败。

---

## 6. 迭代记录

| 轮次 | 改动 | 对应评审 |
|------|------|---------|
| v1 | 初始：6 因子 + 4 预设 + walk-forward + 封顶 | — |
| v2 | 动量因子(4项)、16预设、坐标下降、时间衰减、情境判断 | — |
| v3 | 连阳比例缩放、prevDayPct评分、封顶自适应、坐标下降先粗后细、分段衰减加权、wfCache | — |
| v4 | 多目标投票、加权广度、合成权重搜索、交互项、F1阈值 | 见 v4 评审 |
| v5 | votedSeries缓存、strengthHistory目标特定权重 | 见 v5 评审 |

---

## 7. 剩余待办

| 优先级 | 事项 | 状态 |
|--------|------|------|
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
