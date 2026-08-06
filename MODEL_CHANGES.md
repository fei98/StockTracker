# 模型修改记录（对应 MODEL_REVIEW.md 评审建议）

> 本文件记录按评审报告（MODEL_REVIEW.md / MODEL_REVIEW_V4.md）建议所做的模型改动，与评审报告分开维护。
> 每轮改动对应代码与测试同步更新。

## v5 改动（对应 MODEL_REVIEW_V4.md 高/中优先级）

### 性能：walkForwardVotedSeries 缓存 ✅ 已实施（V4 高优先级）

**改动文件**：`PredictionEngine.kt`

- 新增 `votedCache`（按股票 + 记录内容 hash 签名）与 `votedSeries(stock, date)` 方法
- `runPrediction()` 的阈值/信心/封顶标定改用缓存序列，避免每次预测 O(n³) 重算
- 记录变化（如结果回填）自动失效；与既有 `walkForwardStats` 缓存同模式
- 新增测试：缓存一致性与记录变化后重算（PredictionEngineTest，全量 147 用例）

### 精度：strengthHistory 使用目标特定权重 ✅ 已实施（V4 中优先级）

**改动文件**：`PredictionEngine.kt`

- 原实现 `combinedStrength(it.factors)` 用 DEFAULT 权重计算强度历史，与目标特定权重的当前值标准化存在统计偏差
- 改为按目标各自权重计算：`strengthHistoryByTarget[t] = records.map { combinedStrength(factors, weightsByTarget[t]) }`
- `standardize()` 各目标传入对应权重历史，消除偏差

## v4 改动（2026-08）

### 8.1 多目标加权投票 ✅ 已实施

**改动文件**：`PredictionEngine.kt`、`Calibration.kt`

- 实时评分 = `0.5 × score_CLOSE_VS_OPEN + 0.25 × score_OPEN30M + 0.25 × score_DAY_VS_PREV`（`Calibrator.VOTE_WEIGHTS`）
- 每个目标用各自前缀数据独立标定权重（含各自的翻转护栏与外围桶条件概率）
- 新增 `Calibrator.walkForwardVotedSeries()`：逐折对三目标分别评分加权合成，实际结果以主目标（CLOSE_VS_OPEN）判定
- 阈值 / 信心 / 封顶全部改用投票前推序列标定
- 三目标结果仍每天 10:00 / 15:05 回填，详情弹窗单独回测展示

### 8.2 板块广度加权化 ✅ 已实施

**改动文件**：`AuctionPredictor.kt`（`sectorBreadth`）

- 由"涨跌家数比"改为幅度加权：`weightedBreadth = Σ(sign(g) × min(|g|, 3)) / Σ max(|g|, 1)`
- 龙头涨 5% 与涨 0.1% 不再等同；单股幅度封顶 3 个百分点（`BREADTH_GAP_CAP`）

### 8.3 合成强度权重纳入搜索 ✅ 已实施

**改动文件**：`PredictionModel.kt`、`AuctionPredictor.kt`、`Calibration.kt`

- `CalibratedWeights` 新增 `targetW` / `indexW` 字段（`sectorW = 1 − targetW − indexW` 自动推导）
- `combinedStrength(f, w)` 参数化；`StandardizedFactors` 重构为存 `strengthRaw + strengthMean + strengthStd`，评分时按候选权重重算（新增 `AuctionPredictor.strengthC()`、`Calibrator.scoreFor()`）
- 坐标下降搜索域扩展至合成权重，有界：targetW ∈ [0.2, 0.8]、indexW ∈ [0.05, 0.45]、sectorW ≥ 0.1

### 8.4 因子交互项 ✅ 已实施

**改动文件**：`AuctionPredictor.kt`、`Calibration.kt`

- 评分公式新增交互项：`interC = interactionScale × tanh(strengthC/2) × tanh(momentumC/2) × 2`
- 同向（竞价高开 + 动量向上）共振加分、反向扣分
- `interactionScale` 纳入坐标下降搜索，范围 [0, 1.5]

### 8.5 硬编码常数参数化 ⏳ 待定（低优先级）

- `FALLBACK_*_DIV`、`VOL_Z_DIV`、`STREAK_DIV`、`TREND_CTX_SCALE`、`PREVDAY_CTX_SCALE` 仍为常量
- 后续建议只搜影响最大的 2-3 个（如 `VOL_Z_DIV`、`STREAK_DIV`），注意维度爆炸

### 8.6 存储层升级 ⏳ 待定（长期演进）

- `SharedPreferences + JSON` → Room (SQLite)，支持增量更新与索引查询
- 当前 200 条记录 × 多股票量级影响可忽略

### 8.7 阈值经验曲线改用 F1 最大化 ✅ 已实施

**改动文件**：`Calibration.kt`（`curveThreshold`）

- 由"找最小满足 ≥60% 命中率的阈值"改为 `argmax F1`（precision = 命中/入选、recall = 命中/全部方向样本）
- 对涨跌样本不均衡更稳健；F1 平值段取最低阈值以保留 recall
- 签名由 `curveThreshold(series, minHit)` 改为 `curveThreshold(series)`，引擎调用同步更新

---

## 配套改动

- **序列化**：`SnapshotStore.kt` 的 `buildSf/parseSf`、`buildWeights/parseWeights` 同步新增字段（旧数据缺省值兼容：`interactionScale=0.5`、`targetW=0.5`、`indexW=0.25`）
- **测试**：新增/更新 8 个用例（交互项、合成权重参数化、scoreFor 一致性、权重搜索边界、投票序列、F1 阈值曲线等），全量 146 用例通过
- **文档**：README 竞价预测段落同步更新

## 验证

```
./gradlew :app:testDebugUnitTest   # 147 用例（v5），0 失败
./gradlew :app:assembleDebug       # 构建通过
```

