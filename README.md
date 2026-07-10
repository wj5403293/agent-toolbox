# Agent工具箱 - Android MCP 服务端

基于 **AIDE Pro** 开发的安卓端 MCP（Model Context Protocol）服务端应用，完全遵循 JSON-RPC 2.0 协议规范。内置 **Python 3.14.6** 嵌入式运行时、**Lua 引擎**、**内存修改引擎**，通过 DeepSeek 网页版集成为 AI 提供本地工具调用能力。

---

## 核心特性

### 协议与通信
- 完整实现 MCP 协议（JSON-RPC 2.0 over HTTP）
- 内置 HTTP 服务器，支持跨域访问（CORS）
- DeepSeek 网页版集成，通过 JS Bridge 自动监听工具调用
- 流式传输工具调用 JSON，自动禁用心跳避免中断
- 前端口资源本地化（marked + KaTeX + 字体，无 CDN 依赖）

### Skill 系统
- 支持热加载 skill 包（知识型 + 工具型）
- 来源：`assets/skills/`（APK 内置） + 运行时目录（`files/skills/`，用户可自行添加）
- skill 中的工具自动注册到 ToolManager，LLM 可直接调用
- `skill_read` 工具按需读取 skill 知识（渐进式披露）
- `skills/list` / `skills/reload` MCP 方法

### MT 管理器集成
- 自动连接 MT 管理器的 APK MCP 服务，注册 `mt_apk_*` 工具
- 支持 APK 分析、资源修改、Smali 编辑、重新打包
- 懒加载：服务未运行时自动跳过，运行时重试连接

### 内嵌 Python 3.14.6 (JNI 模式)
- 使用 Python 官方 `python-3.14.6-aarch64-linux-android.tar.gz` 构建
- 通过 JNI 嵌入 `libpython3.14.so`，无需 Termux 或外部 Python
- 完整标准库（含 `lib-dynload/*.so` 扩展模块）
- 支持 `asyncio`、`ssl`、`sqlite3`、`hashlib` 等扩展模块
- 信号保护机制：`Py_Initialize` 崩溃不杀进程
- GIL 安全管理：`PyEval_SaveThread` + `PyGILState_Ensure/Release`

### 工具集

**内置工具（由 ToolManager 注册）：**

| 分类 | 工具名 | 说明 |
|------|--------|------|
| **Python** | `python` | 内嵌 Python 3.14 代码执行 |
| **Shell** | `shell` | Shell 命令执行（需 root） |
| | `sh` | sh 命令执行 |
| | `cmd` | 命令执行（无 root） |
| **文件** | `file_read` | 文件读取（支持行范围） |
| | `file_write` | 文件写入（replace/insert/append） |
| | `file_list` | 目录列表 |
| **网络** | `http_request` | HTTP 请求（GET/POST/PUT/DELETE） |
| | `web` | 网页内容获取 |
| **数学** | `math_calculator` | 高精度数学计算 |
| **Lua** | `lua` | Lua 脚本执行（GameGuardian API） |
| **GM** | `gm_root_status` | Root 状态检查 |
| | `gm_process_list` | 进程列表 |
| | `gm_attach_process` | 附加进程 |
| | `gm_memory_search` | 内存搜索 |
| | `gm_memory_read` | 内存读取 |
| | `gm_memory_write` | 内存写入 |
| | `gm_memory_freeze` | 内存冻结 |
| | `gm_aob_search` | AOB 特征码搜索 |
| **Skill** | `skill_read` | 按 skill_id 读取技能知识（渐进式披露） |
| | `skills/list` | 已加载技能列表 |
| | `skills/reload` | 热加载技能目录 |
| **MT 管理器** | `mt_apk_*`（运行态注册） | APK 打开/分析/修改/打包（通过 MT APK MCP） |

---

## 项目结构

