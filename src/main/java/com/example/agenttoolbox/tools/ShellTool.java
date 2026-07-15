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
                String moduleArgs = args.substring(3).trim();
                code = "import sys, runpy; sys.argv = ['" + moduleArgs.replace("\"", "\\\"") + "'].split(); runpy.run_module('" + moduleArgs.split(" ")[0] + "', run_name='__main__')";
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
}
