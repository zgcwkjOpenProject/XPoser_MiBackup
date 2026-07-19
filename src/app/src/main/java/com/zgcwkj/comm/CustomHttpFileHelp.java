package com.zgcwkj.comm;

import org.json.JSONArray;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.zgcwkj.xpmibackup.R;

/**
 * 自定义HTTP存储适配器
 * 用户脚本只生成请求描述，文件读取、切片、进度和合并仍由Java负责
 */
public class CustomHttpFileHelp {

    private static final String TAG = "XpMiBackup";
    private static final String MODULE_PACKAGE = "com.zgcwkj.xpmibackup";
    private static final int BUFFER_SIZE = 1048576;
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private static volatile OkHttpClient sClient;

    private static volatile String sDefaultScript;

    /** 设置页首次打开时展示的脚本模板；实际内容维护在res/raw/custom_http_default.js */
    public static String getDefaultScript() {
        return getDefaultScript(null);
    }

    /** 从指定Context读取默认脚本，适合设置页在模块自身进程中调用 */
    public static String getDefaultScript(android.content.Context context) {
        var cached = sDefaultScript;
        if (cached != null) {
            return cached;
        }
        synchronized (CustomHttpFileHelp.class) {
            if (sDefaultScript == null) {
                sDefaultScript = readDefaultScript(context);
            }
            return sDefaultScript;
        }
    }

    // ========== 公共方法 ==========

    /** 测试自定义脚本生成的连接请求是否返回2xx/3xx */
    public static boolean testConnection() throws Exception {
        var ctx = baseCtx();
        ctx.put("remoteDir", ConfigHelp.getString("backup_path", ""));
        var resp = execute(callRequestFunction("testConnection", ctx, null), null);
        closeQuietly(resp);
        return resp.code >= 200 && resp.code < 400;
    }

    /** 列出备份根目录下的备份目录名 */
    public static List<String> listDirs() throws Exception {
        var dirs = new ArrayList<String>();
        var backupPath = ConfigHelp.getString("backup_path", "");
        for (var entry : listEntries(backupPath)) {
            if (entry.directory && entry.name != null && !entry.name.isEmpty()) {
                dirs.add(entry.name);
            }
        }
        return dirs;
    }

    /** 通过脚本的listEntries/parseList函数读取远端目录条目 */
    public static List<CloudFileHelp.RemoteEntry> listEntries(String remoteDir) throws Exception {
        var ctx = baseCtx();
        ctx.put("remoteDir", remoteDir != null ? remoteDir : "");
        var resp = execute(callRequestFunction("listEntries", ctx, null), null);
        try {
            if (resp.code < 200 || resp.code >= 400) {
                return List.of();
            }
            var response = new LinkedHashMap<String, Object>();
            response.put("code", resp.code);
            response.put("body", resp.body);
            response.put("headers", resp.headers);
            var parsed = callOptionalFunction("parseList", ctx, response);
            return toEntries(parsed != null ? parsed : resp.body);
        } finally {
            closeQuietly(resp);
        }
    }

    /** 上传单个普通文件，切片由CloudFileHelp统一处理 */
    public static String upload(String localPath, String remoteDir) throws Exception {
        var localFile = new File(localPath);
        uploadFile(localFile, remoteDir, null, "");
        var remotePath = remotePath(remoteDir, localFile.getName());
        return "OK: " + remotePath + " (" + localFile.length() + " bytes)";
    }

    /** 上传单个普通文件并回调进度，切片由CloudFileHelp统一处理 */
    public static void uploadWithProgress(String localPath, Object progressListener, String remoteDir, String taskId) throws Exception {
        var localFile = new File(localPath);
        uploadFile(localFile, remoteDir, progressListener, taskId);
        notifyFinish(progressListener, taskId, 0, "success");
    }

    /** 下载单个普通文件，manifest合并由CloudFileHelp统一处理 */
    public static String downloadFile(String remotePath, String localPath) throws Exception {
        var directResp = execute(callRequestFunction("downloadFile", withRemotePath(remotePath), null), null, false);
        try {
            if (directResp.code < 200 || directResp.code >= 300 || directResp.response == null || directResp.response.body() == null) {
                return "ERROR: HTTP " + directResp.code;
            }
            var localFile = new File(localPath);
            var parent = localFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (var is = directResp.response.body().byteStream(); var fos = new FileOutputStream(localFile)) {
                copyStream(is, fos);
            }
            return "OK: " + remotePath + " -> " + localPath;
        } finally {
            closeQuietly(directResp);
        }
    }

