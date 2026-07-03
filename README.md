# Agent工具箱 - Android MCP 服务端

基于 **AIDE Pro** 开发的安卓端 MCP（Model Context Protocol）服务端应用，完全遵循 JSON-RPC 2.0 协议规范。内置 **Python 3.14.6** 嵌入式运行时、**Lua 引擎**、**内存修改引擎**，通过 DeepSeek 网页版集成为 AI 提供本地工具调用能力。

---

## 核心特性

### 协议与通信
- 完整实现 MCP 协议（JSON-RPC 2.0 over HTTP）
- 内置 HTTP 服务器，支持跨域访问（CORS）
- DeepSeek 网页版集成，通过 JS Bridge 自动监听工具调用
- 流式传输工具调用 JSON，自动禁用心跳避免中断

### 内嵌 Python 3.14.6 (JNI 模式)
- 使用 Python 官方 `python-3.14.6-aarch64-linux-android.tar.gz` 构建
- 通过 JNI 嵌入 `libpython3.14.so`，无需 Termux 或外部 Python
- 完整标准库（含 `lib-dynload/*.so` 扩展模块）
- 支持 `asyncio`、`ssl`、`sqlite3`、`hashlib` 等扩展模块
- 信号保护机制：`Py_Initialize` 崩溃不杀进程
- GIL 安全管理：`PyEval_SaveThread` + `PyGILState_Ensure/Release`

### 工具集 (17 个内置工具)

| 分类 | 工具名 | 说明 |
|------|--------|------|
| **Python** | `python` | 内嵌 Python 3.14 代码执行 |
| **Shell** | `shell` | Shell 命令执行 |
| | `cmd` | 命令执行（无 root） |
| | `sh` | sh 命令执行 |
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

---

## 项目结构

```
agent-toolbox/
├── AndroidManifest.xml                  # 应用清单
├── build.gradle                         # Gradle 构建（arm64-v8a）
├── assets/
│   ├── python/
│   │   ├── include/                     # Python 3.14 C 头文件
│   │   └── stdlib/                      # Python 标准库
│   │       ├── lib-dynload/             # 编译的 .so 扩展模块
│   │       ├── encodings/               # 编码模块
│   │       ├── asyncio/                 # 异步 IO
│   │       └── ...                      # os.py, json/, re/ 等
│   └── test_client.html                 # MCP 测试客户端
├── libs/
│   └── arm64-v8a/
│       ├── libpython3.14.so             # Python 3.14.6 共享库
│       ├── libpython_bridge.so          # JNI 桥接层
│       ├── libcrypto.so                 # OpenSSL 加密库
│       ├── libssl.so                    # OpenSSL SSL 库
│       └── libsqlite3.so                # SQLite 库
├── res/
│   ├── layout/
│   │   ├── activity_main.xml            # 主界面
│   │   └── activity_deepseek.xml        # DeepSeek WebView 界面
│   └── values/
│       └── strings.xml
└── src/
    ├── main/
    │   ├── c/
    │   │   ├── CMakeLists.txt           # NDK CMake 配置
    │   │   ├── python_bridge.c          # JNI Python 桥接层
    │   │   └── include/                 # Python/OpenSSL/SQLite 头文件
    │   └── ...
    └── com/example/agenttoolbox/
        ├── MainActivity.java            # 主界面（服务控制 + 日志）
        ├── DeepSeekActivity.java         # DeepSeek WebView 集成
        ├── DeepSeekChatBridge.java       # DeepSeek 聊天桥接
        ├── JavaScriptBridge.java         # JS <-> Java 工具调用桥
        ├── McpForegroundService.java     # MCP 前台服务
        ├── mcp/
        │   ├── McpServer.java            # MCP HTTP 服务端
        │   ├── JsonRpcRequest.java       # JSON-RPC 请求解析
        │   └── JsonRpcResponse.java      # JSON-RPC 响应构造
        ├── tools/
        │   ├── Tool.java                # 工具接口
        │   ├── ToolManager.java          # 工具管理 + 系统提示词生成
        │   ├── PythonTool.java           # Python 工具入口
        │   ├── PythonBridge.java         # Python JNI 桥接 Java 层
        │   ├── ShellTool.java            # Shell 工具
        │   ├── FileReadTool.java         # 文件读取
        │   ├── FileWriteTool.java        # 文件写入
        │   ├── HttpRequestTool.java      # HTTP 请求
        │   ├── LuaExecuteTool.java       # Lua 执行
        │   └── ...                      # 其他工具
        └── gm/
            ├── LuaEngine.java            # Lua 引擎
            ├── MemoryEngine.java         # 内存读写引擎
            ├── MemoryFreezer.java        # 内存冻结
            ├── ProcessManager.java       # 进程管理
            └── RootManager.java          # Root 权限管理
```

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
2. AIDE Pro 打开项目 → 构建 → 安装

