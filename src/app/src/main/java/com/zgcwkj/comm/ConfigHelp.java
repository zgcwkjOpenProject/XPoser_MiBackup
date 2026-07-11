package com.zgcwkj.comm;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * 配置文件读写工具
 * 配置文件位于 /sdcard/MIUI/backup/config.ini，格式为每行 key=value
 */
public class ConfigHelp {

    private static final String TAG = "XpMiBackup";
    public static final String BACKUP_ROOT = "/sdcard/MIUI/backup";
    private static final String CONFIG_PATH = BACKUP_ROOT + "/config.ini";

    /**
     * 加载配置并补齐默认值
     * 文件不存在或部分 key 缺失时，调用方仍能拿到完整配置
     */
    public static JSONObject load() {
        var map = new LinkedHashMap<String, String>();
        var file = new File(CONFIG_PATH);
        if (file.exists()) {
            try (var reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                var line = reader.readLine();
                while (line != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                        var idx = line.indexOf('=');
                        map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                    }
                    line = reader.readLine();
                }
            } catch (Exception e) {
                LogHelp.e(TAG, "load config failed: " + e.getMessage(), e);
            }
        }

        var defaults = defaultMap();
        for (var entry : defaults.entrySet()) {
            if (!map.containsKey(entry.getKey())) {
                map.put(entry.getKey(), entry.getValue());
            }
        }

        var json = new JSONObject();
        for (var entry : map.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                LogHelp.e(TAG, "put config value failed: " + entry.getKey(), e);
            }
        }
        return json;
    }

    /**
     * 保存配置为 INI 风格文本
     * 先创建父目录再打开文件，避免首次保存时 FileWriter 因目录不存在而失败
     */
    public static void save(JSONObject json) {
        var file = new File(CONFIG_PATH);
        var dir = file.getParentFile();
        try {
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "create config dir failed: " + e.getMessage(), e);
            return;
        }

        try (var writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            var keys = json.keys();
            while (keys.hasNext()) {
                var key = keys.next();
                var val = json.opt(key);
                writer.write(key + "=" + (val != null ? val.toString() : ""));
                writer.newLine();
            }
            writer.flush();
        } catch (Exception e) {
            LogHelp.e(TAG, "save config failed: " + e.getMessage(), e);
        }
    }

    /**
     * 读取字符串配置
     */
    public static String getString(String key, String def) {
        return load().optString(key, def);
    }

    /**
     * 读取整数配置，解析失败时使用调用方提供的默认值
     */
    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(load().optString(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 默认配置值
     */
    private static LinkedHashMap<String, String> defaultMap() {
        var map = new LinkedHashMap<String, String>();
        map.put("device_id", "zgcwkj");
        map.put("device_name", "云端备份设备");
        map.put("device_describe", "我的云端备份设备");
        map.put("backup_path", "MIUI/backup");
        map.put("backup_max", "5");
        map.put("log_enabled", "false");
        map.put("protocol", "smb");
        map.put("upload_threads", "3");
        map.put("smb_server", "192.168.68.1");
        map.put("smb_port", "445");
        map.put("smb_share", "备份数据");
        map.put("smb_user", "");
        map.put("smb_pass", "");
        map.put("webdav_url", "https://192.168.1.1:8080/dav");
        map.put("webdav_user", "");
        map.put("webdav_pass", "");
        return map;
    }
}
