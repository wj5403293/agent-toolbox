package com.example.agenttoolbox;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * APP 更新检查器
 *
 * 启动时调用 UpdateChecker.check(activity) 异步检查 GitHub Releases 最新版本。
 * 若当前 versionName 低于最新 tag_name，弹出更新卡片（样式仿 HTML 设计稿）。
 * 点"立即更新"用浏览器打开 release 页面。
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String RELEASE_API =
        "https://api.github.com/repos/Aasdqwe1/agent-toolbox/releases/latest";

    /** 本次运行是否已弹过窗，避免重复弹出 */
    private static volatile boolean shownThisSession = false;

    public static void check(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    // 1. 当前版本号
                    PackageInfo pi = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0);
                    final String currentVersion = pi.versionName;

                    // 2. 请求 GitHub Releases API
                    URL url = new URL(RELEASE_API);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "agent-toolbox-app");
                    conn.setRequestProperty("Accept", "application/vnd.github+json");

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        AppLogger.w(TAG, "GitHub API 返回: " + code);
                        return;
                    }
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String tagName = json.optString("tag_name", "");
                    String releaseName = json.optString("name", "");
                    String body = json.optString("body", "");
                    String htmlUrl = json.optString("html_url", "");

                    String latestVersion = tagName.startsWith("v") || tagName.startsWith("V")
                        ? tagName.substring(1) : tagName;
                    if (TextUtils.isEmpty(latestVersion) || TextUtils.isEmpty(htmlUrl)) return;

                    // 3. 版本比较：当前 < 最新 才弹窗
                    if (compareVersion(currentVersion, latestVersion) < 0) {
                        final String fVer = latestVersion;
                        final String fBody = body;
                        final String fUrl = htmlUrl;
                        final String fName = TextUtils.isEmpty(releaseName)
                            ? ("V" + latestVersion) : releaseName;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showUpdateDialog(activity, fName, fVer, fBody, fUrl);
                            }
                        });
                    } else {
                        AppLogger.i(TAG, "已是最新版本: " + currentVersion);
                    }
                } catch (Exception e) {
                    AppLogger.w(TAG, "检查更新失败: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    /** 版本号比较：v1 < v2 返回 -1，相等 0，v1 > v2 返回 1 */
    private static int compareVersion(String v1, String v2) {
        if (v1 == null) v1 = "0";
        if (v2 == null) v2 = "0";
        if (v1.startsWith("v") || v1.startsWith("V")) v1 = v1.substring(1);
        if (v2.startsWith("v") || v2.startsWith("V")) v2 = v2.substring(1);
        String[] a = v1.split("\\.");
        String[] b = v2.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < a.length ? safeInt(a[i]) : 0;
            int n2 = i < b.length ? safeInt(b[i]) : 0;
            if (n1 < n2) return -1;
            if (n1 > n2) return 1;
        }
        return 0;
    }

    private static int safeInt(String s) {
        if (s == null) return 0;
        StringBuilder num = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') num.append(c);
            else break;
        }
        try { return num.length() > 0 ? Integer.parseInt(num.toString()) : 0; }
        catch (Exception e) { return 0; }
    }

    /** 解析 release body（Markdown）为更新条目列表 */
    private static List<String> parseItems(String body) {
        List<String> items = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return items;
        String[] lines = body.split("\n");
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            // 跳过 markdown 标题行
            if (l.startsWith("## ") || l.startsWith("# ") || l.startsWith("### ")) continue;
            // 去掉列表符号
            if (l.startsWith("- [x] ") || l.startsWith("- [ ] ")) {
                int idx = l.indexOf("] ");
                if (idx >= 0) l = l.substring(idx + 2).trim();
            } else if (l.startsWith("- ") || l.startsWith("* ")) {
                l = l.substring(2).trim();
            }
            // 去掉行内 markdown 标记
            l = l.replace("`", "").replace("**", "");
            if (!l.isEmpty()) items.add(l);
        }
        return items;
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void showUpdateDialog(final Activity activity, String releaseName,
                                         String latestVersion, String body, final String htmlUrl) {
        if (activity == null || activity.isFinishing()) return;
        if (shownThisSession) return;
        shownThisSession = true;

        int r = dp(activity, 16);

        // ===== 卡片根容器 =====
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(r);
        card.setBackground(cardBg);
        card.setElevation(dp(activity, 8));

        // ===== 头部（蓝色渐变） =====
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 22));
        GradientDrawable headerBg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF4080FF, 0xFF2667E8}
        );
        headerBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        header.setBackground(headerBg);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("发现新版本 V" + latestVersion);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);

        TextView tvSub = new TextView(activity);
        tvSub.setText("全新功能，体验升级");
        tvSub.setTextColor(0xE6FFFFFF);
        tvSub.setTextSize(13);
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setPadding(0, dp(activity, 6), 0, 0);

        header.addView(tvTitle);
        header.addView(tvSub);
        card.addView(header);

        // ===== 内容区（更新条目） =====
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 8));

        List<String> items = parseItems(body);
        if (items.isEmpty()) items.add("修复已知问题，优化用户体验");
        for (String item : items) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(activity, 4), 0, dp(activity, 4));

            TextView tag = new TextView(activity);
            tag.setText("✓");
            tag.setTextColor(0xFF4080FF);
            tag.setTextSize(15);
            tag.setTypeface(tag.getTypeface(), Typeface.BOLD);

            TextView text = new TextView(activity);
            text.setText(item);
            text.setTextColor(0xFF333333);
            text.setTextSize(15);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = dp(activity, 10);
            text.setLayoutParams(tp);

            row.addView(tag);
            row.addView(text);
            content.addView(row);
        }

        // 用 ScrollView 包裹，条目多时可滚动；少时自适应
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(content);
        int scrollH = items.size() > 6 ? dp(activity, 260)
            : LinearLayout.LayoutParams.WRAP_CONTENT;
        card.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, scrollH));

        // ===== 按钮区 =====
        LinearLayout btnGroup = new LinearLayout(activity);
        btnGroup.setOrientation(LinearLayout.HORIZONTAL);
        btnGroup.setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 20));

        int btnRadius = dp(activity, 10);
        int btnHeight = dp(activity, 44);

        // 稍后再说
        TextView btnLater = new TextView(activity);
        btnLater.setText("稍后再说");
        btnLater.setTextColor(0xFF666666);
        btnLater.setTextSize(15);
        btnLater.setGravity(Gravity.CENTER);
        GradientDrawable laterBg = new GradientDrawable();
        laterBg.setColor(0xFFF2F3F5);
        laterBg.setCornerRadius(btnRadius);
        btnLater.setBackground(laterBg);
        btnLater.setLayoutParams(new LinearLayout.LayoutParams(0, btnHeight, 1f));

        // 立即更新
        TextView btnUpdate = new TextView(activity);
        btnUpdate.setText("立即更新");
        btnUpdate.setTextColor(Color.WHITE);
        btnUpdate.setTextSize(15);
        btnUpdate.setTypeface(btnUpdate.getTypeface(), Typeface.BOLD);
        btnUpdate.setGravity(Gravity.CENTER);
        GradientDrawable updateBg = new GradientDrawable();
        updateBg.setColor(0xFF4080FF);
        updateBg.setCornerRadius(btnRadius);
        btnUpdate.setBackground(updateBg);
        LinearLayout.LayoutParams lpUpdate = new LinearLayout.LayoutParams(0, btnHeight, 1f);
        lpUpdate.leftMargin = dp(activity, 12);
        btnUpdate.setLayoutParams(lpUpdate);

        btnGroup.addView(btnLater);
        btnGroup.addView(btnUpdate);
        card.addView(btnGroup);

        // ===== Dialog =====
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = window.getAttributes();
            int maxWidth = dp(activity, 380);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            lp.width = Math.min(maxWidth, screenWidth - dp(activity, 32));
            window.setAttributes(lp);
        }

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dialog.dismiss(); }
        });
        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl));
                    activity.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(activity, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });

        dialog.show();
    }
}
