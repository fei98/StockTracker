# 盘中择时信号评审（INTRADAY_SIGNAL_REVIEW）

> 独立评审文档，不包含在方案文档（`INTRADAY_SIGNAL_PLAN.md`）内。
> **约定：最新评审放最前面，旧评审依次下移。** 实施记录见 `MODEL_CHANGES.md`。

## 评审历史（最新在前）

| 顺序 | 评审 | 对象 | 结论 |
|------|------|------|------|
| 最新 | v13 代码评审 | 盘中门控调优（30分钟出信号/收集态标签）+ 通知历史铃铛 + 总览各标签刷新 | 声明与代码一致；收集态不再显示“不交易”；204 用例全绿 |
| ↓ | v12 代码评审 | 调休周末采集链判据修正（行情时间戳日期判定交易日） | 修复 v11 §4a；204 用例全绿 |
| ↓ | v11 代码评审 | v10 剩余小问题收尾（中间带/原因覆盖/缓存共享/方向显式/窗口纵深/周末采集） | 声明与代码一致；203 用例全绿；§4a 已由 v12 修复 |
| ↓ | v10 代码评审 | 评审遗留项落实（周末调休/接口实测/统计口径修复/竞价窗口与F1） | 声明与代码一致；v9 高优 NO_TRADE 污染已修复；202 用例全绿 |
| ↓ | v9 代码评审 | v9 优化实施（A股规则/去共线性/验证框架） | 声明与代码一致；**复核发现快照统计被非交易时段 NO_TRADE 污染（高）**；199 用例全绿 |
| ↓ | v8 代码评审 | v8 加固实施（墙钟兜底 / 指数拉取去重 / 阈值边界） | **声明与代码一致**；无新漏洞；新增时区边界待处理；192 用例全绿 |
| ↓ | v7 代码评审 | v7 加固实施（顶部反转保护/测试/并发清理） | 声明与代码一致；顶部反转盲区已堵住；191 用例全绿 |
| ↓ | v6 代码评审 | 盘中信号实施（IntradaySignal 等） | 可合入；建议加固顶部反转盲区 |
| ↓ | v2 方案复审 | INTRADAY_SIGNAL_PLAN.md v2 | 全部评审点已落实，可以实施 |
| 最早 | v1 方案评审 | INTRADAY_SIGNAL_PLAN.md v1 | 方向可行，需修 9 点 |

---

# v13 代码评审（最新）

> 对应 `MODEL_CHANGES.md` v13：盘中实时预测门控调优（约 10:00/13:30 出信号，收集态不再误称“不交易”）+ 通知历史落盘与右上角铃铛 + 账户总览各标签刷新。
> 验证：全量 `gradlew :app:testDebugUnitTest` 通过（204/0）。

## 1. 声明与代码一致性（通过）

| v13 声明 | 代码证据 | 结果 |
|---------|----------|------|
| 开盘信号门槛 40→30 分钟（约 10:00 可出信号） | `IntradaySignal.EARLY_POS = 30`（mom30 位置 30 即可计算） | ✅ |
| 新增 `pending` 区分“未就绪”与真实判断 | `IntradaySignal.pending`；`noTrade` 置值；真实看跌 NO_TRADE 保持 null | ✅ |
| 收集态 UI 不再显示“不交易” | `SignalBanner`/`PredictionPanel` 对 pending 渲染中性“收集中” | ✅ |
| 竞价/盘中通知落盘历史 + 铃铛 + 一键清空 | `NotificationLog.kt`、`PredictionNotifier`、`IntradaySignalWorker`、顶栏铃铛 + `NotificationLogDialog` | ✅ |
| 总览浮盈/市值/已实现补刷新 | `OverviewDialog.onRefreshData`（= `refreshAll`） | ✅ |

## 2. 独立复核确认

- **门控调优合理**：mom30 需位置 ≥30，EARLY_POS 由 40 收到 30 后首个可用点位（约 10:00）不早于最小动量窗口，评分项（mom15/mom30/相对强弱/放量）均在就绪后方参与；午后跨午休的 mom30 仍由 AFTERNOON_START 拦截（约 13:30），未破坏语义。
- **“数据不足 vs 真实不交易”边界**：`pending` 仅由阶段门控设置，真实“看跌不买”走末尾 `when` 分支保持 `pending=null`，采集去重与方向统计不受影响。
- **通知历史**：SharedPreferences 落盘、最新在前、上限 300；铃铛在“账户总览”左侧；清空有二次确认。落盘位置在后台线程（worker/notifier），非主线程，安全。
- **总览刷新**：数据标签复用 `refreshAll`（更新 accounts→总量线 recompose 重算），盘中信号标签走信号刷新，两分支互不干扰。
- 无新增逻辑漏洞；统计/采集/调度未回归。

## 3. 结论

v13 声明与代码一致，三项功能落地，全量用例通过。

