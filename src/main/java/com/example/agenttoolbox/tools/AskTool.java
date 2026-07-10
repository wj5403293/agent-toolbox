package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Ask 工具 —— 向用户提问并等待回答。
 *
 * 支持单个问题（question）或多个问题（questions 数组）。
 * 每个问题可带 options 选项列表。
 * AI 需要用户确认、选择或补充信息时调用此工具。
 */
public class AskTool implements Tool {

    @Override
    public String getName() {
        return "ask";
    }

    @Override
    public String getDescription() {
        return "向用户提问。参数 question 为单个问题字符串，questions 为多个问题数组（每项含 question + 可选 options）。AI 需要用户确认、选择或补充信息时调用此工具";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            JSONObject properties = new JSONObject();

            JSONObject question = new JSONObject();
            question.put("type", "string");
            question.put("description", "单个问题内容（与 questions 二选一）");
            properties.put("question", question);

            JSONObject questions = new JSONObject();
            questions.put("type", "array");
            questions.put("description", "多个问题数组，每项格式：{question:\"问题\", options:[\"选项1\",\"选项2\"]}（与 question 二选一）");
            JSONObject qItem = new JSONObject();
            qItem.put("type", "object");
            JSONObject qItemProps = new JSONObject();
            JSONObject qText = new JSONObject();
            qText.put("type", "string");
            qText.put("description", "问题内容");
            qItemProps.put("question", qText);
            JSONObject qOpts = new JSONObject();
            qOpts.put("type", "array");
            qOpts.put("description", "可选的回答选项");
            JSONArray optItem = new JSONArray();
            JSONObject oi = new JSONObject();
            oi.put("type", "string");
            optItem.put(oi);
            qOpts.put("items", optItem);
            qItemProps.put("options", qOpts);
            qItem.put("properties", qItemProps);
            questions.put("items", qItem);
            properties.put("questions", questions);

            JSONObject options = new JSONObject();
            options.put("type", "array");
            options.put("description", "可选的选项列表（仅单问题时有效）");
            JSONObject item = new JSONObject();
            item.put("type", "string");
            options.put("items", item);
            properties.put("options", options);

            schema.put("properties", properties);
            JSONArray required = new JSONArray();
            // question 和 questions 至少一个
            schema.put("required", required);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        if (arguments == null) {
            throw new Exception("缺少参数");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("__ASK_MULTI__\n");

        // 多问题模式
        if (arguments.has("questions")) {
            JSONArray qs = arguments.optJSONArray("questions");
            if (qs == null || qs.length() == 0) {
                throw new Exception("questions 数组不能为空");
            }
            for (int i = 0; i < qs.length(); i++) {
                JSONObject q = qs.optJSONObject(i);
                if (q == null) continue;
                String qText = q.optString("question", "");
                if (qText.isEmpty()) continue;
                sb.append("Q").append(i + 1).append(": ").append(qText).append("\n");
                JSONArray opts = q.optJSONArray("options");
                if (opts != null && opts.length() > 0) {
                    for (int j = 0; j < opts.length(); j++) {
                        sb.append("  ").append(i + 1).append(".").append(j + 1).append(" ").append(opts.optString(j, "")).append("\n");
                    }
                    sb.append("  ").append(i + 1).append(".0 其他\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        // 单问题模式
        if (!arguments.has("question")) {
            throw new Exception("缺少参数 question 或 questions");
        }
        String question = arguments.getString("question");
        sb.append("Q: ").append(question).append("\n");
        JSONArray opts = arguments.optJSONArray("options");
        if (opts != null && opts.length() > 0) {
            for (int i = 0; i < opts.length(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(opts.optString(i, "")).append("\n");
            }
            sb.append("  0. 其他\n");
        }
        return sb.toString();
    }
}
