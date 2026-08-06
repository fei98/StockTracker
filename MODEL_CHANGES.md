# 模型修改记录（对应 MODEL_REVIEW.md 评审建议）

> 本文件记录按评审报告建议所做的模型改动，与评审报告（MODEL_REVIEW.md / INTRADAY_SIGNAL_REVIEW.md）分开维护。
> **约定：最新改动放在最前面，旧改动依次下移。每一轮均对应代码与测试同步更新。**

## v12 改动（2026-08-07：调休周末采集链判据修正）

> 依据 `INTRADAY_SIGNAL_REVIEW.md` v11 §4a：`continueOnWeekend` 以"是否采到方向信号"为判据，在调休周末交易日的 WATCH/HOLD slot 或午休时段会误判为非交易日，导致 10 分钟采集链中断、当日剩余时段不再采集。

### 1. 交易日判定改用行情时间戳日期 ✅

**代码**：`PredictionWorkers.kt`、`MarketData.kt`（新增纯函数 `quoteTimeIsOnDate`）

- `IntradaySignalWorker` 在运行起始判定"今日是否真实交易日"：任意持仓的行情时间戳日期（腾讯 `qt.gtimg.cn` 字段 30，格式 `YYYYMMDDHHMMSS`）== 今天（Asia/Shanghai）→ `isTradingDay = true`。
- 普通周末：行情时间戳是上一交易日 → false → 排班顺延下周一（一次空跑）；调休周末交易日：行情时间戳是今天 → true → 10 分钟采集链全天延续（含午休 slot，不再因无方向信号中断）。
- 替代 v11 的 `capturedAny`（是否存了方向快照）判据。
- 新增 `quoteTimeIsOnDate(time, yyyyMMdd)`：容忍分隔符变体（`20260807102532` / `2026-08-07 10:25:32`），可单测。

### 2. 测试与验证

- `MarketDataTest` 新增用例：行情时间戳日期判定（同日/上一交易日/空/前缀不足，含分隔符变体）。
- 实测 `gradlew :app:testDebugUnitTest` = **204/0 通过**；`assembleDebug` 构建通过。

**仍待数据积累**：阈值/权重标定（v3 主体）；回填用当前价近似（v10 §4d，标定时按 outcome30Ms 过滤评估）。

---

## v11 改动（2026-08-07：v10 评审剩余小问题收尾）

> 依据 `INTRADAY_SIGNAL_REVIEW.md` v10 评审：收口 v7 残余中间带、v8 ③ 锁粒度、v10 §2 周末采集折中、v10 §4 注意项 b/c/e。

### 1. 残余中间带收口 ✅（v7 §3）

- `BUY_FROM_HIGH_MAX` 由 −1.0 收紧到 −0.5：距日内高点回落 ≥0.5% 禁买，消除 `fromHigh ∈ (−1.0, −0.5]` 且短动量仍正时可出 BUY 的盲区；测试用例 25 固化边界。
- 行为更保守："回踩买点"（回落 0.5%–1%）不再出 BUY，与"不追回落"护栏语义一致；待数据积累后可参数化回退。

### 2. 顶部反转原因覆盖补齐 ✅（v7 §3 轻微项）

- 双条件同时命中（短动量转弱 + 距高点回落）时两个原因都展示，不再 if/else if 只显示一个。

### 3. 指数分钟缓存进程级共享 ✅（v8 ③）

- 新增 `IndexMinuteCache`（60 秒 TTL + Mutex 双重校验），`StockViewModel` / `PredictionViewModel` 共用，消除同进程内每 VM 各发一次指数请求的重复。

### 4. 方向语义显式化 ✅（v10 §4e）

- `IntradaySignal` 新增 `direction` 字段（BUY→看涨 / SELL→看跌 / 看跌 NO_TRADE→看跌 / 其余 null），`directionOf` 直接取字段，不再依赖"看跌"文案字符串匹配（文案变化不再静默失效）。

### 5. 引擎与调度纵深防御 ✅（v10 §4b/4c）