---

# v12 代码评审（最新）

> 对应 `MODEL_CHANGES.md` v12：修复 v11 §4a —— 调休周末采集链判据脆弱（`continueOnWeekend` 以"是否采到方向信号"为判据，WATCH/HOLD slot 或午休会中断链）。
> 验证：全量 204 用例通过（实测 `gradlew :app:testDebugUnitTest` = 204/0）。

## 1. 声明与代码一致性（通过）

| v12 声明 | 代码证据 | 结果 |
|---------|----------|------|
| 交易日判定改用行情时间戳日期（今天 Asia/Shanghai） | `PredictionWorkers.kt` `IntradaySignalWorker` 起始判定 `quoteTimeIsOnDate(q.time, todayCN)`，`isTradingDay` 驱动 `scheduleIntradayNext(continueOnWeekend)` | ✅ |
| 普通周末跳周一、调休周末全天延续（含午休 slot） | 行情时间戳普通周末为上一交易日 → false；调休交易日为今天 → true | ✅ |
| 新增可单测纯函数 `quoteTimeIsOnDate` | `MarketData.kt`（容忍分隔符变体） | ✅ |
| 全量 204 用例通过 | 实测 204/0 | ✅ |

## 2. 独立复核确认

- 修复方向正确：判据从"是否产出方向信号"（受 WATCH/HOLD/午休影响）改为"今日是否真实交易日"（由行情时间戳日期决定），消除调休周末链中断；普通周末一次空跑后跳下周一的行为保持。
- 判据取"任意持仓行情时间戳 == 今天"即 break，调用开销小（至多全部持仓各一次 quote）；网络失败时 `isTradingDay=false`（普通周末正确、调休周末会漏采一天，属可接受的尽力而为）。
- 时间戳解析 `quoteTimeIsOnDate` 取数字前缀比较，兼容 `YYYYMMDDHHMMSS` 与带分隔符格式；空/前缀不足返回 false（不误判）。
- 无新增漏洞；采集/回填/统计逻辑未受影响。

## 3. 结论

v12 声明与代码一致，修复到位，204 用例全绿。

---

# v11 代码评审

> 对应 `MODEL_CHANGES.md` v11。按 v10 评审 §1 表中 ⏳ 项与 §2/§4 注意项逐条收尾。
> 验证：全量 203 用例通过（实测 `gradlew :app:testDebugUnitTest` = 203/0）；`assembleDebug` 构建通过。

## 1. 收尾项与结果

| 项 | 处理 | 结果 |
|----|------|------|
| v7 §3 残余中间带（fromHigh ∈ (−1.0, −0.5]，⏳） | `BUY_FROM_HIGH_MAX` −1.0 → −0.5，回落 ≥0.5% 禁买；用例 25 固化边界 | ✅ 收口（保守方向，待数据积累后可参数化回退） |
| v7 §3 原因覆盖不全（轻微） | 顶部反转双条件命中时两个原因都展示 | ✅ |
| v8 ③ 锁粒度 per-VM（无害说明） | 新增 `IndexMinuteCache` 进程级共享，两 ViewModel 复用 | ✅ |
| v10 §4b `runPrediction` 无窗口守卫 | 引擎层加 `isPredictionWindow` 纵深防御 | ✅ |
| v10 §4c `delayToNext` 设备时区 | 统一 Asia/Shanghai | ✅ |
| v10 §4e `directionOf` 文案匹配 | `IntradaySignal` 新增 `direction` 字段显式化 | ✅ 用例 24 集成断言 |
| v10 §2 调休周末仅手动刷新 | `scheduleIntradayNext(continueOnWeekend)`：周末一次自动采集，有方向信号则延续 10 分钟链，普通周末空跑后跳周一 | ✅ |

## 2. 仍未改（有依据，非遗漏）

- **v10 §4a 周末部分残留数据与墙钟对齐的极端场景**：接口实测大概率返回完整 241 点或空；评审自述"可接受的折中，记录即可"，维持。
- **v10 §4d 回填用当前价近似**（30–40 / 60–70 分钟上偏）：已记录；标定时按 `outcome30Ms` 过滤评估，分钟级精确回填（跨午休边界）留作后续优化。
- **v3 阈值/权重标定**：需数据积累后实施，评估时按天/按股做自相关聚类。

## 3. 结论

v11 声明与代码一致；203 用例全绿；v10 评审中可执行的小问题全部收口，模型行为更保守（回落 ≥0.5% 禁买）。

## 4. 独立复核补充：注意事项（低，不阻塞）

独立核对代码（`IntradaySignal.kt` / `IndexMinuteCache.kt` / `PredictionEngine.kt` / `PredictionWorkers.kt` / `StockViewModel.kt` / `PredictionViewModel.kt`）后确认上表全部属实（含 203/0 实测）；另补充：

