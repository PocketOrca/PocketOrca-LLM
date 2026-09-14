<div align="center">

# PocketOrca-LLM

**专为高通芯片设计的本地大模型服务器**

Hexagon NPU · Adreno GPU · CPU 三引擎 · OpenAI 兼容端点 · 数据永不出设备

永久免费 · 无广告 · 无内购

[下载最新版](../../releases/latest) · [使用手册](docs/USER-GUIDE-zh.md) · [发布说明](docs/RELEASE-NOTES-v1.3.5.md) · [报告问题](../../issues)

[English](README.md)

</div>

---

> [!IMPORTANT]
> 本软件在运行时建议插入充电器使用，并做好设备散热。

> 本软件**不包含任何模型文件**，安装后需自行下载 GGUF 格式模型（[使用手册 §2](docs/USER-GUIDE-zh.md#2-下载模型)）。

> 本软件基于 [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) 构建，感谢开源社区。

## 它是什么

PocketOrca-LLM 把你的骁龙手机变成一台局域网 LLM 服务器：

- **三引擎按需选择**：NPU（Hexagon）/ Adreno GPU（OpenCL）/ CPU，按机型与模型量化自由选择
- **OpenAI 兼容端点**：`http://<手机IP>:8080/v1/chat/completions`，OpenWebUI、ChatBox、SillyTavern 等任何标准客户端直连
- **后台稳定在线**：息屏、滑卡、拔电不断连；进程被系统回收后 1.5 秒内自动复活
- **调参实验台**：temperature / top_k / top_p / min_p / repeat_penalty / system prompt 即时生效
- **Chat 双模式**：本地流式对话；也可直连任意远程 OpenAI 兼容 API
- **隐私**：模型加载与推理全部在本机完成，无任何数据上传；API Key 经 Android Keystore AES-GCM 加密存储

## 快速开始

1. 从 [Releases](../../releases/latest) 下载 APK 安装，打开后依次授予**电池豁免 / 所有文件访问 / 通知**三个权限（建议全允许，电池豁免保证息屏不断连）
2. 从 [hf-mirror.com](https://hf-mirror.com) 或 [modelscope.cn](https://modelscope.cn) 下载 GGUF 模型放到手机任意目录（RAM 与模型上限：8GB 以下建议 4B 及以下，12GB 建议不超 9B，16GB 可自行测试；作者实测上限：Qwen3.5-9B-Q4_K_M（GPU/CPU）、Qwen2.5-7B-Q4_0（NPU））
3. 回到「服务」页浏览选择模型 → 选引擎 → 启动
4. 局域网内任何 OpenAI 兼容客户端指向 `http://<手机IP>:8080/v1`

详细步骤见[使用手册](docs/USER-GUIDE-zh.md)。

## 从源码构建

```bash
# 1. 自备构建环境：JDK 21、Android SDK (aapt2/d8/apksigner)、Android NDK r27
# 2. NPU/GPU 推理运行时（ocl-libs，约 210MB）从 Releases 附件下载，解压到仓库根目录
# 3. 修改 scripts/build.sh 顶部的 SDK/NDK 路径与版本号后执行：
bash scripts/build.sh          # 产物 app-release.apk
bash scripts/build-verify.sh   # 完整性验收（libs 闭包 / manifest / assets）
```

预编译的 Hexagon skel（`prebuilt/htp-libs-16k`）与 vendor 闭包（`prebuilt/vendor`）已随仓库分发。

## 性能要求

- **推荐**：骁龙 8 Gen 2 及以上，12GB+ RAM
- **NPU 引擎**：骁龙 8 Elite 及之后（HTP v75+）。跑 NPU 请选 Q4_0 版 GGUF（详见下方 NPU 兼容表说明）
- **实测速度**（三星 S25，OneUI 8.0）：1B Q4_0 约 30 t/s；Qwen2.5-7B-Q4_0（NPU）稳定 10 t/s
- **上下文**：12GB RAM 建议 ≤32K
- 联发科及其他 ARMv8 机型可测试使用 CPU 引擎

## 兼容性说明（2026-09 实测定版）

> 作者仅在自有设备上实测，其他机型可能因芯片、系统版本、驱动差异存在不兼容；如遇问题请到 [Issues](../../issues) 反馈（附机型 + 引擎 + 模型量化格式）。

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
| 骁龙 8 Elite (SM8750) | v75 | ✅ 已验证（1B 约 23 t/s；7B约10 t/s，Qwen3 系除外） |
| 骁龙 8 Elite Gen 5 (SM8850) | v79 | ⚠️ 待验证 |
| 骁龙 8 Gen 5 | 新代 Hexagon | ⚠️ 待验证 |
| 骁龙 7+ Gen 3 (SM7675) | v73 | ⚠️ 待真机验证 |
| 骁龙 8 Gen 2 | v73 | ⚠️ 上游支持不完整，小模型（≤4B）可用，7B 等待上游修复 |

> 上游 llama.cpp 对 Hexagon 的支持尚处早期，NPU 仅对 Q4_0 等简单 4-bit 格式有原生 kernel；Q4_K_M 等常用格式会回退 CPU。现阶段实测仅覆盖 Q4_0 (INT4)，作者最大验证模型为 Qwen2.5-7B-Q4_0。

更早的 7 系（Gen 1 / Gen 2, v69）不支持 NPU 引擎，请使用 CPU / GPU 引擎。

> [!NOTE]
> 实际兼容性与性能因机型和系统版本而异，请以实测为准。欢迎提交 issue 反馈你的机型实测结果，共同完善兼容矩阵。

## 已知限制

- **Qwen3 系模型在 NPU 引擎存在上游僵死问题**（llama.cpp 上游 bug），App 会自动引导切换 GPU 引擎
- **骁龙 8 Gen 2 GPU 驱动缺陷**：Q4_K 系量化输出乱码，可改用 CPU（设备驱动问题，升 Android 15 可能自愈）
- 联发科机型仅支持 CPU 引擎
- 推理长时间高负载，注意散热

## 技术栈

llama.cpp (MIT) · Hexagon SDK 6.6 · Android NDK r27 · 手工构建链（aapt2 + d8 + apksigner）· WebView + JS 桥 · 16KB page-size 合规 · targetSdk 36

## 许可证

MIT（本软件自身代码），详见 [LICENSE](LICENSE)。llama.cpp 及其依赖遵循各自的开源协议；预编译目录中的 Qualcomm Hexagon 组件仅限在用户自有高通设备上构建运行使用。
