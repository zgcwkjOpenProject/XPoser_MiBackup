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

        /** 确保远程目录存在 */
        void mkdir(String path) {
            if (path != null && !path.isEmpty()) {
                try { share.mkdir(path); } catch (Exception ignored) {}
            }
        }

        /** 创建远程目录链（rootDir/backupPath/remoteDir） */
        void mkdirs(String remoteDir) {
            var rootDir = backupPath.contains("/") ? backupPath.split("/")[0] : "MIUI";
            mkdir(rootDir);
            mkdir(backupPath);
            mkdir(remoteDir);
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

    /** 递归删除SMB目录 */
    /** 兼容完整云端路径和相对备份目录名两种删除调用 */
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
            var smbFile = s.share.openFile(remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.SYNCHRONIZE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));

            // try-with-resources保证异常时流和SMB文件句柄都被关闭
            try (var fis = new FileInputStream(localFile); var fos = smbFile.getOutputStream()) {
                var total = streamCopy(fis, fos);
                return "OK: " + remotePath + " (" + total + " bytes)";
            } finally {
                try { smbFile.close(); } catch (Exception ignored) {}
            }
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
            var smbFile = s.share.openFile(remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.SYNCHRONIZE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));

            try (var fis = new FileInputStream(localFile); var fos = smbFile.getOutputStream()) {
                var buffer = new byte[BUFFER_SIZE];
                var totalWritten = 0L;
                var lastReportTime = 0L;
                var bytesRead = 0;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    fos.flush();
                    totalWritten += bytesRead;
                    var now = System.currentTimeMillis();
                    if (progressListener != null && (now - lastReportTime >= 200 || totalWritten == fileSize)) {
                        lastReportTime = now;
                        notifyProgress(progressListener, taskId, totalWritten, fileSize);
                    }
                }
            } finally {
                try { smbFile.close(); } catch (Exception ignored) {}
            }

            notifyFinish(progressListener, taskId, 0, "success");
        }
    }

    // ========== 下载 ==========

    /** 下载单个文件从SMB到本地 */
    public static String downloadFile(String remotePath, String localPath) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var smbFile = s.share.openFile(remotePath,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.SYNCHRONIZE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));

            var localFile = new File(localPath);
            var parent = localFile.getParentFile();
            if (parent != null) parent.mkdirs();

            // try-with-resources保证异常时输入输出流和SMB文件句柄都被关闭
            try (var is = smbFile.getInputStream(); var fos = new FileOutputStream(localFile)) {
                var total = streamCopy(is, fos);
                return "OK: " + remotePath + " -> " + localPath + " (" + total + " bytes)";
            } finally {
                try { smbFile.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 从SMB下载单个恢复文件到本地（根据文件名推断路径） */
    public static void downloadFromSmb(String localPath) throws Exception {
        var localFile = new File(localPath);
        var dirName = localFile.getParentFile().getName();
        var fileName = localFile.getName();
        var backupPath = ConfigHelp.getString("backup_path", "");

        if (fileName.equals("restoring")) {
            var descriptFile = new File(localFile.getParent(), "descript.xml");
            if (descriptFile.exists()) {
                var content = new String(java.nio.file.Files.readAllBytes(descriptFile.toPath()));
                var idx = content.indexOf("<bakFile>");
                if (idx > 0) {
                    var endIdx = content.indexOf("</bakFile>", idx);
                    if (endIdx > 0) {
                        var bakFileName = content.substring(idx + 9, endIdx);
                        downloadFile(backupPath + "/" + dirName + "/" + bakFileName, localPath);
                    }
                }
            }
        } else {
            downloadFile(backupPath + "/" + dirName + "/" + fileName, localPath);
        }
    }

    // ========== 恢复辅助 ==========

    /** 从SMB读取所有备份的descript.xml内容到内存 */
    public static List<String> readBackupXmls() throws Exception {
        var xmlList = new ArrayList<String>();
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var backupDirs = new ArrayList<String>();
            for (var entry : s.share.list(s.backupPath)) {
                var name = entry.getFileName();
                if (!name.equals(".") && !name.equals("..") && (entry.getFileAttributes() & 0x10) != 0) {
                    backupDirs.add(name);
                }
            }

            for (var dirName : backupDirs) {
                try {
                    var smbFile = s.share.openFile(s.backupPath + "/" + dirName + "/descript.xml",
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.SYNCHRONIZE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                    // try-with-resources保证SMB文件句柄和输入流被关闭
                    try (var is = smbFile.getInputStream()) {
                        var xml = new String(is.readAllBytes(), "UTF-8");
                        xmlList.add(dirName + "|" + xml);
                    } finally {
                        try { smbFile.close(); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        }
        return xmlList;
    }

    /** 列出备份目录名并下载各自的descript.xml到本地 */
    public static String listAndDownloadXml(String localTempPath) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var backupDirs = new ArrayList<String>();
            for (var entry : s.share.list(s.backupPath)) {
                var name = entry.getFileName();
                if (!name.equals(".") && !name.equals("..") && (entry.getFileAttributes() & 0x10) != 0) {
                    backupDirs.add(name);
                }
            }

            var result = new StringBuilder();
            for (var dirName : backupDirs) {
                try {
                    var localDir = new File(localTempPath, dirName);
                    localDir.mkdirs();

                    var smbFile = s.share.openFile(s.backupPath + "/" + dirName + "/descript.xml",
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.SYNCHRONIZE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));

                    // try-with-resources保证异常时输入输出流和SMB文件句柄都被关闭
                    var localFile = new File(localDir, "descript.xml");
                    try (var is = smbFile.getInputStream(); var fos = new FileOutputStream(localFile)) {
                        streamCopy(is, fos);
                    } finally {
                        try { smbFile.close(); } catch (Exception ignored) {}
                    }

                    var rstFile = new File(localDir, "restoring");
                    if (!rstFile.exists()) rstFile.createNewFile();

                    if (result.length() > 0) result.append(",");
                    result.append(dirName);
                } catch (Exception ignored) {}
            }
            return result.toString();
        }
    }

    // ========== 工具方法 ==========

    /** 流拷贝（不负责关闭流，由调用方用try-with-resources管理） */
    private static long streamCopy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        var buffer = new byte[BUFFER_SIZE];
        var total = 0L;
        var bytesRead = 0;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush();
            total += bytesRead;
        }
        return total;
    }

    /** IFileOperationProgressListener回调：通知进度 */
    private static void notifyProgress(Object listener, String taskId, long current, long total) {
        if (listener == null) return;
        invokeProgress(listener, "D0", new Class[]{String.class, long.class, long.class}, taskId, current, total);
    }

    /** IFileOperationProgressListener回调：通知完成 */
    private static void notifyFinish(Object listener, String taskId, int code, String msg) {
        if (listener == null) return;
        invokeProgress(listener, "l0", new Class[]{String.class, int.class, String.class}, taskId, code, msg);
    }

    /** 兼容新版混淆名和旧版明文名，按参数签名兜底查找回调方法 */
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
