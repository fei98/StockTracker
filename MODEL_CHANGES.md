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