- **a.（已由 v12 修复）`continueOnWeekend` 判据脆弱，调休周末采集链可能在首个无方向 slot 中断**：Worker 原以 `capturedAny`（是否存了方向快照）决定 `continueOnWeekend`。调休周末交易日的某个 10 分钟 slot 若全是 WATCH/HOLD（无方向，常见），`capturedAny=false` → 顺延下周一，当日剩余时段不再采集。**v12 改为"行情时间戳日期 == 今天(Asia/Shanghai)"判定真实交易日**（新增 `quoteTimeIsOnDate`），普通周末正确跳周一、调休周末全天延续采集。
- **b. `IndexMinuteCache` 单槽缓存（无害）**：进程内仅一个 index code 槽位，多板块并发请求相互覆盖；每次返回数据正确，仅缓存命中率略降。
- **c. Worker 未复用 `IndexMinuteCache`（无害）**：PredictionWorkers.kt:154 仍直接 `api.fetchIntraday`，后台采集与 UI 层各自拉一次；可统一，非必须。
- **d. `PredictionEngine.today()` 仍用设备默认时区（预存）**：与 CN_TZ 窗口不一致，极端时区（日期边界跨中国交易时段）下记录/快照日期可能错位；中国用户无影响，非遗 v11 引入。
- **e. 顶部反转双原因已修复但"回落≥0.5%"与追高拦截互补**：`chase`（fromHigh > −0.5）与 `fromHigh ≤ −0.5`（顶转）在 −0.5 处连续无缝隙，收口正确；注意这使"回踩 0.5%–1% 再买"彻底失效，属有意的保守化（文档已声明）。

---

# v10 代码评审（最新）

> 对应 `MODEL_CHANGES.md` v10。按 v8/v9 遗留项与跨引用待办（MODEL_REVIEW v6 ①②③）逐一评估后落实，重点修复 v9 复核发现的高优项（NO_TRADE 统计污染）。
> 验证：全量 202 用例通过（实测 `gradlew :app:testDebugUnitTest` = 202/0）；`assembleDebug` 构建通过。

## 1. 遗留项逐条评估与落实

| 遗留项 | 评估 | 处理 | 结果 |
|--------|------|------|------|
| v9 §1 复核发现：快照统计被非交易时段 NO_TRADE 污染（高） | 确认成立：状态类 NO_TRADE（数据积累中/午休/已收盘）会被 `predictedDirectionOf` 一律当作看跌计方向 | 快照新增 `direction` 字段采集时刻固化；新增 `directionOf(signal)` 仅"看跌"语义的 NO_TRADE 有方向；Worker 只存方向快照 | ✅ 用例 22/24 |
| v9 §3：看跌 NO_TRADE 被扣双边成本 | 不成立的经济语义：未交易不产生成本/收益 | `statsOf` 净变动仅计 BUY/SELL | ✅ |
| v8 §1.3⑥ 指数分钟接口格式待真机验证 | 可实测：网络可用 | 实测 sz399006/399001/sh000001/sh000688 均返回与个股同格式分钟数据 | ✅ 已闭环，rsIndex 全链路可用 |
| v8 ② 周末调休交易日被压制 | 合理：调休周末有真实行情应出信号 | 墙钟不再无条件判周末 CLOSED，改由数据 + 新鲜度决定（数据优先） | ✅ 用例 23 |
| MODEL_REVIEW v6 ① 竞价 worker 迟到污染记录（高） | 合理且重要 | PredictWorker 仅 9:20–9:35（Asia/Shanghai）执行，窗口外跳过当天、不落记录、不抓联动基线 | ✅ |
| MODEL_REVIEW v6 ② 回填窗口校验（中） | 合理 | 30 分钟回填限 10:00–10:15；收盘回填限 15:00–16:00 | ✅ 含窗口外用例 |
| MODEL_REVIEW v6 ③ F1 阈值 predicted 不随候选 t 重算（中低） | 合理：t<2.0 时精度被低估且标定与运行不一致 | `curveThreshold` 每个候选 t 现场 `classify(score, t)` | ✅ |
| v7 §3 残余中间带（fromHigh ∈ (−1.0, −0.5] 可能出 BUY） | 评审自述"已知可接受，建议实测后参数化" | 维持现状，待数据积累后参数化 | ⏳ 数据驱动 |
| v6 ③ 非 A 股基准错配 | v9 已用降级标注处理（符合"或标注"选项） | 维持 | ✅（v9 闭环） |

## 2. 已知折中（诚实声明）

- 后台采集调度仍按周一至周五排班（普通周末省电）；调休周末交易日可手动刷新出信号，与竞价 worker 的国产 ROM 延迟同属尽力而为。
- 阈值/权重标定（v3 主体）仍需数据积累后实施，评估时按天/按股做自相关聚类。

## 3. 结论

