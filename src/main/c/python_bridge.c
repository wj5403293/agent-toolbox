#include <jni.h>
#include <Python.h>
#include <string.h>
#include <stdlib.h>

/**
 * Python JNI 桥接层
 *
 * 通过 libpython3.14.so 在进程内执行 Python 代码，
 * 避免启动外部进程，直接共享内存空间。
 */

// 全局状态：Python 是否已初始化
static int python_initialized = 0;

/**
 * 初始化 Python 运行时
 *
 * @param home  PYTHONHOME 路径（包含 lib/python3.14/ 的目录）
 * @return 0 成功，-1 失败
 */
JNIEXPORT jint JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeInit(
    JNIEnv *env, jobject obj, jstring home
) {
    if (python_initialized) {
        return 0;
    }

    const char *home_utf8 = (*env)->GetStringUTFChars(env, home, NULL);

    PyConfig config;
    PyConfig_InitPythonConfig(&config);

    // 设置 Python Home（标准库位置）
    PyStatus status = PyConfig_SetBytesString(&config, &config.home, home_utf8);
    if (PyStatus_Exception(status)) {
        (*env)->ReleaseStringUTFChars(env, home, home_utf8);
        PyConfig_Clear(&config);
        return -1;
    }

    // 不注册信号处理器（Android 环境）
    config.install_signal_handlers = 0;

    // 不导入 site 模块（减少启动时间）
    config.site_import = 0;

    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    (*env)->ReleaseStringUTFChars(env, home, home_utf8);

    if (PyStatus_Exception(status)) {
        return -1;
    }

    python_initialized = 1;
    return 0;
}

/**
 * 执行 Python 代码并捕获 stdout/stderr
 *
 * @param code  要执行的 Python 代码
 * @return 执行结果（stdout + stderr 合并）
 */
JNIEXPORT jstring JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeExec(
    JNIEnv *env, jobject obj, jstring code
) {
    if (!python_initialized) {
        return (*env)->NewStringUTF(env, "错误: Python 未初始化，请先调用 init()");
    }

    const char *code_utf8 = (*env)->GetStringUTFChars(env, code, NULL);

    // 用 Python 的 io.StringIO 捕获输出
    const char *wrapper =
        "import sys, io\n"
        "_stdout = sys.stdout\n"
        "_stderr = sys.stderr\n"
        "sys.stdout = io.StringIO()\n"
        "sys.stderr = io.StringIO()\n"
        "_exit_code = 0\n"
        "_error_msg = ''\n"
        "try:\n"
        "    exec(compile(_code, '<agent>', 'exec'))\n"
        "except SystemExit as e:\n"
        "    _exit_code = e.code if e.code is not None else 0\n"
        "except Exception as e:\n"
        "    import traceback\n"
        "    _error_msg = traceback.format_exc()\n"
        "_out = sys.stdout.getvalue()\n"
        "_err = sys.stderr.getvalue()\n"
        "sys.stdout = _stdout\n"
        "sys.stderr = _stderr\n";

    // 设置 _code 变量
    PyObject *main_module = PyImport_AddModule("__main__");
    PyObject *globals = PyModule_GetDict(main_module);

    // 注入代码变量
    PyObject *code_obj = PyUnicode_FromString(code_utf8);
    PyDict_SetItemString(globals, "_code", code_obj);
    Py_DECREF(code_obj);

    // 执行包装脚本
    PyObject *result = PyRun_String(wrapper, Py_file_input, globals, globals);

    jstring ret;
    if (result == NULL) {
        // Python 异常
        PyObject *err = PyErr_Occurred();
        if (err) {
            PyObject *type, *value, *tb;
            PyErr_Fetch(&type, &value, &tb);
            PyErr_NormalizeException(&type, &value, &tb);

            PyObject *tb_mod = PyImport_ImportModule("traceback");
            PyObject *fmt = NULL;
            if (tb_mod && value && tb) {
                fmt = PyObject_CallMethod(tb_mod, "format_exception", "OOO", type, value, tb);
            } else if (value) {
                PyObject *str = PyObject_Str(value);
                if (str) {
                    fmt = PyList_New(1);
                    PyList_SetItem(fmt, 0, str);
                }
            }

            if (fmt) {
                PyObject *joined = PyUnicode_Join(PyUnicode_FromString(""), fmt);
                const char *err_str = PyUnicode_AsUTF8(joined);
                ret = (*env)->NewStringUTF(env, err_str ? err_str : "未知 Python 错误");
                Py_DECREF(joined);
                Py_DECREF(fmt);
            } else {
                ret = (*env)->NewStringUTF(env, "Python 执行异常（无法获取错误信息）");
            }

            Py_XDECREF(tb_mod);
            Py_XDECREF(type);
            Py_XDECREF(value);
            Py_XDECREF(tb);
            PyErr_Clear();
        } else {
            ret = (*env)->NewStringUTF(env, "Python 执行异常");
        }
    } else {
        Py_DECREF(result);

        // 读取 _out 和 _err
        PyObject *out = PyDict_GetItemString(globals, "_out");
        PyObject *err = PyDict_GetItemString(globals, "_err");
        PyObject *exit_code = PyDict_GetItemString(globals, "_exit_code");
        PyObject *error_msg = PyDict_GetItemString(globals, "_error_msg");

        const char *out_str = out ? PyUnicode_AsUTF8(out) : "";
        const char *err_str = err ? PyUnicode_AsUTF8(err) : "";
        const char *err_msg_str = (error_msg && PyUnicode_GetLength(error_msg) > 0)
                                  ? PyUnicode_AsUTF8(error_msg) : "";

        int code_val = exit_code ? (int)PyLong_AsLong(exit_code) : 0;

        // 组装结果
        char result_buf[65536];  // 64KB 上限
        int offset = 0;

        if (out_str && strlen(out_str) > 0) {
            offset += snprintf(result_buf + offset, sizeof(result_buf) - offset,
                              "%s", out_str);
        }

        if (err_str && strlen(err_str) > 0) {
            offset += snprintf(result_buf + offset, sizeof(result_buf) - offset,
                              "[stderr]\n%s", err_str);
        }

        if (err_msg_str && strlen(err_msg_str) > 0) {
            offset += snprintf(result_buf + offset, sizeof(result_buf) - offset,
                              "[异常]\n%s", err_msg_str);
        }

        if (code_val != 0) {
            offset += snprintf(result_buf + offset, sizeof(result_buf) - offset,
                              "\n[exit_code=%d]", code_val);
        }

        if (offset == 0) {
            ret = (*env)->NewStringUTF(env, "(无输出)");
        } else {
            ret = (*env)->NewStringUTF(env, result_buf);
        }
    }

    (*env)->ReleaseStringUTFChars(env, code, code_utf8);
    return ret;
}

/**
 * 关闭 Python 运行时
 */
JNIEXPORT void JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeShutdown(
    JNIEnv *env, jobject obj
) {
    if (python_initialized) {
        Py_Finalize();
        python_initialized = 0;
    }
}

/**
 * 检查 Python 是否已初始化
 */
JNIEXPORT jboolean JNICALL
Java_com_example_agenttoolbox_tools_PythonBridge_nativeIsInitialized(
    JNIEnv *env, jobject obj
) {
    return python_initialized ? JNI_TRUE : JNI_FALSE;
}
