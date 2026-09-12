# 第三方代码和资源

模型、运行库、激活提示音和测试语音均来自公开上游。用户的设备资料、录音、绑定信息和签名私钥不在仓库中。

| 内容 | 固定来源 | 许可与说明 |
| --- | --- | --- |
| sherpa-onnx Android AAR | [官方 v1.13.7](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar) | Apache-2.0；AAR 原样保留，APK 仅打包 ARMv7 |
| 中文唤醒模型 | [WenetSpeech 3.3M，2024-01-01](https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2) | 上游 README 声明 Apache-2.0；选用 epoch-12 / avg-2 / chunk-16 / left-64 的 int8 encoder、int8 joiner 和 fp32 decoder |
| 发音词典 | [pypinyin 0.55.0](https://pypi.org/project/pypinyin/0.55.0/) | MIT；从字符、词组读音生成 TSV，按模型 token 拆分声母韵母 |
| 内嵌 ONNX Runtime | sherpa-onnx AAR 携带的运行库（版本字符串 1.27.1） | MIT；许可证及 ThirdPartyNotices 均随 assets 保存 |
| 官方绑定提示与数字读音 | [xiaozhi-esp32 固定提交](https://github.com/78/xiaozhi-esp32/tree/c7241272f2d5fd140c77542f3cf12d09e717fc2f/assets/locales/zh-CN) | MIT；完整声明在根目录 NOTICE |
| OkHttp 4.12.0 / Okio 3.6.0 | Maven Central，上游 Square 项目 | Apache-2.0 |
| Kotlin 标准库 1.9.10 / JetBrains annotations 13.0 | Maven Central，上游 JetBrains 项目 | Apache-2.0 |
| JUnit 4.13.2 / Hamcrest 1.3 / JSON-java 20240303 | Maven Central，仅用于测试 | 各自上游许可证；不作为业务实现打包进主 APK |

许可证文件位于 `app/src/main/assets/kws/`：`SHERPA-LICENSE.txt`、`PYPINYIN-LICENSE.txt`、`ONNXRUNTIME-LICENSE.txt` 和 `ONNXRUNTIME-ThirdPartyNotices.txt`。模型原始 README 同目录保留。

## 资源校验

`assets-sha256.json` 保存已打包资源的精确 SHA-256，`scripts/verify_assets.py` 在本地和 CI 中逐个核对。

上游原始输入的 SHA-256：

```text
c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8  sherpa-onnx-1.13.7.aar
b2f7c89690dc8ce4c6ed6afeab7cd800c36ad1421fb6b6302b4a4b194cf7f35f  sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2
d53b1e8ad2cdb815fb2cb604ed3123372f5a28c6f447571244aca36fc62a286f  pypinyin-0.55.0-py2.py3-none-any.whl
```

## 测试语音和原创提示音

- `app/src/androidTest/assets/kws-tests/0.pcm` 至 `6.pcm` 是上述公开模型包内 `test_wavs/0.wav` 至 `6.wav` 的原始 PCM；发布前已逐字节比对。
- `7.pcm` 是公开样本 `4.pcm` 加入确定性高斯噪声的测试副本（5 dB，随机种子 1264），不是用户录音。
- `app/src/main/res/raw/` 中的唤醒、发送、结束三段提示音为本项目合成音，不来自其他 APK。

本项目原创部分尚未指定开源许可证。公开仓库不改变上述第三方材料各自的许可条件。