v10 声明与代码一致；202 用例全绿；v9 高优污染项已修复，v8/v9 遗留可执行项与竞价引擎三项待办全部落实，指数分钟接口实测可用。

## 4. 独立复核补充：注意事项（均低，不阻塞）

独立核对代码（`IntradaySignal.kt` / `IntradaySignalStore.kt` / `PredictionWorkers.kt` / `PredictionEngine.kt` / `Calibration.kt`）后确认上表全部属实；另补充以下低优先级注意项：

- **a. 正常周末"部分残留数据与墙钟对齐"仍可能出陈旧信号**：freshness 用 `lastPos vs expectedMinuteIndex`，若 API 在非交易日返回上一交易日**部分**数据且最后点位置恰与当前周末墙钟位置一致（如周六 14:00 + 周五 14:00 数据），会通过新鲜度校验并出 AFTERNOON 信号。现实中 API 大概率返回完整 241 点（→已收盘）或空（→无信号），且后台周末不采集（仅手动刷新），概率低。可接受的折中，记录即可。
- **b. 窗口校验只在 Worker 层，`runPrediction` 内部无防御**：目前仅 `PredictWorker` 调用 `runPrediction`，窗口守卫（PredictionWorkers.kt:57）已足够；但若未来恢复手动预测入口，会重新引入盘中价污染。建议后续在 `runPrediction` 内也加 `isPredictionWindow` 守卫（纵深防御）。
- **c. 竞价任务调度 `delayToNext` 仍用设备默认时区，窗口校验用 CN_TZ**：设备时区≠中国时，9:25 本地调度触发时窗口校验可能不通过 → 预测静默不执行（中国用户无影响）。非 v10 引入，建议后续统一 CN_TZ。
- **d. v9 ② 结果回填"当前价近似"仍未解决**：30/60 分钟结果仍用 Worker 运行时刻实时价回填（实际 30–40 / 60–70 分钟区间上偏），v10 未改，标定时需知悉。
- **e. `directionOf` 用 `reasons.contains("看跌")` 字符串匹配**：当前语义唯一（仅看跌 NO_TRADE 含"看跌"），但若未来文案变化会静默失效；建议改存枚举语义。

---

# v9 代码评审（最新）

> 对应 `MODEL_CHANGES.md` v9。依据独立检阅实施：A 股规则补齐（涨跌停/T+1/指数基准/时区/数据新鲜度）、去共线性评分、v3 验证框架（快照+回填+统计）。
> 验证：全量 199 用例通过（实测 `gradlew :app:testDebugUnitTest` = 199/0）；`assembleDebug` 构建通过。

## 1. 声明与代码一致性（全部通过，无虚报）

| v9 声明 | 代码证据 | 结果 |
|---------|----------|------|
| 涨跌停护栏：`priceLimitPct` 按板块识别，接近涨停 BUY 降级、接近跌停 SELL 降级 | `IntradaySignal.kt`（护栏块先于追高拦截） | ✅ |
| T+1：`evaluate(canSell)`，SELL 在无可用卖出量时降级 HOLD；调用方传 `sellableQty > 0` | `IntradaySignal.kt`；`StockViewModel.kt`、`PredictionViewModel.kt` | ✅ |
| 指数基准 `indexCodeFor`：内置 > 市场/板块映射，北交所 null 降级 | `MarketData.kt`；两个 VM 的 `fetchIndexPoints` 同步 | ✅ |
| 时区固定 Asia/Shanghai | `IntradaySignal.kt` `CN_TZ` + `wallClockPhase`/`expectedMinuteIndex` | ✅ |
| 数据新鲜度：位置与墙钟错位 >10 分钟停用信号 | `evaluate` 内 stale 检查（用例 18） | ✅ |
| 去共线性：`acc15 = mom15 − mom30` 进公式，去掉原 mom15 权重 | `IntradaySignal.kt` 评分块；用例 19 断言 acc15 恒等式 | ✅ |
| v3 验证框架：快照存储 + 10 分钟采集 Worker + 30/60 分钟回填 + `statsOf` 统计 | `IntradaySignalStore.kt`、`PredictionWorkers.kt`、统计块；用例 22 | ✅ |
| UI 可靠性可视化：持仓信号行显示命中率与扣费期望 | `ui/StockApp.kt`（PredictionPanel 统计行） | ✅ |

## 2. 模型行为变化（有意为之）

- 指数缺失时 rsIndex 无加成 → 用例 9 由 BUY 改为 WATCH（无证据不冒进）。
- 护栏顺序调整为 涨跌停 → 追高 → 顶部反转：涨停线命中优先展示"封板难买"语义，避免被泛化追高文案覆盖。

## 3. 独立复核补充：发现的问题（按优先级）