    /** 删除远端目录；递归行为由用户脚本对应的服务器接口决定 */
    public static void deleteDir(String remoteDir) throws Exception {
        var ctx = withRemotePath(remoteDir);
        var resp = execute(callRequestFunction("deletePath", ctx, null), null);
        closeQuietly(resp);
    }

    // ========== 上传下载实现 ==========

    /** 上传单个普通文件，脚本只负责生成请求描述 */
    private static void uploadFile(File localFile, String remoteDir, Object listener, String taskId) throws Exception {
        if (!localFile.exists()) throw new FileNotFoundException("file not found: " + localFile.getAbsolutePath());

        var fileSize = localFile.length();
        var remotePath = remotePath(remoteDir, localFile.getName());
        var ctx = withRemotePath(remotePath);
        ctx.put("remoteDir", remoteDir != null ? remoteDir : "");
        ctx.put("fileName", localFile.getName());
        ctx.put("fileSize", fileSize);

        var body = new FileRequestBody(localFile, listener, taskId);
        var resp = execute(callRequestFunction("uploadFile", ctx, null), body);
        try {
            if (resp.code < 200 || resp.code >= 300) {
                throw new IOException("upload file failed: HTTP " + resp.code);
            }
        } finally {
            closeQuietly(resp);
        }
    }

    // ========== 脚本与HTTP ==========

    /** 执行脚本生成的HTTP请求；默认会把响应体读入内存，适合小响应 */
    private static ScriptResponse execute(RequestSpec spec, RequestBody streamBody) throws Exception {
        return execute(spec, streamBody, true);
    }

    /** 执行HTTP请求；下载大文件/分片时设置readBody=false以保持流式读取 */
    private static ScriptResponse execute(RequestSpec spec, RequestBody streamBody, boolean readBody) throws Exception {
        if (spec == null || spec.url == null || spec.url.isEmpty()) {
            throw new IllegalArgumentException("custom script returned empty request url");
        }
        var builder = new Request.Builder().url(spec.url);
        for (var entry : spec.headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        var method = spec.method != null ? spec.method.toUpperCase(Locale.ROOT) : "GET";
        var body = streamBody != null ? streamBody : bodyFromSpec(spec);
        builder.method(method, methodAllowsRequestBody(method) ? body : null);
        var response = getClient().newCall(builder.build()).execute();
        var result = new ScriptResponse();
        result.code = response.code();
        result.response = response;
        result.headers = new LinkedHashMap<>();
        for (var name : response.headers().names()) {
            result.headers.put(name, response.header(name, ""));
        }
        if (readBody && response.body() != null) {
            result.body = response.body().string();
            response.close();
            result.response = null;
        }
        return result;
    }

    /** 将脚本返回的字符串body转换成OkHttp RequestBody */
    private static RequestBody bodyFromSpec(RequestSpec spec) {
        if (spec.body == null) {
            return null;
        }
        var contentType = spec.headers.get("Content-Type");
        var mediaType = contentType != null ? MediaType.parse(contentType) : MediaType.parse("text/plain; charset=utf-8");
        return RequestBody.create(mediaType, spec.body);
    }

    /** OkHttp要求GET/HEAD不能携带请求体 */
    private static boolean methodAllowsRequestBody(String method) {
        return !"GET".equals(method) && !"HEAD".equals(method);
    }

    /** 调用必须返回HTTP请求对象的脚本函数 */
    private static RequestSpec callRequestFunction(String functionName, Map<String, Object> ctx, Map<String, Object> response) throws Exception {
        var result = callFunction(functionName, ctx, response, true);
        return toRequestSpec(result);
    }

    /** 调用可选脚本函数，例如parseList不存在时让调用方走默认处理 */
    private static Object callOptionalFunction(String functionName, Map<String, Object> ctx, Map<String, Object> response) throws Exception {
        return callFunction(functionName, ctx, response, false);
    }

    /** 在Rhino解释模式中执行脚本函数，并关闭Java类访问以降低误用风险 */
    private static Object callFunction(String functionName, Map<String, Object> ctxMap, Map<String, Object> responseMap, boolean required) throws Exception {
        var cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setClassShutter(className -> false);
            var scope = cx.initStandardObjects();
            cx.evaluateString(scope, script(), "custom-storage.js", 1, null);
            var fn = ScriptableObject.getProperty(scope, functionName);
            if (!(fn instanceof org.mozilla.javascript.Function)) {
                if (required) {
                    throw new IllegalArgumentException("missing custom script function: " + functionName);
                }
                return null;
            }
            var args = responseMap != null
                ? new Object[]{toNativeObject(scope, ctxMap), toNativeObject(scope, responseMap)}
                : new Object[]{toNativeObject(scope, ctxMap)};
            return ((org.mozilla.javascript.Function) fn).call(cx, scope, scope, args);
        } finally {
            Context.exit();
        }
    }

