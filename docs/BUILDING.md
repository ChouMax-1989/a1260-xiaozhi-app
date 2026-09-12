# 从源码构建

普通用户直接到 [Releases](https://github.com/ChouMax-1989/a1260-xiaozhi-app/releases/latest) 下载 APK，不需要安装开发工具。

## 开发环境

- JDK 17。
- Android SDK Platform 35、Build Tools 34.0.0，以及 platform-tools（连接设备时使用）。
- Gradle 8.9 Wrapper 已包含在仓库里；Android Gradle Plugin 为 8.7.3。
- `compileSdk=35`，`minSdk=30`、`targetSdk=30`。原生库只打包 `armeabi-v7a`。

设置 `JAVA_HOME` 指向 JDK 17，设置 `ANDROID_HOME` 指向你自己的 Android SDK。也可通过本机 `local.properties` 配置 `sdk.dir`，不要提交该文件。

首次构建需要联网下载 Gradle 和 Maven 依赖。公开的唤醒模型、发音词典和 sherpa-onnx AAR 已在仓库中，不需要从其他 APK 提取文件。

## 构建与检查

Linux / macOS：

```bash
python3 scripts/verify_assets.py
./gradlew --no-daemon --dependency-verification strict :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Windows PowerShell 7：

```powershell
py -3 scripts/verify_assets.py
./gradlew.bat --no-daemon --dependency-verification strict :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

依赖校验文件与锁文件随源码保存；不要为了绕过下载错误而删除校验。GitHub Actions 会在干净的 Ubuntu 环境运行资源哈希校验、单元测试、Lint 和 APK 构建。

## 签名和安装

当前构建脚本让非调试 release 变体使用本机 debug 签名配置，方便个人构建。**签名私钥不在仓库中**，不同电脑生成的签名一般不同。GitHub Actions 产生的测试构建不能保证直接覆盖 Releases 中的安装包。

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

覆盖安装要求应用 ID 和签名一致。签名不同请使用原发布渠道的 APK，或自行规划数据迁移；不要直接卸载已有应用来“修复”签名问题，因为卸载会丢掉本地设置和绑定资料。

已连接 A1260 时可请求预编译：

```bash
adb shell cmd package compile -m speed -f io.github.choumax.a1260xiaozhi
```

这不是网络或服务器提速开关，只影响本机代码执行。

## 实机测试

设备测试 APK 使用与主 APK 相同的本机签名：

```bash
./gradlew --no-daemon :app:assembleReleaseAndroidTest
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w io.github.choumax.a1260xiaozhi.test/io.github.choumax.a1260xiaozhi.PlaybackLatencyDeviceInstrumentation
```

该测试播放短合成音频，不上传测试音频。它测的是 Android 音频框架进度，不是用外部麦克风测扬声器首声。KWS、结束音和会话分支的其他测试入口在 `app/src/androidTest/AndroidManifest.xml`。

## 文件说明

- `app/src/main/java`：应用逻辑和界面。
- `app/src/main/assets`：公开来源的模型、词典、激活提示音及许可证。
- `app/src/test`：不依赖实机的单元测试。
- `app/src/androidTest`：实机测试与公开语音样本。
- `docs/THIRD_PARTY.md`：资源出处和固定版本。

本机工具链、构建缓存、原始研究记录、设备备份、录音、密钥和历史 APK 不属于公开源码。
