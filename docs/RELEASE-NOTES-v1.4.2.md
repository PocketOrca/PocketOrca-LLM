PocketOrca-LLM v1.4.2 更新日志

**修复与改进**

1. 主要：新增多模态支持：模型服务启动后，在Chat页附件内可测试图片+语音便签。发图需视觉模型（如 Qwen3-VL/Qwen2.5-VL），mmproj 与模型同目录即自动加载，图片支持 JPEG/PNG，HEIC/WebP 请先转存，分辨率不宜过大；语音便签搭配识别模型（如 Qwen3-ASR-0.6B+mmproj）即可录音转文字，无需任何设置，NPU/GPU/MTP引擎下支持。
2. 新增会话落盘：保存/恢复/删除会话。恢复直接还原 KV 缓存免 prefill，回放聊天记录后无缝续写；
3. 引擎升级：llama.cpp 基线升级——Q2/Q3_K 量化模型上 NPU（Q3_K 与 Q4_K_M 同速，9B 换 Q3_K_M 省约 1.3 GB RAM），MTP 投机解码随官方基线发布
4. 修复骁龙 8 Elite Gen 5 等新平台 NPU 启动崩溃（上游 DMA64 映射与旧 DSP 固件不兼容，已默认规避，感谢@qwerkilo上报issue）

安装
下载 PocketOrca-LLM-v1.4.2-release.apk
系统要求：Android 12 及以上，arm64
与 v1.4.1 同证书签名，可直接覆盖安装
Github仓库：https://github.com/PocketOrca/PocketOrca-LLM/releases
Gitee仓库：https://gitee.com/PocketOrca/pocket-orca-llm/releases
