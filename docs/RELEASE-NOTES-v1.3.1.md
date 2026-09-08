# AnNPU-LM v1.3.1 — 首个公开发布

AnNPU-LM 把你的骁龙手机变成一台**完全离线**的局域网大模型服务器：Hexagon NPU / Adreno GPU / CPU 三引擎推理，OpenAI 兼容端点，任何标准客户端直连。**永久免费、无广告、无内购，数据永不出设备。**

> 本软件不包含任何模型文件，需自行下载 GGUF 格式模型（见使用手册）。

## ✨ 主要功能

- **三引擎自动适配**：Hexagon NPU（htp）/ Adreno GPU（ocl）/ CPU，按 SoC 与模型量化自动选择，手动可切
- **OpenAI 兼容端点**：`http://<手机IP>:8080/v1/chat/completions`，OpenWebUI、ChatBox、SillyTavern 等任何标准客户端直连
- **后台稳定在线**：息屏、滑卡、拔电不断连；进程被系统回收后 1.5 秒内自动复活并恢复服务（v1.3.1 核心修复）
- **通知栏实时监控**：CPU / GPU / RAM / 温度 / 生成速度 / 连接数
- **调参实验台**：temperature / top_k / top_p / min_p / repeat_penalty / system prompt 即时生效
- **Chat 双模式**：本地流式对话；也可直连任意远程 OpenAI 兼容 API
- **API Key 加密存储**（Android Keystore，AES-GCM）
- **三语界面**（简体中文 / 繁體中文 / English）+ 明暗主题
- **隐私**：完全离线运行，无任何数据上传

## 🛠 本版关键修复（v1.3.1）

- **息屏 / 滑卡后服务断连**（相对内部 v1.3.0 的最后一道关口）：上滑清理会杀死整个应用进程（`am_kill remove task`），前台服务无法拦截。v1.3.1 增加三层防护：
  1. 进程被杀后 1.5 秒精确闹钟自动拉起服务（`SCHEDULE_EXACT_ALARM`，Android 14+ 自动授予）
  2. 复活时自动接管残留的孤儿推理进程，不中断正在进行的生成
  3. 服务参数持久化，复活后按原配置重启
- **主动停止不复活**：在 App 内点「停止」即真正停止，不会被闹钟拉起
- **电池豁免引导**：首次启动顺序引导（电池豁免 → 文件访问 → 通知），保证后台存活

## 📱 兼容性

### CPU 引擎 — 所有 Android 手机
高通全代 / 联发科全代 / 其他 ARMv8+。基线 armv8-a 编译 + 运行时特性检测（dotprod / i8mm / SVE 自动分发）。老机型（A53/A55 小核）可跑但速度有限，建议小尺寸量化（Q4_0 / Q4_K_M）+ 4GB 以上 RAM。

### GPU 引擎 — Adreno (OpenCL)

| SoC | Adreno | 状态 |
|---|---|---|
| 骁龙 8 Elite Gen 5 | 840 | ✅ 已验证 |
| 骁龙 8 Elite | 830 | ✅ 主力实测 |
| 骁龙 8 Gen 5 | 829 | ⚠️ 待验证 |
| 骁龙 8 Gen 3 | 750 | ✅ 已验证 |
| 骁龙 8 Gen 2 | 740 | ⚠️ 仅 Q4_0 量化（Q4_K 无 GPU 内核） |

联发科（Mali GPU）暂不支持 GPU 引擎，请使用 CPU 引擎。

### NPU 引擎 — Hexagon

| SoC | HTP 架构 | 状态 |
|---|---|---|
| 骁龙 8 Elite (SM8750) | v75 | ✅ 已验证（1B 约 23 t/s） |
| 骁龙 8 Elite Gen 5 (SM8850) | v79 | ⚠️ 待验证 |
| 骁龙 8 Gen 5 | 新代 Hexagon | ⚠️ 待验证 |
| 骁龙 7+ Gen 3 (SM7675) | v73 | ⚠️ 待真机验证 |

更早的 7 系（Gen 1 / Gen 2, v69）不支持 NPU 引擎，请使用 CPU / GPU 引擎。

## ⚠️ 已知限制

- **Qwen3 系模型在 NPU 引擎存在上游僵死问题**（llama.cpp 上游 bug），App 会自动引导切换 GPU 引擎
- **骁龙 8 Gen 2 的 GPU 驱动存在缺陷**：Q4_K 系量化输出乱码，仅 Q4_0 可用或改用 CPU 引擎（属设备驱动问题，无法在应用侧修复，升 OneUI 7 可能自愈）
- 联发科机型仅支持 CPU 引擎
- 推理长时间高负载，注意散热
- 实际兼容性与性能因机型和系统版本而异，请以实测为准

## 📦 下载与校验

| 文件 | 说明 |
|---|---|
| `AnNPU-LM-v1.3.1-vc56.apk` (43 MB) | Android 8.0+ / arm64-v8a |

- MD5: `6ffcbd1c4dd51515b02719570da0b9e6`
- 国内镜像直链：（发布时补充，如蓝奏云）

## 🙏 致谢

基于 [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) 构建，感谢 ggml-org 与开源社区的杰出工作。Hexagon NPU 支持依赖上游 ggml-hexagon 后端。

---

*使用说明详见 [使用手册](USER-GUIDE-zh.md)。本软件永久免费，如对你有帮助欢迎 Star。*
