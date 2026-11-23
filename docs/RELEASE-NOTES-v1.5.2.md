# PocketOrca-LLM v1.5.2 更新日志

本次更新给手机装上了「耳朵和嘴」：语音识别与语音合成两个独立服务上线，Chat 页可以直接说话、直接朗读。至此手机端五件套齐了——推理（8080）、搜索（8081）、知识库（8082）、文字转语音（8084）、语音转文字（8085）。

## 一、语音服务

引擎：sherpa-onnx。两个服务完全独立进程、独立前台通知、独立访问 Key，启停互不影响。

| 服务 | 端口 | 标准接口 | 推荐模型 | Key 前缀 |
|---|---|---|---|---|
| TTS 语音合成 | 8084 | `POST /v1/audio/speech`（返回 wav） | melo-tts-zh_en（中英混合，fp32） | `sk-tts-` |
| ASR 语音识别 | 8085 | `POST /v1/audio/transcriptions` | SenseVoice-small | `sk-asr-` |

- 接口兼容 OpenAI 音频端点形态，第三方客户端 / 脚本 / Agent 可直接调用；仅 `/health` 公开，其余都在 Key 门内，鉴权失败返回 401。
- 中文编码兜底：服务端强制 UTF-8，外部直连不带 charset 也不会再把中文解成乱码（此前会「合成结果为空」）。
- 48k / 24k 外部音频重采样改为抗混叠，外部音频不再丢字。
- 音色按模型真实 speaker 数生成：单音色模型（如 melo）自动隐藏下拉框，多音色模型显示并可选。

### 模型包导入（不内置，需自备）

进入服务页语音卡 → 二级页「导入模型包」，选一个 zip 即可：解包 → 校验 → 原子替换，失败保留旧模型不动（支持的包格式、布局、文件清单见下节）。

模型包用 `po-model.json` 自描述（kind / family / name / files / display），缺 manifest 时按目录特征自动判族。判据是文件结构契约，不是模型名白名单——满足契约就能装，所以"支持范围"就是下面这组条件（已对 `ModelManifest` / `TtsConfigFactory` / `SpeechAsr` 源码逐条核对）。

### TTS（VITS 系）：包内必须同时具备

| 必需项 | 说明 |
|---|---|
| `model*.onnx` | 声学 + 声码器图，int8 / fp32 均可。建议 fp32；App 内按 min(6, 核数 − 1) 装配线程 |
| `tokens.txt` | 音素表 |
| `lexicon.txt` | 词典文件（可为空文件） |
| `dict/` | jieba 词典目录，中文分词必需 |

可选增强（有则自动生效）：`espeak-ng-data/`（piper 型英文 VITS 需要）、`date.fst` / `number.fst` / `phone.fst` / `new_heteronym.fst`（日期、数字、电话、多音字文本正则）。

- ✅ **已实机验证**：sherpa 官方 `vits-melo-tts-zh_en`（fp32；本 App 提供打包好的 zip）
- ✅ **结构满足即可装**（未逐一实机验证）：`vits-melo-tts-zh`、其它带 jieba 词典的中英 VITS、多音色 VITS（音色下拉按 speaker 数自动生成，sid 0…N-1）
- ⚠️ **会被拒**：「文件不齐」——官方 piper 英文包默认不带 `lexicon.txt` / `dict/`，直装会被校验拦下；非 VITS 族模型同样拒
- ⛔ **族不支持**：Matcha-TTS、Kokoro、Kitten（引擎有配置位、装配层留分支，选到明确报「本版不支持」）；ZipVoice 在 sherpa-onnx 1.12.15 里根本没有这一族

### ASR（SenseVoice 系）：包内必须同时具备

| 必需项 | 说明 |
|---|---|
| `model*.onnx` | SenseVoice 权重，int8 / fp32 均可 |
| `tokens.txt` | 词表 |