**①（高）快照统计被"非交易时段 NO_TRADE"污染**
- Worker 采集条件为 `wallClockPhase(now)==null`（PredictionWorkers.kt:149），**午休 11:30–13:00 与 13:00–13:30（AFTERNOON_START）也会采集**。此时 `evaluate` 早退返回 `NO_TRADE("午间休市"/"数据积累中")`，快照照存（action=NO_TRADE）。
- `predictedDirectionOf(NO_TRADE) = DOWN`（IntradaySignal.kt:340）→ 这些"休市/积累中"快照被当成**看跌预测**计入 directional/hits/avgNetMove，污染命中率与扣费期望（每交易日约 12–15 条假样本）。
- 快照只存 `action` 未存 phase/reason，事后无法区分"看跌"与"非交易时段"。
- **建议**：Worker 仅在 phase ∈ {MORNING, AFTERNOON} 时落快照；或在快照中记录 phase/reason，`statsOf` 只统计方向性 NO_TRADE。

**②（中）30/60 分钟结果用"当前价"近似回填，测量区间系统性偏长**
- 回填用 Worker 运行时刻的实时价（PredictionWorkers.kt:130-144）→ `outcome30mPct` 实为 30–40 分钟、`outcome60mPct` 实为 60–70 分钟的结果，存在 +0–10 分钟区间上偏；午休前快照的 30 分钟结果跨午休无意义。v1 采集可接受，标定时需知悉偏差。

**③（低）新鲜度校验被时段早退绕过**
- phase 判定在新鲜度校验之前（IntradaySignal.kt:205-219），LUNCH/CLOSED/EARLY/AFTERNOON_START 均提前返回。若节假日残留昨日部分数据（如 120 点），会显示"午间休市"而非"数据异常"。与①同根，建议统一按 phase 语义处理。

**④（行为提示）新评分使 BUY 更稀缺，阈值未重标定**
- 数学上 `1.0·mom30 + 0.6·(mom15−mom30) = 0.6·mom15 + 0.4·mom30`。稳态趋势 acc15≈0 → 动量贡献约 1.0·m（原 1.8·m）；BUY_SCORE=2.5 未随新量纲调整 → 稳态上涨中 BUY 更难触发（更保守）。文档已注明"待积累数据标定阈值"，符合预期，真机需观察信号频率。

**⑤（轻微）其他**
- `expectedMinuteIndex` 在午休时刻返回 60（错误值），因午休早退暂不生效，属脆弱性（若调整判断顺序会引入 bug）。
- ST 判断用 `stock.name.contains("ST")`，名称是添加时的快照，个股后变 ST 时判断滞后。
- 竞价任务调度 `delayToNext` 仍用设备默认时区，与 intraday 链的 CN_TZ 不一致（竞价引擎既有问题，非 v9 引入）。

## 4. 剩余待办（需数据积累 / 真机）

- **阈值与权重标定**（v3 主体）：本期只完成数据采集与统计框架，待积累足够样本后按衰减加权标定 BUY/SELL 阈值；评估时必须按天/按股做自相关聚类，避免同日相邻分钟样本虚增有效样本量。
- 指数分钟接口对 sz399006/399001 格式真机确认（v8 遗留）。
- 竞价引擎侧：worker 迟到窗口校验、回填窗口校验（MODEL_REVIEW.md v6 ① ②）仍待修。

## 5. 结论

v9 声明与代码一致；199 用例全绿；A 股规则缺口已补齐，验证框架已就位。
**注意（独立复核）**：① 快照统计污染为必须修（数据质量，直接影响后续标定）；②③ 建议一并修；④ 行为变化需实测；⑤ 记录即可。
模型有效性需在数据积累后以统计结果为准，当前仍按"经验规则过滤器"对待。

---

# v8 代码评审（最新）

> 对应 `MODEL_CHANGES.md` v8。v8 落实 v7 评审 §1.3 剩余项（⑤墙钟兜底 / ⑦指数拉取去重 / ⑩阈值边界）及 v2 评审 §2.2b。
> 验证：全量 192 用例通过（实测 `gradlew :app:testDebugUnitTest` = 192/0，与文档一致）。
> 涉及代码：`IntradaySignal.kt`、`IntradaySignalTest.kt`、`StockViewModel.kt`、`PredictionViewModel.kt`。

## 1. 声明与代码一致性（全部通过，无虚报）

