package com.example.agenttoolbox.mcp;

import org.json.JSONObject;

/**
 * Python 工具独立工作流 — 硬编码强制步骤
 * 
 * 流转规则：
 *   IDLE → NEED_GEN_SCRIPT → RUN_SCRIPT → EXEC_SUCCESS / EXEC_ERROR
 * 
 * 强制约束：
 *   1. 超时 60 秒固定写死
 *   2. 拦截危险调用（os.system、subprocess 等）
 *   3. 工具调用 JSON 模板生成，LLM 只输出纯 Python 代码
 */
public class PythonWorkflow {

    public enum PyState {
        IDLE,
        NEED_GEN_SCRIPT,  // 需要 LLM 生成代码
        RUN_SCRIPT,       // 待执行 python
        EXEC_SUCCESS,
        EXEC_ERROR
    }

    private PyState state = PyState.IDLE;
    private String userRequest;     // 用户原始需求
    private String script;          // LLM 生成的 Python 代码
    private String execResult;      // 执行结果
    private String errorMessage;

    private static final long TIMEOUT_MS = 60000; // 60秒

    // 危险调用黑名单
    private static final String[] DANGEROUS_CALLS = {
        "os.system", "subprocess", "import os", "exec(", "eval(",
        "__import__", "open(", "compile(", "pty.spawn", "commands.",
        "shutil.rmtree", "os.remove", "os.unlink", "os.rmdir",
        "os.chmod", "os.chown", "socket.", "urllib.", "requests.",
        "multiprocessing", "threading.Thread", "ctypes."
    };

    /** 重置状态机 */
    public void reset() {
        state = PyState.IDLE;
        userRequest = null;
        script = null;
        execResult = null;
        errorMessage = null;
    }

    /** 用户发起 Python 任务，进入 NEED_GEN_SCRIPT */
    public void start(String request) {
        reset();
        userRequest = request;
        state = PyState.NEED_GEN_SCRIPT;
    }

    /** LLM 生成代码后，安全校验 */
    public boolean setScript(String code) {
        if (state != PyState.NEED_GEN_SCRIPT) return false;
        if (code == null || code.trim().isEmpty()) {
            errorMessage = "代码为空";
            state = PyState.EXEC_ERROR;
            return false;
        }
        // 安全校验
        String lowerCode = code.toLowerCase();
        for (String danger : DANGEROUS_CALLS) {
            if (lowerCode.contains(danger.toLowerCase())) {
                errorMessage = "代码包含危险调用: " + danger;
                state = PyState.EXEC_ERROR;
                return false;
            }
        }
        script = code;
        state = PyState.RUN_SCRIPT;
        return true;
    }

    /** 构建 python 工具调用模板 */
    public JSONObject buildRunCall(long conversationId) {
        if (state != PyState.RUN_SCRIPT || script == null) return null;
        try {
            JSONObject rpc = new JSONObject();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", "tools/call");
            JSONObject params = new JSONObject();
            params.put("name", "python");
            JSONObject args = new JSONObject();
            args.put("script", script);
            // 多行代码自动用 \n，JSON 模板统一处理转义
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
        if (state == PyState.RUN_SCRIPT) {
            execResult = result;
            state = result != null && !result.startsWith("错误") ? PyState.EXEC_SUCCESS : PyState.EXEC_ERROR;
        }
    }

    // Getters
    public PyState getState() { return state; }
    public String getUserRequest() { return userRequest; }
    public String getScript() { return script; }
    public String getExecResult() { return execResult; }
    public String getErrorMessage() { return errorMessage; }
    public long getTimeoutMs() { return TIMEOUT_MS; }
    public boolean isDone() { return state == PyState.EXEC_SUCCESS || state == PyState.EXEC_ERROR; }
    public boolean hasError() { return state == PyState.EXEC_ERROR; }
}