- `runPrediction` 引擎层增加 `isPredictionWindow` 守卫：即使未来恢复手动预测入口，盘中价也不会污染历史记录。
- `PredictionScheduler.delayToNext` 统一 Asia/Shanghai 时区：设备时区错置时 9:25 调度与窗口校验不再互相矛盾。

### 6. 调休周末后台自动采集 ✅（v10 §2 折中收口）

- `scheduleIntradayNext(continueOnWeekend)`：首次调度允许落在周末（调休交易日 10:10 自动采集，采集到方向信号后 10 分钟链继续）；普通周末空跑一次后自动跳到下周一。
- 原"调休周末仅手动刷新"折中解除。

### 7. 测试与验证

- 新增用例 25（回落中间带）；方向映射改为显式语义 + 集成断言；PredictionEngineTest 全部 `runPrediction` 调用改到 9:25 窗口内。
- 实测 `gradlew :app:testDebugUnitTest` = **203/0 通过**；`assembleDebug` 构建通过。

**仍待数据积累**：阈值/权重标定（v3 主体）；回填用当前价近似（v10 §4d，标定时按 outcome30Ms 过滤评估）。

## v10 改动（2026-08-07：评审遗留项落实 —— 周末调休 / 指数接口实测 / 统计口径修正 / 竞价窗口与 F1 阈值）

> 依据 `INTRADAY_SIGNAL_REVIEW.md` 全量评估：落实 v8 评审②（周末调休）、闭环"指数分钟接口待验证"、修正 v9 统计口径自身缺陷，并修复该文档跨引用的竞价引擎三项待办（MODEL_REVIEW v6 ①②③）。

### 1. 指数分钟接口实测 ✅（闭环 v8 §1.3⑥ / v9 §3）

实测 `https://web.ifzq.gtimg.cn/appstock/app/minute/query` 对 `sz399006 / sz399001 / sh000001 / sh000688` 均返回与个股同格式的分钟数据（`"0930 3472.15 1703047 6052505881.54"`），`parseMinuteData` 可直接解析。**rsIndex 基准全链路可用，无需降级**；"待真机验证"项关闭。

### 2. 周末调休交易日支持 ✅（v8 评审②）

**代码**：`IntradaySignal.kt`、`PredictionWorkers.kt`

- `wallClockPhase` 不再无条件把周末判 CLOSED（仅保留 ≥15:00 的收盘兜底）；周末是否交易日由**数据 + 新鲜度校验**决定（数据优先）。
  - 普通周末残留数据：数据相位"已收盘"或新鲜度校验"数据异常"拦截；
  - 调休周末交易日：实时数据与墙钟对齐 → 正常出信号（用例 23）。
- `IntradaySignalWorker` 删除周末硬跳过：普通周末因无方向信号不存快照，调休周末正常采集。
- 已知折中：后台采集调度仍按周一至周五排班（普通周末省电），调休周末交易日可手动刷新出信号（与竞价 worker 的国产 ROM 延迟同属尽力而为）。

### 3. 快照统计口径修正 ✅（v9 自评估发现）

**代码**：`IntradaySignal.kt`、`IntradaySignalStore.kt`、`PredictionWorkers.kt`

- `IntradaySignalSnapshot` 新增 `direction` 字段（采集时刻固化）；新增 `directionOf(signal)`：BUY→看涨、SELL→看跌、**仅"看跌"语义的 NO_TRADE→看跌**，状态类 NO_TRADE（数据积累中/午休/已收盘/数据异常）与 WATCH/HOLD 无方向。
- 修复 v9 缺陷：此前 NO_TRADE 一律按看跌计方向，"数据积累中"快照会污染命中率。
- `statsOf` 净变动只计可执行的 BUY/SELL（看跌 NO_TRADE 未交易，不扣成本、不计收益）。
- Worker 只保存有方向的快照，普通周末/状态信号不进入统计。

### 4. 竞价引擎窗口与 F1 阈值 ✅（MODEL_REVIEW v6 ①②③）

