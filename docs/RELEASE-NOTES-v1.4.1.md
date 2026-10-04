# PocketOrca-LLM v1.4.1 更新日志

**中文**


## 修复与改进

- 主要：新增MTP 引擎档： 实测Qwen3.5-4B/9B MTP 系全 NPU 卸载 + 草稿模型投机解码：Qwen3.5-4B（三星s25，骁龙 8 Elite）实测 18.9 t/s，输出速度提升约30%，长上下文最高 +60%
- KV 缓存量化默认 q8_0：全引擎档生效，KV 内存约省一半
- 增加空闲自动卸载：关/3/10/30 分钟四档，超时自动卸载模型与 KV 缓存，内存全释放，下次请求自动重载
- 内存自检告警：每秒扫描进程内存，内存不足时提示
- 思考控制：Chat 页新增深度思考开关
- Anthropic 兼容端点：新增 /v1/messages，Claude Code 可直连手机（ANTHROPIC_BASE_URL=http://<手机IP>:8080）
- 修复骁龙 8 Elite（Adreno 830）GPU 引擎「KV 卸载」开启时输出乱码的问题；「KV 卸载」开关现默认关闭（KV 与权重同设备，输出正常且更快），打开仅作省内存备用（向引擎透传 --no-kv-offload）
- 修复部分机型（如 Ace3P，骁龙 8 Gen 3）NPU 启动报 invalid device：系统会跳过解压 APK 内非 lib 前缀的 vendor DSP 库，应用现于启动时自动补齐

## 安装

- 下载 `PocketOrca-LLM-v1.4.1-release.apk`
- 系统要求：Android 12 及以上，arm64
- 与 v1.4.0 同证书签名，可直接覆盖安装
