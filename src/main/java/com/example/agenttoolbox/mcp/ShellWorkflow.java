package com.example.agenttoolbox.mcp;

import org.json.JSONObject;

/**
 * Shell 工具独立工作流 — 硬编码强制步骤
 * 
 * 流转规则：
 *   IDLE → NEED_PARSE_CMD → RUN_CMD → CMD_SUCCESS / CMD_ERROR
 * 
 * 强制约束：
 *   1. 命令黑名单完全在代码判断，不靠模型自觉
 *   2. 超时 30 秒固定
 *   3. 工具调用 JSON 模板生成，LLM 只输出 command 字符串
 */
public class ShellWorkflow {

    public enum ShellState {
        IDLE,
        NEED_PARSE_CMD,  // LLM 提取 shell 命令
        RUN_CMD,
        CMD_SUCCESS,
        CMD_ERROR
    }

    private ShellState state = ShellState.IDLE;
    private String userRequest;     // 用户原始需求
    private String command;         // LLM 提取的安全命令
    private String execResult;      // 执行结果
    private String errorMessage;

    private static final long TIMEOUT_MS = 30000; // 30秒

    // 高危指令黑名单
    private static final String[] DANGEROUS_COMMANDS = {
        "rm -rf", "rm -r", "dd if=", "mkfs.", "mkswap",
        "su", "sudo", "chmod 777", "chmod -R",
        "mount", "umount", "fdisk", "parted",
        ":(){ :|:& };:",  // fork bomb
        "> /dev/sda", "> /dev/mmcblk",
        "mv /system", "mv /data", "mv /vendor",
        "cp /system", "cp /data",
        "setenforce", "reboot", "shutdown",
        "killall", "pkill -9",
        "iptables", "ip6tables",
        "chattr", "lsattr"
    };

    /** 重置状态机 */
    public void reset() {
        state = ShellState.IDLE;
        userRequest = null;
        command = null;
        execResult = null;
        errorMessage = null;
    }

    /** 用户发起 Shell 任务，进入 NEED_PARSE_CMD */
    public void start(String request) {
        reset();
        userRequest = request;
        state = ShellState.NEED_PARSE_CMD;
    }

    /** LLM 提取命令后，安全校验 */
    public boolean setCommand(String cmd) {
        if (state != ShellState.NEED_PARSE_CMD) return false;
        if (cmd == null || cmd.trim().isEmpty()) {
            errorMessage = "命令为空";
            state = ShellState.CMD_ERROR;
            return false;
        }
        String trimmed = cmd.trim();
        // 风险过滤
        String lowerCmd = trimmed.toLowerCase();
        for (String danger : DANGEROUS_COMMANDS) {
            if (lowerCmd.contains(danger.toLowerCase())) {
                errorMessage = "高危指令被拦截: " + danger;
                state = ShellState.CMD_ERROR;
                return false;
            }
        }
        // 禁止修改系统分区
        if (lowerCmd.contains("/system") || lowerCmd.contains("/vendor") 
            || lowerCmd.contains("/boot") || lowerCmd.contains("/recovery")) {
            errorMessage = "禁止操作系统分区";
            state = ShellState.CMD_ERROR;
            return false;
        }
        command = trimmed;
        state = ShellState.RUN_CMD;
        return true;
    }

    /** 构建 shell 工具调用模板 */
    public JSONObject buildRunCall(long conversationId) {
        if (state != ShellState.RUN_CMD || command == null) return null;
        try {
            JSONObject rpc = new JSONObject();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", "tools/call");
            JSONObject params = new JSONObject();
            params.put("name", "shell");
            JSONObject args = new JSONObject();
            args.put("command", command);
            params.put("arguments", args);
            rpc.put("params", params);
            rpc.put("id", conversationId);
            return rpc;
        } catch (Exception e) {
            return null;
        }
    }

    /** 执行完成 */
    public void onExecResult(String result) {
        if (state == ShellState.RUN_CMD) {
            execResult = result;
            state = result != null && !result.startsWith("错误") ? ShellState.CMD_SUCCESS : ShellState.CMD_ERROR;
        }
    }

    // Getters
    public ShellState getState() { return state; }
    public String getUserRequest() { return userRequest; }
    public String getCommand() { return command; }
    public String getExecResult() { return execResult; }
    public String getErrorMessage() { return errorMessage; }
    public long getTimeoutMs() { return TIMEOUT_MS; }
    public boolean isDone() { return state == ShellState.CMD_SUCCESS || state == ShellState.CMD_ERROR; }
    public boolean hasError() { return state == ShellState.CMD_ERROR; }
}