/*
 * python_bridge.c - Python 3.14 JNI 桥接层
 *
 * 使用 Python 3.14.6 官方 Android (aarch64) 构建。
 * 通过 Py_Initialize() 嵌入式初始化 Python 解释器。
 */
#include <jni.h>
#include <Python.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <signal.h>
#include <setjmp.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>

#define LOG_TAG "PythonBridge-C"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int python_initialized = 0;
static char last_error[2048] = "";
static JavaVM *cached_jvm = NULL;

/* 信号保护: 防止 Py_Initialize 崩溃杀进程 */
static sigjmp_buf init_jmp;
static volatile sig_atomic_t init_in_progress = 0;

static void crash_handler(int sig) {
    LOGE("crash_handler: 收到信号 %d", sig);
    if (init_in_progress) {
        siglongjmp(init_jmp, sig);
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

/* 确保 fd 0/1/2 存在（Android 应用可能关闭了它们） */
static void ensure_std_fds() {
    int fd = open("/dev/null", O_RDWR);
    if (fd < 0) {
        LOGE("ensure_std_fds: 打开 /dev/null 失败: %s", strerror(errno));
        return;
    }
    while (fcntl(STDIN_FILENO, F_GETFD) < 0 && errno == EBADF)
        dup2(fd, STDIN_FILENO);
    while (fcntl(STDOUT_FILENO, F_GETFD) < 0 && errno == EBADF)
        dup2(fd, STDOUT_FILENO);
    while (fcntl(STDERR_FILENO, F_GETFD) < 0 && errno == EBADF)
        dup2(fd, STDERR_FILENO);
    if (fd > STDERR_FILENO) close(fd);
    LOGI("ensure_std_fds: ok");
}

static void log_check_path(const char *path) {
    struct stat st;
    if (stat(path, &st) == 0) {
        if (S_ISDIR(st.st_mode)) {
            DIR *d = opendir(path);
            int count = 0;
            if (d) {
                struct dirent *ent;
                while ((ent = readdir(d)) != NULL) count++;
                closedir(d);
            }
            LOGI("  [目录] %s (%d 项)", path, count);
        } else {
            LOGI("  [文件] %s (%ldB)", path, (long)st.st_size);
        }
    } else {
        LOGE("  [缺失] %s (%s)", path, strerror(errno));
    }
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("JNI_OnLoad: libpython_bridge.so 已加载");
    cached_jvm = vm;

    /* 预加载 libpython3.14.so */
    void *handle = dlopen("libpython3.14.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle) {
        LOGI("JNI_OnLoad: libpython3.14.so 预加载成功");
    } else {
        LOGE("JNI_OnLoad: libpython3.14.so 预加载失败: %s", dlerror());
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeGetLastError(
    JNIEnv *env, jobject obj)
{
    return (*env)->NewStringUTF(env, last_error);
}

JNIEXPORT jint JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeInit(
    JNIEnv *env, jobject obj, jstring home)
{
    if (python_initialized) {
        LOGI("nativeInit: 已初始化");
        return 0;
    }

    const char *home_utf8 = (*env)->GetStringUTFChars(env, home, NULL);
    LOGI("nativeInit: PYTHONHOME=%s", home_utf8);

    /* 目录诊断 */
    LOGI("nativeInit: === 路径检查 ===");
    log_check_path(home_utf8);
    char buf[512];
    snprintf(buf, sizeof(buf), "%s/lib", home_utf8); log_check_path(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14", home_utf8); log_check_path(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14/os.py", home_utf8); log_check_path(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14/encodings", home_utf8); log_check_path(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14/encodings/__init__.py", home_utf8); log_check_path(buf);
    snprintf(buf, sizeof(buf), "%s/lib/python3.14/lib-dynload", home_utf8); log_check_path(buf);
    LOGI("nativeInit: === 检查结束 ===");

    /* 确保动态库已加载 */
    void *handle = dlopen("libpython3.14.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        snprintf(last_error, sizeof(last_error),
                 "dlopen libpython3.14.so 失败: %s", dlerror());
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        return -1;
    }
    LOGI("nativeInit: libpython3.14.so 已加载");

    /* Android 修复: 确保 stdio fd 存在 */
    ensure_std_fds();

    /* 设置环境变量 */
    setenv("PYTHONHOME", home_utf8, 1);
    setenv("PYTHONNOUSERSITE", "1", 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    LOGI("nativeInit: PYTHONHOME=%s", getenv("PYTHONHOME"));

    /* Py_PreInitialize (Python config, 读取环境变量) */
    LOGI("nativeInit: Py_PreInitialize...");
    PyPreConfig preconfig;
    PyPreConfig_InitPythonConfig(&preconfig);
    preconfig.utf8_mode = 1;
    preconfig.allocator = PYMEM_ALLOCATOR_MALLOC;

    PyStatus status = Py_PreInitialize(&preconfig);
    if (PyStatus_Exception(status)) {
        snprintf(last_error, sizeof(last_error),
                 "Py_PreInitialize 失败: %s",
                 status.err_msg ? status.err_msg : "unknown");
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        return -2;
    }
    LOGI("nativeInit: Py_PreInitialize 成功");

    /* 安装信号处理器保护 Py_Initialize */
    struct sigaction sa;
    sa.sa_handler = crash_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);

    init_in_progress = 1;
    int crash_sig = sigsetjmp(init_jmp, 1);

    if (crash_sig != 0) {
        init_in_progress = 0;
        snprintf(last_error, sizeof(last_error),
                 "Py_Initialize 崩溃 (信号=%d)", crash_sig);
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        signal(SIGSEGV, SIG_DFL);
        signal(SIGABRT, SIG_DFL);
        signal(SIGBUS, SIG_DFL);
        return -3;
    }

    /* Py_Initialize */
    LOGI("nativeInit: Py_Initialize...");
    Py_Initialize();
    LOGI("nativeInit: Py_Initialize 返回");

    init_in_progress = 0;
    signal(SIGSEGV, SIG_DFL);
    signal(SIGABRT, SIG_DFL);
    signal(SIGBUS, SIG_DFL);

    if (!Py_IsInitialized()) {
        snprintf(last_error, sizeof(last_error),
                 "Py_Initialize 失败: 未初始化");
        LOGE("nativeInit: %s", last_error);
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        return -3;
    }
    LOGI("nativeInit: Py_Initialize 成功!");

    (*env)->ReleaseStringUTFChars(env, home, home_utf8);

    /* 验证核心模块 */
    PyGILState_STATE gstate = PyGILState_Ensure();

    PyObject *mod;
    if ((mod = PyImport_ImportModule("encodings"))) {
        Py_DECREF(mod);
        LOGI("nativeInit: encodings 验证通过");
    } else {
        LOGE("nativeInit: encodings 导入失败");
        PyErr_Clear();
    }
    if ((mod = PyImport_ImportModule("os"))) {
        Py_DECREF(mod);
        LOGI("nativeInit: os 验证通过");
    } else {
        LOGE("nativeInit: os 导入失败");
        PyErr_Clear();
    }

    PyGILState_Release(gstate);

    python_initialized = 1;
    LOGI("nativeInit: Python 初始化完成!");
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeIsInitialized(
    JNIEnv *env, jobject obj)
{
    return python_initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeExec(
    JNIEnv *env, jobject obj, jstring code)
{
    if (!python_initialized) {
        return (*env)->NewStringUTF(env,
            "[错误] Python 未初始化。请重启应用或查看 logcat (PythonBridge-C)");
    }

    PyGILState_STATE gstate = PyGILState_Ensure();

    const char *code_utf8 = (*env)->GetStringUTFChars(env, code, NULL);
    LOGI("nativeExec: 执行代码 (长度=%d)", (int)strlen(code_utf8));

    PyObject *main_module = PyImport_AddModule("__main__");
    PyObject *globals = PyModule_GetDict(main_module);

    PyObject *code_obj = PyUnicode_FromString(code_utf8);
    PyDict_SetItemString(globals, "_user_code", code_obj);
    Py_DECREF(code_obj);

    const char *wrapper =
        "import sys, io, traceback\n"
        "_old_stdout = sys.stdout\n"
        "_old_stderr = sys.stderr\n"
        "sys.stdout = io.StringIO()\n"
        "sys.stderr = io.StringIO()\n"
        "_result = ''\n"
        "_error = ''\n"
        "try:\n"
        "    exec(compile(_user_code, '<agent>', 'exec'))\n"
        "    _result = sys.stdout.getvalue()\n"
        "    _error = sys.stderr.getvalue()\n"
        "except SystemExit:\n"
        "    _result = sys.stdout.getvalue()\n"
        "    _error = sys.stderr.getvalue()\n"
        "except Exception:\n"
        "    _result = sys.stdout.getvalue()\n"
        "    _error = sys.stderr.getvalue() + traceback.format_exc()\n"
        "finally:\n"
        "    sys.stdout = _old_stdout\n"
        "    sys.stderr = _old_stderr\n";

    PyObject *result = PyRun_String(wrapper, Py_file_input, globals, globals);

    jstring ret;
    if (result == NULL) {
        PyObject *type, *value, *tb;
        PyErr_Fetch(&type, &value, &tb);
        PyErr_NormalizeException(&type, &value, &tb);
        PyObject *str = value ? PyObject_Str(value) : NULL;
        const char *err_str = str ? PyUnicode_AsUTF8(str) : "未知错误";
        char errbuf[4096];
        snprintf(errbuf, sizeof(errbuf), "Python 异常: %s", err_str);
        ret = (*env)->NewStringUTF(env, errbuf);
        LOGE("nativeExec: %s", errbuf);
        Py_XDECREF(str);
        Py_XDECREF(type);
        Py_XDECREF(value);
        Py_XDECREF(tb);
        PyErr_Clear();
    } else {
        Py_DECREF(result);

        PyObject *out = PyDict_GetItemString(globals, "_result");
        PyObject *err = PyDict_GetItemString(globals, "_error");
        const char *out_str = out ? PyUnicode_AsUTF8(out) : "";
        const char *err_str = err ? PyUnicode_AsUTF8(err) : "";

        char outbuf[65536];
        int offset = 0;
        if (out_str && *out_str)
            offset += snprintf(outbuf + offset, sizeof(outbuf) - offset, "%s", out_str);
        if (err_str && *err_str)
            offset += snprintf(outbuf + offset, sizeof(outbuf) - offset, "[stderr]\n%s", err_str);

        ret = (*env)->NewStringUTF(env, offset == 0 ? "(无输出)" : outbuf);
        LOGI("nativeExec: 成功 (输出=%dB)", offset);
    }

    (*env)->ReleaseStringUTFChars(env, code, code_utf8);
    PyGILState_Release(gstate);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeShutdown(
    JNIEnv *env, jobject obj)
{
    if (python_initialized) {
        PyGILState_Ensure();
        Py_Finalize();
        python_initialized = 0;
        LOGI("nativeShutdown: Python 已关闭");
    }
}
