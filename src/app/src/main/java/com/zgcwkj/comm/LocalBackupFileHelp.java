package com.zgcwkj.comm;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.File;

/**
 * 小米备份本地临时文件工具
 */
public final class LocalBackupFileHelp {
    public static final String TEMP_BACKUP_ROOT = "/sdcard/MIUI/backup/AllBackupTemp/";
    private static final String TAG = "XpMiBackup";

    private LocalBackupFileHelp() {
    }

    /**
     * 解析小米备份已经写好的本地文件，切片文件会跟随该文件目录
     */
    public static File resolveUploadFile(ParcelFileDescriptor pfd, String aidlPath) {
        var file = localBackupFile(aidlPath);
        if (file != null) {
            return file;
        }
        return localBackupFile(readFdPath(pfd));
    }

    /**
     * 按备份数据相对路径创建本地上传临时文件
     */
    public static File createUploadTempFile(String aidlPath, String fallbackFileName) {
        var relativePath = localBackupRelativePath(aidlPath);
        if (relativePath.isEmpty()) {
            relativePath = fallbackFileName == null || fallbackFileName.isEmpty() ? "upload.tmp" : fallbackFileName;
        }
        return new File(TEMP_BACKUP_ROOT, relativePath);
    }

    /**
     * 从叶子目录开始清理空目录，最多清理到AllBackupTemp
     */
    public static void deleteEmptyDirsUntilTempRoot(File dir) {
        if (dir == null) {
            return;
        }
        var root = new File(TEMP_BACKUP_ROOT);
        var current = dir;
        var rootPath = root.getAbsolutePath();
        while (current != null && current.exists() && current.isDirectory()) {
            var currentPath = current.getAbsolutePath();
            if (currentPath.equals(rootPath) || !currentPath.startsWith(rootPath)) {
                return;
            }
            var children = current.list();
            if (children == null || children.length > 0) {
                return;
            }
            if (!current.delete()) {
                logError("delete temp dir failed", new IllegalStateException(current.getAbsolutePath()));
                return;
            }
            current = current.getParentFile();
        }
    }

    /**
     * 从文件描述符反查真实路径，AIDL只传虚拟远端路径时使用
     */
    private static String readFdPath(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return "";
        }
        try {
            return Os.readlink("/proc/self/fd/" + pfd.getFd());
        } catch (Exception e) {
            logError("read upload fd path failed", e);
            return "";
        }
    }

    /**
     * 仅接受AllBackupTemp里的真实文件
     */
    private static File localBackupFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        var file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        return isUnderTempBackupRoot(file) ? file : null;
    }

    /**
     * 判断文件是否位于小米备份固定临时目录下
     */
    private static boolean isUnderTempBackupRoot(File file) {
        try {
            var rootPath = appendSlash(new File(TEMP_BACKUP_ROOT).getCanonicalPath());
            var filePath = file.getCanonicalPath();
            return filePath.startsWith(rootPath);
        } catch (Exception e) {
            logError("check local backup path failed", e);
            return false;
        }
    }

    /**
     * 从本地AllBackupTemp路径中得到相对路径
     */
    private static String localBackupRelativePath(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return "";
        }
        var path = trimLeadingSlash(sourcePath.replace('\\', '/'));
        var root = trimLeadingSlash(TEMP_BACKUP_ROOT.replace('\\', '/'));
        if (!path.startsWith(root)) {
            return "";
        }
        return cleanPathSegments(trimLeadingSlash(path.substring(root.length())));
    }

    /**
     * 移除路径里的空段和父级跳转
     */
    private static String cleanPathSegments(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        var cleaned = new StringBuilder();
        for (var segment : path.split("/")) {
            if (segment == null || segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                || ".AllBackup".equals(segment) || ".AppBackup".equals(segment)) {
                continue;
            }
            if (cleaned.length() > 0) {
                cleaned.append("/");
            }
            cleaned.append(segment);
        }
        return cleaned.toString();
    }

    /**
     * 移除路径开头的分隔符
     */
    private static String trimLeadingSlash(String path) {
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    /**
     * 给目录路径补齐结尾分隔符，用于目录边界判断
     */
    private static String appendSlash(String path) {
        if (path == null || path.endsWith(File.separator)) {
            return path;
        }
        return path + File.separator;
    }

    /**
     * 统一记录本地备份文件处理异常
     */
    private static void logError(String message, Throwable e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
}
