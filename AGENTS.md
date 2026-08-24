# AGENTS.md

本文件为本项目的协作规范和开发约束。每个新会话会自动加载本文件，请严格遵循以下规则。

> **约定：所有回复一律使用中文。** 与开发者交流、解释代码、提交信息等一律用中文。

## 一、代码修改与测试

1. 每次修改完代码后，必须编写相应的测试用例进行测试，并确保测试通过。
2. 如果涉及**股票预测模型算法更新**，必须同步更新 `MODEL_CHANGES.md`（最新改动放最前），记录本次算法变更内容。模型评审对应 `WEEKLY_TREND_REVIEW.md`（未来一周趋势预测，方案在 `WEEKLY_TREND_PLAN.md`），改动前先读评审，改完要能在文档中自洽。旧模型（盘中信号 `INTRADAY_SIGNAL_*`、竞价预测 `MODEL_REVIEW.md`）已于 v14 退役，文档仅作历史存档。

## 开发命令（Windows / PowerShell）

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # AGP 8.13 需 JDK 25；仅装 JDK 17 会触发 foojay 下载失败
.\gradlew.bat :app:testDebugUnitTest   # JUnit 4 单元测试（无需设备/模拟器），全量 171 例
.\gradlew.bat :app:assembleDebug       # 构建 APK
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- 测试在 `app/src/test/java/com/example/stocktracker/`；纯逻辑模块（`WeeklyTrend.kt` 等）应保持纯函数、无 IO，便于单测；时间相关逻辑通过注入固定时刻/纯输入避免依赖运行时间。`ViewModel`/协程相关测试用 `MainDispatcherRule.kt` 切换主线程调度器。只跑单个用例用 JUnit 类名过滤即可，如：`.\gradlew.bat :app:testDebugUnitTest --tests "com.example.stocktracker.WeeklyTrendTest"`。**注意：CLI 构建 Gradle 守护进程需 JDK 25**（`compileOptions`/`kotlinOptions` 为 17 指字节码版本，不等于构建 JVM）；`JAVA_HOME` 指向 Android Studio 自带 JBR（`C:\Program Files\Android\Android Studio\jbr`），否则 Gradle 会尝试从 foojay 下载 JDK 25 而失败。
- 无 CI、无 lint 配置；本地验证 = 全量单测通过 + `assembleDebug` 通过。
- README 的项目结构已随 v14 更新，但后续新增文件后可能再次滞后（现有 `Overview.kt`、`FeeConfigStore.kt`、`SettingsStore.kt`、`UpdateChecker.kt`、`WeeklyTrend.kt`、`WeeklyTrendViewModel.kt` 已列）。
- 版本号在 `app/build.gradle.kts` 的 `defaultConfig`（当前 versionCode 34 / versionName 3.13），改动前先核对。

## 二、安装验证（重要）

1. 在测试用例全部通过之后，需要构建 APK，并通过 `adb` 安装到开发者的手机上。
2. **在开发者确认效果之前，严禁私自 `commit` 和 `push` 到远程仓库。**
3. 必须先等开发者亲眼确认功能效果并同意后，才能进入下一步（提交）。

## 三、版本号与产物

1. 在开发者同意代码提交之后，每次提交前必须**检查版本号（versionCode / versionName）是否需要更新**。
2. 除非是同一轮迭代中的小修改，否则版本号应递增（versionName 小数位 +1，如 3.11 → 3.12，versionCode +1）。
3. **有源码改动（版本号递增）的推送必须发布**：凡包含源码改动并递增版本号（`versionName`）的推送，`git push` 前必须先把对应版本号（`v{versionName}`，如 `v3.13`）的构建产物（apk）发布到仓库的 `GitHub Releases`：`https://github.com/fei98/StockTracker/releases/`。发布即创建 tag 并附 `app\build\outputs\apk\debug\app-debug.apk`（用 `gh release create`）；若漏发，则本次推送视为未完成，需要先补发。**纯文档/无源码改动（版本号不变）的推送无需发布**。
4. **push 失败处理**：`git push` 不成功时，过一小会**只重试一次**；仍失败则**停止，不排查原因**（多为 GitHub 访问不稳定），等待下次推送时再推。不要反复重试、不要改动网络/代理配置。
4. 版本号更新后，需要将构建产物（apk）发布到仓库的 `GitHub Releases`：`https://github.com/fei98/StockTracker/releases/`。

## 四、仓库信息

- 仓库地址：`https://github.com/fei98/StockTracker/`
- Git 用户名：`fei98`
- Git 邮箱：`1910274402@qq.com`

> 注意：提交时请使用上述用户名与邮箱进行身份认证，避免提交身份不匹配。