**代码**：`PredictionEngine.kt`、`PredictionWorkers.kt`、`Calibration.kt`

- ① 竞价 worker 迟到污染（高）：`PredictWorker` 仅 9:20–9:35（Asia/Shanghai）执行，窗口外跳过当天、不落记录、不抓联动基线（盘后抓到的会是全天涨幅，污染联动学习）。
- ② 回填窗口校验（中）：`recordOutcome30m` 仅 10:00–10:15、`recordOutcomeClose` 仅 15:00–16:00 执行，防迟到用盘中价误标 outcome。
- ③ F1 阈值（中低）：`curveThreshold` 每个候选 t 现场 `classify(score, t)` 重算 predicted，消除固定 REF_THRESHOLD=2.0 标签在 t<2.0 时精度被系统性低估、且标定与运行时分类不一致的问题。

### 5. 测试与验证

- 新增：周末调休交易日、信号方向映射、回填窗口外不写结果（IntradaySignalTest 24 例 / PredictionEngineTest +1）。
- 实测 `gradlew :app:testDebugUnitTest` = **202/0 通过**；`assembleDebug` 构建通过。

**剩余（需数据积累）**：盘中信号阈值/权重标定（v3 主体）；后台采集对调休周末的自动排班（当前为手动刷新折中）。

## v9 改动（2026-08-07：A 股规则补齐 + 去共线性评分 + v3 验证框架）

> 依据对盘中实时信号模型的独立检阅实施（对应评审：涨跌停、T+1、指数基准、时区、数据新鲜度、因子共线性、缺回测）。

### 1. A 股规则补齐 ✅

**代码**：`IntradaySignal.kt`、`MarketData.kt`、`StockViewModel.kt`、`PredictionViewModel.kt`

| 项 | 实现 | 测试 |
|----|------|------|
| 涨跌停护栏 | `priceLimitPct(stock)` 按板块识别（主板 10%、ST 5%、创业板/科创板 20%、北交所 30%）；接近涨停（距板 ≤0.5%）BUY 降级 WATCH 提示封板难买；接近跌停 SELL 降级 HOLD | 用例 16/20 |
| T+1 可卖 | `evaluate(canSell=…)`：当日买入无可用卖出量时 SELL 降级 HOLD 并注明 T+1 冻结；调用方传 `sellableQty > 0` | 用例 17 |
| 指数基准 | `indexCodeFor(stock)`：内置配置 > 沪主板(sh000001)/科创板(sh000688)/沪ETF(sh000300)/深主板(sz399001)/创业板(sz399006)；北交所返回 null → 信号降级（修复一律用深证成指的基准错配） | 用例 21 |
| 时区 | `wallClockPhase`/`expectedMinuteIndex` 固定 Asia/Shanghai（落实 v8 评审①） | 用例 15 |
| 数据新鲜度 | 交易日盘中数据位置与墙钟错位 >10 分钟 → NO_TRADE（防数据滞后/节假日残留陈旧信号） | 用例 18 |

### 2. 去共线性评分（v9 公式）✅

**代码**：`IntradaySignal.kt`

```text
score = 1.0×mom30 + 0.6×acc15 + 1.5×rsIndex + 2.0×aboveAvg + 0.8×volBoost
acc15 = mom15 − mom30   // 短时加速度，替换原 mom15，消除 15/30 分钟重叠双重计价
```

- 特征层新增 `acc15`，评分不再对同一段行情计 1.8 倍权重（mom15 是 mom30 子区间）。
- 护栏顺序：涨跌停 → 追高 → 顶部反转 → T+1；涨停线命中时优先展示"封板难买"语义。
- 行为变化：指数缺失时分数不再含 rsIndex 加成，BUY 阈值可能达不到 → 降级 WATCH（无证据不冒进），用例 9 预期同步更新。

### 3. v3 验证框架（快照 + 回填 + 统计）✅

**代码**：`IntradaySignalStore.kt`（新增）、`PredictionWorkers.kt`、`PredictionViewModel.kt`、`ui/StockApp.kt`

