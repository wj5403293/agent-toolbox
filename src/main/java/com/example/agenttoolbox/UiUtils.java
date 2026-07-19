package com.example.agenttoolbox;

import android.content.Context;
import android.content.res.Resources;

/**
 * UI 工具类：状态栏相关辅助
 */
public final class UiUtils {
    private UiUtils() {}

    /** 获取系统状态栏高度（px），取不到时按约 24dp 兜底 */
    public static int getStatusBarHeight(Context ctx) {
        int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        return (int) (24 * Resources.getSystem().getDisplayMetrics().density);
    }
}