| v8 声明 | 代码证据 | 结果 |
|---------|----------|------|
| 墙钟辅助时段 `wallClockPhase(nowMillis)`：周末或 ≥15:00 → CLOSED，交易时段返回 null | `IntradaySignal.kt:102-109`（`CLOSED_WALL_MINUTES=900`） | ✅ |
| `features`/`evaluate` 可选参数 `nowMillis`（默认 null=纯位置判定）；实机传入 `System.currentTimeMillis()` | `IntradaySignal.kt:133-173`；`StockViewModel.kt:68`、`PredictionViewModel.kt:70,93` | ✅ |
| 指数拉取 `Mutex.withLock` + 锁内二次校验缓存 | `StockViewModel.kt:76-91`、`PredictionViewModel.kt:105-119`（double-checked locking） | ✅ |
| 追高阈值 `>` 改 `>=`（杜绝 2.0%/1.5% 恰好不触发抖动） | `IntradaySignal.kt:209` | ✅ |
| 新增第 15 例：156 点缺行 + 墙钟 15:30 → 已收盘；周六上午 → 已收盘；交易时段墙钟不干扰数据判段 | `IntradaySignalTest.kt:225-251`，`atTime()` 与实现同用默认时区 | ✅ |
| 全量 192 用例通过 | 实测 192/0 | ✅ |

## 2. 实现正确性评估（无新漏洞）

- **墙钟兜底逻辑正确**：`wallClockPhase` 只返回 CLOSED（收盘/周末）或 null（其余），盘中时段始终由数据位置判定，不覆盖 LUNCH/AFTERNOON_START 等既有语义；缺行场景（241→240）15:00 后正确判"已收盘"。
- **Mutex 双重校验正确**：快路径无锁命中缓存；锁内二次校验保证并发只发一次真实请求；`withLock` 挂起语义无死锁风险；锁与缓存均为 per-VM，同 VM 内正确去重。
- **`>=` 边界**：`dayGain==2.0%`、`mom30==1.5%` 恰好命中时现在触发拦截，规则更保守，符合预期（非 bug）。
- **测试确定性**：`atTime()` 与 `wallClockPhase` 同用默认时区，往返一致；用例所用日期（2026-08-07 周五 / 08-08 周六）经实测通过，CI 换时区也不会闪断。

## 3. 新发现问题（按优先级）

**① 墙钟用设备本地时区（中低，建议修复）**
- `wallClockPhase` 用 `Calendar` 默认时区。若设备时区 ≠ Asia/Shanghai（出国、时区错置），时段判定会整体错位：例如本地 15:00=中国凌晨 → 全天"已收盘"；中国 9:30=本地夜间 → 同样 CLOSED，盘中信号全部消失。
- 旧实现（纯数据位置判段）无此依赖，是 v8 引入的边界。建议固定 `TimeZone.getTimeZone("Asia/Shanghai")` 计算时段。

**② 周末调休交易日被压制（低）**
- 中国股市偶有周末补班交易日，`wallClockPhase` 无条件把周末判 CLOSED，即使当日有真实行情也不出信号。
- 建议：仅当数据缺失/缺行时才用墙钟兜底（数据可判则数据优先），或对周末交易日放行。

**③ 锁粒度 per-VM（无害，说明）**
- `StockViewModel` 与 `PredictionViewModel` 各自持有 Mutex 与缓存，进程内两个 VM 对同一指数仍可能各发一次真实请求；同一 VM 内并发已正确去重。可接受。

**④ 遗留待办（非 v8 范围，需跟踪）**
- 指数分钟接口对 sz399006/399001 的返回格式真机确认（v8"剩余"已列出，有 degraded 降级路径）。
- 竞价引擎/标定层三项（见 `MODEL_REVIEW.md` v6 评审）：① 竞价 worker 迟到污染记录（高）；② 回填窗口校验（中）；③ F1 阈值 predicted 不随候选 t 重算（中低）。

## 4. 结论

- v8 声明的改动与代码一致，无虚报；192 用例全绿。
- **无新增漏洞**；①②为新增边界（尤其①时区），建议后续固定 Asia/Shanghai 时区；③④可接受/跟踪。

---

# v7 代码评审

> 对应 `MODEL_CHANGES.md` v7。v7 依据 v6 代码评审结论实施：顶部反转加固、测试补齐、并发与死代码清理。
> 验证：全量 191 用例通过（实测与文档一致）。

## 1. 声明与代码一致性（全部通过，无虚报）

| v7 声明 | 代码证据 | 结果 |
|---------|----------|------|
| 顶部反转保护：`BUY_MOM15_MIN=0.0`（BUY 需 mom15>0） | `IntradaySignal.kt:67`；守卫 `mom15 <= 0 → WATCH` | ✅ |
| 顶部反转保护：`BUY_FROM_HIGH_MAX=-1.0`（距高点回落≥1% 禁 BUY） | `IntradaySignal.kt:68`；守卫 `fromHigh <= -1.0 → WATCH` | ✅ |
| 与既有追高拦截互补（near-high −0.5% / pullback −1.0%） | 原追高拦截 + 新守卫分段覆盖 | ✅ |
| 测试 12→14 例：AFTERNOON 分支 + 顶部反转回归 + 浮点边界规避 | 用例 13（lastPos=156）、14（回落/横盘，mom30 用 1.4% 避开 1.5%） | ✅ |
| `loadIntraday` 指数/个股并发 | `StockViewModel.kt:56-63` `async(Dispatchers.IO)` | ✅ |
| 删除死代码 `signedPct()` | `ui/StockApp.kt` 已无引用 | ✅ |

