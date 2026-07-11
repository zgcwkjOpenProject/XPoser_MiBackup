package com.zgcwkj.comm;

import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一处理应用日志输出，可按配置追加写入本地日志文件。
 */
public class LogHelp {
    private static final String LOG_KEY = "log_enabled";
    private static final String CONFIG_PATH = ConfigHelp.BACKUP_ROOT + "/config.ini";
    private static final String LOG_DIR = ConfigHelp.BACKUP_ROOT + "/logs";

    /**
     * 输出详细日志，开启文件日志时同步追加到每日日志文件。
     */
    public static void v(String tag, String message) {
        log(Log.VERBOSE, tag, message, null);
    }

    /**
     * 输出详细日志和异常堆栈，开启文件日志时同步追加到每日日志文件。
     */
    public static void v(String tag, String message, Throwable throwable) {
        log(Log.VERBOSE, tag, message, throwable);
    }

    /**
     * 输出调试日志，开启文件日志时同步追加到每日日志文件。
     */
    public static void d(String tag, String message) {
        log(Log.DEBUG, tag, message, null);
    }

    /**
     * 输出调试日志和异常堆栈，开启文件日志时同步追加到每日日志文件。
     */
    public static void d(String tag, String message, Throwable throwable) {
        log(Log.DEBUG, tag, message, throwable);
    }

    /**
     * 输出信息日志，开启文件日志时同步追加到每日日志文件。
     */
    public static void i(String tag, String message) {
        log(Log.INFO, tag, message, null);
    }

    /**
     * 输出信息日志和异常堆栈，开启文件日志时同步追加到每日日志文件。
     */
    public static void i(String tag, String message, Throwable throwable) {
        log(Log.INFO, tag, message, throwable);
    }

    /**
     * 输出警告日志，开启文件日志时同步追加到每日日志文件。
     */
    public static void w(String tag, String message) {
        log(Log.WARN, tag, message, null);
    }

    /**
     * 输出警告日志和异常堆栈，开启文件日志时同步追加到每日日志文件。
     */
    public static void w(String tag, String message, Throwable throwable) {
        log(Log.WARN, tag, message, throwable);
    }

    /**
     * 输出错误日志，开启文件日志时同步追加到每日日志文件。
     */
    public static void e(String tag, String message) {
        log(Log.ERROR, tag, message, null);
    }

    /**
     * 输出带异常堆栈的错误日志，开启文件日志时同步追加到每日日志文件。
     */
    public static void e(String tag, String message, Throwable throwable) {
        log(Log.ERROR, tag, message, throwable);
    }

    /**
     * 按日志类型输出到系统日志，并在开关开启时写入本地文件。
     */
    public static void log(int priority, String tag, String message, Throwable throwable) {
        if (throwable == null) {
            Log.println(priority, tag, message);
        } else {
            Log.println(priority, tag, message + "\n" + Log.getStackTraceString(throwable));
        }
        writeFileLog(priority, tag, message, throwable);
    }

    /**
     * 判断文件日志开关是否已开启，读取失败时按关闭处理。
     */
    private static boolean isFileLogEnabled() {
        var file = new File(CONFIG_PATH);
        if (!file.exists()) {
            return false;
        }
        try (var reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            var line = reader.readLine();
            while (line != null) {
                line = line.trim();
                if (!line.startsWith("#") && line.startsWith(LOG_KEY + "=")) {
                    var value = line.substring((LOG_KEY + "=").length()).trim();
                    return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
                }
                line = reader.readLine();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 追加写入本地日志文件，任何写入异常都只回落到系统日志，避免影响主流程。
     */
    private static void writeFileLog(int priority, String tag, String message, Throwable throwable) {
        if (!isFileLogEnabled()) {
            return;
        }
        try {
            var dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            var date = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
            var time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            var file = new File(dir, date + ".log");
            try (var writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                writer.write(time + " " + priorityToLetter(priority) + "/" + tag + ": " + message);
                writer.newLine();
                if (throwable != null) {
                    writer.write(Log.getStackTraceString(throwable));
                    writer.newLine();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 将Android日志优先级转换为文件内展示的单字母类型。
     */
    private static String priorityToLetter(int priority) {
        switch (priority) {
            case Log.VERBOSE:
                return "V";
            case Log.DEBUG:
                return "D";
            case Log.INFO:
                return "I";
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            case Log.ASSERT:
                return "A";
            default:
                return String.valueOf(priority);
        }
    }
}
