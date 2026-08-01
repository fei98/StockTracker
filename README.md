# 炒股持仓记账本 (StockTracker)

一个简洁的 Android 炒股交易记录 App：记录每次买入/卖出，自动计算持仓均价、卖出盈亏与浮动盈亏。

## 功能

- **买入记录**：输入单价和数量即可记录，支持多次分批买入，自动计算持仓均价（总成本 ÷ 总数量）
- **智能卖出匹配**：卖出时按**最低买入价优先**抵扣持仓（先卖最便宜的批次），并自动计算本次交易的实现盈亏
- **现价盈亏**：输入当前股价后，逐批显示每笔买入的浮动盈亏及百分比，顶部汇总总市值与总浮动盈亏
- **持仓明细**：每个买入批次的价格、剩余数量与浮动盈亏
- **交易记录**：完整的买卖历史（含时间、价格、数量、卖出盈亏）
- **一键清空**：一键清除所有数据
- 红涨绿跌配色（中国习惯），自动适配深色/浅色模式

## 卖出匹配规则示例

先后买入：5 元 × 200 股、3 元 × 300 股、1 元 × 100 股。

股价上涨后以 **4 元卖出 300 股**：

1. 先抵扣最便宜的 **1 元 × 100 股**（盈利 300 元）
2. 再抵扣 **3 元 × 200 股**（盈利 200 元）
3. 剩余持仓：**3 元 × 100 股** + 5 元 × 200 股
4. 本次交易实现盈利：**500 元**

若此时输入现价 2 元，浮动盈亏为：1 元批次 +100 元、3 元批次 -300 元、5 元批次 -600 元。

## 技术栈

- Kotlin + Jetpack Compose (Material 3)
- ViewModel + StateFlow（无状态 UI 架构）
- Android Gradle Plugin 8.13 / Gradle 9.3
- JUnit 4 单元测试（28 个用例，覆盖买入、卖出匹配、盈亏、边界条件）

## 构建运行

1. 用 Android Studio（推荐 2024.1+）打开本项目
2. 等待 Gradle 同步完成
3. 连接设备或启动模拟器，点击 Run

命令行构建：

```bash
./gradlew :app:assembleDebug
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```
app/src/main/java/com/example/stocktracker/
├── MainActivity.kt          # 入口 Activity
├── Model.kt                 # 数据模型（批次/交易记录/状态）
├── StockViewModel.kt        # 核心业务逻辑（买入/卖出匹配/盈亏）
└── ui/
    ├── StockApp.kt          # Compose 界面
    └── theme/Theme.kt       # 主题与配色
```

## 测试

核心逻辑（最低价优先卖出匹配、均价计算、盈亏计算）由 `app/src/test/java/com/example/stocktracker/StockViewModelTest.kt` 中的 28 个单元测试覆盖。