## 2. 加固效果评估（无新漏洞）

- `mom15 > 0` 硬门槛正确拦截"横盘/短动量转负"的 BUY 残留；`fromHigh ≤ −1.0%` 正确拦截"冲高回落≥1%"的 BUY 残留。
- 用例 14 复现盲区（score≈6.49/4.03 均 ≥2.5）→ 断言 WATCH，v6 盲区已测试固化。

## 3. 剩余边界与注意事项

- **残余中间带（已知，可接受）**：`fromHigh ∈ (−1.0%, −0.5%]` 且 `mom15>0`、`mom30≤1.5%`、`dayGain≤2%` 时 BUY 仍可能出。建议实测后参数化。
- **行为更保守**：`mom15>0` 硬门槛减少 BUY，属设计取舍。
- **原因覆盖不全（轻微）**：双条件同时命中时只显示"转弱"（`if/else if`）。
- **浮点边界**：`mom15 > 0` 严格判定，完全横盘（浮点误差为微小负值）被降级，方向安全。

## 4. 结论

v7 声明与代码一致；顶部反转盲区已实质性堵住；191 用例全绿。

---

# v6 代码评审

> 对应 `MODEL_CHANGES.md` v6。评审对象：`IntradaySignal.kt`、`IntradaySignalTest.kt`、`PredictionViewModel.kt`（重写）、`StockViewModel.kt`、`ui/StockApp.kt`、`SnapshotStore.kt`（去重修复）、`PredictionEngineTest.kt`。验证：全量 189 用例通过。

## 1. 总评

- **代码正确性：高。** 无致命 bug；按位置取点正确处理 minute=120 重复索引；午后 121~150 排除正确避免 mom30 跨午休。
- **数据去重修复：正确且必要。** `addSnapshot`/`addRecord` 按日期去重（SnapshotStore.kt:44/67），放量基线（volZ）不再被重复点击污染。
- **并发防护：合格。** `refresh/refreshAll` 用自增 token + `cancel()` 防乱序覆盖；指数 60 秒缓存落实。
- **模型可靠性：存在顶部反转盲区**（v7 已加固）。

## 2. 模型可靠性问题（v7 已全部修复）

**① 顶部反转盲区（最重要，v7 已修复）**
- `aboveAvg` 用当日 VWAP（滞后指标），冲高回落后最初几分钟仍可能 score≥2.5 → 下跌刚开始仍显示 BUY。v7 用 `mom15>0` 确认 + `fromHigh≤−1.0%` 禁 BUY 修复。

**② 追高拦截边界盲区（已知，仍开放）**
- `dayGain > 2.0%` 严格大于恰好 +2.0% 不触发。→ v8 已改 `>=`。✅

**③ 非 A 股个股的信号语义错配（低，仍开放）**
- 分时弹窗对所有股票开放，港股/美股会用兜底指数 sz399001 做 rsIndex，基准错配。建议非 A 股不计算信号或使用对应指数并标注。

**④ 可靠性可视性退化（产品取舍）**
- 竞价预测详情弹窗（含分目标前推回测命中率）已下线，用户无法自证模型准确度。

## 3. 实现缺陷与细节（v7/v8 已清理 ⑧⑨）

| # | 问题 | 严重度 | 状态 |
|---|------|--------|------|
| ⑤ | `CLOSED` 依赖 241 个点，15:00 后可能漏判 | 低 | ✅ v8 墙钟兜底 |
| ⑥ | 指数分钟接口实机格式未验证 | 待验证 | 开放（有降级路径） |
| ⑦ | `refreshAll` 多股共享同指数代码可能并发重复拉取 | 无害 | ✅ v8 Mutex 去重 |
| ⑧ | `loadIntraday` 串行拉指数再拉个股 | 轻微 | ✅ v7 async 并发 |
| ⑨ | 死代码 `signedPct()` | 轻微 | ✅ v7 已删 |
| ⑩ | `dayGain == 2.0%` 恰好不触发拦截 | 边界 | ✅ v8 改 `>=` |

## 4. 测试覆盖缺口（v7 已补齐 ⑪⑫）

- **⑪ AFTERNOON 分支未测**：→ v7 补用例 13（lastPos=156）✅。
- **⑫ 无"顶部反转"用例**：→ v7 补用例 14 ✅。

## 5. 结论

v6 代码可合入；去重修复有价值；主要问题已由 v7/v8 加固。

---

# v2 方案复审（对应 INTRADAY_SIGNAL_PLAN.md v2）

**结论：全部评审点已落实，可以实施。** 剩余为实施细节建议，不阻塞。

## 1. 评审点落实情况

