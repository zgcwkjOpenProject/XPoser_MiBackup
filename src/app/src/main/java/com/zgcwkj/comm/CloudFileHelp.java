package com.zgcwkj.comm;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 云端文件访问门面
 * 根据config.ini里的协议配置，把文件操作分发到SMB、WebDAV或自定义HTTP实现
 */
public class CloudFileHelp {

    private static final String TAG = "XpMiBackup";
    private static final int BUFFER_SIZE = 1048576;
    private static final int DEFAULT_CHUNK_SIZE_MB = 64;
    private static final int MIN_CHUNK_SIZE_MB = 1;
    private static final int MAX_CHUNK_SIZE_MB = 1024;
    private static final String MANIFEST_SUFFIX = ".mibak.json";

    /**
     * 远端文件条目，用于AIDL层构造小米DFS的SmbFile对象
     */
    public static class RemoteEntry {
        public final String name;
        public final long size;
        public final boolean directory;
        public final long modifiedTime;

        /**
         * 保存远端文件或目录的基础属性
         */
        public RemoteEntry(String name, long size, boolean directory, long modifiedTime) {
            this.name = name;
            this.size = size;
            this.directory = directory;
            this.modifiedTime = modifiedTime;
        }
    }

    /**
     * 测试当前配置的远端存储是否可连接
     */
    public static boolean testConnection() {
        var protocol = getProtocol();
        try {
            if (isCustom()) {
                return CustomHttpFileHelp.testConnection();
            } else if (isWebdav()) {
                return WebdavFileHelp.testConnection();
            } else {
                return SmbFileHelp.testConnection();
            }
        } catch (Exception e) {
            logError("testConnection failed [protocol=" + protocol + "]", e);
            return false;
        }
    }

    /**
     * 列出backup_path下面的备份目录
     */
    public static List<String> listDirs() {
        try {
            if (isCustom()) {
                return CustomHttpFileHelp.listDirs();
            } else if (isWebdav()) {
                return WebdavFileHelp.listDirs();
            } else {
                return SmbFileHelp.listDirs();
            }
        } catch (Exception e) {
            logError("listDirs failed [protocol=" + getProtocol() + "]", e);
            return List.of();
        }
    }

    /**
     * 列出指定远端目录下的文件和文件夹
     */
    public static List<RemoteEntry> listEntries(String remoteDir) {
        try {
            var entries = (List<RemoteEntry>) null;
            if (isCustom()) {
                entries = CustomHttpFileHelp.listEntries(remoteDir);
            } else if (isWebdav()) {
                entries = WebdavFileHelp.listEntries(remoteDir);
            } else {
                entries = SmbFileHelp.listEntries(remoteDir);
            }
            return normalizeChunkEntries(entries);
        } catch (Exception e) {
            logError("listEntries failed [protocol=" + getProtocol() + ", dir=" + remoteDir + "]", e);
            return List.of();
        }
    }

