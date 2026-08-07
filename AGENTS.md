# AGENTS.md

本文件为本项目的协作规范和开发约束。每个新会话会自动加载本文件，请严格遵循以下规则。

## 一、代码修改与测试

1. 每次修改完代码后，必须编写相应的测试用例进行测试，并确保测试通过。
2. 如果涉及**股票预测模型算法更新**，必须同步更新 `MODEL_CHANGES.md`（最新改动放最前），记录本次算法变更内容。模型评审对应 `MODEL_REVIEW.md`（竞价预测）与 `INTRADAY_SIGNAL_REVIEW.md`（盘中信号），改动前先读对应评审，改完要能在文档中自洽。

## 开发命令（Windows / PowerShell）

```powershell
.\gradlew.bat :app:testDebugUnitTest   # JUnit 4 单元测试（无需设备/模拟器），全量约 204 例
.\gradlew.bat :app:assembleDebug       # 构建 APK
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- 测试在 `app/src/test/java/com/example/stocktracker/`；纯逻辑模块（`IntradaySignal.kt` / `AuctionPredictor.kt` / `Calibration.kt`）应保持纯函数、无 IO，便于单测；时间相关逻辑通过注入 `nowMillis` / `atTime()` 固定时刻，避免依赖运行时间。`ViewModelt`/协程相关测试用 `MainDispatcherRule.kt` 切换主线程调度器。只跑单个用例追加 `-Pandroid.testInstrumentationRunnerArguments.class` 无效，用 JUnit 类名过滤即可，如：`.\gradlew.bat :app:testDebugUnitTest --tests "com.example.stocktracker.AuctionPredictorTest"`。需 JDK 17（`compileOptions` / `kotlinOptions` 均为 17）。
- 无 CI、无 lint 配置；本地验证 = 全量单测通过 + `assembleDebug` 通过。
- README 的项目结构已滞后：实际还包含 `NotificationLog.kt`、`IndexMinuteCache.kt`、`IntradaySignalStore.kt`、`Overview.kt`、`FeeConfigStore.kt`、`UpdateChecker.kt`、`SettingsStore.kt`。
- 版本号在 `app/build.gradle.kts` 的 `defaultConfig`（当前 versionCode 25 / versionName 3.4），改动前先核对。

## 二、安装验证（重要）

1. 在测试用例全部通过之后，需要构建 APK，并通过 `adb` 安装到开发者的手机上。
2. **在开发者确认效果之前，严禁私自 `commit` 和 `push` 到远程仓库。**
3. 必须先等开发者亲眼确认功能效果并同意后，才能进入下一步（提交）。

## 三、版本号与产物

1. 在开发者同意代码提交之后，每次提交前必须**检查版本号（versionCode / versionName）是否需要更新**。
2. 除非是同一轮迭代中的小修改，否则版本号应递增（versionName 主版本 +1，versionCode +1）。
3. 版本号更新后，需要将构建产物（apk）发布到仓库的 `GitHub Releases`：`https://github.com/fei98/StockTracker/releases/`。

## 四、仓库信息

- 仓库地址：`https://github.com/fei98/StockTracker/`
- Git 用户名：`fei98`
- Git 邮箱：`1910274402@qq.com`

> 注意：提交时请使用上述用户名与邮箱进行身份认证，避免提交身份不匹配。