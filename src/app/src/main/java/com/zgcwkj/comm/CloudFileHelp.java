package com.zgcwkj.comm;

import java.util.List;

/**
 * 云端文件访问门面。
 * 根据config.ini里的协议配置，把文件操作分发到SMB或WebDAV实现。
 */
public class CloudFileHelp {

    private static final String TAG = "XpMiBackup";

    /**
     * 远端文件条目，用于AIDL层构造小米DFS的SmbFile对象。
     */
    public static class RemoteEntry {
        public final String name;
        public final long size;
        public final boolean directory;
        public final long modifiedTime;

        /**
         * 保存远端文件或目录的基础属性。
         */
        public RemoteEntry(String name, long size, boolean directory, long modifiedTime) {
            this.name = name;
            this.size = size;
            this.directory = directory;
            this.modifiedTime = modifiedTime;
        }
    }

    /**
     * 测试当前配置的远端存储是否可连接。
     */
    public static boolean testConnection() {
        var protocol = getProtocol();
        try {
            return isWebdav() ? WebdavFileHelp.testConnection() : SmbFileHelp.testConnection();
        } catch (Exception e) {
            logError("testConnection failed [protocol=" + protocol + "]", e);
            return false;
        }
    }

    /**
     * 列出backup_path下面的备份目录。
     */
    public static List<String> listDirs() {
        try {
            return isWebdav() ? WebdavFileHelp.listDirs() : SmbFileHelp.listDirs();
        } catch (Exception e) {
            logError("listDirs failed [protocol=" + getProtocol() + "]", e);
            return List.of();
        }
    }

    /**
     * 列出指定远端目录下的文件和文件夹。
     */
    public static List<RemoteEntry> listEntries(String remoteDir) {
        try {
            return isWebdav() ? WebdavFileHelp.listEntries(remoteDir) : SmbFileHelp.listEntries(remoteDir);
        } catch (Exception e) {
            logError("listEntries failed [protocol=" + getProtocol() + ", dir=" + remoteDir + "]", e);
            return List.of();
        }
    }

    /**
     * 上传本地文件，不回调小米DFS进度。
     */
    public static String upload(String localPath, String remoteDir) {
        try {
            return isWebdav() ? WebdavFileHelp.upload(localPath, remoteDir) : SmbFileHelp.upload(localPath, remoteDir);
        } catch (Exception e) {
            logError("upload failed [protocol=" + getProtocol() + "]", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 上传备份文件，并回调小米DFS传输进度。
     */
    public static void uploadWithProgress(String localPath, Object progressListener, String remoteDir, String taskId) {
        try {
            if (isWebdav()) {
                WebdavFileHelp.uploadToWebdav(localPath, progressListener, remoteDir, taskId);
            } else {
                SmbFileHelp.uploadToSmb(localPath, progressListener, remoteDir, taskId);
            }
        } catch (Exception e) {
            logError("uploadWithProgress failed [protocol=" + getProtocol() + "]", e);
            notifyFinish(progressListener, taskId, -1, e.getMessage());
        }
    }

    /**
     * 下载单个远端文件到本地路径。
     */
    public static String downloadFile(String remotePath, String localPath) {
        try {
            return isWebdav() ? WebdavFileHelp.downloadFile(remotePath, localPath) : SmbFileHelp.downloadFile(remotePath, localPath);
        } catch (Exception e) {
            logError("downloadFile failed [protocol=" + getProtocol() + "]", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 按恢复侧本地路径推导远端路径并下载文件。
     */
    public static void downloadFromCloud(String localPath) {
        try {
            if (isWebdav()) {
                WebdavFileHelp.downloadFromWebdav(localPath);
            } else {
                SmbFileHelp.downloadFromSmb(localPath);
            }
        } catch (Exception e) {
            logError("downloadFromCloud failed [protocol=" + getProtocol() + "]", e);
        }
    }

    /**
     * 读取所有备份目录里的descript.xml内容。
     */
    public static List<String> readBackupXmls() {
        try {
            return isWebdav() ? WebdavFileHelp.readBackupXmls() : SmbFileHelp.readBackupXmls();
        } catch (Exception e) {
            logError("readBackupXmls failed [protocol=" + getProtocol() + "]", e);
            return List.of();
        }
    }

    /**
     * 列出备份目录，并把每个descript.xml下载到本地临时目录。
     */
    public static String listAndDownloadXml(String localTempPath) {
        try {
            return isWebdav() ? WebdavFileHelp.listAndDownloadXml(localTempPath) : SmbFileHelp.listAndDownloadXml(localTempPath);
        } catch (Exception e) {
            logError("listAndDownloadXml failed [protocol=" + getProtocol() + "]", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 删除远端目录及其所有内容。
     */
    public static void deleteRemoteDir(String remoteDir) {
        try {
            if (isWebdav()) {
                WebdavFileHelp.deleteDir(remoteDir);
            } else {
                SmbFileHelp.deleteDir(remoteDir);
            }
        } catch (Exception e) {
            logError("deleteRemoteDir failed [protocol=" + getProtocol() + "]", e);
        }
    }

    /**
     * 只保留backup_max个最新备份目录；backup_max小于等于0表示不限制。
     */
    public static void cleanupOldBackups() {
        try {
            var max = ConfigHelp.getInt("backup_max", 5);
            if (max <= 0) {
                return;
            }
            var dirs = new java.util.ArrayList<>(listDirs());
            if (dirs.size() <= max) {
                return;
            }
            java.util.Collections.sort(dirs);
            var toDelete = dirs.subList(0, dirs.size() - max);
            for (var dir : toDelete) {
                var backupPath = ConfigHelp.getString("backup_path", "");
                deleteRemoteDir(backupPath + "/" + dir);
            }
        } catch (Exception e) {
            logError("cleanupOldBackups failed", e);
        }
    }

    /**
     * 从配置中读取当前协议名称。
     */
    public static String getProtocol() {
        return ConfigHelp.getString("protocol", "smb");
    }

    /**
     * 判断当前协议是否为WebDAV。
     */
    private static boolean isWebdav() {
        return "webdav".equals(ConfigHelp.getString("protocol", "smb"));
    }

    /**
     * 通知IFileOperationProgressListener.onFinish或混淆后的等价方法。
     */
    private static void notifyFinish(Object listener, String taskId, int code, String msg) {
        if (listener == null) {
            return;
        }
        try {
            listener.getClass().getMethod("onFinish", String.class, int.class, String.class).invoke(listener, taskId, code, msg);
        } catch (Exception e) {
            try {
                for (var method : listener.getClass().getMethods()) {
                    if (method.getParameterCount() == 3
                        && method.getParameterTypes()[0] == String.class
                        && method.getParameterTypes()[1] == int.class
                        && method.getParameterTypes()[2] == String.class
                        && method.getReturnType() == void.class) {
                        method.invoke(listener, taskId, code, msg);
                        return;
                    }
                }
            } catch (Exception fallbackError) {
                logError("notifyFinish fallback failed", fallbackError);
            }
        }
    }

    /**
     * 统一记录云端文件操作异常。
     */
    private static void logError(String message, Exception e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
}
