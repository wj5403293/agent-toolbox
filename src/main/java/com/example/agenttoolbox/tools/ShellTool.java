package com.example.agenttoolbox.tools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shell 命令执行工具 - 优化版
 *
 * 改进：
 * 1. 移除不可靠的黑名单（靠 Android 权限系统）
 * 2. 使用 ProcessRunner 统一进程管理（安全超时、防泄漏、输出限制）
 * 3. 移除调试信息泄露
 * 4. 输出格式精简
 */
public class ShellTool implements Tool {

    private Context context;

    public ShellTool() {}

    public ShellTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public String getDescription() {
        return "在 Android 系统上执行 shell 命令（sh -c），支持管道、重定向等。" +
                "常用命令: ls, cat, echo, ps, grep, find, stat, mkdir, mv, cp, touch, kill 等";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject command = new JSONObject();
            command.put("type", "string");
            command.put("description", "要执行的 shell 命令");
            properties.put("command", command);

            JSONObject timeout = new JSONObject();
            timeout.put("type", "integer");
            timeout.put("description", "超时时间（秒），默认 30");
            timeout.put("default", 30);
            properties.put("timeout", timeout);

            schema.put("properties", properties);

            JSONArray requiredArray = new JSONArray();
            requiredArray.put("command");
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String command = arguments.getString("command");
        int timeout = arguments.has("timeout") ? arguments.getInt("timeout") : 30;

        if (command == null || command.trim().isEmpty()) {
            throw new Exception("命令不能为空");
        }

        // 截获 python/pip 命令，桥接到内嵌 Python 环境
        String trimmed = command.trim();
        if (trimmed.startsWith("python3 ") || trimmed.startsWith("python ") || trimmed.equals("python3") || trimmed.equals("python")) {
            return executePython(trimmed, timeout);
        }
        if (trimmed.startsWith("pip ") || trimmed.equals("pip")) {
            return executePython("python -m " + trimmed, timeout);
        }
        // 拦截 which/command -v 查询 python/pip：嵌入式 Python 不在系统 PATH，
        // which 必然找不到，直接返回内嵌环境状态避免误导
        if (isPythonPathQuery(trimmed)) {
            return reportEmbeddedPython(command);
        }

        ProcessRunner.Result result = ProcessRunner.execShell(command, timeout);

        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(command).append("\n");
        sb.append("退出码: ").append(result.exitCode).append("\n");

        if (result.timedOut) {
            sb.append("⚠️ 命令超时（").append(timeout).append("秒），已被强制终止\n");
        }
        if (result.truncated) {
            sb.append("⚠️ 输出过长，已截断\n");
        }

        if (!result.stdout.isEmpty()) {
            sb.append("\n").append(result.stdout);
        }
        if (!result.stderr.isEmpty()) {
            sb.append("\n[stderr]\n").append(result.stderr);
        }

        // 失败时附加简要诊断提示
        if (result.exitCode != 0 && !result.timedOut) {
            sb.append("\n提示: 命令失败。注意：Python 请直接用 python 工具执行，不要用 shell 调用");
        }

        return sb.toString();
    }

    /**
     * 桥接到内嵌 Python 环境处理 python 命令
     */
    private String executePython(String cmd, int timeout) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(cmd).append("\n");
        
        String args = cmd.replaceFirst("^python3?\\s*", "").trim();
        
        try {
            String code;
            if (args.startsWith("-c ")) {
                code = args.substring(3).trim();
                if ((code.startsWith("\"") && code.endsWith("\"")) || 
                    (code.startsWith("'") && code.endsWith("'"))) {
                    code = code.substring(1, code.length() - 1);
                }
            } else if (args.startsWith("-m ")) {
                // python -m module args — 执行模块
                // moduleArgs 形如 "pip install requests"，需拆成模块名 + 参数
                String moduleArgs = args.substring(3).trim();
                String[] parts = moduleArgs.split("\\s+", 2);
                String moduleName = parts[0];
                // sys.argv[0] 是模块名，后续是参数。用 Python list 字面量构造，避免转义问题
                // 注意：原代码 ['pip install requests'].split() 错误地对 list 调 split，
                // 改为对字符串 split：sys.argv = 'pip install requests'.split()
                code = "import sys, runpy; sys.argv = '" + moduleArgs.replace("'", "\\'") + "'.split(); runpy.run_module('" + moduleName.replace("'", "\\'") + "', run_name='__main__')";
            } else if (!args.isEmpty()) {
                String path = args;
                java.io.File file = new java.io.File(path);
                if (!file.isAbsolute()) {
                    file = new java.io.File("/sdcard", path);
                }
                if (!file.exists()) {
                    return sb.toString() + "错误: 文件不存在: " + file.getAbsolutePath();
                }
                code = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
            } else {
                return sb.toString() + 
                    "内嵌 Python 环境已就绪。请使用 python -c \"代码\" 执行单行代码，\n" +
                    "或通过 python 工具（tools/call）执行多行代码。\n";
            }
            
            String result = execPythonCode(code);
            sb.append(result);
        } catch (Exception e) {
            sb.append("错误: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 执行 Python 代码前先确保 PythonBridge 已初始化。
     * shell 截获的 python/pip 命令必须先 init，否则 exec 返回
     * "Python 未初始化"（jniInitOk 保持 false，jniInitRetCode 为默认 0）。
     */
    private String execPythonCode(String code) throws Exception {
        if (context == null) {
            return "[错误] ShellTool 未注入 Context，无法初始化 Python，请重启应用";
        }
        PythonBridge.init(context);
        return PythonBridge.exec(code);
    }

    /**
     * 判断是否为查询 python/pip 路径的命令（which / command -v / type）
     */
    private boolean isPythonPathQuery(String cmd) {
        // 匹配 "which python", "which python3", "which pip",
        // "command -v python", "type python" 等
        return (cmd.startsWith("which ") || cmd.startsWith("command -v ") || cmd.startsWith("type "))
            && (cmd.contains("python") || cmd.contains("pip"));
    }

    /**
     * 对 which python 等查询返回内嵌 Python 环境说明，
     * 避免系统 PATH 找不到 python 造成"未安装"的误解。
     */
    private String reportEmbeddedPython(String originalCmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(originalCmd).append("\n");
        sb.append("退出码: 0\n\n");
        sb.append("说明: 本应用使用 JNI 内嵌 Python（Python 3.14.6），")
          .append("没有独立的 python 可执行文件，不在系统 PATH 中，")
          .append("所以 which/command -v 找不到。\n\n");
        sb.append("状态: ").append(PythonBridge.getStatus()).append("\n");
        sb.append("用法: \n")
          .append("  - 直接用 python 工具（tools/call name=python）执行代码\n")
          .append("  - shell 里用 `python -c \"代码\"` 或 `pip install 包名` 会被桥接到内嵌环境\n");
        return sb.toString();
    }
}
