package com.example.agenttoolbox.mcp;

import org.json.JSONObject;

/**
 * 文件读写独立工作流 — 硬编码强制步骤
 * 
 * 流转规则：
 *   IDLE → NEED_READ → READ_SUCCESS → NEED_EDIT → WRITE_READY → WRITE_DONE
 * 
 * 强制约束：
 *   1. 必须经过 file_read 才能 file_write（不可跳过读取）
 *   2. write 的 mode/line/path 由代码填充，LLM 只输出 content
 *   3. 路径白名单校验，非法路径直接 ERROR
 */
public class FileWorkflow {

    public enum FileState {
        IDLE,
        NEED_READ,      // 必须先读文件
        READ_SUCCESS,   // 文件内容已缓存到会话 ctx
        NEED_EDIT,      // 需要修改内容
        WRITE_READY,    // 待写入
        WRITE_DONE,
        FILE_ERROR
    }

    private FileState state = FileState.IDLE;
    private String targetPath;
    private String fileContent;    // 读取到的原始文件内容
    private String newContent;     // LLM 输出的修改后内容
    private String errorMessage;

    // 路径白名单
    private static final String[] PATH_WHITELIST = {
        "/sdcard/",
        "/storage/emulated/0/",
        "/data/local/tmp/"
    };

    /** 重置状态机 */
    public void reset() {
        state = FileState.IDLE;
        targetPath = null;
        fileContent = null;
        newContent = null;
        errorMessage = null;
    }

    /** 用户发起文件操作，进入 NEED_READ */
    public JSONObject startFileOp(String path) {
        reset();
        if (!isPathAllowed(path)) {
            state = FileState.FILE_ERROR;
            errorMessage = "路径不在白名单: " + path;
            return null;
        }
        targetPath = path;
        state = FileState.NEED_READ;
        return buildFileReadCall(path);
    }

    /** 读取完成，缓存内容 */
    public void onReadResult(String content) {
        if (state != FileState.NEED_READ) return;
        fileContent = content;
        state = FileState.READ_SUCCESS;
    }

    /** 只读模式：读取后直接结束 */
    public boolean isReadOnly() {
        return state == FileState.READ_SUCCESS;
    }

    /** 用户要求修改 → NEED_EDIT */
    public void requestEdit() {
        if (state == FileState.READ_SUCCESS) {
            state = FileState.NEED_EDIT;
        }
    }

    /** LLM 输出修改后内容 → WRITE_READY */
    public void onEditContent(String content) {
        if (state == FileState.NEED_EDIT) {
            newContent = content;
            state = FileState.WRITE_READY;
        }
    }

    /** 构建 file_write 调用（mode/replace 由代码决定） */
    public JSONObject buildWriteCall(int line, String mode) {
        if (state != FileState.WRITE_READY || targetPath == null) return null;
        try {
            JSONObject rpc = new JSONObject();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", "tools/call");
            JSONObject params = new JSONObject();
            params.put("name", "file_write");
            JSONObject args = new JSONObject();
            args.put("path", targetPath);
            args.put("content", newContent != null ? newContent : "");
            args.put("mode", mode);
            if (!"append".equals(mode)) {
                args.put("line", line > 0 ? line : 1);
            }
            params.put("arguments", args);
            rpc.put("params", params);
            return rpc;
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入完成 */
    public void onWriteDone() {
        if (state == FileState.WRITE_READY) {
            state = FileState.WRITE_DONE;
        }
    }

    /** 构建 file_read 调用模板 */
    public JSONObject buildFileReadCall(String path) {
        try {
            JSONObject rpc = new JSONObject();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", "tools/call");
            JSONObject params = new JSONObject();
            params.put("name", "file_read");
            JSONObject args = new JSONObject();
            args.put("path", path);
            params.put("arguments", args);
            rpc.put("params", params);
            return rpc;
        } catch (Exception e) {
            return null;
        }
    }

    /** 路径白名单校验 */
    private boolean isPathAllowed(String path) {
        if (path == null) return false;
        for (String prefix : PATH_WHITELIST) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    // Getters
    public FileState getState() { return state; }
    public String getTargetPath() { return targetPath; }
    public String getFileContent() { return fileContent; }
    public String getNewContent() { return newContent; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isDone() { return state == FileState.WRITE_DONE || state == FileState.FILE_ERROR; }
    public boolean hasError() { return state == FileState.FILE_ERROR; }
}