    /** 将Java Map转成脚本可读取的普通对象 */
    private static Scriptable toNativeObject(Scriptable scope, Map<String, Object> map) {
        var obj = Context.getCurrentContext().newObject(scope);
        for (var entry : map.entrySet()) {
            ScriptableObject.putProperty(obj, entry.getKey(), Context.javaToJS(entry.getValue(), scope));
        }
        return obj;
    }

    /** 将脚本返回对象规整成内部HTTP请求描述 */
    private static RequestSpec toRequestSpec(Object value) {
        if (!(value instanceof Scriptable)) {
            throw new IllegalArgumentException("custom script must return a request object");
        }
        var obj = (Scriptable) value;
        var spec = new RequestSpec();
        spec.method = stringProperty(obj, "method", "GET");
        spec.url = stringProperty(obj, "url", "");
        spec.body = stringProperty(obj, "body", null);
        var headers = ScriptableObject.getProperty(obj, "headers");
        if (headers instanceof Scriptable) {
            for (var id : ((Scriptable) headers).getIds()) {
                var key = String.valueOf(id);
                var headerValue = ScriptableObject.getProperty((Scriptable) headers, key);
                if (headerValue != null && headerValue != Scriptable.NOT_FOUND) {
                    spec.headers.put(key, Context.toString(headerValue));
                }
            }
        }
        return spec;
    }

    /** 将脚本数组或JSON数组转换成统一的远端目录条目 */
    private static List<CloudFileHelp.RemoteEntry> toEntries(Object value) throws Exception {
        if (value instanceof CharSequence) {
            var text = value.toString().trim();
            if (text.isEmpty()) {
                return List.of();
            }
            return toEntries(new JSONArray(text));
        }
        if (value instanceof NativeArray) {
            var array = (NativeArray) value;
            var entries = new ArrayList<CloudFileHelp.RemoteEntry>();
            var length = array.getLength();
            for (var i = 0L; i < length; i++) {
                var item = array.get((int) i, array);
                var entry = entryFromScript(item);
                if (entry != null) entries.add(entry);
            }
            return entries;
        }
        if (value instanceof JSONArray) {
            var array = (JSONArray) value;
            var entries = new ArrayList<CloudFileHelp.RemoteEntry>();
            for (var i = 0; i < array.length(); i++) {
                var item = array.optJSONObject(i);
                if (item == null) continue;
                entries.add(new CloudFileHelp.RemoteEntry(
                    item.optString("name", ""),
                    item.optLong("size", 0L),
                    item.optBoolean("directory", false),
                    item.optLong("modifiedTime", System.currentTimeMillis())));
            }
            return entries;
        }
        return List.of();
    }

    /** 解析脚本返回的单个目录条目对象 */
    private static CloudFileHelp.RemoteEntry entryFromScript(Object value) {
        if (!(value instanceof Scriptable)) {
            return null;
        }
        var obj = (Scriptable) value;
        return new CloudFileHelp.RemoteEntry(
            stringProperty(obj, "name", ""),
            longProperty(obj, "size", 0L),
            booleanProperty(obj, "directory", false),
            longProperty(obj, "modifiedTime", System.currentTimeMillis()));
    }

    private static String stringProperty(Scriptable obj, String name, String def) {
        var value = ScriptableObject.getProperty(obj, name);
        return value == null || value == Scriptable.NOT_FOUND ? def : Context.toString(value);
    }