- `IntradaySignalWorker`：交易时段 10:10–14:50 每 10 分钟对全部 A 股持仓做信号快照；每次运行先给 ≥30/60 分钟前的快照回填当前价作为结果；周末自动顺延。
- `IntradaySignalSnapshot`：信号时刻的价格/动作/分数 + 30/60 分钟结果回填（同日近似，不跨日）。
- `statsOf()`：方向命中率（±0.15% 平盘带，复用 outcomeOf）+ 扣费后平均净变动（双边成本 0.12%），WATCH/HOLD 不计方向。
- UI：持仓信号行新增"历史验证：命中率 x%（n 样本）· 扣费期望 +y%"，可靠性可视化。
- 调度：`PredictionScheduler.scheduleIntradayNext` 自续链，随 `ensureScheduled` 启动。

### 4. 测试与验证

- `IntradaySignalTest.kt` 15 → 22 例（新增涨跌停/T+1/数据新鲜度/acc15/涨跌停幅度/指数基准/信号统计）。
- 实测 `gradlew :app:testDebugUnitTest` = **199/0 通过**；`assembleDebug` 构建通过。

**剩余（需数据积累）**：用积累的快照 + 结果回填标定 BUY/SELL 阈值与权重（沿用 Calibration 衰减加权思路）；本期先完成数据采集与统计框架。

## v8 改动（2026-08-06：评审 §1.3 剩余边界与注意事项）

> 对应 INTRADAY_SIGNAL_REVIEW.md §1.3 剩余项（⑤墙钟兜底 / ⑦指数拉取去重 / ⑩阈值边界）及 §2.2b 落实。⑧⑨ 已在 v7 完成。

### 1. 墙钟辅助时段判定 ✅ 已实施（⑤ + §2.2b）

**代码**：`IntradaySignal.kt`

- 新增 `wallClockPhase(nowMillis)`：周末或本地时间 ≥15:00 → CLOSED；交易时段内返回 null（仍由数据位置判定，不干扰盘中）
- `features`/`evaluate` 新增可选参数 `nowMillis`（默认 null = 纯位置判定，单测不受运行时刻影响）；实机调用传入 `System.currentTimeMillis()`
- 解决：分钟接口少返回一行（241→240）时，15:00 后原会误判 AFTERNOON 出陈旧信号，现由墙钟兜底判"已收盘"

### 2. 指数拉取互斥去重 ✅ 已实施（⑦）

**代码**：`PredictionViewModel.kt`、`StockViewModel.kt`

- `fetchIndexPoints` 包在 `Mutex.withLock` 内，锁内二次校验缓存：`refreshAll` 多股共享同一指数代码时并发只发起一次真实请求，其余命中 60 秒缓存

### 3. 追高拦截阈值边界 ✅ 已实施（⑩）

**代码**：`IntradaySignal.kt`

- `dayGain > 2.0%` / `mom30 > 1.5%` 的严格大于改为 `>=`，杜绝恰好卡在阈值时不触发拦截的抖动

### 4. 测试与验证

**新增测试**：`IntradaySignalTest.kt` 第 15 例（14 → 15 例）：156 点缺行 + 墙钟 15:30 → 已收盘；周六上午 → 已收盘；交易时段墙钟不干扰数据判段。测试用 `atTime()` 构造本地时刻，与实现同用默认时区保证确定性

```
./gradlew :app:testDebugUnitTest   # 全量 192 用例通过
./gradlew :app:assembleDebug       # 构建通过；已安装真机（设备 3B15C1012F100000）
```

**剩余（评审标注待实机/可选）**：指数分钟接口对 sz399006/399001 的返回格式真机确认（§1.3⑥，失败已有 degraded 降级路径）；数据新鲜度"更新于 HH:mm"提示（§2.2e，可选）。

---

## v7 改动（2026-08-06：按 INTRADAY_SIGNAL_REVIEW.md §1 加固项实施）

