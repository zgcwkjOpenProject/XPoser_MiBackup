package com.zgcwkj.comm;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;

/**
 * 配置文件读写工具
 * 文件路径：/sdcard/MIUI/backup/config.ini
 */
public class ConfigHelp {

    public static final String BACKUP_ROOT = "/sdcard/MIUI/backup";
    private static final String CONFIG_PATH = BACKUP_ROOT + "/config.ini";

    /**
     * 加载配置文件，解析INI格式为JSONObject
     * 文件不存在或格式异常时返回默认值
     */
    public static JSONObject load() {
        var map = new LinkedHashMap<String, String>();
        var f = new File(CONFIG_PATH);
        if (f.exists()) {
            // try-with-resources保证异常时Reader被关闭
            try (var r = new BufferedReader(new FileReader(f))) {
                var line = r.readLine();
                while (line != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                        var idx = line.indexOf('=');
                        map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                    }
                    line = r.readLine();
                }
            } catch (Exception ignored) {}
        }
        // 合并默认值（文件中没有的key用默认值补）
        var defs = defaultMap();
        for (var entry : defs.entrySet()) {
            if (!map.containsKey(entry.getKey())) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        // 转为JSONObject保持接口兼容
        var json = new JSONObject();
        for (var entry : map.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (Exception ignored) {}
        }
        return json;
    }

    /**
     * 将JSONObject保存为INI格式文件
     * 每个key=value占一行，便于手工编辑
     */
    public static void save(JSONObject json) {
        // try-with-resources保证异常时Writer被关闭，避免文件内容残缺
        try (var w = new BufferedWriter(new FileWriter(new File(CONFIG_PATH)))) {
            var dir = new File(CONFIG_PATH).getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            var keys = json.keys();
            while (keys.hasNext()) {
                var key = keys.next();
                var val = json.opt(key);
                w.write(key + "=" + (val != null ? val.toString() : ""));
                w.newLine();
            }
            w.flush();
        } catch (Exception e) {}
    }

    /**
     * 读取字符串配置值
     */
    public static String getString(String key, String def) {
        return load().optString(key, def);
    }

    /**
     * 读取整数配置值
     */
    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(load().optString(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 默认配置值，文件不存在时使用
     */
    private static LinkedHashMap<String, String> defaultMap() {
        var map = new LinkedHashMap<String, String>();
        map.put("device_id", "zgcwkj");
        map.put("device_name", "云端备份设备");
        map.put("device_describe", "我的云端备份设备");
        map.put("upload_threads", "3");
        map.put("backup_path", "MIUI/backup");
        map.put("backup_max", "5");
        map.put("protocol", "smb");
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