**Gradle（需 Android Studio）：**
```bash
./gradlew assembleDebug
# APK 输出到 app/build/outputs/apk/debug/
```

### 2. 启动 MCP 服务

1. 打开应用 → 点击「启动MCP服务」
2. 记录显示的地址（如 `http://192.168.1.100:8080`）
3. 同一局域网浏览器访问该地址打开测试客户端

### 3. 使用 DeepSeek 集成

1. 点击「打开 DeepSeek 助手」
2. 登录 DeepSeek 账号
3. 对话中 AI 会自动调用本地工具

---

## MCP 协议接口

### 支持的方法

| 方法 | 说明 |
|------|------|
| `initialize` | 初始化连接（返回工具列表和协议信息） |
| `tools/list` | 获取所有工具列表 |
| `tools/call` | 调用指定工具 |
| `notifications/initialized` | 初始化完成通知 |

### JavaScript 调用示例

```javascript
// 调用 Python 工具
fetch('http://手机IP:8080', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    jsonrpc: '2.0',
    id: 1001,
    method: 'tools/call',
    params: {
      name: 'python',
      arguments: { script: "print('Hello from Python 3.14!')" }
    }
  })
})
.then(res => res.json())
.then(data => console.log(data));
```

### 错误码

| 错误码 | 说明 |
|--------|------|
| -32700 | 解析错误 |
| -32600 | 请求无效 |
| -32601 | 方法不存在 |
| -32602 | 参数无效 |
| -32603 | 内部错误 |

---

## 调试

### 查看 Python 初始化日志

```bash
adb logcat -s PythonBridge PythonBridge-C
```

关键日志标签：
- `PythonBridge` — Java 层日志（标准库提取、初始化状态）
- `PythonBridge-C` — Native 层日志（目录检查、Py_Initialize、GIL 管理）

### 常见问题排查

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| Python 工具未初始化 | JNI 加载失败 | 查看 logcat `PythonBridge-C` |
| `Failed to import encodings` | 标准库路径错误 | 检查 `PYTHONHOME/lib/python3.14/encodings/` |
| `Py_Initialize 崩溃` | fd 0/1/2 关闭 | `ensure_std_fds()` 自动修复 |
| 第二次调用挂起 | GIL 死锁 | `PyEval_SaveThread` 正确释放 |
| `os.py 不存在` | assets 提取错误 | 删除 `files/python/` 重新提取 |

---

## 扩展开发

### 添加新工具

1. 实现 `Tool` 接口：

```java
public class MyTool implements Tool {
    @Override
    public String getName() { return "my_tool"; }

    @Override
    public String getDescription() { return "我的工具"; }

    @Override
    public JSONObject getInputSchema() {
        // 返回 JSON Schema
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        // 实现逻辑，返回文本结果
    }
}
```

2. 在 `ToolManager.init()` 中注册：

```java
registerTool(new MyTool());
```

---

## 版本信息

- **Python**: 3.14.6 (官方 Android aarch64 构建)
- **协议**: MCP (JSON-RPC 2.0 over HTTP)
- **最低 Android**: API 24 (Android 7.0)
- **目标 SDK**: API 32 (Android 12)
- **ABI**: arm64-v8a

### 更新日志

**v2.0.0 — Python 3.14 内嵌重构**
- 使用 Python 3.14.6 官方 Android 包重新适配
- JNI 嵌入模式，无需 Termux 或外部 Python
- 修复 GIL 死锁（`PyEval_SaveThread`）
- 修复 fd 0/1/2 关闭导致的崩溃
- 信号保护机制防止 `Py_Initialize` 崩溃杀进程
- 完整标准库含 `lib-dynload` 扩展模块

**v1.2.0**
- 修复流式传输工具调用 JSON 时心跳中断问题

**v1.1.0**
- 修复 DeepSeek 源码提取超时（改用 `evaluateJavascript`）

**v1.0.0**
- 初始版本：MCP 服务端 + DeepSeek 集成 + 4 个内置工具
