# 未来一周趋势预测设计方案 v1

> 依据开发者需求：把"盘中实时信号 + 竞价预测"两套实时/当日模型下线，改为**预测未来一周（5 个交易日）整体方向**。
> 实施记录见 `MODEL_CHANGES.md` v14；评审见 `WEEKLY_TREND_REVIEW.md`。

## 1. 背景与目标

**问题**：盘中实时信号回答"现在能不能买"，但存在结构性缺陷——`aboveAvg`（现价 vs 当日均价线）在日内高点取最大值，评分在顶部最高，导致**买入信号常出现在当日高点**（已见顶）。竞价预测只回答当日"收盘 vs 开盘"方向，与"未来走势"决策错位。

**目标**：基于日K线（qfq 前复权）构建**未来 5 个交易日方向**的预测：看涨 / 看跌 / 震荡，并给出信心等级与 walk-forward 历史回测命中率。

**契约**：预测是"未来 5 日方向倾向"（历史命中率 55–65% 为现实预期），是决策过滤器而非保证；回测命中率/平均前向收益在界面上明示，用户自行判断。

## 2. 数据输入

| 输入 | 来源 | 备注 |
|------|------|------|
| 个股日K线 | `web.ifzq.gtimg.cn/appstock/app/fqkline/get?param={code},day,,,300,qfq`（`StockApi.fetchDailyKline`） | 返回 `qfqday` = [日期,开,收,高,低,量]，日期升序，一次约 14 个月 |
| 指数日K线 | 同接口，代码取 `indexCodeFor(stock)`（内置配置 > 板块映射；北交所 null 降级） | 用于相对强弱因子，缺失时降级 |
| 持仓 / 现价 | UI 已有 | 仅决定"哪些股票出预测"，不参与评分 |

**取点纪律**：所有特征只用 `[0, i]` 的数据；标定阈值只用 `[0, i)` 的前推样本（严格 out-of-sample，不偷看未来）。

## 3. 特征定义（均从日K纯函数计算）

| 特征 | 公式 | 说明 |
|------|------|------|
| `ret5/20/60` | close[i] vs close[i−5/20/60] 涨跌幅 % | 短/中/长期动量 |
| `ma20Dev/ma60Dev` | 现价 vs MA20/MA60 % | 站上/跌破均线 |
| `ma20Slope` | MA20 相对 5 日前变化 % | 均线方向 |
| `ma5vsMa20` | MA5 vs MA20 % | 多头/空头排列 |
| `rsi14` | 简化 RSI(14) | 强度（不参与 z 标准化，直接 tanh 映射） |
| `pctOffHigh20/60` | 现价 vs 20/60 日最高 % | 趋势延续 / 回撤 |
| `volRatio` | 近 5 日均量 / 近 20 日均量 | 量能配合 |
| `volatility20` | 20 日收益标准差 | 波动率（高波动降权） |
| `streak` | 连涨（正）/连跌（负）天数 | 比例缩放 |
| `indexRS20` | ret20 − 指数 ret20 | 相对强弱（指数缺失 → null 降级） |

## 4. 评分与决策

```
score = 1.0·C(ret20) + 0.8·C(ret60) + 0.8·C(ma20Dev) + 0.6·C(ma5vsMa20)
      + 0.5·C(ma20Slope) + 0.5·C(volRatio) + 0.6·C(indexRS20)
      + 0.3·C(rsi) + 0.3·C(streak) + 0.4·C(pctOffHigh60) − 0.2·C(volatility20)
C(x) = 2·tanh(z(x)/scale)，z 为滚动 120 窗口 z 标准化；rsi/streak 不走 z
```

- 名义范围约 ±12；`|score| ≥ 阈值` → 看涨/看跌，否则震荡。
- **阈值**：walk-forward 前推序列上 F1 最大化标定（候选 0.5–3.0）；样本不足用默认 1.5。
- **信心**：历史前推中 `|score 差| ≤ 1` 邻域的衰减加权命中率（≥70% 高 / ≥60% 中 / 其余低 / 样本不足"数据积累中"）。
- **目标**：`close[i+5]/close[i]−1`，±1.0% 以内为震荡（方向样本剔除，不计入命中率）。

## 5. 集成改动

| 文件 | 改动 |
|------|------|
| `StockApi.kt`（改） | 新增 `fetchDailyKline` + 纯解析 `parseDailyKline`（正则，无 org.json） |
| `MarketData.kt`（裁剪） | 保留 `QuoteFields`/`parseQuoteFields`/`quoteTimeIsOnDate`/`indexCodeFor`；删除竞价抓取 |
| `WeeklyTrend.kt`（新增） | 纯逻辑：特征/评分/分类/目标/walk-forward/信心/完整预测，无 Android 依赖 |
| `WeeklyTrendViewModel.kt`（新增） | 当前股 + 全部持仓状态；日K进程级缓存（按天），按需刷新 |
| `StockViewModel.kt`（改） | 移除盘中信号 StateFlow/指数分钟拉取；**保留分时图** |
| `MainActivity.kt`（改） | 移除 Worker 调度与通知渠道 |
| `ui/StockApp.kt`（改） | 首页预测卡 → "未来一周预测"；总览第 2 标签 → "周趋势"；删除信号横幅/通知铃铛/通知历史 |

**下线**：`IntradaySignal*`、`Prediction*`（AuctionPredictor/Calibration/PredictionEngine/PredictionWorkers/PredictionNotifier/PredictionViewModel）、`SnapshotStore`、`SectorLearner`、`SectorResolver`、`IndexMinuteCache`、`NotificationLog`，及 `androidx.work` 依赖。

## 6. 展示样例

```
[未来一周预测 · 贵州茅台]     [刷新]
  看涨 +6.5   信心高
  · 20日动量+4.8%  · 站上20日线  · 均线多头排列  · 近5日放量
  回测：方向命中率 58%（142 样本）· 平均前向收益 +0.9%
```

## 7. 测试计划（WeeklyTrendTest，16 例）

1. K线解析真实格式 / 异常输入
2. 特征：动量/均线/RSI/连阳/距高点/量比
3. 特征：指数相对强弱与降级
4. 评分：横盘后急拉强正 / 急跌强负
5. 目标：未来五日方向与 ±1% 震荡带 / 未来不足
6. 分类阈值边界
7. walk-forward：急拉样本命中高、平均前向收益正 / 样本不足返回空
8. 阈值与信心：样本不足返回 null / "数据积累中"
9. 完整预测：K线不足出"数据积累中" / 足够出方向与回测 / 无指数降级
10. A 股判定

## 8. 已知取舍

- 前推阈值在每折用此前样本拟合（严格 out-of-sample）；界面回测命中率即该序列的真实样本外命中率。
- 特征 z 标准化对"匀速上涨"（动量 % 递减）偏中性偏空，属相对动量口径，真机以回测数据为准。
- 权重为经验初始值，未做逐特征权重搜索（竞价模型的 walk-forward 权重搜索需大量样本）；阈值已标定，权重可待数据积累后参数化。
- 日K缓存按天，进入首页/总览周趋势标签刷新一次；多日维度不做 30 秒自动刷新。

---

*本方案为数据参考，不构成投资建议。*