---

## v6 改动（2026-08-06：盘中实时预测重构 + 数据去重修复）

> 本轮不涉及竞价模型公式本身，方向：把"9:25 竞价一次性预测"的展示层重构为"盘中实时信号"，并修复重复点击预测按钮导致评分逐次下降的 bug。竞价后台引擎（PredictionWorkers）保持不变。

### 1. 新增盘中信号模块 ✅ 已实施

**改动文件**：`IntradaySignal.kt`（新增，纯函数无 IO）

- `IntradaySignalEvaluator.evaluate(points, dayGainPct, prevClose, hasPosition)` 输出 `IntradaySignal`
- 评分（单位=百分点，相对昨收的涨跌幅口径）：
  `score = 1.0×mom15 + 0.8×mom30 + 1.5×rsIndex + 2.0×aboveAvg + 0.8×volBoost`
  - `mom15/mom30`：最近 15/30 分钟涨跌幅
  - `rsIndex`：个股相对板块指数的分钟涨跌差（指数代码 `TencentMarketDataApi.SECTOR_MAP[marketCode]?.indexCode ?: FALLBACK_CONFIG.indexCode`）
  - `aboveAvg`：当前价相对当日均价（VWAP）偏离；`volBoost`：近 5 分钟量比突增（>1.5 倍）
- 阈值：`BUY ≥ +2.5`、`SELL ≤ −2.5`
- **追高拦截**：`(dayGain > 2% 或 mom30 > 1.5%) 且 fromHigh > −0.5%` 时，BUY 降级为 WATCH 并附加"追高风险大"原因
- **持仓分支**：有持仓时 score ≤ −2.5 → SELL，其余情况 HOLD（不再输出 BUY 语义）
- **时段语义**（按分钟列表位置，11:30 与 13:00 均映射位置 120）：
  - 位置 < 40 或 121~150 → NO_TRADE("数据积累中")
  - 位置 = 120 → NO_TRADE("午间休市")
  - 位置 ≥ 240 → NO_TRADE("已收盘")
  - 不足 40 个点 → NO_TRADE("数据积累中")
- `NO_TRADE` 保留评分与原因字段，仅 action 无交易语义（供 UI 展示）

### 2. PredictionViewModel 重写 ✅ 已实施

**改动文件**：`PredictionViewModel.kt`（整体重写）

- 依赖从 `PredictionEngine + PrefsSnapshotStore` 简化为仅 `StockApi`
- 新接口：`refresh(stock, prevClose, hasPosition)`（单股）、`refreshAll(accounts)`（并发全部持仓）
- 新增 `HoldingSignal(stock, signal)` 与 `UiState(allSignals, running, error)`
- 指数分钟线 60 秒缓存（`indexCacheCode/indexCacheAt/indexCachePoints`），多股复用
- 自增 token 竞态保护：旧请求返回时若 token 不匹配则丢弃，防止乱序覆盖

### 3. UI 改造 ✅ 已实施

**改动文件**：`ui/StockApp.kt`

- **预测卡片**：标题"竞价预测"→"盘中实时预测 · {股票名}"；按钮"开始预测"→"刷新"；内容区显示 `SignalBanner`（动作+分数+原因+追高警告）
- **自动刷新**：`LaunchedEffect(predStock?.marketCode)` 切换股票或每 30 秒自动 `refresh`；`refreshAll` 同理在总览页停留期间 30 秒刷新
- **总览第 4 标签**："预测"→"盘中信号"；列表渲染 `predUi.allSignals`（名称+动作+分数+原因摘要）；**每次点击标签立即刷新**（不依赖 30 秒），面板头部新增"刷新"按钮；SegmentedButton 通过 `icon = {}` 去掉选中勾图标（防 4 个标签文字被挤出）
- **移除**：`PredictionDetailDialog`（竞价详情/单目标回测）、`SectorPickerDialog`（行业联动配置入口）、`showPredDetail/showSectorPicker` 状态。竞价引擎本身与通知保持不变，仅 UI 入口下线
- **分时弹窗**（StockViewModel）：`signal: StateFlow<IntradaySignal?>` + 60 秒指数缓存，弹窗内展示信号横幅

### 4. 数据去重修复 ✅ 已实施（bug）

**改动文件**：`SnapshotStore.kt`、`PredictionEngineTest.kt`

- **根因**：`addRecord`/`addSnapshot` 无同日去重。同一股票当天多次点击预测会向 40 天放量基线（volZ）和 200 条历史记录灌入多条当日数据，把当日巨额成交额计入 baseline → volZ 被稀释 → 评分逐次下降；同日快照还污染次日标定
- **修复**：`addSnapshot` 按 `date` 过滤替换同日后 `takeLast(40)`；`addRecord` 同法 `.takeLast(200)`；`FakeSnapshotStore`（测试用）同步去重行为
- **回归测试**：`同日重复预测_不产生重复记录或快照`（3 次点击 → 仅 1 条记录、11 条快照）

### 5. 测试与验证

**新增测试**：`IntradaySignalTest.kt` 12 例（阈值分档、追高拦截、午休/收盘/积累中时段、11:30 与 13:00 同位置取点、持仓分支、放量加分、NO_TRADE 保留评分原因）

```
./gradlew :app:testDebugUnitTest   # 全量通过（PredictionEngineTest 12 例含新回归，IntradaySignalTest 12 例）
./gradlew :app:assembleDebug       # 构建通过
adb install -r app-debug.apk       # 已安装真机验证（设备 3B15C1012F100000）
```

**未改动**：竞价引擎（PredictionEngine/Calibration/AuctionPredictor）、PredictionWorkers（9:15/9:20/9:25 竞价任务与通知）、竞价历史记录仍在积累。
