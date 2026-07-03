package com.example.agenttoolbox.tools;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 进程执行器 - 统一处理 shell 命令执行
 *
 * 解决的问题：
 * 1. 超时后竞态条件 → waitFor 先于 exitValue
 * 2. 进程泄漏 → finally 块强制 destroy
 * 3. 输出 OOM → 累计超限截断
 * 4. readLine() 丢换行 → 逐字符读取保留原始格式
 * 5. 二进制输出挂起 → 带超时的流读取
 */
public class ProcessRunner {

    private static final int MAX_OUTPUT_CHARS = 64 * 1024;  // 64KB 输出上限
    private static final long STREAM_READ_TIMEOUT_MS = 5000; // 流读取超时

    public static class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean truncated;
        public final boolean timedOut;

        public Result(int exitCode, String stdout, String stderr, boolean truncated, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.truncated = truncated;
            this.timedOut = timedOut;
        }
    }

    /**
     * 执行命令（String[] 方式，安全无注入）
     */
    public static Result exec(String[] cmd, int timeoutSeconds) throws Exception {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(cmd);
            return waitForAndRead(process, timeoutSeconds);
        } finally {
            destroyProcess(process);
        }
    }

    /**
     * 执行 shell 命令字符串（sh -c 方式）
     */
    public static Result execShell(String command, int timeoutSeconds) throws Exception {
        return exec(new String[]{"sh", "-c", command}, timeoutSeconds);
    }

    /**
     * 等待进程完成并读取输出
     */
    private static Result waitForAndRead(Process process, int timeoutSeconds) throws Exception {
        // 并行读取 stdout 和 stderr
        final StringBuilder stdoutBuf = new StringBuilder();
        final StringBuilder stderrBuf = new StringBuilder();
        final boolean[] truncated = {false};

        Thread stdoutThread = new Thread(() -> {
            try {
                truncated[0] = readStream(process.getInputStream(), stdoutBuf) || truncated[0];
            } catch (Exception ignored) {}
        });

        Thread stderrThread = new Thread(() -> {
            try {
                truncated[0] = readStream(process.getErrorStream(), stderrBuf) || truncated[0];
            } catch (Exception ignored) {}
        });

        stdoutThread.start();
        stderrThread.start();

        // 等待进程完成
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        // 等待流读取线程结束
        stdoutThread.join(STREAM_READ_TIMEOUT_MS);
        stderrThread.join(STREAM_READ_TIMEOUT_MS);

        int exitCode;
        boolean timedOut = false;

        if (finished) {
            exitCode = process.exitValue();
        } else {
            // 超时：强制杀死进程
            timedOut = true;
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            exitCode = -1;
        }

        return new Result(exitCode, stdoutBuf.toString(), stderrBuf.toString(), truncated[0], timedOut);
    }

    /**
     * 逐字符读取流，保留原始换行格式，超限截断
     *
     * @return true 如果发生了截断
     */
    private static boolean readStream(InputStream is, StringBuilder buf) {
        try {
            int ch;
            while ((ch = is.read()) != -1) {
                if (buf.length() >= MAX_OUTPUT_CHARS) {
                    buf.append("\n... [输出截断，超过 ").append(MAX_OUTPUT_CHARS / 1024).append("KB 限制]");
                    // 继续读完但不存储，防止管道满导致进程挂起
                    while (is.read() != -1) {}
                    return true;
                }
                buf.append((char) ch);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 强制销毁进程
     */
    private static void destroyProcess(Process process) {
        if (process == null) return;
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }
    }
}
