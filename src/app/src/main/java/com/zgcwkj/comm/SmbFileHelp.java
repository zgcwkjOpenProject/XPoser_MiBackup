package com.zgcwkj.comm;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.share.DiskShare;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * SMB文件操作工具类
 * 上传、下载、列表、恢复辅助均在此类实现
 */
public class SmbFileHelp {

    private static final String TAG = "XpMiBackup";
    private static final int BUFFER_SIZE = 1048576; // 1MB

    // ========== SMB会话管理 ==========

    /** SMB连接资源封装，支持 try-with-resources 自动关闭 */
    private static class SmbSession implements AutoCloseable {
        final DiskShare share;
        final String backupPath;
        final com.hierynomus.smbj.session.Session session;
        final com.hierynomus.smbj.connection.Connection connection;

        SmbSession(String shareName) {
            var cfg = ConfigHelp.load();
            var server = cfg.optString("smb_server", "");
            var port = cfg.optInt("smb_port", 445);
            var user = cfg.optString("smb_user", "");
            var pass = cfg.optString("smb_pass", "");
            this.backupPath = cfg.optString("backup_path", "");

            try {
                var config = SmbConfig.builder().build();
                var client = new SMBClient(config);
                this.connection = client.connect(server, port);
                this.session = this.connection.authenticate(new AuthenticationContext(user, pass.toCharArray(), ""));
                this.share = (DiskShare) session.connectShare(shareName);
            } catch (Exception e) {
                throw new RuntimeException("SMB connect failed", e);
            }
        }

        /** 创建远程目录 */
        void mkdir(String path) {
            if (path != null && !path.isEmpty()) {
                try { share.mkdir(path); } catch (Exception ignored) {}
            }
        }

        /** 递归创建远程目录链 */
        void mkdirs(String remoteDir) {
            mkdirsRecursive(backupPath);
            mkdirsRecursive(remoteDir);
        }

        /** 按路径段逐级创建目录 */
        void mkdirsRecursive(String path) {
            if (path == null || path.isEmpty()) {
                return;
            }
            var current = "";
            for (var part : path.replace('\\', '/').split("/")) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                current = current.isEmpty() ? part : current + "/" + part;
                mkdir(current);
            }
        }

