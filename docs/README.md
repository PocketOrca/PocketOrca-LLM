<div align="center">

# ⚡ AnNPU-LM

**专为高通骁龙手机设计的本地大模型服务器**

Hexagon NPU · Adreno GPU · CPU 三引擎 · OpenAI 兼容端点 · 完全离线

永久免费 · 无广告 · 无内购 · 数据永不出设备

[下载 v1.3.1](../../releases/latest) · [使用手册](USER-GUIDE-zh.md) · [更新日志](CHANGELOG.md) · [报告问题](../../issues)

</div>

---

> [!IMPORTANT]
> 本软件**不包含任何模型文件**，安装后需自行下载 GGUF 格式模型（[使用手册 §2](USER-GUIDE-zh.md#2-下载模型)）。
> 本软件基于 [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) 构建，感谢开源社区。

## 它是什么

AnNPU-LM 把你的骁龙手机变成一台局域网 LLM 服务器：

- **三引擎自动适配**：Hexagon NPU（htp）/ Adreno GPU（ocl）/ CPU，按机型与模型量化自动选择，手动可切
- **OpenAI 兼容端点**：`http://<手机IP>:8080/v1/chat/completions`，OpenWebUI、ChatBox、SillyTavern 等任何标准客户端直连
- **后台稳定在线**：息屏、滑卡、拔电不断连；进程被系统回收后 1.5 秒内自动复活（v1.3.1）
- **通知栏实时监控**：CPU / GPU / RAM / 温度 / 生成速度 / 连接数
- **调参实验台**：temperature / top_k / top_p / min_p / repeat_penalty / system prompt 即时生效
- **Chat 双模式**：本地流式对话；也可直连任意远程 OpenAI 兼容 API
- **隐私**：完全离线运行，无任何数据上传；API Key 经 Android Keystore AES-GCM 加密存储

## 快速开始

1. 安装 APK，打开后依次授予**电池豁免 / 所有文件访问 / 通知**三个权限（建议全允许，电池豁免保证息屏不断连）
2. 从 [hf-mirror.com](https://hf-mirror.com) 或 [modelscope.cn](https://modelscope.cn) 下载 GGUF 模型放到手机任意目录
3. 回到「服务」页浏览选择模型 → 选引擎 → 启动
4. 局域网内任何 OpenAI 兼容客户端指向 `http://<手机IP>:8080/v1`

详细图文步骤见[使用手册](USER-GUIDE-zh.md)。

## 性能要求

- **推荐**：骁龙 8 Gen 2 及以上，8GB+ RAM
- **NPU 引擎**：骁龙 8 Elite 及之后（HTP v75+）；1B Q4 实测约 23 t/s
- **上下文**：12GB RAM 建议 ≤32K
- 联发科及其他 ARMv8 机型可使用 CPU 引擎

## 兼容性说明（2026-09 实测定版）

### CPU 引擎 — 所有 Android 手机
高通全代 / 联发科全代 / 其他 ARMv8+。基线 armv8-a 编译 + 运行时特性检测（dotprod / i8mm / SVE 自动分发）。老机型（A53/A55 小核）可跑但速度有限，建议小尺寸量化（Q4_0 / Q4_K_M）+ 4GB 以上 RAM。

### GPU 引擎 — Adreno (OpenCL)，高通 8 系

| SoC | Adreno | 状态 |
|---|---|---|
| 骁龙 8 Elite Gen 5 | 840 | ✅ 已验证 |
| 骁龙 8 Elite | 830 | ✅ 主力实测 |
| 骁龙 8 Gen 5 | 829 | ⚠️ 待验证 |
| 骁龙 8 Gen 3 | 750 | ✅ 已验证 |
| 骁龙 8 Gen 2 | 740 | ⚠️ 仅 Q4_0 量化（Q4_K 无 GPU 内核），约 2 倍于 CPU |

联发科（Mali GPU）暂不支持 GPU 引擎，请使用 CPU 引擎。

### NPU 引擎 — Hexagon

| SoC | HTP 架构 | 状态 |
|---|---|---|
| 骁龙 8 Elite (SM8750) | v75 | ✅ 已验证（1B 约 23 t/s；Qwen3 系除外） |
| 骁龙 8 Elite Gen 5 (SM8850) | v79 | ⚠️ 待验证 |
| 骁龙 8 Gen 5 | 新代 Hexagon | ⚠️ 待验证 |
| 骁龙 7+ Gen 3 (SM7675) | v73 | ⚠️ 待真机验证 |

更早的 7 系（Gen 1 / Gen 2, v69）不支持 NPU 引擎，请使用 CPU / GPU 引擎。

> [!NOTE]
> 实际兼容性与性能因机型和系统版本而异，请以实测为准。欢迎提交 issue 反馈你的机型实测结果，共同完善兼容矩阵。

## 已知限制

- **Qwen3 系模型在 NPU 引擎存在上游僵死问题**（llama.cpp 上游 bug），App 会自动引导切换 GPU 引擎
- **骁龙 8 Gen 2 GPU 驱动缺陷**：Q4_K 系量化输出乱码，仅 Q4_0 可用或改用 CPU（设备驱动问题，升 OneUI 7 可能自愈）
- 联发科机型仅支持 CPU 引擎
- 推理长时间高负载，注意散热

## 技术栈

llama.cpp (MIT) · Hexagon SDK 6.6 · Android NDK r27 · 手工构建链（aapt2 + d8 + apksigner）· WebView + JS 桥 · 16KB page-size 合规 · targetSdk 36

## 许可证

MIT（本软件自身代码）。llama.cpp 及其依赖遵循各自的开源协议。