    /**
     * 上传本地文件，不回调小米DFS进度
     */
    public static String upload(String localPath, String remoteDir) {
        try {
            var localFile = new File(localPath);
            if (shouldChunk(localFile.length())) {
                return uploadChunked(localFile, remoteDir, null, "");
            }
            return uploadSingle(localPath, remoteDir);
        } catch (Exception e) {
            logError("upload failed [protocol=" + getProtocol() + "]", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 上传备份文件，并回调小米DFS传输进度
     */
    public static void uploadWithProgress(String localPath, Object progressListener, String remoteDir, String taskId) {
        try {
            var localFile = new File(localPath);
            if (shouldChunk(localFile.length())) {
                uploadChunked(localFile, remoteDir, progressListener, taskId);
                notifyFinish(progressListener, taskId, 0, "success");
            } else {
                uploadSingleWithProgress(localPath, progressListener, remoteDir, taskId);
            }
        } catch (Exception e) {
            logError("uploadWithProgress failed [protocol=" + getProtocol() + "]", e);
            notifyFinish(progressListener, taskId, -1, e.getMessage());
        }
    }

    /**
     * 下载单个远端文件到本地路径
     */
    public static String downloadFile(String remotePath, String localPath) {
        try {
            var result = downloadChunked(remotePath, localPath);
            if (result != null) {
                return result;
            }
            return downloadSingle(remotePath, localPath);
        } catch (Exception e) {
            logError("downloadFile failed [protocol=" + getProtocol() + "]", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 按恢复侧本地路径推导远端路径并下载文件
     */
    public static void downloadFromCloud(String localPath) {
        try {
            var remotePath = remotePathFromRestoreLocal(localPath);
            if (remotePath != null && !remotePath.isEmpty()) {
                downloadFile(remotePath, localPath);
            }
        } catch (Exception e) {
            logError("downloadFromCloud failed [protocol=" + getProtocol() + "]", e);
        }
    }

    /**
     * 读取所有备份目录里的descript.xml内容
     */
    public static List<String> readBackupXmls() {
        try {
            var xmlList = new java.util.ArrayList<String>();
            var backupPath = ConfigHelp.getString("backup_path", "");
            for (var dirName : listDirs()) {
                var local = File.createTempFile("descript", ".xml");
                try {
                    var result = downloadFile(remotePath(backupDirPath(backupPath, dirName), "descript.xml"), local.getAbsolutePath());
                    if (result != null && !result.startsWith("ERROR:")) {
                        var xml = new String(java.nio.file.Files.readAllBytes(local.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                        xmlList.add(dirName + "|" + xml);
                    }
                } catch (Exception ignored) {
                } finally {
                    deleteTempFile(local);
                }
            }
            return xmlList;
        } catch (Exception e) {
            logError("readBackupXmls failed [protocol=" + getProtocol() + "]", e);
            return List.of();
        }
    }

    /**
     * 列出备份目录，并把每个descript.xml下载到本地临时目录
     */
    public static String listAndDownloadXml(String localTempPath) {
        try {
            var backupPath = ConfigHelp.getString("backup_path", "");
            var result = new StringBuilder();
            for (var dirName : listDirs()) {
                try {
                    var localDir = new File(localTempPath, dirName);
                    localDir.mkdirs();
                    var localFile = new File(localDir, "descript.xml");
                    var downloaded = downloadFile(remotePath(backupDirPath(backupPath, dirName), "descript.xml"), localFile.getAbsolutePath());
                    if (downloaded != null && !downloaded.startsWith("ERROR:")) {
                        var rstFile = new File(localDir, "restoring");
                        if (!rstFile.exists()) rstFile.createNewFile();
                        if (result.length() > 0) result.append(",");
                        result.append(dirName);
                    }
                } catch (Exception ignored) {
                }
            }
            return result.toString();
        } catch (Exception e) {
            logError("listAndDownloadXml failed [protocol=" + getProtocol() + "]", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 删除远端目录及其所有内容
     */
    public static void deleteRemoteDir(String remoteDir) {
        try {
            if (isCustom()) {
                CustomHttpFileHelp.deleteDir(remoteDir);
            } else if (isWebdav()) {
                WebdavFileHelp.deleteDir(remoteDir);
            } else {
                SmbFileHelp.deleteDir(remoteDir);
            }
        } catch (Exception e) {
            logError("deleteRemoteDir failed [protocol=" + getProtocol() + "]", e);
        }
    }

    /**
     * 只保留backup_max个最新备份目录；backup_max小于等于0表示不限制
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
                deleteRemoteDir(backupDirPath(backupPath, dir));
            }
        } catch (Exception e) {
            logError("cleanupOldBackups failed", e);
        }
    }

    /**
     * 从配置中读取当前协议名称
     */
    public static String getProtocol() {
        return ConfigHelp.getString("protocol", "smb");
    }

    /**
     * 判断当前协议是否为WebDAV
     */
    private static boolean isWebdav() {
        return "webdav".equals(ConfigHelp.getString("protocol", "smb"));
    }

    /**
     * 判断当前协议是否为自定义HTTP脚本
     */
    private static boolean isCustom() {
        return "custom".equals(ConfigHelp.getString("protocol", "smb"));
    }

    // ========== 通用切片 ==========

    /**
     * 通用切片上传：Cloud层生成part和manifest，底层协议只负责上传普通文件
     */
    private static String uploadChunked(File localFile, String remoteDir, Object listener, String taskId) throws Exception {
        if (!localFile.exists()) {
            throw new java.io.FileNotFoundException("file not found: " + localFile.getAbsolutePath());
        }
        var fileSize = localFile.length();
        var chunkSize = chunkSizeBytes();
        var totalParts = Math.max(1L, (fileSize + chunkSize - 1L) / chunkSize);
        var remotePath = remotePath(remoteDir, localFile.getName());
        var tempDir = localFile.getParentFile();
        var buffer = new byte[BUFFER_SIZE];
        var totalWritten = 0L;

        try (var fis = new FileInputStream(localFile)) {
            for (var i = 0L; i < totalParts; i++) {
                var partName = localFile.getName() + ".part" + partName(i);
                var partFile = new File(tempDir, partName);
                var remaining = Math.min(chunkSize, fileSize - totalWritten);
                try {
                    try (var fos = new FileOutputStream(partFile)) {
                        while (remaining > 0) {
                            var read = fis.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (read == -1) {
                                break;
                            }
                            fos.write(buffer, 0, read);
                            remaining -= read;
                            totalWritten += read;
                        }
                    }
                    var uploadResult = uploadSingle(partFile.getAbsolutePath(), remoteDir);
                    if (uploadResult != null && uploadResult.startsWith("ERROR:")) {
                        throw new IllegalStateException(uploadResult);
                    }
                    notifyProgress(listener, taskId, totalWritten, fileSize);
                } finally {
                    deleteTempFile(partFile);
                }
            }
        }

        var manifest = new JSONObject();
        manifest.put("version", 1);
        manifest.put("name", localFile.getName());
        manifest.put("size", fileSize);
        manifest.put("chunkSize", chunkSize);
        manifest.put("parts", totalParts);
        manifest.put("createdTime", System.currentTimeMillis());

        var manifestFile = new File(tempDir, localFile.getName() + MANIFEST_SUFFIX);
        try {
            try (var fos = new FileOutputStream(manifestFile)) {
                fos.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            var uploadResult = uploadSingle(manifestFile.getAbsolutePath(), remoteDir);
            if (uploadResult != null && uploadResult.startsWith("ERROR:")) {
                throw new IllegalStateException(uploadResult);
            }
        } finally {
            deleteTempFile(manifestFile);
        }
        return "OK: " + remotePath + " (" + fileSize + " bytes, chunked)";
    }

    /**
     * 若远端存在manifest，则按分片顺序下载并合并；不存在时返回null
     */
    private static String downloadChunked(String remotePath, String localPath) throws Exception {
        var localFile = new File(localPath);
        var parent = localFile.getParentFile();
        if (parent != null) parent.mkdirs();

        var manifestFile = File.createTempFile("mibak_manifest", ".json", parent);
        try {
            var manifestResult = downloadSingle(remotePath + MANIFEST_SUFFIX, manifestFile.getAbsolutePath());
            if (manifestResult == null || manifestResult.startsWith("ERROR:")) {
                return null;
            }
            var manifest = new JSONObject(new String(java.nio.file.Files.readAllBytes(manifestFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            var parts = manifest.optLong("parts", 0L);
            if (parts <= 0) {
                return null;
            }
            try (var out = new FileOutputStream(localFile)) {
                for (var i = 0L; i < parts; i++) {
                    var partFile = File.createTempFile("mibak_part", ".tmp", parent);
                    try {
                        var partResult = downloadSingle(remotePath + ".part" + partName(i), partFile.getAbsolutePath());
                        if (partResult == null || partResult.startsWith("ERROR:")) {
                            throw new IllegalStateException(partResult);
                        }
                        try (var in = new FileInputStream(partFile)) {
                            streamCopy(in, out);
                        }
                    } finally {
                        deleteTempFile(partFile);
                    }
                }
            }
            return "OK: " + remotePath + " -> " + localPath + " (chunked)";
        } finally {
            deleteTempFile(manifestFile);
        }
    }

    /**
     * 隐藏part文件，并将manifest展示成原文件名，避免恢复列表看到内部文件
     */
    private static List<RemoteEntry> normalizeChunkEntries(List<RemoteEntry> entries) {
        var normalized = new java.util.ArrayList<RemoteEntry>();
        var visibleNames = new java.util.HashSet<String>();
        for (var entry : entries) {
            if (entry.name == null || entry.name.isEmpty()) continue;
            if (entry.name.matches(".*\\.part\\d{5,}$")) continue;
            if (!entry.directory && entry.name.endsWith(MANIFEST_SUFFIX)) {
                var name = entry.name.substring(0, entry.name.length() - MANIFEST_SUFFIX.length());
                if (visibleNames.add(name)) {
                    normalized.add(new RemoteEntry(name, entry.size, false, entry.modifiedTime));
                }
                continue;
            }
            if (visibleNames.add(entry.name)) {
                normalized.add(entry);
            }
        }
        return normalized;
    }

    /** 上传单个普通文件，不在Cloud层做切片 */
    private static String uploadSingle(String localPath, String remoteDir) throws Exception {
        if (isCustom()) {
            return CustomHttpFileHelp.upload(localPath, remoteDir);
        } else if (isWebdav()) {
            return WebdavFileHelp.upload(localPath, remoteDir);
        } else {
            return SmbFileHelp.upload(localPath, remoteDir);
        }
    }

    /** 上传单个普通文件，并交给底层协议回调细粒度进度 */
    private static void uploadSingleWithProgress(String localPath, Object progressListener, String remoteDir, String taskId) throws Exception {
        if (isCustom()) {
            CustomHttpFileHelp.uploadWithProgress(localPath, progressListener, remoteDir, taskId);
        } else if (isWebdav()) {
            WebdavFileHelp.uploadToWebdav(localPath, progressListener, remoteDir, taskId);
        } else {
            SmbFileHelp.uploadToSmb(localPath, progressListener, remoteDir, taskId);
        }
    }

    /** 下载单个普通远端文件，不在Cloud层处理manifest */
    private static String downloadSingle(String remotePath, String localPath) throws Exception {
        if (isCustom()) {
            return CustomHttpFileHelp.downloadFile(remotePath, localPath);
        } else if (isWebdav()) {
            return WebdavFileHelp.downloadFile(remotePath, localPath);
        } else {
            return SmbFileHelp.downloadFile(remotePath, localPath);
        }
    }

    /** 根据恢复侧本地文件推导云端路径 */
    private static String remotePathFromRestoreLocal(String localPath) throws Exception {
        var localFile = new File(localPath);
        var parent = localFile.getParentFile();
        if (parent == null) {
            return null;
        }
        var dirName = parent.getName();
        var fileName = localFile.getName();
        var backupPath = ConfigHelp.getString("backup_path", "");
        var remoteDir = backupDirPath(backupPath, dirName);
        if (!fileName.equals("restoring")) {
            return remotePath(remoteDir, fileName);
        }

        var descriptFile = new File(parent, "descript.xml");
        if (!descriptFile.exists()) {
            return null;
        }
        var content = new String(java.nio.file.Files.readAllBytes(descriptFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        var idx = content.indexOf("<bakFile>");
        if (idx < 0) {
            return null;
        }
        var endIdx = content.indexOf("</bakFile>", idx);
        if (endIdx <= 0) {
            return null;
        }
        var bakFileName = content.substring(idx + 9, endIdx);
        return remotePath(remoteDir, bakFileName);
    }

    /** 合成备份目录路径 */
    private static String backupDirPath(String backupPath, String dirName) {
        return backupPath == null || backupPath.isEmpty() ? dirName : backupPath + "/" + dirName;
    }

    /** 只有大于切片大小的文件才切片；0表示关闭切片 */
    private static boolean shouldChunk(long fileSize) {
        var chunkSize = chunkSizeBytes();
        return chunkSize > 0 && fileSize > chunkSize;
    }

    /** 读取并限制切片大小；0表示不切片，负数按默认值处理 */
    private static long chunkSizeBytes() {
        var mb = ConfigHelp.getInt("chunk_size_mb", DEFAULT_CHUNK_SIZE_MB);
        if (mb == 0) return 0L;
        if (mb < MIN_CHUNK_SIZE_MB) {
            mb = DEFAULT_CHUNK_SIZE_MB;
        } else if (mb > MAX_CHUNK_SIZE_MB) {
            mb = MAX_CHUNK_SIZE_MB;
        }
        return mb * 1024L * 1024L;
    }

    /** 合成远端文件路径 */
    private static String remotePath(String remoteDir, String fileName) {
        return (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + fileName;
    }

    /** 分片编号至少5位，确保字典序和上传顺序一致 */
    private static String partName(long index) {
        return String.format(java.util.Locale.ROOT, "%05d", index);
    }

    /** 拷贝流内容，调用方负责关闭输入输出流 */
    private static void streamCopy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        var buffer = new byte[BUFFER_SIZE];
        var len = 0;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
    }

    /** 删除临时切片文件，失败时只记录日志 */
    private static void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            logError("delete temp chunk file failed", new IllegalStateException(file.getAbsolutePath()));
        }
    }

    /** 通知IFileOperationProgressListener.onProgress或混淆后的等价方法 */
    private static void notifyProgress(Object listener, String taskId, long current, long total) {
        if (listener == null) return;
        try {
            listener.getClass().getMethod("D0", String.class, long.class, long.class).invoke(listener, taskId, current, total);
        } catch (Exception e) {
            try {
                for (var method : listener.getClass().getMethods()) {
                    if (method.getParameterCount() == 3
                        && method.getParameterTypes()[0] == String.class
                        && method.getParameterTypes()[1] == long.class
                        && method.getParameterTypes()[2] == long.class
                        && method.getReturnType() == void.class) {
                        method.invoke(listener, taskId, current, total);
                        return;
                    }
                }
            } catch (Exception fallbackError) {
                logError("notifyProgress fallback failed", fallbackError);
            }
        }
    }

    /**
     * 通知IFileOperationProgressListener.onFinish或混淆后的等价方法
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
     * 统一记录云端文件操作异常
     */
    private static void logError(String message, Exception e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
}
