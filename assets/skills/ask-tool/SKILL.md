---
name: ask-tool
description: 向用户提问的规范技能。当需要向用户确认、选择、补充信息时，必须用 method=tools/call 调用 ask 工具，绝不能用文本回复代替。涵盖 ask 工具的参数格式、单问题/多问题模式、与计划的交互、常见幻觉错误。
when_to_use: 当需要向用户提问、确认、选择选项、补充信息时；当发现自己想写"请选择""请确认""请回答"等文字时；当不确定是否该用 ask 时
---

# ask 工具使用规范

## 核心规则（违反将导致用户无法交互）

**向用户提问必须用 `method=tools/call` 调用 ask 工具，绝不能用 `result.type=reply` 的文本回复代替。**

- ❌ 错误：用文本回复"请选择一个选项：A B C" → 用户只看到文字，无法选择，无法把回答传回
- ✅ 正确：调用 ask 工具 → 前端弹出问题卡片，用户点选/输入 → 答案作为新消息传回

## 调用格式

### 单问题模式

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ask",
    "arguments": {
      "question": "是否继续执行？",
      "options": ["继续", "取消", "让我想想"]
    }
  },
  "id": 1001
}
```

- `question`（字符串，必填）：问题内容
- `options`（字符串数组，可选）：选项列表。提供时前端显示可点击选项；不提供时前端显示文本输入框

### 多问题模式

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ask",
    "arguments": {
      "questions": [
        {
          "question": "使用哪个文件？",
          "options": ["config.txt", "settings.json", "其他"]
        },
        {
          "question": "修改哪个字段？",
          "options": ["timeout", "retries", "其他"]
        }
      ]
    }
  },
  "id": 1001
}
```

- `questions`（数组，与 question 二选一）：多个问题，每项含 `question` + 可选 `options`
- 前端逐个显示问题，用户答完一个切下一个

## ask 工具的执行流程

1. 你调用 ask 工具（`method=tools/call`）
2. 服务端执行 ask，返回 `__ASK_MULTI__` 格式的工具结果
3. 前端解析结果，显示问题卡片（选项可点击 / 文本框可输入）
4. 你收到工具结果后，回复一句文本（如"请回答上述问题"）→ **当前 SSE 流结束**
5. 用户在前端选择/输入答案 → 前端把答案作为新用户消息发送 → **开启新的对话轮**
6. 你在新轮次中通过 DeepSeek 网页历史看到自己问的问题和用户答案，继续执行

## 与计划的交互（关键）

**调用 ask 后不要立即 plan_update 推进任务。**

ask 调用后当前 SSE 流会结束，用户还没回答。如果此时 `plan_update complete_task`，会把任务标记为完成，但用户回答后任务实际还没做完。

正确流程：
1. 计划任务中需要用户确认 → 调用 ask 工具
2. 收到 ask 工具结果 → 回复文本"请回答上述问题"（不带 plan_update）
3. 用户回答 → 新轮次开始
4. 根据用户回答完成任务 → 此时才 `plan_update complete_task` 推进

## 常见幻觉错误

### 错误 1：用文本代替工具调用

```
用户: 我测试一下这个功能
❌ LLM 回复: {"result":{"type":"reply","content":"好的，我来演示一下 ask 工具的使用。这是一个测试问题，请选择一个选项："}}
```
问题：用户只看到文字，没有任何可交互的选项卡片。

✅ 正确：直接调用 ask 工具，question 写"这是一个测试问题"，options 写选项列表。

### 错误 2：没有计划时乱用 plan_update

```
❌ LLM 回复: {"result":{"type":"reply","content":"...","plan_update":{"action":"complete_task","task_id":"T001"}}}
```
问题：从未输出过 `{"tasks":[...]}` 建立计划，哪来的 T001？这是幻觉。

✅ 正确：没有计划时不要用 plan_update。plan_update 仅在 `{"tasks":[...]}` 计划已加载时才能用。

### 错误 3：ask 后立即 plan_update

```
❌ LLM 回复: {"result":{"type":"reply","content":"请选择","plan_update":{"action":"complete_task","task_id":"T002"}}}
```
问题：ask 还没真正调用（没用 method=tools/call），且 plan_update 在用户回答前就推进了任务。

✅ 正确：用 `method=tools/call` 调 ask，不带 plan_update，等用户回答后再推进。

## 何时该用 ask

| 场景 | 是否用 ask |
|---|---|
| 需要用户在多个选项中选择 | ✅ 用 ask + options |
| 需要用户确认是否继续（是/否） | ✅ 用 ask + options=["是","否"] |
| 需要用户输入文本（文件名、路径、参数值） | ✅ 用 ask，不提供 options |
| 需要用户补充缺失信息 | ✅ 用 ask |
| 只是告知用户结果 | ❌ 直接文本回复 |
| 用户意图明确，无需确认 | ❌ 直接执行 |

## 自检清单

调用 ask 前检查：
- [ ] 用的是 `method=tools/call` + `params.name=ask`，不是 `result.type=reply` 文本
- [ ] 参数是 `question`（单问题）或 `questions`（多问题），二选一
- [ ] 没有在没有计划时附带 `plan_update`
- [ ] ask 后的文本回复不带 `plan_update`（等用户回答后再推进）
