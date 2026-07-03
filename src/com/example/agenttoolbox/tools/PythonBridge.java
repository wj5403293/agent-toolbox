package com.example.agenttoolbox.tools;

import android.content.Context;

import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Python JNI 桥接层
 *
 * 架构：
 * - libpython3.14.so + 依赖库 → jniLibs/arm64-v8a/（APK 自动解压到 native lib 目录）
 * - Python 标准库 → assets/python/stdlib/（运行时解压到 filesDir/python/）
 * - libpython_bridge.so → 我们的 JNI 封装（NDK 编译，链接 libpython3.14）
 *
 * 使用流程：
 * 1. init(context) — 解压标准库 + 初始化 Python
 * 2. exec(code)    — 执行 Python 代码
 * 3. shutdown()    — 关闭 Python（通常不需要）
 */
public class PythonBridge {

    private static final String STDLIB_ASSET_DIR = "python/stdlib";
    private static final String PYTHON_DIR_NAME = "python";
    private static final String VERSION_FILE = ".python_version";
    private static final String EXPECTED_VERSION = "3.14.6";

    private static boolean jniLoaded = false;
    private static boolean initialized = false;
    private static File pythonHome;

    static {
        try {
            // 加载我们的桥接库（它会自动链接 libpython3.14.so）
            System.loadLibrary("python_bridge");
            jniLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            jniLoaded = false;
        }
    }

    /**
     * 初始化 Python 环境
     */
    public static synchronized boolean init(Context context) throws Exception {
        if (initialized) return true;
        if (!jniLoaded) throw new Exception("python_bridge JNI 库未加载，请确认 NDK 编译配置正确");

        pythonHome = new File(context.getFilesDir(), PYTHON_DIR_NAME);
        File versionFile = new File(context.getFilesDir(), VERSION_FILE);

        // 检查是否已解压标准库
        if (!isStdlibReady(pythonHome) || !readFile(versionFile).equals(EXPECTED_VERSION)) {
            extractStdlib(context);
            writeFile(versionFile, EXPECTED_VERSION);
        }

        // 设置 TMPDIR（Android API < 33 不自动设置）
        try {
            Os.setenv("TMPDIR", context.getCacheDir().getAbsolutePath(), false);
        } catch (Exception ignored) {
            // API < 21 没有 Os.setenv，Python 会用默认值
        }

        // 初始化 Python
        int ret = nativeInit(pythonHome.getAbsolutePath());
        if (ret != 0) {
            throw new Exception("Python 初始化失败 (nativeInit 返回 " + ret + ")");
        }

        initialized = true;
        return true;
    }

    /**
     * 执行 Python 代码
     */
    public static String exec(String code) throws Exception {
        if (!initialized) throw new Exception("Python 未初始化");
        return nativeExec(code);
    }

    public static boolean isAvailable() { return jniLoaded; }
    public static boolean isInitialized() { return initialized && (jniLoaded ? nativeIsInitialized() : false); }

    public static String getStatus() {
        if (!jniLoaded) return "JNI 库未加载（需要 NDK 编译）";
        if (!initialized) return "已加载，未初始化";
        return "已初始化，Python " + EXPECTED_VERSION;
    }

    public static void shutdown() {
        if (initialized) {
            nativeShutdown();
            initialized = false;
        }
    }

    // ===== JNI =====
    private static native int nativeInit(String home);
    private static native String nativeExec(String code);
    private static native void nativeShutdown();
    private static native boolean nativeIsInitialized();

    // ===== 内部 =====

    /**
     * 从 assets 递归解压目录（按文件逐个解压，不依赖 tar）
     */
    private static void extractStdlib(Context context) throws Exception {
        if (pythonHome.exists()) {
            deleteRecursive(pythonHome);
        }
        pythonHome.mkdirs();

        // 递归解压 assets/python/stdlib/ 到 filesDir/python/
        extractAssetDir(context, STDLIB_ASSET_DIR, pythonHome);
    }

    private static void extractAssetDir(Context context, String assetPath, File targetDir)
            throws Exception {
        String[] names;
        try {
            names = context.getAssets().list(assetPath);
        } catch (Exception e) {
            return; // 空目录或不存在
        }

        if (names == null || names.length == 0) {
            // 这是一个文件，不是目录
            extractAssetFile(context, assetPath, targetDir);
            return;
        }

        // 是目录，创建并递归
        File subDir = new File(targetDir, assetPath.substring(assetPath.lastIndexOf('/') + 1));
        if (!subDir.exists()) subDir.mkdirs();

        for (String name : names) {
            String childPath = assetPath + "/" + name;
            String[] childNames = context.getAssets().list(childPath);

            if (childNames != null && childNames.length > 0) {
                // 子目录
                File childDir = new File(subDir, name);
                if (!childDir.exists()) childDir.mkdirs();
                extractAssetDirRecursive(context, childPath, childDir);
            } else {
                // 文件
                extractAssetFile(context, childPath, subDir);
            }
        }
    }

    private static void extractAssetDirRecursive(Context context, String assetPath, File targetDir)
            throws Exception {
        String[] names = context.getAssets().list(assetPath);
        if (names == null) return;

        for (String name : names) {
            String childPath = assetPath + "/" + name;
            String[] childNames = context.getAssets().list(childPath);

            if (childNames != null && childNames.length > 0) {
                File childDir = new File(targetDir, name);
                if (!childDir.exists()) childDir.mkdirs();
                extractAssetDirRecursive(context, childPath, childDir);
            } else {
                extractAssetFile(context, childPath, targetDir);
            }
        }
    }

    private static void extractAssetFile(Context context, String assetPath, File targetDir)
            throws Exception {
        String fileName = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        File outFile = new File(targetDir, fileName);

        InputStream is = context.getAssets().open(assetPath);
        try {
            FileOutputStream fos = new FileOutputStream(outFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
                fos.flush();
            } finally {
                fos.close();
            }
        } finally {
            is.close();
        }
    }

    private static boolean isStdlibReady(File dir) {
        if (!dir.exists()) return false;
        // 检查关键文件是否存在
        return new File(dir, "os.py").exists()
                || new File(dir, "importlib").exists();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static String readFile(File f) {
        try {
            byte[] d = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            fis.read(d);
            fis.close();
            return new String(d, "UTF-8").trim();
        } catch (Exception e) { return ""; }
    }

    private static void writeFile(File f, String s) {
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(s.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }
}
