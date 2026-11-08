# 新仓位跟随模式交付与验证记录

## 1. 当前状态

- 详细规则：已完成，见[模型文档](POSITION_FOLLOWING_MODEL.md)。
- 代码替换：已完成，旧策略执行逻辑已由固定网格与持久阶段替代。
- Android全量单元测试：**303项通过，失败0、错误0、跳过0**。
- Debug APK：**构建成功**，已检查ZIP完整性、新策略类及推荐资源确实打入包内。
- 手机安装：**原签名正式版v3.24已覆盖安装**，账本保留；并行测试版已删除，手机仅保留正式包。
- 验收：303项单测及4项正式包真机返回回归通过，开发者已确认暂未发现问题，并授权本轮提交和推送。

## 2. 参数配置要求

成本上下限、目标金额、步长、固定参考价、策略资金和初始现金均按股票独立配置。算法没有星网锐捷专属数值分支，经济参数未设置时不产生可执行策略。

`app/src/main/res/raw/trade_plan_presets.json`仅保存可选推荐数据。目前002396推荐为10000／30000／8%／每笔目标8000元，用户点击填表后仍可编辑，确认保存才启用。其他股票不自动套用，旧配置不被推荐值覆盖。

## 3. 本轮实现范围

- 固定百分比网格、正向／下跌反向减仓／上涨反向跟随状态、反向到中线恢复。
- 行情游标与待处理提示持久化；报价不移动锚点，不直接改变阶段；成交消费提示。
- 每格剩余数量占用，部分卖出及转增零股不释放；历史买入清仓标记使用当前实际库存。
- 可用现金、整笔目标、盈利批次及A股T+1检查；实际部分成交、陈旧单拒绝、普通真实记账联动。
- 每笔交易前快照、同轮撤销与跨起点保护、保存串行化。
- Schema3序列化与旧配置迁移；异常新增策略字段原文归档，不清空股票和普通交易账本。
- 手动分红／转增／实际扣税、批次成本与未摊费守恒、仿射调整网格、成熟数量快照、权益登记撤销。
- 实际分红／扣款进入日、月、年收益；普通交易汇总不生成虚假卖出。
- 同股及单股／全股请求顺序保护；无效或过期报价不影响最新价格与策略。

## 4. Android测试覆盖

除原有买卖、费用、行情、周趋势与总览测试外，新增／重写重点包括：

| 模块 | 内容 |
|---|---|
| TradePlanTest | 35项新规则核心测试，包含中线、固定格子、参数通用性、预算、T+1、过期单、实际部分成交、公司事件、现金精度 |
| TriggeredPlanTest | 8项新到价汇总语义 |
| TradePlanPersistenceTest等 | 配置与快照往返、v1/v2/future迁移、畸形状态隔离、恢复原文 |
| TradePlanViewModelTest | 14项普通及策略交易、撤销、重启、权益与异步保存联动 |
| QuoteRequestOrderingTest | 8项反序响应、手动价格优先、移除重加、非法报价隔离 |
| TradePlanPresetTest | 7项可选推荐元数据解析及通用性 |
| PlanHistoryInventoryTest | 3项转增零股及真实剩余库存的买入行状态 |

单测XML：`app/build/test-results/testDebugUnitTest/`。可读报告：`app/build/reports/tests/testDebugUnitTest/index.html`。

## 5. 构建环境

本机JDK使用Android Studio JBR 25。原共享Gradle转换缓存存在损坏和文件锁；此次移出的缺失文件已恢复，最终验证使用短路径独立缓存`D:\stg-StockTracker`，复用已下载依赖、离线重建转换结果，没有修改项目依赖版本或全局仓库配置。

