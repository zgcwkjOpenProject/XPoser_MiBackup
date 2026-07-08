package com.zgcwkj.comm;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * WebDAV文件操作工具类
 * 基于OkHttp实现WebDAV协议（PROPFIND/MKCOL/PUT/GET）
 * OkHttp原生支持自定义HTTP方法，无Android兼容性问题
 */
public class WebdavFileHelp {

    private static final String TAG = "XpMiBackup";
    private static final int BUFFER_SIZE = 131072;
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final String XML_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>";

    // PROPFIND成功响应的HTTP状态码（Multi-Status），用于准确判断连接是否真正可达
    private static final int HTTP_MULTISTATUS = 207;

    // 单例OkHttpClient：内部含连接池/线程池，必须全局复用，否则每次new都会重新握手且造成资源泄漏
    private static volatile OkHttpClient sClient;

    // ========== 连接管理 ==========

    /** 获取单例OkHttpClient（信任所有SSL证书，仅适用于内网自签名场景） */
    private static OkHttpClient getClient() {
        if (sClient != null) return sClient;
        synchronized (WebdavFileHelp.class) {
            if (sClient != null) return sClient;
            var trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }
            };
            var builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);
            try {
                var sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                builder.sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((h, s) -> true);
            } catch (Exception ignored) {}
            sClient = builder.build();
            return sClient;
        }
    }

    /** 获取WebDAV基础URL */
    private static String baseUrl() {
        var url = ConfigHelp.getString("webdav_url", "");
        if (!url.endsWith("/")) url += "/";
        return url;
    }

    /** 构建带Basic Auth的Request.Builder */
    private static Request.Builder newRequest(String url) {
        var builder = new Request.Builder().url(url);
        var user = ConfigHelp.getString("webdav_user", "");
        var pass = ConfigHelp.getString("webdav_pass", "");
        if (!user.isEmpty()) {
            builder.header("Authorization", okhttp3.Credentials.basic(user, pass));
        }
        return builder;
    }

    /** 执行PROPFIND请求，返回[状态码, 响应XML] */
    private static String[] propfind(String url, int depth) throws Exception {
        var body = RequestBody.create(XML, XML_BODY);
        var request = newRequest(url).method("PROPFIND", body)
            .header("Depth", String.valueOf(depth)).build();
        try (var resp = getClient().newCall(request).execute()) {
            var xml = resp.body() != null ? resp.body().string() : "";
            return new String[]{ String.valueOf(resp.code()), xml };
        }
    }

    // ========== 公共方法 ==========

    /** 测试WebDAV连接是否可达（必须返回207 Multi-Status才算真正成功） */
    public static boolean testConnection() throws Exception {
        var url = baseUrl();
        var res = propfind(url, 0);
        return Integer.parseInt(res[0]) == HTTP_MULTISTATUS;
    }

    /** 列出backup_path目录中的备份子目录名 */
    public static List<String> listDirs() throws Exception {
        var dirs = new ArrayList<String>();
        var backupPath = ConfigHelp.getString("backup_path", "");
        var entries = listDirectory(backupPath);
        for (var name : entries) {
            if (!name.isEmpty()) dirs.add(name);
        }
        return dirs;
    }

    /** 列出指定路径下的子项名称 */
    private static List<String> listDirectory(String path) throws Exception {
        var names = new ArrayList<String>();
        var url = baseUrl() + path;
        if (!url.endsWith("/")) url += "/";
        // propfind返回[状态码, xml]，取响应体
        var xml = propfind(url, 1)[1];
        // 兼容不同服务器的命名空间前缀（D:/d:/oc:/lp1:/无前缀），大小写不敏感
        // PROPFIND首个href是当前目录本身，跳过它
        var pattern = Pattern.compile("<\\w*:?href>([^<]+)</\\w*:?href>", Pattern.CASE_INSENSITIVE);
        var matcher = pattern.matcher(xml);
        var first = true;
        while (matcher.find()) {
            var href = matcher.group(1);
            var clean = href.replaceAll("/$", "");
            var lastSlash = clean.lastIndexOf('/');
            var name = lastSlash >= 0 ? clean.substring(lastSlash + 1) : clean;
            // 跳过空名和当前目录自身（PROPFIND depth=1第一个href是目录本身）
            if (name.isEmpty()) continue;
            if (first) { first = false; continue; }
            names.add(name);
        }
        return names;
    }

    /** 创建远程目录（MKCOL） */
    public static void mkdir(String path) throws Exception {
        var url = baseUrl() + path;
        if (!url.endsWith("/")) url += "/";
        var request = newRequest(url).method("MKCOL", null).build();
        try (var resp = getClient().newCall(request).execute()) {}
    }

    /** 递归创建目录链 */
    public static void mkdirs(String remoteDir) throws Exception {
        var parts = remoteDir.split("/");
        var current = "";
        for (var part : parts) {
            if (part.isEmpty()) continue;
            current = current.isEmpty() ? part : current + "/" + part;
            try { mkdir(current); } catch (Exception ignored) {}
        }
    }

    /**
     * 删除远端目录及其所有内容
     * 按RFC 4918，WebDAV的DELETE天然递归删除非空集合，无需客户端先列再删
     * 少数不支持递归删除的服务器再走回退方案
     */
    public static void deleteDir(String remoteDir) throws Exception {
        var url = baseUrl() + remoteDir;
        if (!url.endsWith("/")) url += "/";
        var request = newRequest(url).method("DELETE", null).build();
        int code;
        try (var resp = getClient().newCall(request).execute()) {
            code = resp.code();
        }
        // 2xx/3xx视为成功；若服务器不支持递归删除（409/501等），回退到逐项删除
        if (code < 200 || code >= 400) {
            try {
                var entries = listDirectory(remoteDir);
                for (var name : entries) {
                    if (name.isEmpty()) continue;
                    try { deleteDir(remoteDir + "/" + name); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            var retry = newRequest(url).method("DELETE", null).build();
            try (var resp = getClient().newCall(retry).execute()) {}
        }
    }

    /** 删除远端单个文件（WebDAV DELETE） */
    public static void deleteFile(String remotePath) throws Exception {
        var url = baseUrl() + remotePath;
        var request = newRequest(url).method("DELETE", null).build();
        try (var resp = getClient().newCall(request).execute()) {}
    }

    // ========== 上传 ==========

    /** 上传本地文件到WebDAV（无进度回调） */
    public static String upload(String localPath, String remoteDir) throws Exception {
        var localFile = new File(localPath);
        if (!localFile.exists()) throw new java.io.FileNotFoundException("file not found: " + localPath);
        mkdirs(remoteDir);
        var remotePath = (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + localFile.getName();
        var requestBody = RequestBody.create(MediaType.parse("application/octet-stream"), localFile);
        var request = newRequest(baseUrl() + remotePath).put(requestBody).build();
        try (var resp = getClient().newCall(request).execute()) {
            var code = resp.code();
            if (code >= 200 && code < 300) {
                return "OK: " + remotePath + " (" + localFile.length() + " bytes)";
            }
            return "ERROR: HTTP " + code;
        }
    }

    /** 上传文件并回调进度（备份用） */
    public static void uploadToWebdav(String localPath, Object progressListener, String remoteDir, String taskId) throws Exception {
        var localFile = new File(localPath);
        if (!localFile.exists()) throw new java.io.FileNotFoundException("file not found: " + localPath);

        var fileSize = localFile.length();
        invoke(progressListener, "onStart", new Class[]{String.class}, taskId);

        mkdirs(remoteDir);
        var remotePath = (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + localFile.getName();

        // try-with-resources保证异常时fis被关闭，避免文件句柄泄漏
        try (var fis = new FileInputStream(localFile)) {
            var progressBody = new ProgressRequestBody(fis, fileSize, progressListener, taskId);
            var request = newRequest(baseUrl() + remotePath).put(progressBody).build();
            try (var resp = getClient().newCall(request).execute()) {
                var code = resp.code();
                if (code >= 200 && code < 300) {
                    invoke(progressListener, "onFinish", new Class[]{String.class, int.class, String.class}, taskId, 0, "success");
                } else {
                    invoke(progressListener, "onFinish", new Class[]{String.class, int.class, String.class}, taskId, -1, "HTTP " + code);
                }
            }
        }
    }

    /** 带进度回调的RequestBody */
    private static class ProgressRequestBody extends RequestBody {
        private final java.io.InputStream inputStream;
        private final long totalSize;
        private final Object listener;
        private final String taskId;

        ProgressRequestBody(java.io.InputStream is, long totalSize, Object listener, String taskId) {
            this.inputStream = is;
            this.totalSize = totalSize;
            this.listener = listener;
            this.taskId = taskId;
        }

        @Override
        public MediaType contentType() { return MediaType.parse("application/octet-stream"); }

        @Override
        public long contentLength() { return totalSize; }

        @Override
        public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
            // try-with-resources保证okio.Source关闭（它关闭会连带关闭底层inputStream）
            try (var source = okio.Okio.source(inputStream)) {
                var buffer = new okio.Buffer();
                var totalWritten = 0L;
                var lastReportTime = 0L;
                var read = 0L;
                while ((read = source.read(buffer, BUFFER_SIZE)) != -1) {
                    sink.write(buffer, read);
                    totalWritten += read;
                    var now = System.currentTimeMillis();
                    if (listener != null && (now - lastReportTime >= 200 || totalWritten == totalSize)) {
                        lastReportTime = now;
                        invoke(listener, "onProgress", new Class[]{String.class, long.class, long.class}, taskId, totalWritten, totalSize);
                    }
                }
            }
        }
    }

    // ========== 下载 ==========

    /** 下载单个文件从WebDAV到本地 */
    public static String downloadFile(String remotePath, String localPath) throws Exception {
        var request = newRequest(baseUrl() + remotePath).get().build();
        try (var resp = getClient().newCall(request).execute()) {
            var code = resp.code();
            if (code != 200) return "ERROR: HTTP " + code;
            var localFile = new File(localPath);
            var parent = localFile.getParentFile();
            if (parent != null) parent.mkdirs();
            var body = resp.body();
            if (body == null) return "ERROR: empty response body";
            // try-with-resources保证输入输出流都被关闭
            try (var is = body.byteStream(); var fos = new FileOutputStream(localFile)) {
                var total = streamCopy(is, fos);
                return "OK: " + remotePath + " -> " + localPath + " (" + total + " bytes)";
            }
        }
    }

    /** 从WebDAV下载恢复文件到本地 */
    public static void downloadFromWebdav(String localPath) throws Exception {
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

    /** 从WebDAV读取所有备份的descript.xml内容到内存 */
    public static List<String> readBackupXmls() throws Exception {
        var xmlList = new ArrayList<String>();
        var backupPath = ConfigHelp.getString("backup_path", "");
        var backupDirs = listDirectory(backupPath);

        for (var dirName : backupDirs) {
            try {
                var request = newRequest(baseUrl() + backupPath + "/" + dirName + "/descript.xml").get().build();
                try (var resp = getClient().newCall(request).execute()) {
                    if (resp.code() == 200 && resp.body() != null) {
                        var xml = resp.body().string();
                        xmlList.add(dirName + "|" + xml);
                    }
                }
            } catch (Exception ignored) {}
        }
        return xmlList;
    }

    /** 列出备份目录名并下载各自的descript.xml到本地 */
    public static String listAndDownloadXml(String localTempPath) throws Exception {
        var backupPath = ConfigHelp.getString("backup_path", "");
        var backupDirs = listDirectory(backupPath);
        var result = new StringBuilder();

        for (var dirName : backupDirs) {
            try {
                var localDir = new File(localTempPath, dirName);
                localDir.mkdirs();
                var url = baseUrl() + backupPath + "/" + dirName + "/descript.xml";

                // 直接GET：文件不存在时服务器返回404，省掉一次HEAD往返
                var getRequest = newRequest(url).get().build();
                try (var getResp = getClient().newCall(getRequest).execute()) {
                    if (getResp.code() == 200 && getResp.body() != null) {
                        var localFile = new File(localDir, "descript.xml");
                        try (var is = getResp.body().byteStream(); var fos = new FileOutputStream(localFile)) {
                            streamCopy(is, fos);
                        }
                        var rstFile = new File(localDir, "restoring");
                        if (!rstFile.exists()) rstFile.createNewFile();
                    }
                }
                if (result.length() > 0) result.append(",");
                result.append(dirName);
            } catch (Exception ignored) {}
        }
        return result.toString();
    }

    // ========== 工具方法 ==========

    /** 流拷贝（不负责关闭流，由调用方用try-with-resources管理） */
    private static long streamCopy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        var buffer = new byte[BUFFER_SIZE];
        var total = 0L;
        var len = 0;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
            total += len;
        }
        return total;
    }

    private static void invoke(Object obj, String method, Class<?>[] types, Object... args) {
        if (obj == null) return;
        try { obj.getClass().getMethod(method, types).invoke(obj, args); } catch (Exception ignored) {}
    }
}
