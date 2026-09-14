# PocketOrca-LLM v1.3.5 — 首个公开发布

[English](RELEASE-NOTES-v1.3.5.en.md) | **中文**

PocketOrca-LLM 把你的骁龙手机变成一台**可离线运行**的局域网大模型服务器：Hexagon NPU / Adreno GPU / CPU 三引擎推理，OpenAI 兼容端点，任何标准客户端直连。**永久免费、无广告、无内购，数据永不出设备。**

> 本软件不包含任何模型文件，需自行下载 GGUF 格式模型（见[使用手册](USER-GUIDE-zh.md)）。

## 📦 安装

- 下载 `PocketOrca-LLM-v1.3.5-vc61-release.apk`（md5 `6421457e`）
- 系统要求：Android 8.0 及以上，arm64 设备
- **从 v1.3.0 及更早版本升级必须先卸载再安装**（旧包为 debug 签名，与正式签名不同，无法覆盖安装）；首次启动依次授予电池豁免 / 所有文件访问 / 通知三个权限

## ✨ 主要功能

- **三引擎按需选择**：Hexagon NPU（htp）/ Adreno GPU（ocl）/ CPU，按机型与模型量化自由选择
- **OpenAI 兼容端点**：`http://<手机IP>:8080/v1/chat/completions`，OpenWebUI、ChatBox、SillyTavern 等任何标准客户端直连
- **后台稳定在线**：息屏、滑卡、拔电不断连；进程被系统回收后 1.5 秒内自动复活并恢复服务
- **通知栏实时监控**：CPU / GPU / RAM / 温度 / 生成速度 / 连接数
- **调参实验台**：temperature / top_k / top_p / min_p / repeat_penalty / system prompt 即时生效
- **Chat 双模式**：本地流式对话；也可直连任意远程 OpenAI 兼容 API
- **API Key 加密存储**（Android Keystore，AES-GCM）
- **三语界面**（简体中文 / 繁體中文 / English）+ 明暗主题
- **隐私**：模型加载与推理全部在本机完成，无任何数据上传

## 📱 兼容性

### CPU 引擎 — 理论上支持所有 2023 年之后上市的 Android 手机

高通 / 联发科 / 其他 ARMv8+ 均可尝试。老机型（A53/A55 小核）可跑但速度有限，建议小尺寸量化（Q4_0 / Q4_K_M）+ 4GB 以上 RAM，具体表现请自行测试并反馈。

### GPU 引擎 — Adreno (OpenCL)，高通 8 系

| SoC | Adreno | 状态 |
|---|---|---|
| 骁龙 8 Elite Gen 5 | 840 | ✅ 已验证 |
| 骁龙 8 Elite | 830 | ✅ 主力实测 |
| 骁龙 8 Gen 5 | 830 | ⚠️ 待验证 |
| 骁龙 8 Gen 3 | 750 | ✅ 已验证 |
| 骁龙 8 Gen 2 | 740 | ⚠️ Adreno 740 驱动缺陷，Q4_K 系乱码 |

联发科（Mali GPU）暂不支持 GPU 引擎，请使用 CPU 引擎。

### NPU 引擎 — Hexagon

| SoC | HTP 架构 | 状态 |
|---|---|---|
| 骁龙 8 Elite (SM8750) | v75 | ✅ 已验证（1B 约 23 t/s；7B 约 10 t/s，Qwen3 系除外） |
| 骁龙 8 Elite Gen 5 (SM8850) | v79 | ⚠️ 待验证 |
| 骁龙 8 Gen 5 | 新代 Hexagon | ⚠️ 待验证 |
| 骁龙 7+ Gen 3 (SM7675) | v73 | ⚠️ 待真机验证 |
| 骁龙 8 Gen 2 | v73 | ⚠️ 上游支持不完整，小模型（≤4B）可用，7B 等待上游修复 |

> 上游 llama.cpp 对 Hexagon 的支持尚处早期，NPU 仅对 Q4_0 等简单 4-bit 格式有原生 kernel；Q4_K_M 等常用格式会回退 CPU。现阶段实测仅覆盖 Q4_0 (INT4)，作者最大验证模型为 Qwen2.5-7B-Q4_0。
> 实测速度均在三星 S25（OneUI 8.0）取得。

更早的 7 系（Gen 1 / Gen 2, v69）不支持 NPU 引擎，请使用 CPU / GPU 引擎。

## ⚠️ 已知限制

- **Qwen3 系模型在 NPU 引擎存在上游僵死问题**（llama.cpp 上游 bug），App 会自动引导切换 GPU 引擎
- **骁龙 8 Gen 2 GPU 驱动缺陷**：Q4_K 系量化输出乱码，可改用 CPU（设备驱动问题，升 Android 15 可能自愈）
- 联发科机型仅支持 CPU 引擎
- 推理长时间高负载发热明显，建议插电并注意散热

## 📄 许可证

MIT（本软件自身代码）。基于 [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) 构建；预编译目录中的 Qualcomm Hexagon 组件仅限在用户自有高通设备上构建运行使用。详见 [LICENSE](../LICENSE)。