```
agent-toolbox/
├── AndroidManifest.xml                  # 应用清单
├── build.gradle                         # Gradle 构建（arm64-v8a）
├── README.md
├── BUILD_ENV_ADAPTATION.md
├── assets/
│   ├── python/                          # Python 3.14 运行时
│   ├── lib/                             # 前端资源本地缓存
│   │   ├── marked.min.js                # Markdown 解析器
│   │   └── katex/                       # KaTeX 数学公式 + 字体
│   ├── skills/                          # 内置 skill（agent-toolbox, demo-echo）
│   ├── test_client.html                 # MCP 工具箱前端
│   └── system_prompt_template.json      # LLM 系统提示词模板
├── libs/arm64-v8a/                      # Native 库
│   ├── libpython3.14.so
│   ├── libpython_bridge.so
│   ├── libcrypto.so / libssl.so
│   └── libsqlite3.so
├── res/
│   ├── drawable/                        # UI Shape/Style 资源
│   ├── layout/
│   │   ├── activity_main.xml            # 主界面
│   │   └── activity_deepseek.xml        # DeepSeek WebView 界面
│   └── values/
│       ├── colors.xml                   # 冷色调色板
│       ├── styles.xml                   # 排版层级
│       └── strings.xml
├── skills/                              # Agent 端 skill 定义（非打包入 APK）
└── src/
    ├── main/c/                          # JNI 桥接源码（C/CMake）
    └── main/java/com/example/agenttoolbox/
        ├── MainActivity.java            # 主界面（服务控制 + 日志 + 技能目录）
        ├── DeepSeekActivity.java        # DeepSeek WebView 集成
        ├── DeepSeekChatBridge.java      # DeepSeek 聊天桥接
        ├── JavaScriptBridge.java        # JS <-> Java 工具调用桥
        ├── McpForegroundService.java    # MCP 前台服务
        ├── skills/                      # Skill 子系统
        │   ├── Skill.java               # 数据模型
        │   ├── SkillTool.java           # 工具包装（PythonBridge 执行）
        │   └── SkillManager.java        # 发现/解析/注册/热加载
        ├── mcp/
        │   ├── McpServer.java           # MCP HTTP 服务端
        │   ├── TaskManager.java         # 待办计划管理
        │   └── Task.java                # 任务数据模型
        ├── tools/                       # 工具实现
        │   ├── Tool.java                # 工具接口
        │   ├── ToolManager.java         # 工具管理 + 系统提示词注入
        │   ├── PythonBridge.java        # Python JNI 桥接层
        │   ├── PythonTool.java
        │   ├── ApkMcpClient.java        # MT APK MCP 客户端
        │   ├── SkillReadTool.java       # skill_read 工具
        │   └── ...                      # 其他工具
        └── gm/                          # GM 内存修改引擎
```

---

## MCP 协议接口

### 支持的方法

| 方法 | 说明 |
|------|------|
| `initialize` | 初始化连接（返回工具列表、技能列表、协议信息） |
| `tools/list` | 获取所有工具列表（含内置工具 + skill 工具 + APK 工具） |
| `tools/call` | 调用指定工具（包括 `skill_read`、`skills/list`、`skills/reload`） |
| `skills/list` | 获取已加载技能摘要 |
| `skills/reload` | 重新扫描技能目录并热加载 |
| `notifications/initialized` | 初始化完成通知 |

### 系统提示词注入

`ToolManager.getSystemPrompt()` 从 `assets/system_prompt_template.json` 加载模板，运行时动态注入：
- `tools` 数组：所有已注册工具（含技能提供的工具、MT APK 工具）
- `loaded_skills` 数组：当前已加载技能摘要

---

## Skill 系统

### 安装位置

```
运行时目录（用户自行添加）：
  /storage/emulated/0/Android/data/com.example.agenttoolbox/files/skills/

内置目录（APK 打包自带）：
  assets/skills/
```

### 包格式

支持两种格式：

**文件夹格式（支持 tools/references/scripts）：**
```
skills/<skill-id>/
├── SKILL.md              # YAML frontmatter(name/description/when_to_use) + 正文
├── tools.json            # 可选：[{name, description, inputSchema, exec:{type, src/code}}]
├── references/           # 可选：知识文件（skill_read 按需读取）
└── scripts/              # 可选：工具引用的脚本
```

**单文件格式（仅知识，最简单）：**
```
skills/<skill-id>.md      # 文件名即 skill_id，内容含 frontmatter + 正文
```

### 热加载

放入文件后，LLM 调用 `skills/reload` 即可重新扫描加载，无需重启应用。

---

## MT 管理器集成

### APK MCP 连接

