# 固定签名与换电脑构建

## 为什么另一台电脑的安装包不能覆盖

默认调试密钥位于各电脑自己的用户目录中，由本机生成，通常互不相同。Android要求同包名升级继续使用相同签名，仅有同一份源码和相同版本号不够。

默认文件位置：

- Windows：`%USERPROFILE%\.android\debug.keystore`
- macOS／Linux：`~/.android/debug.keystore`

手机原正式包的密钥已从原电脑找回并核对。原签名证书SHA-256为：

```text
6d4f5275851c1a6f52022c700d11883e8294e60e5a372f8e8342e5493545a62e
```

这是公开证书指纹，不是私钥；可以保存在仓库作为发版核验依据。

## 本项目如何指定同一份密钥

项目按现有发布约定构建并发布debug APK。支持显式指定仓库外的原调试密钥：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:STOCKTRACKER_KEYSTORE = 'C:\secure\StockTracker-original.keystore'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

也可以仅对本次构建指定路径，命令行属性优先于环境变量：

```powershell
.\gradlew.bat '-PstockTrackerKeystore=C:\secure\StockTracker-original.keystore' :app:assembleDebug
```

还支持在本机已被Git忽略的`local.properties`中保存文件路径（不保存密码）：

```properties
stockTrackerKeystore=C:/secure/StockTracker-original.keystore
```

优先级为命令行属性、环境变量、`local.properties`。本机已将找回的原密钥另存到用户`.android`目录的独立文件，并在本机`local.properties`配置路径；原全局调试密钥没有覆盖。换电脑后需复制同一份原密钥并配置该电脑的路径。

此选项只替换Android标准debug签名配置的文件路径，使用原Android调试密钥的标准别名与凭据，不自动兼容任意自定义release密钥。不要为了换电脑覆盖该电脑全局`debug.keystore`，以免影响它的其他项目。

不设置时仍允许本机临时调试构建，但这种包可能使用另一份签名，**不能据此直接替换正式发布包**。发版前必须核对APK证书指纹与上面一致。

## 密钥备份与共享

- 仓库是公开仓库，不提交未加密keystore、密码或含密码的本地属性文件；`.gitignore`包含相关忽略规则。
- 原密钥在安全的仓库外位置保存，并做独立的加密备份；其他电脑通过受控方式取得同一份文件。
- 如使用CI，文件及所需凭据通过CI密钥管理配置，不写进工作流正文或日志。
- 丢失原私钥后不能从APK证书恢复它；不要因为可以生成新密钥就随意替换签名。
- 如确有保存到仓库的需求，只考虑经过可靠加密的备份，并让解密密钥与仓库分离；本项目本轮未采用此方式。

## v3.24处理记录

本轮已找回原密钥并验证与手机正式包一致，因此正式包升级采用原签名及正常包名，不再需要并行测试版或卸载正式包。并行测试版打包选项已移除。

本机此次提供的文件位于仓库外；它没有被复制到Git工作区，也不纳入提交。APK最终签名与设备验证结果见`POSITION_FOLLOWING_VERIFICATION.md`。
