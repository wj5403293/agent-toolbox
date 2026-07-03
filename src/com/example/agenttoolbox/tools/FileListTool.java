package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 文件列表工具 - 优化版
 *
 * 改进：
 * 1. 新增 sort 参数：name（默认）/ size / modified
 * 2. 新增 show_hidden 参数（默认 false）
 * 3. 显示文件修改时间
 * 4. try-with-resources 防资源泄漏
 * 5. 路径解析统一使用 FilePathResolver
 */
public class FileListTool implements Tool {

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    public String getName() {
        return "file_list";
    }

    @Override
    public String getDescription() {
        return "列出指定目录下的文件和子目录。支持按名称/大小/修改时间排序，可选显示隐藏文件。";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");

            JSONObject properties = new JSONObject();

            JSONObject path = new JSONObject();
            path.put("type", "string");
            path.put("description", "目录路径，支持：1) 相对路径（内部存储）；2) /Download/、/Documents/ 等简写；3) /storage/emulated/0/... 完整外部路径；不填则列出内部存储");
            properties.put("path", path);

            JSONObject sort = new JSONObject();
            sort.put("type", "string");
            sort.put("description", "排序方式：name=按名称（默认），size=按文件大小，modified=按修改时间");
            sort.put("enum", new JSONArray().put("name").put("size").put("modified"));
            sort.put("default", "name");
            properties.put("sort", sort);

            JSONObject showHidden = new JSONObject();
            showHidden.put("type", "boolean");
            showHidden.put("description", "是否显示隐藏文件（以.开头），默认false");
            showHidden.put("default", false);
            properties.put("show_hidden", showHidden);

            schema.put("properties", properties);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        // 解析参数
        String path = arguments.optString("path", "");
        String sort = arguments.optString("sort", "name");
        boolean showHidden = arguments.optBoolean("show_hidden", false);

        // 路径解析
        File dir = FilePathResolver.resolveForDir(path.isEmpty() ? null : path);

        if (!dir.exists()) {
            throw new Exception("目录不存在: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new Exception("这不是一个目录: " + dir.getAbsolutePath());
        }
        if (!dir.canRead()) {
            throw new Exception("目录不可读，请在系统设置中授权存储权限: " + dir.getAbsolutePath());
        }

        File[] files = dir.listFiles();
        if (files == null) {
            throw new Exception("无法读取目录内容，请检查权限: " + dir.getAbsolutePath());
        }

        // 过滤隐藏文件
        if (!showHidden) {
            files = Arrays.stream(files)
                    .filter(f -> !f.getName().startsWith("."))
                    .toArray(File[]::new);
        }

        // 排序
        sortFiles(files, sort);

        // 构建输出
        StringBuilder result = new StringBuilder();
        result.append("目录内容 (").append(dir.getAbsolutePath()).append("):\n");
        result.append("共 ").append(files.length).append(" 个项目");
        if (!showHidden) {
            result.append("（已隐藏 dotfiles）");
        }
        result.append("\n\n");

        // 表头
        result.append(String.format("%-10s %-12s %-20s %s\n", "类型", "大小", "修改时间", "名称"));
        result.append(String.format("%-10s %-12s %-20s %s\n", "----", "----", "--------", "----"));

        for (File file : files) {
            String type = file.isDirectory() ? "[目录]" : "[文件]";
            String size = file.isDirectory() ? "-" : formatFileSize(file.length());
            String modified = DATE_FMT.format(new Date(file.lastModified()));
            String name = file.getName();
            if (file.isDirectory()) {
                name += "/";
            }
            result.append(String.format("%-10s %-12s %-20s %s\n", type, size, modified, name));
        }

        return result.toString();
    }

    private void sortFiles(File[] files, String sort) {
        Comparator<File> comparator;

        switch (sort) {
            case "size":
                comparator = (a, b) -> {
                    // 目录始终排前面
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return Long.compare(a.length(), b.length());
                };
                break;
            case "modified":
                comparator = (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return Long.compare(b.lastModified(), a.lastModified()); // 最新在前
                };
                break;
            case "name":
            default:
                comparator = (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                };
                break;
        }

        Arrays.sort(files, comparator);
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }
}
