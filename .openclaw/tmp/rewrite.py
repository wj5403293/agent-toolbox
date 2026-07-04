#!/usr/bin/env python3
import sys

filepath = "app/src/main/java/com/example/agenttoolbox/DeepSeekChatBridge.java"
with open(filepath) as f:
    content = f.read()

# Find observerScript boundaries
obs_start = content.find('final String observerScript = "(function() {\\n"')
obs_end = content.find('// ========== Step 2:')

if obs_start == -1 or obs_end == -1:
    print(f"Not found: obs_start={obs_start}, obs_end={obs_end}")
    sys.exit(1)

print(f"observerScript: offset {obs_start} to {obs_end}")

# The new observerScript - clean implementation following the guide
# Uses M8.3125 for send button, M2 4.88 for stop button
# Clean pollOnce: wait button ready -> collect text -> parse JSON -> finish
new_observer = r'''        final String observerScript = "(function() {\n" +
            "  var __rid = " + JSONObject.quote(requestId) + ";\n" +
            "  var __prefix = 'ds_' + __rid + '_';\n" +
            "  window.__deepseekRid = __rid;\n" +
            "  if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "  var finished = false;\n" +
            "  var pollCount = 0;\n" +
            "  var lastTextLen = 0;\n" +
            "  var stableCount = 0;\n" +
            "\n" +
            "  // ===== Helper Functions =====\n" +
            "  function getAssistantMessages() {\n" +
            "    var list = document.querySelectorAll('.ds-markdown.ds-assistant-message-main-content');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    list = document.querySelectorAll('[class*=\"ds-assistant-message-main-content\"]');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    list = document.querySelectorAll('.ds-markdown--block');\n" +
            "    if (list && list.length > 0) return list;\n" +
            "    list = document.querySelectorAll('[class*=\"ds-markdown\"]');\n" +
            "    return list || [];\n" +
            "  }\n" +
            "\n" +
            "  function isSendButtonReady() {\n" +
            "    var paths = document.querySelectorAll('svg path');\n" +
            "    for (var i = 0; i < paths.length; i++) {\n" +
            "      var d = paths[i].getAttribute('d') || '';\n" +
            "      if (d.indexOf('M8.3125') === 0) return true;\n" +
            "      if (d.indexOf('M2 4.88') === 0) return false;\n" +
            "    }\n" +
            "    return true;\n" +
            "  }\n" +
            "\n" +
            "  function finish(reply) {\n" +
            "    if (finished) return;\n" +
            "    finished = true;\n" +
            "    if (window[__prefix + 'poll']) clearInterval(window[__prefix + 'poll']);\n" +
            "    Android.log('[DEBUG][' + __rid + '] pollOnce: finish, len=' + (reply ? reply.length : 0));\n" +
            "    Android.onDeepSeekReply(__rid, reply || '');\n" +
            "  }\n" +
            "\n" +
            "  // ===== Core Polling (per guide) =====\n" +
            "  function pollOnce() {\n" +
            "    if (finished) return;\n" +
            "    pollCount++;\n" +
            "\n" +
            "    // Timeout: 5 min\n" +
            "    if (pollCount > 600) {\n" +
            "      Android.log('[DEBUG][' + __rid + '] pollOnce: timeout');\n" +
            "      finish('');\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // Step 3: Wait for send button ready (M8.3125 = arrow = LLM stopped)\n" +
            "    if (!isSendButtonReady()) {\n" +
            "      stableCount = 0;\n" +
            "      lastTextLen = 0;\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // Step 4: Collect last AI message text\n" +
            "    var list = getAssistantMessages();\n" +
            "    if (list.length === 0) return;\n" +
            "    var lastEl = list[list.length - 1];\n" +
            "    var rawText = (lastEl.innerText || lastEl.textContent || '').trim();\n" +
            "    if (!rawText || rawText.length < 2) return;\n" +
            "\n" +
            "    // Stability check: text must stop changing for 3 polls (1.5s)\n" +
            "    if (rawText.length === lastTextLen) {\n" +
            "      stableCount++;\n" +
            "    } else {\n" +
            "      stableCount = 0;\n" +
            "      lastTextLen = rawText.length;\n" +
            "    }\n" +
            "    if (stableCount < 3) return;\n" +
            "\n" +
            "    // Step 5: Extract JSON from first {\n" +
            "    var firstBrace = rawText.indexOf('{');\n" +
            "    if (firstBrace === -1) {\n" +
            "      finish(rawText);\n" +
            "      return;\n" +
            "    }\n" +
            "    var jsonStr = rawText.substring(firstBrace);\n" +
            "\n" +
            "    // Step 6: JSON.parse\n" +
            "    var parsed = null;\n" +
            "    try { parsed = JSON.parse(jsonStr); } catch(e) {}\n" +
            "    if (!parsed) {\n" +
            "      // Fix common escape issues\n" +
            "      var fixed = jsonStr.replace(/\\\\'/g, \"'\");\n" +
            "      try { parsed = JSON.parse(fixed); } catch(e) {}\n" +
            "    }\n" +
            "    if (!parsed) {\n" +
            "      finish(rawText);\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    // Step 7: Determine type and finish\n" +
            "    var isToolCall = parsed.method && parsed.method === 'tools/call';\n" +
            "    Android.log('[DEBUG][' + __rid + '] pollOnce: JSON parsed, method=' + (parsed.method || 'none') + ', isToolCall=' + isToolCall);\n" +
            "    finish(jsonStr);\n" +
            "  }\n" +
            "\n" +
            "  window[__prefix + 'poll'] = setInterval(pollOnce, 500);\n" +
            "  Android.log('[DEBUG][' + __rid + '] observer started');\n" +
            "  return 'observer_started_' + __rid;\n" +
            "})()";'''

# Replace
new_content = content[:obs_start] + new_observer + content[obs_end:]

with open(filepath, 'w') as f:
    f.write(new_content)

print(f"Done. New file: {len(new_content)} chars (was {len(content)})")