- ✅ **已实机验证**：`sensevoice-small`（中 / 粤 / 英 / 日 / 韩，附带语种、情感、事件标签）
- ✅ **可用**：同架构的其它 SenseVoice 权重，识别语言可选 auto / zh / en / ja / ko / yue
- ⛔ **不支持**：Transducer（Zipformer / Conformer）、Paraformer、Whisper、FireRedAsr、Moonshine、Nemo-CTC、WeNet-CTC、Zipformer-CTC、Canary、Dolphin。这些族的包文件形态与 SenseVoice 相同（model + tokens），会被判族逻辑认成 SenseVoice，但装配固定按 SenseVoice 加载，实跑必然失败——一律按「不支持」对待，别指望换上就能用。

### 打包与落盘约束

- 只认 **zip**（首选，逐条 CRC 校验）和 **tar.gz**；**tar.bz2 不支持**，请先转 zip
- 包内布局不限：扁平或套一层目录都行，自动递归定位 `model*.onnx` 所在目录当模型根（深度 ≤6，最浅优先）
- 建议包内放 `po-model.json`；不放也能装（按目录特征判族），但多模型并存与语言 / 音色信息靠它
- **同名模型不能覆盖**：换模型包必须改 manifest 的 `name`，否则报「同名模型目录无法替换」
- 解包在 `_tmp` 内校验，不合格不动旧模型，页面列出缺失项
- 多模型并存 + 列表切换；ASR 建议包 ≤ 240MB（与主模型同驻时更吃内存）
- **语音模型不随包提供**：TTS 约 182MB（fp32）/ ASR 约 240MB，需自行导入 zip
- 语音服务与主模型同时常驻会占用较多内存，低内存机型建议按需启动

## 二、Chat 页语音识别与播放

- 🎤 **按住说话**：录完自动识别，文本回填输入框，可直接编辑后发送。
- 🔊 **自动播报开关**：开启后所有回复自动朗读，不是单次朗读；播放中再点即停。
- **流式边播**：模型开始吐字就出声，不等整段生成完；按句切分，首段 20 字、后续 50 字，段间预合成，接缝无空档。
- 停止 / 重播 / 连发多条时队列整体重置，不再串段或重复播。

## 三、修复

1. **NPU / MTP 档 KV 量化改 Q4_0 必崩（exit 134）**：改为三层静默钳制——下拉框不再提供 Q4_0、切档时脏值自动回 Q8_0、Java 侧按 `--device HTP*` 二次钳制；不弹提示，静默改回安全值。感谢 @halagua、@Soleil27k 的反馈。
2. **顶部状态胶囊永远显示 off**：状态增加 3 秒真值轮询（原先只靠推送，冷启或接管在跑的服务时 UI 会停在旧状态），并修复滑块缺类导致的视觉错位；点胶囊先查真状态再决定启停。
3. **二级页无返回**：适配 Android 预测性返回（`onBackPressed` 在 targetSdk 36 已不再被调用），改用 `OnBackInvokedDispatcher`；返回键 / 侧滑回服务聚合页并恢复底部 tab 高亮，根级返回仍退出应用。
4. **服务页 Key 行**：未启动的服务不再显示 Key 行，启动后才出现；四张卡的 Key 值与复制按钮统一右对齐。
5. **TTS / ASR 二级页**：按钮改为整行满宽，启动后变暗红「停止服务」态；补全繁中 / 英文文案。

## 安装

下载 `PocketOrca-LLM-v1.5.2-release.apk`

- 系统要求：Android 12 及以上，arm64，与 v1.5.0 / v1.5.1 同证书签名，可直接覆盖安装
- 文件大小 122,283,871 字节；MD5 `fb7e46ceb992d6c6626785f83160ead9`
- 装完先到设置页确认版本号 1.5.2；语音功能需先导入模型包再启动服务

GitHub 仓库：https://github.com/PocketOrca/PocketOrca-LLM/releases
Gitee 仓库：https://gitee.com/PocketOrca/pocket-orca-llm/releases
