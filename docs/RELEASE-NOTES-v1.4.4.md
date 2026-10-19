PocketOrca-LLM v1.4.4 更新日志

**修复与改进**

1. 主要：修复 Android 17 机型 NPU/MTP/MoE 引擎无法启动的问题（捆绑库遮蔽系统库所致，感谢社区用户反馈）
2. 系统提示词：新增系统提示词输入，Chat 对话、CLI 调试、API 请求自动附带
3. 自定义 CLI 参数：高级设置新增附加参数文本框，可向本地服务透传 llama.cpp 参数（进阶用户）
4. KV 缓存量化三档可选：f16 / q8_0（默认）/ q4_0，按需选择。NPU/MTP 档建议 q8_0，如使用 Q4_0，请开启 KV 卸载。
5. NPU 功耗模式：NPU/MTP 档新增功耗档位选择（全速 / 平衡 / 省电，默认全速），在速度与发热、续航之间按需取舍。Qwen3.5-4B 实测生成速度（骁龙 8 Elite，三星 S25）：全速 NPU 约 17.9、MTP 约 21.0 token/s；平衡约 14.2、17.8（约为全速档的 85%）；省电约 10.7、10.9（约为全速档的 52%）
6. 本地推理引擎升级（llama.cpp 新基线）
7. 修复部分场景 NPU 档静默回退 CPU 的问题
8. 服务页交互优化：修复小尺寸机型键盘遮挡输入框问题

安装：
下载 PocketOrca-LLM-v1.4.4-release.apk
系统要求：Android 12 及以上，arm64，与 v1.4.3 同证书签名，可直接覆盖安装
Github仓库：https://github.com/PocketOrca/PocketOrca-LLM/releases
Gitee仓库：https://gitee.com/PocketOrca/pocket-orca-llm/releases
