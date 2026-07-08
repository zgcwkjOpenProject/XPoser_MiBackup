package com.zgcwkj.comm;

import android.util.Log;

import java.util.List;

/**
 * 云存储统一接口
 * 根据config.ini中的protocol字段，自动路由到SMB或WebDAV实现
 * 统一捕获异常并记录日志，方便排查问题
 */
public class CloudFileHelp {

    private static final String TAG = "XpMiBackup";

    /** 测试连接是否可达 */
    public static boolean testConnection() {
        var proto = getProtocol();
        try {
            return isWebdav() ? WebdavFileHelp.testConnection() : SmbFileHelp.testConnection();
        } catch (Exception e) {
            Log.e(TAG, "testConnection failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + proto + "]");
            return false;
        }
    }

    /** 列出backup_path中的备份子目录名 */
    public static List<String> listDirs() {
        try {
            return isWebdav() ? WebdavFileHelp.listDirs() : SmbFileHelp.listDirs();
        } catch (Exception e) {
            Log.e(TAG, "listDirs failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + getProtocol() + "]");
            return List.of();
        }
    }

    /** 上传本地文件到远端（无进度回调） */
    public static String upload(String localPath, String remoteDir) {
        try {
            return isWebdav() ? WebdavFileHelp.upload(localPath, remoteDir) : SmbFileHelp.upload(localPath, remoteDir);
        } catch (Exception e) {
            Log.e(TAG, "upload failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + getProtocol() + "]");
            return "ERROR: " + e.getMessage();
        }
    }

    /** 上传文件并回调进度（备份用） */
    public static void uploadWithProgress(String localPath, Object progressListener, String remoteDir, String taskId) {
        try {
            if (isWebdav()) {
                WebdavFileHelp.uploadToWebdav(localPath, progressListener, remoteDir, taskId);
            } else {
                SmbFileHelp.uploadToSmb(localPath, progressListener, remoteDir, taskId);
            }
        } catch (Exception e) {
            Log.e(TAG, "uploadWithProgress failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + getProtocol() + "]");
            try {
                var invokeMethod = progressListener.getClass().getMethod("onFinish", String.class, int.class, String.class);
                invokeMethod.invoke(progressListener, taskId, -1, e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    /** 下载单个文件到本地 */
    public static String downloadFile(String remotePath, String localPath) {
        try {
            return isWebdav() ? WebdavFileHelp.downloadFile(remotePath, localPath) : SmbFileHelp.downloadFile(remotePath, localPath);
        } catch (Exception e) {
            Log.e(TAG, "downloadFile failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + getProtocol() + "]");
            return "ERROR: " + e.getMessage();
        }
    }

    /** 下载恢复文件到本地（根据文件名推断路径） */
    public static void downloadFromCloud(String localPath) {
        try {
            if (isWebdav()) {
                WebdavFileHelp.downloadFromWebdav(localPath);
            } else {
                SmbFileHelp.downloadFromSmb(localPath);
            }
        } catch (Exception e) {
            Log.e(TAG, "downloadFromCloud failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + getProtocol() + "]");
        }
    }

    /** 读取所有备份的descript.xml到内存 */
    public static List<String> readBackupXmls() {
        try {
            return isWebdav() ? WebdavFileHelp.readBackupXmls() : SmbFileHelp.readBackupXmls();
        } catch (Exception e) {
            Log.e(TAG, "readBackupXmls failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + getProtocol() + "]");
            return List.of();
        }
    }

    /** 列出备份目录名并下载descript.xml到本地 */
    public static String listAndDownloadXml(String localTempPath) {
        try {
            return isWebdav() ? WebdavFileHelp.listAndDownloadXml(localTempPath) : SmbFileHelp.listAndDownloadXml(localTempPath);
        } catch (Exception e) {
            Log.e(TAG, "listAndDownloadXml failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + getProtocol() + "]");
            return "ERROR: " + e.getMessage();
        }
    }

    /** 设置备份目录（SMB专用，WebDAV无此概念） */
    public static void setBackupDir(String dir) {
        if (!isWebdav()) SmbFileHelp.setBackupDir(dir);
    }

    /** 删除远端目录及其内容（备份取消时清理云端文件） */
    public static void deleteRemoteDir(String remoteDir) {
        try {
            if (isWebdav()) {
                WebdavFileHelp.deleteDir(remoteDir);
            } else {
                SmbFileHelp.deleteDir(remoteDir);
            }
        } catch (Exception e) {
            Log.e(TAG, "deleteRemoteDir failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " [protocol=" + getProtocol() + "]");
        }
    }

    /** 清理旧备份，保留最近backup_max个（0=不限） */
    public static void cleanupOldBackups() {
        try {
            var max = ConfigHelp.getInt("backup_max", 5);
            if (max <= 0) return;
            var dirs = new java.util.ArrayList<>(listDirs());
            if (dirs.size() <= max) return;
            java.util.Collections.sort(dirs);
            var toDelete = dirs.subList(0, dirs.size() - max);
            for (var dir : toDelete) {
                var backupPath = ConfigHelp.getString("backup_path", "");
                deleteRemoteDir(backupPath + "/" + dir);
                Log.i(TAG, "Cleaned up old backup: " + dir);
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanupOldBackups failed: " + e.getMessage());
        }
    }

    /** 获取当前协议名称 */
    public static String getProtocol() {
        return ConfigHelp.getString("protocol", "smb");
    }

    /** 判断当前协议是否为WebDAV */
    private static boolean isWebdav() {
        return "webdav".equals(ConfigHelp.getString("protocol", "smb"));
    }
}