> 依据 v6 代码评审结论（可合入，建议合入前加固）：顶部反转盲区、AFTERNOON 分支测试、死代码清理。

### 1. 顶部反转保护 ✅ 已实施（§1.2①，模型缺陷修复）

**改动文件**：`IntradaySignal.kt`

- 问题：`aboveAvg` 用当日 VWAP（滞后指标），快速拉升后价格自高点回落的最初几分钟 `aboveAvg` 仍大正值，score 仍可能 ≥2.5 → **下跌刚开始仍显示 BUY**
- 新增 `BUY_MOM15_MIN = 0.0`：BUY 必须 `mom15 > 0`（短动量向上确认），横盘/转负 → 降级 WATCH + "15 分钟动量转弱…顶部反转风险，暂缓买入"
- 新增 `BUY_FROM_HIGH_MAX = -1.0`：距日内高点回落 ≥1% 禁止 BUY → WATCH + "已从日内高点回落…不追回落"
- 与既有追高拦截互补：原拦截覆盖"贴近日内高点（fromHigh>−0.5%）"区，新护栏覆盖"已明显回落（≤−1%）"区

### 2. 测试补齐（§1.4 ⑪⑫）

**代码**：`IntradaySignalTest.kt`（12 → 14 例）

- 补充 AFTERNOON 分支：完整上午 121 点 + 午后 ≥31 点（lastPos=156 → 13:30+），正常出 BUY/HOLD 且 score ≥2.5
- 顶部反转回归：冲高回落后 VWAP 仍读多头、score 仍 ≥2.5 时严禁出 BUY，断言 WATCH + 对应原因（回落分支靠 fromHigh、横盘分支靠 mom15=0 短动量）
- 修复实现边界的浮点陷阱：阈值恰为 1.5% 时 `mom30 > 1.5` 可能误判，测试数据改用 1.4% 避开

### 3. 并发与死代码清理（§1.3 ⑧⑨）

**代码**：`StockViewModel.kt`、`ui/StockApp.kt`

- `StockViewModel.loadIntraday` 指数与个股分钟线由串行两次请求改为 `async` 并发，消除信号滞后
- 删除死代码 `signedPct()`（竞价详情弹窗删除后遗留）

### 4. 验证

- 全量 191 用例通过（含新增 2 例）；`assembleDebug` 通过；已安装真机（设备 3B15C1012F100000）

**未做（评审标注可接受/待实机）**：CLOSED 墙钟判定（§1.5⑤）、指数分钟接口实机格式验证（§1.3⑥）、追高/阈值参数化（§1.2②）。

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
- **总览第 4 标签**："预测"→"盘中信号"；列表渲染 `predUi.allSignals`（名称+动作+分数+理由摘要）；**每次点标签立即刷新**（不等 30 秒），面板顶部另有"刷新"按钮；SegmentedButton 用 `icon = {}` 去掉选中勾，防 4 个标签文字被挤出
- **移除**：`PredictionDetailDialog`（竞价详情/分目标回测）、`SectorPickerDialog`（行业联动配置入口）、`showPredDetail/showSectorPicker` 状态。竞价引擎本身与通知保持不变，仅 UI 入口下线
- **分时弹窗**（StockViewModel）：`signal: StateFlow<IntradaySignal?>` + 60 秒指数缓存，弹窗内展示信号横幅

### 4. 数据去重修复 ✅ 已实施（bug）

**改动文件**：`SnapshotStore.kt`、`PredictionEngineTest.kt`

- **根因**：`addRecord`/`addSnapshot` 无同日去重，同一股票当天多次点击预测会向 40 天放量基线（volZ）和 200 条历史记录灌入多条当日数据，当日巨额成交额把 baseline 抬高 → volZ 被稀释 → 评分逐次下降；同日快照还污染次日标定
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

---

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

**文件**：`PredictionEngine.kt`、`Calibration.kt`