    private static long longProperty(Scriptable obj, String name, long def) {
        try {
            var value = ScriptableObject.getProperty(obj, name);
            return value == null || value == Scriptable.NOT_FOUND ? def : ((Number) Context.jsToJava(value, Long.class)).longValue();
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean booleanProperty(Scriptable obj, String name, boolean def) {
        var value = ScriptableObject.getProperty(obj, name);
        return value == null || value == Scriptable.NOT_FOUND ? def : Context.toBoolean(value);
    }

    // ========== 工具方法 ==========

    /** 懒加载OkHttpClient，复用连接池避免频繁握手 */
    private static OkHttpClient getClient() {
        if (sClient != null) return sClient;
        synchronized (CustomHttpFileHelp.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
            return sClient;
        }
    }

    /** 构造传给脚本的基础上下文 */
    private static Map<String, Object> baseCtx() {
        var ctx = new LinkedHashMap<String, Object>();
        ctx.put("backupPath", ConfigHelp.getString("backup_path", ""));
        return ctx;
    }

    /** 构造包含远端文件路径的脚本上下文 */
    private static Map<String, Object> withRemotePath(String remotePath) {
        var ctx = baseCtx();
        ctx.put("remotePath", remotePath != null ? remotePath : "");
        return ctx;
    }

    /** 从配置读取用户脚本；未配置或解码失败时使用默认示例脚本 */
    private static String script() {
        var b64 = ConfigHelp.getString("custom_script_b64", "");
        if (b64 == null || b64.isEmpty()) {
            return getDefaultScript();
        }
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LogHelp.e(TAG, "decode custom script failed", e);
            return getDefaultScript();
        }
    }

    /** 读取raw资源中的默认脚本；无Context时返回短兜底脚本，正常设置页会传入Context */
    private static String readDefaultScript(android.content.Context context) {
        try {
            if (context == null) {
                throw new IllegalStateException("context is null");
            }
            var sourceContext = context.createPackageContext(MODULE_PACKAGE, android.content.Context.CONTEXT_IGNORE_SECURITY);
            try (var is = sourceContext.getResources().openRawResource(R.raw.custom_http_default)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "read default custom script failed", e);
            return "function testConnection(ctx){return {method:'GET',url:'http://127.0.0.1/'};}\n";
        }
    }

    /** 合成远端文件路径 */
    private static String remotePath(String remoteDir, String fileName) {
        return (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + fileName;
    }

    /** 简单流拷贝，调用方负责关闭输入输出流 */
    private static void copyStream(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        var buffer = new byte[BUFFER_SIZE];
        var len = 0;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
    }

    /** 关闭HTTP响应，释放连接 */
    private static void closeQuietly(ScriptResponse response) {
        if (response != null && response.response != null) {
            response.response.close();
        }
    }

    /** 通知小米备份当前上传进度 */
    private static void notifyProgress(Object listener, String taskId, long current, long total) {
        if (listener == null) return;
        invokeProgress(listener, "D0", new Class[]{String.class, long.class, long.class}, taskId, current, total);
    }

    /** 通知小米备份任务完成或失败 */
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

    /** 脚本返回的HTTP请求描述 */
    private static class RequestSpec {
        String method;
        String url;
        String body;
        final Map<String, String> headers = new LinkedHashMap<>();
    }

    /** HTTP响应的轻量包装，支持小响应缓存和大响应流式读取两种模式 */
    private static class ScriptResponse {
        int code;
        String body = "";
        Response response;
        Map<String, String> headers = Map.of();
    }

    /** 流式读取本地文件作为请求体，并在写入时回调进度 */
    private static class FileRequestBody extends RequestBody {
        private final File file;
        private final Object listener;
        private final String taskId;

        FileRequestBody(File file, Object listener, String taskId) {
            this.file = file;
            this.listener = listener;
            this.taskId = taskId;
        }

        @Override
        public MediaType contentType() {
            return OCTET_STREAM;
        }

        @Override
        public long contentLength() {
            return file.length();
        }

        @Override
        public void writeTo(okio.BufferedSink sink) throws IOException {
            var buffer = new byte[BUFFER_SIZE];
            var total = file.length();
            var written = 0L;
            var lastReportTime = 0L;
            try (var in = new java.io.FileInputStream(file)) {
                var read = 0;
                while ((read = in.read(buffer)) != -1) {
                    sink.write(buffer, 0, read);
                    written += read;
                    var now = System.currentTimeMillis();
                    if (listener != null && (now - lastReportTime >= 200 || written == total)) {
                        lastReportTime = now;
                        notifyProgress(listener, taskId, written, total);
                    }
                }
            }
        }
    }
}