本机实际成功命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$gradleBin = 'C:\Users\19102\.gradle\wrapper\dists\gradle-9.3.0-bin\79n14ral3mx1ozqr3csh2u872\gradle-9.3.0\bin\gradle.bat'
& $gradleBin --gradle-user-home 'D:\stg-StockTracker' --offline --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest
& $gradleBin --gradle-user-home 'D:\stg-StockTracker' --offline --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDebug
```

缓存正常的环境可继续使用项目原`gradlew.bat`命令。编译保留了既有Compose图标等弃用警告，没有新增依赖或引入阻断构建的警告配置。

## 6. 首轮策略APK历史记录

本节记录首次构建时的产物，后续已加入返回修复；当前手机使用的独立测试包见第8节，不能用本节旧哈希校验当前`app-debug.apk`。

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 包名：`com.example.stocktracker`
- 应用版本：`3.23`，versionCode `44`。本轮为未提交测试包，未擅自递增版本并发布。
- 大小：17409710字节。
- SHA-256：`0d8712b1383fd493f95930263fde27f6a68f61275ff8e370a392cac636cbfc67`。
- ZIP完整性验证通过；Dex中存在`TradePlanRuntime`，raw推荐配置存在且与002396预设匹配。

版本号在获得提交授权后、正式提交前按项目约定再次检查，并重新构建相应版本；当前APK以本哈希识别。

## 7. 等待手机后的步骤

第1—4步曾按第8节完成并行测试，之后按第9节用原签名覆盖正式版、移除测试版。开发者确认测试效果后已授权提交和推送。

1. 检查`adb devices -l`，确认设备已授权；多设备时先确认目标。
2. 若已安装应用且调试权限可读，先将旧SharedPreferences保存为本地备份，不输出持仓内容，不加入Git。
3. 使用`adb install -r`保留数据覆盖安装；不自动卸载、不清除数据。签名不匹配时停止并说明，不以删数据解决。
4. 启动App，检查进程及本包崩溃日志，确认旧账本能恢复、新策略待用户确认、推荐按钮仅填表。
5. 由开发者核对参数编辑、到价、成交、撤销、重启和权益登记效果；不在真实账本中擅自制造测试买卖。
6. 开发者亲眼确认后，再等待是否提交的明确指示；提交不自动包含推送授权。

## 8. 首页手势返回修复与并行安装（2026-11-08）

### 问题与修改

设备首页返回日志出现旧版返回兼容路径、预测返回未启用。原首页没有启用根返回回调，会走系统／OEM后备分发。

- 根`BackHandler`保持启用：其他主标签返回首页；首页直接调用Activity的`moveTaskToBack(true)`，失败时调用`finish()`，不重新调用返回分发器。
- 子页面的后注册回调与独立弹窗仍优先处理返回。
- Manifest显式启用`android:enableOnBackInvokedCallback="true"`。

### 签名与安装方式

原应用与本机调试包签名不同，Android以`INSTALL_FAILED_UPDATE_INCOMPATIBLE`拒绝覆盖。已备份原APK和6份SharedPreferences，未卸载原应用。用户明确选择并行测试后，新包采用：

- 应用名：`炒股持仓记账本·测试版`。
- 包名：`com.example.stocktracker.preview`，与原包数据隔离。
- APK：`app/build/outputs/apk/debug/stocktracker-preview-back-fix.apk`。
- SHA-256：`04186e557282dabb19ecf76e0fcc73269545c615c41619b70be2b4fb3a3cb95b`。
- 大小：17409774字节；版本仍为3.23／44。
- 通过命令行属性`-PstockTrackerParallelInstall=true`构建；不设置该属性时仍生成正常包名及原应用名。

### 数据副本与核验

复制数据到测试版，原应用不修改。首次stdin导入时大文件截断，字节核验发现后改用文件推送和设备端解包；6份XML逐字节与备份一致后，重新启动和重跑全部真机回归。

启动迁移后再次按股票、持仓批次、买卖和已保存旧策略参数逐项核对，原应用及测试版账本均一致。未创建测试买卖，也未自动应用推荐参数。

备份及核验记录仅保存在Git忽略的`.gradle/device-backups/`和`.gradle/device-verification/`，不在源码文档中公开持仓内容。

### 真机回归

设备PLJ110，Android17／API37。运行：

```powershell
python tools/verify_android_back.py --serial '<已授权设备序列号>' --package com.example.stocktracker.preview
```

4项均通过：

1. 首页实际侧边返回手势，冷启动后及两次温恢复，共3轮退出与再进入，无延迟自动跳回。
2. 首页返回键可正常退出到桌面。
3. “我的→设置”逐级返回菜单、再回首页，不提前退出。
4. “关于”弹窗手势只关闭弹窗，不退出应用。

测试结束保持测试版首页；本包当前进程日志未发现`FATAL EXCEPTION`或`StackOverflowError`。原应用仍存在、未覆盖，测试结论针对本次独立测试包；开发者仍需亲自确认完整功能效果。

## 9. v3.24正式交付（2026-11-08）

开发者确认测试效果并授权提交推送，随后要求移除测试版。已找回另一台电脑的原`debug.keystore`，其证书与手机正式包一致，因而未卸载正式应用，直接通过`adb install -r`覆盖升级。

- 版本：3.24，versionCode45。
- 包名：`com.example.stocktracker`；名称沿用普通应用名。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，17409730字节。
- APK SHA-256：`d054628091cccfdf77395535f298d0de50a5d51636d3bc821b45bfa026eab805`。
- 签名证书SHA-256：`6d4f5275851c1a6f52022c700d11883e8294e60e5a372f8e8342e5493545a62e`。
- 所有并行测试版打包选项已移除；正式包验证正常后才删除`.preview`应用。
- 覆盖前后实际持仓、买卖及原策略参数逐项核对一致；未产生测试交易。
- 正式包全量303项单测通过，4项手机返回回归再跑通过，当前进程未见崩溃／栈溢出。
- 手机包列表仅保留`com.example.stocktracker`，没有测试版。

密钥在仓库外保存，本机通过被Git忽略的`local.properties`配置路径；不会提交密钥、密码或手机备份。跨电脑签名使用方式见`SIGNING.md`。

提交包含应用源码、测试、规则文档及回测脚本／摘要，原始行情、运行缓存和大型逐bar产物留在本地。原作者草稿`模型.txt`未擅自纳入公开提交。