        @Override
        public void close() {
            try { share.close(); } catch (Exception ignored) {}
            try { session.close(); } catch (Exception ignored) {}
            try { connection.close(); } catch (Exception ignored) {}
        }
    }

    // ========== 公共方法 ==========

    /** 测试SMB连接是否可达 */
    public static boolean testConnection() throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            return true;
        }
    }

    /** 列出backup_path目录中的备份子目录名 */
    public static List<String> listDirs() throws Exception {
        var dirs = new ArrayList<String>();
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            for (var entry : s.share.list(s.backupPath)) {
                var name = entry.getFileName();
                if (!name.equals(".") && !name.equals("..") && (entry.getFileAttributes() & 0x10) != 0) {
                    dirs.add(name);
                }
            }
        }
        return dirs;
    }

    /** 列出指定SMB目录下的文件条目，路径相对于共享根目录 */
    public static List<CloudFileHelp.RemoteEntry> listEntries(String remoteDir) throws Exception {
        var entries = new ArrayList<CloudFileHelp.RemoteEntry>();
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            for (var entry : s.share.list(remoteDir)) {
                var name = entry.getFileName();
                if (name.equals(".") || name.equals("..")) continue;
                var isDir = (entry.getFileAttributes() & 0x10) != 0;
                var size = isDir ? 0L : entry.getEndOfFile();
                var modified = entry.getLastWriteTime() != null
                    ? entry.getLastWriteTime().toEpochMillis()
                    : System.currentTimeMillis();
                entries.add(new CloudFileHelp.RemoteEntry(name, size, isDir, modified));
            }
        }
        return entries;
    }

    /** 删除远端目录及其所有内容 */
    public static void deleteDir(String remoteDir) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var path = normalizeDeletePath(s.backupPath, remoteDir);
            deleteDirRecursive(s.share, path);
        }
    }

    /** 递归删除SMB目录
     * 兼容完整云端路径和相对备份目录名两种删除调用
     */
    private static String normalizeDeletePath(String backupPath, String remoteDir) {
        if (remoteDir == null || remoteDir.isEmpty()) {
            return backupPath;
        }
        var path = remoteDir.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (backupPath == null || backupPath.isEmpty() || path.startsWith(backupPath + "/") || path.equals(backupPath)) {
            return path;
        }
        return backupPath + "/" + path;
    }

    private static void deleteDirRecursive(DiskShare share, String path) {
        try {
            var entries = share.list(path);
            for (var entry : entries) {
                var name = entry.getFileName();
                if (name.equals(".") || name.equals("..")) continue;
                var childPath = path + "/" + name;
                if ((entry.getFileAttributes() & 0x10) != 0) {
                    deleteDirRecursive(share, childPath);
                } else {
                    try { share.rm(childPath); } catch (Exception ignored) {}
                }
            }
            share.rmdir(path, false);
        } catch (Exception ignored) {}
    }

    // ========== 上传 ==========

    /** 上传本地文件到SMB共享（无进度回调），失败自动重试3次 */
    public static String upload(String localPath, String remoteDir) throws Exception {
        var lastError = (Exception) null;
        for (var attempt = 1; attempt <= 3; attempt++) {
            try {
                return doUpload(localPath, remoteDir);
            } catch (Exception e) {
                lastError = e;
                LogHelp.w(TAG, "SMB upload attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < 3) Thread.sleep(2000);
            }
        }
        throw lastError;
    }

    private static String doUpload(String localPath, String remoteDir) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var localFile = new File(localPath);
            if (!localFile.exists()) throw new FileNotFoundException("file not found: " + localPath);

            s.mkdirs(remoteDir);
            var remotePath = (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + localFile.getName();
            uploadWholeFileToSmb(s.share, localFile, remotePath, null, "", 0L, localFile.length());
            return "OK: " + remotePath + " (" + localFile.length() + " bytes)";
        }
    }

    /** 上传文件到SMB并实时回调进度（备份用），失败自动重试3次 */
    public static void uploadToSmb(String localPath, Object progressListener, String remoteDir, String taskId) throws Exception {
        var lastError = (Exception) null;
        for (var attempt = 1; attempt <= 3; attempt++) {
            try {
                doUploadToSmb(localPath, progressListener, remoteDir, taskId);
                return;
            } catch (Exception e) {
                lastError = e;
                LogHelp.w(TAG, "SMB upload attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < 3) Thread.sleep(2000);
            }
        }
        throw lastError;
    }

    private static void doUploadToSmb(String localPath, Object progressListener, String remoteDir, String taskId) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var localFile = new File(localPath);
            if (!localFile.exists()) throw new FileNotFoundException("file not found: " + localPath);

            var fileSize = localFile.length();

            s.mkdirs(remoteDir);
            var remotePath = (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + localFile.getName();
            uploadWholeFileToSmb(s.share, localFile, remotePath, progressListener, taskId, 0L, fileSize);

            notifyFinish(progressListener, taskId, 0, "success");
        }
    }

    /**
     * SMB整文件上传，CloudFileHelp会在进入这里前统一处理切片
     */
    private static long uploadWholeFileToSmb(DiskShare share, File localFile, String remotePath, Object progressListener, String taskId, long baseWritten, long totalSize) throws Exception {
        var smbFile = openSmbOutput(share, remotePath);
        try (var fis = new FileInputStream(localFile); var fos = smbFile.getOutputStream()) {
            return streamCopyWithProgress(fis, fos, progressListener, taskId, baseWritten, totalSize);
        } finally {
            try { smbFile.close(); } catch (Exception ignored) {}
        }
    }

    // ========== 下载 ==========

    /** 下载单个文件从SMB到本地 */
    public static String downloadFile(String remotePath, String localPath) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var localFile = new File(localPath);
            var parent = localFile.getParentFile();
            if (parent != null) parent.mkdirs();

            var smbFile = s.share.openFile(remotePath,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.SYNCHRONIZE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));

            // try-with-resources保证异常时输入输出流和SMB文件句柄都被关闭
            try (var is = smbFile.getInputStream(); var fos = new FileOutputStream(localFile)) {
                var total = streamCopy(is, fos);
                return "OK: " + remotePath + " -> " + localPath + " (" + total + " bytes)";
            } finally {
                try { smbFile.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ========== 工具方法 ==========

    /** 流拷贝（不负责关闭流，由调用方用try-with-resources管理） */
    private static long streamCopy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        return streamCopyWithProgress(in, out, null, "", 0L, 0L);
    }

    /** 流拷贝并按时间间隔回调上传进度 */
    private static long streamCopyWithProgress(java.io.InputStream in, java.io.OutputStream out, Object listener, String taskId, long baseWritten, long totalSize) throws Exception {
        var buffer = new byte[BUFFER_SIZE];
        var total = 0L;
        var lastReportTime = 0L;
        var bytesRead = 0;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush();
            total += bytesRead;
            var current = baseWritten + total;
            var now = System.currentTimeMillis();
            if (listener != null && (now - lastReportTime >= 200 || current == totalSize)) {
                lastReportTime = now;
                notifyProgress(listener, taskId, current, totalSize);
            }
        }
        return total;
    }

    /** 打开SMB远端文件用于覆盖写入 */
    private static com.hierynomus.smbj.share.File openSmbOutput(DiskShare share, String remotePath) {
        return share.openFile(remotePath,
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.SYNCHRONIZE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }

    /** IFileOperationProgressListener回调：通知进度 */
    private static void notifyProgress(Object listener, String taskId, long current, long total) {
        if (listener == null) return;
        taskId = ProgressCallbackHelp.safeString(taskId);
        invokeProgress(listener, "D0", new Class[]{String.class, long.class, long.class}, taskId, current, total);
    }

    /** IFileOperationProgressListener回调：通知完成 */
    private static void notifyFinish(Object listener, String taskId, int code, String msg) {
        if (listener == null) return;
        taskId = ProgressCallbackHelp.safeString(taskId);
        msg = ProgressCallbackHelp.safeString(msg);
        invokeProgress(listener, "l0", new Class[]{String.class, int.class, String.class}, taskId, code, msg);
    }

    /** 按参数签名查找进度回调方法 */
    private static void invokeProgress(Object listener, String method, Class<?>[] types, Object... args) {
        try {
            listener.getClass().getMethod(method, types).invoke(listener, args);
        } catch (Exception e) {
            try {
                for (var m : listener.getClass().getMethods()) {
                    if (m.getParameterCount() != types.length || m.getReturnType() != void.class) continue;
                    var matched = true;
                    for (var i = 0; i < types.length; i++) {
                        if (m.getParameterTypes()[i] != types[i]) {
                            matched = false;
                            break;
                        }
                    }
                    if (matched) {
                        m.invoke(listener, args);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}