应用通过 `ApkMcpClient` 自动连接 MT 管理器的 APK MCP 服务（`http://127.0.0.1:8787/mcp`）。连接成功后注册 18+ 个 `mt_apk_*` 工具到 `ToolManager`。

### 可用工具

| 分类 | 工具 |
|------|------|
| **打开 APK** | `mt_apk_list_available_apks`、`mt_apk_open` |
| **阅读检索** | `mt_apk_list`、`mt_apk_outline_class`、`mt_apk_read_text`、`mt_apk_read_zip_bytes`、`mt_apk_read_resource`、`mt_apk_search`、`mt_apk_xref_dex`、`mt_apk_xref_resource`、`mt_apk_continue` |
| **修改打包** | `mt_apk_edit_open`、`mt_apk_edit_text`、`mt_apk_edit_resource`、`mt_apk_edit_check`、`mt_apk_build` |
| **清理** | `mt_apk_close` |

### 懒加载机制

- 应用启动时 MT 服务未运行 → 跳过注册，不报错
- LLM 调用 `tools/list` 或 `mt_apk_status` 时系统自动尝试重连
- 重连成功后工具自动可用
- `mt_apk_status` 提供 TCP + HTTP 诊断

---

## 计划/任务执行

支持多步骤计划自动推进（由 `TaskManager` + `PlanState` 管理）：

1. LLM 回复中嵌入 `{"tasks":[...]}` 计划 JSON →
2. 系统解析后驱动 LLM 逐个执行 →
3. 每步完成后 LLM 回复带 `plan_update` 推进 →
4. 全部完成后自动生成总结

关键特性：
- 自动分配 task_id（若缺失）
- 依赖解析（`deps` 字段）
- 优先级排序
- 可完成任意 task_id（不限于当前活跃任务）
- 失败重试（最多 3 次）

---

## Python 内嵌架构

### 工作原理

```
AI 调用 python 工具
    │
    ▼
PythonTool.execute()
    │
    ▼
PythonBridge.init(context)          ← 首次调用时初始化
    │  1. 从 assets 提取标准库到 files/python/lib/python3.14/
    │  2. 设置 PYTHONHOME = files/python/
    │  3. nativeInit() → JNI 调用
    │
    ▼
python_bridge.c: nativeInit()
    │  1. ensure_std_fds()          ← 修复 Android fd 0/1/2
    │  2. setenv("PYTHONHOME", ...)
    │  3. Py_PreInitialize()        ← Python 预初始化
    │  4. 信号保护 (sigsetjmp)
    │  5. Py_Initialize()           ← 初始化解释器
    │  6. 验证 encodings/os 模块
    │  7. PyEval_SaveThread()       ← 释放 GIL
    │
    ▼
PythonBridge.exec(code)
    │
    ▼
python_bridge.c: nativeExec()
    │  1. PyGILState_Ensure()       ← 获取 GIL
    │  2. 重定向 stdout/stderr 到 StringIO
    │  3. PyRun_String()            ← 执行用户代码
    │  4. 捕获输出和异常
    │  5. PyGILState_Release()      ← 释放 GIL
    │
    ▼
返回结果给 AI
```

### 标准库提取

首次启动时，`PythonBridge` 将 `assets/python/stdlib/` 提取到：

```
/data/data/com.example.agenttoolbox/files/python/
└── lib/
    └── python3.14/          ← PYTHONHOME/lib/python3.14/
        ├── os.py            ← Python 核心模块
        ├── encodings/       ← 编码支持
        ├── lib-dynload/     ← 编译扩展 (.so)
        ├── asyncio/         # 异步 IO
        ├── json/            # JSON 处理
        └── ...
```

版本号存储在 `files/.python_version`，升级版本号会强制重新提取。

### 编译 JNI 库

```bash
# 使用 Android NDK r27b 编译
mkdir build && cd build
cmake \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-24 \
    -DANDROID_STL=none \
    -DCMAKE_BUILD_TYPE=Release \
    ../src/main/c
cmake --build .

# 部署到项目
cp libpython_bridge.so ../libs/arm64-v8a/
cp libpython_bridge.so ../app/src/main/jniLibs/arm64-v8a/
```

---

## 快速开始

### 1. 编译安装

