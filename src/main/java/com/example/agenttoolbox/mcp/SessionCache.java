package com.example.agenttoolbox.mcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话缓存 — 存储每个会话的系统规则、工具定义、中间状态
 * 
 * 协议优化：
 *   1. initialize 仅执行 1 次，客户端发完整 system+tools → 存入缓存
 *   2. 后续每轮只传 conversationId + query，服务端从缓存读取拼接
 * 
 * 多工具状态机：
 *   会话共用缓存，每个工具流水线单独一套状态
 *   current_task_type: file / python / shell / gm
 *   切换任务类型时自动清空旧流水线临时缓存
 */
public class SessionCache {

    public enum TaskType {
        FILE, PYTHON, SHELL, GM, NONE
    }

    private static SessionCache instance;

    private final Map<Long, SessionData> sessions = new ConcurrentHashMap<>();

    public static synchronized SessionCache getInstance() {
        if (instance == null) {
            instance = new SessionCache();
        }
        return instance;
    }

    public void put(long conversationId, SessionData data) {
        sessions.put(conversationId, data);
    }

    public SessionData get(long conversationId) {
        return sessions.get(conversationId);
    }

    public void remove(long conversationId) {
        sessions.remove(conversationId);
    }

    public boolean hasSession(long conversationId) {
        return sessions.containsKey(conversationId);
    }

    public void updateState(long conversationId, String key, Object value) {
        SessionData data = sessions.get(conversationId);
        if (data != null) {
            data.intermediateState.put(key, value);
        }
    }

    public Object getState(long conversationId, String key) {
        SessionData data = sessions.get(conversationId);
        if (data != null) {
            return data.intermediateState.get(key);
        }
        return null;
    }

    /**
     * 切换任务类型，清空旧流水线临时缓存
     */
    public void switchTaskType(long conversationId, TaskType newType) {
        SessionData data = sessions.get(conversationId);
        if (data == null) return;
        if (data.currentTaskType != newType) {
            data.currentTaskType = newType;
            // 重置所有流水线状态机
            data.fileWorkflow.reset();
            data.pythonWorkflow.reset();
            data.shellWorkflow.reset();
        }
    }

    /**
     * 会话数据 — 包含三套独立工作流状态机
     */
    public static class SessionData {
        /** 系统提示词 JSON 字符串 */
        public String systemPrompt;
        /** 工具列表 JSONArray */
        public JSONArray toolsList;
        /** 系统提示词解析后的 JSONObject */
        public JSONObject systemObj;
        /** 中间状态：是否已查Root、进程列表、已附加pid、内存搜索地址等 */
        public final Map<String, Object> intermediateState = new ConcurrentHashMap<>();
        /** 是否为该会话的第一条消息 */
        public boolean isFirstMessage = true;

        // 当前任务类型
        public TaskType currentTaskType = TaskType.NONE;

        // 三套独立工作流状态机（每个流水线单独一套状态，互不干扰）
        public final FileWorkflow fileWorkflow = new FileWorkflow();
        public final PythonWorkflow pythonWorkflow = new PythonWorkflow();
        public final ShellWorkflow shellWorkflow = new ShellWorkflow();

        public SessionData() {}

        public SessionData(String systemPrompt, JSONArray toolsList, JSONObject systemObj) {
            this.systemPrompt = systemPrompt;
            this.toolsList = toolsList;
            this.systemObj = systemObj;
        }
    }
}