| 评审点 | v2 处理 | 状态 |
|--------|---------|------|
| ① 量纲失衡 | aboveAvg ×10→×2，单位明确百分点 | ✅ |
| ② 追高拦截 | 新增 `dayGain`，条件 `(dayGain>2% 或 mom30>1.5%) 且 fromHigh>−0.5%` | ✅ |
| ③ 午休边界 | 按位置取点、午后 121~150 过滤 | ✅ |
| ④ NO_TRADE 语义 | 拆分"数据积累中"/"看跌，暂不建议买入" | ✅ |
| ⑤ 非交易时段 | 午间休市/已收盘标注 | ✅ |
| ⑥ HOLD 死分支 | 有持仓分支合并为 SELL/HOLD | ✅ |
| ⑦ volRatio | 按位置差分 perVol[i]=cumVol[i]−cumVol[i−1] | ✅ |
| ⑧ 指数缓存 | ~60 秒缓存 | ✅ |
| ⑨ 指数接口 | 保留实测 + 降级路径 | ✅ |

## 2. 实施细节建议（不阻塞，实施时注意）

- **a. `rsIndex` 对齐**：`mom30(指数)` 按指数序列各自的尾部相对取点。
- **b. 时段判定主用墙钟**：午间休市与 13:00 最后点都是 minute=120，建议墙钟辅助。→ **v8 已实施**（用默认时区，见 v8 评审 ①）。
- **c. `dayGain` 空安全**：`prevClose=null` 时退化为只看 mom30。
- **d. `volRatio` 前置**：点数 <6 时 `volBoost=0`。
- **e. 数据新鲜度（可选）**：横幅可加"更新于 HH:mm"。

## 3. 结论

方案 v2 已覆盖 v1 评审全部要点，工程可行、可单测，具备实施条件。

---

# v1 方案评审（对应 INTRADAY_SIGNAL_PLAN.md v1）

## 1. 总评

**方向正确、工程上可行。** 核心风险不在"能不能做"，而在信号质量与实现边界。

| 维度 | 结论 |
|------|------|
| 可行性 | 高：纯函数 + 无 Android 依赖 + 可单测 + 复用现有分钟线 |
| 数据 | `fetchIntraday`（StockApi.kt:113）；`avgPrice` 已由累计额/累计量算好（Model.kt:196） |
| 指数 | sz399006/399001 代码存在（MarketData.kt:157/165），接口格式需实测 |
| 主要风险 | 评分公式量纲失衡、追高拦截覆盖不足、午休边界脏数据 |

## 2. 必须修正的缺陷

**① 公式量纲失衡：`aboveAvg×10` 一家独大（最高优先级）**

```
score = 1.0×mom15 + 0.8×mom30 + 1.5×rsIndex + 1.2×aboveAvg×10 + 0.8×volBoost
```

- `aboveAvg` 单位是百分点，×10 即 ±3，而 mom30 常态仅 0.3~1.5%。score 几乎被"现价相对均价线"支配 → BUY 常亮/穿线频繁翻转。
- 建议：改为 tanh 或 ×2，并明确单位。→ **v2 已改 ×2**。

**② 追高拦截未必能挡住"159915 教训"**
- 原条件 = `mom30 > 1.5% 且 fromHigh > −0.5%`。慢涨到午间最高点时 mom30 未必超 1.5%。
- 建议：加 `dayGain` 因子，`(dayGain>2% 或 mom30>1.5%) 且 fromHigh>−0.5%`。→ **v2 已实现**。

**③ 午休边界会产生脏数据（实现陷阱）**
- `parseMinuteIndex`（StockApi.kt:150）把 11:30 与 13:00 都映射为 index 120，列表存在两个 minute=120 的点。
- 所有"最近 N 分钟"特征必须按列表位置取点。→ **已实现**。

## 3. 建议一并修正

| # | 问题 | 建议 |
|---|------|------|
| ④ | NO_TRADE 语义重复 | 分开显示"数据积累中"/"看跌，暂不建议买入" |
| ⑤ | 非交易时段出陈旧信号 | 标注"已收盘/午间休市" |
| ⑥ | 有持仓分支死代码 | 合并为 SELL/HOLD |
| ⑦ | volRatio 全天均量计算 | 按位置差分 |
| ⑧ | 每 10 秒多一次指数请求 | 指数缓存 ~60 秒 |
| ⑨ | 指数接口格式支持未知 | 实测，不支持则降级 |

## 4. 确认保留的设计

- 规则版先行，v2 再落盘+回填+标定——路线正确。
- 追高拦截思路正确，但需按 §2② 加强。

## 5. 决策契约

- 信号回答"当前点位动量是否配合买入/持有/离场"，是方向倾向（55–60% 命中）而非保证。
- 震荡市会反复翻转；必须配合仓位与止损纪律。

---

*本评审为数据参考，不构成投资建议。*