- 实时评分 = `0.5 × score_CLOSE_VS_OPEN + 0.25 × score_OPEN30M + 0.25 × score_DAY_VS_PREV`（`Calibrator.VOTE_WEIGHTS`）
- 每个目标用各自前缀数据独立标定权重（含各自的翻转护栏与外围桶条件概率）
- 新增 `Calibrator.walkForwardVotedSeries()`：逐折对三目标分别评分加权合成，实际结果以主目标（CLOSE_VS_OPEN）判定
- 阈值 / 信心 / 封顶全部改用投票前推序列标定
- 三目标结果仍每天 10:00 / 15:05 回填，详情弹窗单独回测展示

### 8.2 板块广度加权化 ✅ 已实施

**文件**：`AuctionPredictor.kt`（`sectorBreadth`）

- 由"涨跌家数比"改为幅度加权：`weightedBreadth = Σ(sign(g) × min(|g|, 3)) / Σ max(|g|, 1)`
- 龙头涨 5% 与涨 0.1% 不再等同；单股幅度封顶 3 个百分点（`BREADTH_GAP_CAP`）

### 8.3 合成强度权重纳入搜索 ✅ 已实施

**文件**：`PredictionModel.kt`、`AuctionPredictor.kt`、`Calibration.kt`

- `CalibratedWeights` 新增 `targetW` / `indexW` 字段（`sectorW = 1 − targetW − indexW` 自动推导）
- `combinedStrength(f, w)` 参数化；`StandardizedFactors` 重构为存 `strengthRaw + strengthMean + strengthStd`，评分时按候选权重重算（新增 `AuctionPredictor.strengthC()`、`Calibrator.scoreFor()`）
- 坐标下降搜索域扩展至合成权重，有界：targetW ∈ [0.2, 0.8]、indexW ∈ [0.05, 0.45]、sectorW ≥ 0.1

### 8.4 因子交互项 ✅ 已实施

**文件**：`AuctionPredictor.kt`、`Calibration.kt`

- 评分公式新增交互项：`interC = interactionScale × tanh(strengthC/2) × tanh(momentumC/2) × 2`
- 同向（竞价高开 + 动量向上）共振加分、反向扣分
- `interactionScale` 纳入搜索，范围 [0, 1.5]

### 8.5 硬编码常数参数化 ⏳ 待定（低优先级）

- `FALLBACK_*_DIV`、`VOL_Z_DIV`、`STREAK_DIV`、`TREND_CTX_SCALE`、`PREVDAY_CTX_SCALE` 仍为常量
- 后续建议只搜影响最大的 2-3 个（如 `VOL_Z_DIV`、`STREAK_DIV`），注意维度爆炸

### 8.6 存储层升级 ⏳ 待定（长期演进）

- `SharedPreferences + JSON` → Room (SQLite)，支持增量更新与索引查询
- 当前 200 条记录 × 多股票量级影响可忽略

### 8.7 阈值经验曲线改用 F1 最大化 ✅ 已实施

**文件**：`Calibration.kt`（`curveThreshold`）

- 由"找最小满足 ≥60% 命中率的阈值"改为 `argmax F1`（precision = 命中/入选、recall = 命中/全部方向样本）
- 对涨跌样本不均衡更稳健；F1 平值段取最低阈值以保留 recall
- 签名由 `curveThreshold(series, minHit)` 改为 `curveThreshold(series)`，引擎调用同步更新

---

## 配套改动（v4 轮）

- **序列化**：`SnapshotStore.kt` 的 `buildSf/parseSf`、`buildWeights/parseWeights` 同步新增字段（旧数据缺省值兼容：`interactionScale=0.5`、`targetW=0.5`、`indexW=0.25`）
- **测试**：新增/更新 8 个用例（交互项、合成权重参数化、scoreFor 一致性、权重搜索边界、投票序列、F1 阈值曲线等），全量 146 用例通过
- **文档**：README 竞价预测段落同步更新

## 验证

```
./gradlew :app:testDebugUnitTest   # 147 用例（v5），0 失败
./gradlew :app:assembleDebug       # 构建通过
```