**AIDE Pro（推荐）：**
1. 将项目复制到手机
2. AIDE Pro 打开项目 → Build → Rebuild（需 Clean 后 Rebuild 以识别新资源文件）

**Gradle（需 Android Studio）：**
```bash
./gradlew assembleDebug
```

### 2. 启动 MCP 服务

1. 打开应用 → 点击「启动 MCP 服务」
2. 记录显示的地址（如 `http://192.168.1.100:8080`）
3. 同一局域网浏览器访问该地址打开 MCP 工具箱
4. 监听地址可点击复制到剪贴板

### 3. 使用 DeepSeek 集成

1. 启动 MCP 服务 → 点击「打开 DeepSeek 助手」
2. 登录 DeepSeek 账号
3. 对话中 AI 会自动调用本地工具

### 4. 连接 MT 管理器

1. 在 MT 管理器中打开侧拉栏 → 工具 → APK MCP → 点击启动
2. 返回工具箱，LLM 即可调用 `mt_apk_*` 工具

### 5. 安装技能

将 `.md` 文件放入运行时技能目录，调 `skills/reload` 即可：

```
/storage/emulated/0/Android/data/com.example.agenttoolbox/files/skills/
```

---

## 调试

### 查看 Python 初始化日志

```bash
adb logcat -s PythonBridge PythonBridge-C
```

关键日志标签：
- `PythonBridge` — Java 层日志（标准库提取、初始化状态）
- `PythonBridge-C` — Native 层日志（目录检查、Py_Initialize、GIL 管理）
- `SkillManager` — Skill 扫描与注册
- `ApkMcpClient` — MT APK MCP 连接

### 常见问题排查

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| Python 工具未初始化 | JNI 加载失败 | 查看 logcat `PythonBridge-C` |
| `Failed to import encodings` | 标准库路径错误 | 检查 `PYTHONHOME/lib/python3.14/encodings/` |
| `Py_Initialize` 崩溃 | fd 0/1/2 关闭 | `ensure_std_fds()` 自动修复 |
| 第二次调用挂起 | GIL 死锁 | `PyEval_SaveThread` 正确释放 |
| `os.py 不存在` | assets 提取错误 | 删除 `files/python/` 重新提取 |
| `mt_apk_*` 工具不可用 | MT 服务未运行 | 启动 MT APK MCP 后调 `skills/reload` 或重启工具箱 |
| 技能读不到 | 路径不对或没 reload | 调 `skills/reload`，检查 Logcat 中 `SkillManager` 日志 |

---

## 版本信息

- **版本**: 2.1.7（commit 数 /100→大版本，余数/10→小版本，个位→补丁，共 217 次提交）
- **Python**: 3.14.6 (官方 Android aarch64 构建)
- **协议**: MCP (JSON-RPC 2.0 over HTTP)
- **最低 Android**: API 24 (Android 7.0)
- **目标 SDK**: API 32 (Android 12)
- **ABI**: arm64-v8a
- **前端渲染**: marked + KaTeX（本地加载，无 CDN）
- **UI 主题**: 冷色调色板 + 统一间距/圆角体系

### 更新日志

**v2.1.0 — Skill 子系统 + MT 集成 + UI 翻新**
- Skill 子系统：`SkillManager` 发现/解析/注册/热加载技能
- `skill_read` 工具渐进式披露知识
- `skills/list` / `skills/reload` MCP 方法
- MT 管理器 APK MCP 自动集成（懒加载 + 诊断）
- 18+ `mt_apk_*` 工具支持
- 前端资源本地化（marked + KaTeX + 字体，无 CDN）
- Android 原生 UI 翻新（colors/styles/drawable 设计体系）
- 每轮回复后自动清理注入 DOM，长对话不卡
- 系统提示词动态注入技能列表

**v2.0.0 — Python 3.14 内嵌重构**
- 使用 Python 3.14.6 官方 Android 包重新适配
- JNI 嵌入模式，无需 Termux 或外部 Python
- 修复 GIL 死锁（`PyEval_SaveThread`）
- 修复 fd 0/1/2 关闭导致的崩溃
- 信号保护机制防止 `Py_Initialize` 崩溃杀进程

**v1.0.0**
- 初始版本：MCP 服务端 + DeepSeek 集成 + 4 个内置工具
