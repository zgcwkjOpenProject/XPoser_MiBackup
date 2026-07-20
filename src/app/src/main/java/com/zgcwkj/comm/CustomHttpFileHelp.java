package com.zgcwkj.comm;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * 用户脚本负责描述或直接完成具体HTTP操作，文件切片和manifest合并由CloudFileHelp统一处理
 */
public class CustomHttpFileHelp {

    private static final String TAG = "XpMiBackup";
    private static final String MODULE_PACKAGE = "com.zgcwkj.xpmibackup";
    private static final int BUFFER_SIZE = 1048576;
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private static volatile OkHttpClient sClient;

    private static volatile String sDefaultScript;
    private static final ThreadLocal<UploadContext> sUploadContext = new ThreadLocal<>();
    private static final ThreadLocal<DownloadContext> sDownloadContext = new ThreadLocal<>();

    /** 设置页首次打开时展示的脚本模板；实际内容维护在res/raw/custom_default.js */
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

    /** 测试自定义脚本连接；具体请求和成功条件都由脚本判断，必须返回true或false */
    public static boolean testConnection() throws Exception {
        var ctx = baseCtx();
        ctx.put("remoteDir", ConfigHelp.getString("backup_path", ""));
        var result = callFunction("testConnection", ctx, null, true);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        throw new IllegalArgumentException("custom script testConnection must return true or false");
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

    /** 通过脚本读取远端目录条目；支持直接返回条目数组或返回请求对象后交给parseList解析 */
    public static List<CloudFileHelp.RemoteEntry> listEntries(String remoteDir) throws Exception {
        var ctx = baseCtx();
        ctx.put("remoteDir", remoteDir != null ? remoteDir : "");
        var result = callFunction("listEntries", ctx, null, true);
        if (!isRequestObject(result)) {
            return toEntries(result);
        }
        var resp = execute(toRequestSpec(result), null);
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
        var ctx = withRemotePath(remotePath);
        ctx.put("localPath", localPath != null ? localPath : "");
        sDownloadContext.set(new DownloadContext(new File(localPath)));
        try {
            var result = callFunction("downloadFile", ctx, null, true);
            if (isHandledResult(result)) {
                return "OK: " + remotePath + " -> " + localPath;
            }
            var directResp = execute(toRequestSpec(result), null, false);
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
        } finally {
            sDownloadContext.remove();
        }
    }

    /** 删除远端目录；递归行为由用户脚本对应的服务器接口决定 */
    public static void deleteDir(String remoteDir) throws Exception {
        var ctx = withRemotePath(remoteDir);
        var result = callFunction("deletePath", ctx, null, true);
        if (isHandledResult(result)) {
            return;
        }
        var resp = execute(toRequestSpec(result), null);
        closeQuietly(resp);
    }

    // ========== 上传下载实现 ==========

    /** 上传单个普通文件；脚本可以自行完成，也可以返回HTTP请求描述交给Java执行 */
    private static void uploadFile(File localFile, String remoteDir, Object listener, String taskId) throws Exception {
        if (!localFile.exists()) throw new FileNotFoundException("file not found: " + localFile.getAbsolutePath());

        var fileSize = localFile.length();
        var remotePath = remotePath(remoteDir, localFile.getName());
        var ctx = withRemotePath(remotePath);
        ctx.put("remoteDir", remoteDir != null ? remoteDir : "");
        ctx.put("fileName", localFile.getName());
        ctx.put("fileSize", fileSize);
        ctx.put("contentHash", sha256(localFile));
        ctx.put("contentHashAlgorithm", "SHA256");

        sUploadContext.set(new UploadContext(localFile, listener, taskId));
        try {
            var result = callFunction("uploadFile", ctx, null, true);
            if (isHandledResult(result)) {
                return;
            }
            var body = new FileRequestBody(localFile, listener, taskId);
            var resp = execute(toRequestSpec(result), body);
            try {
                if (resp.code < 200 || resp.code >= 300) {
                    throw new IOException("upload file failed: HTTP " + resp.code);
                }
            } finally {
                closeQuietly(resp);
            }
        } finally {
            sUploadContext.remove();
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
            installUtilityFunctions(scope);
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
        spec.streamFile = optionalBooleanProperty(obj, "streamFile");
        spec.readBody = optionalBooleanProperty(obj, "readBody");
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

    /** 判断脚本是否已经自己完成了当前操作 */
    private static boolean isHandledResult(Object value) {
        if (!(value instanceof Scriptable)) {
            return false;
        }
        return booleanProperty((Scriptable) value, "handled", false);
    }

    /** 判断脚本返回值是否是Java可继续执行的HTTP请求对象 */
    private static boolean isRequestObject(Object value) {
        if (!(value instanceof Scriptable)) {
            return false;
        }
        var obj = (Scriptable) value;
        return hasProperty(obj, "url") || hasProperty(obj, "method") || hasProperty(obj, "headers") || hasProperty(obj, "body");
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

    /** 从脚本对象读取字符串属性，缺省时返回默认值 */
    private static String stringProperty(Scriptable obj, String name, String def) {
        var value = ScriptableObject.getProperty(obj, name);
        return value == null || value == Scriptable.NOT_FOUND ? def : Context.toString(value);
    }

    /** 从脚本对象读取长整型属性，失败时返回默认值 */
    private static long longProperty(Scriptable obj, String name, long def) {
        try {
            var value = ScriptableObject.getProperty(obj, name);
            return value == null || value == Scriptable.NOT_FOUND ? def : ((Number) Context.jsToJava(value, Long.class)).longValue();
        } catch (Exception e) {
            return def;
        }
    }

    /** 从脚本对象读取布尔属性，缺省时返回默认值 */
    private static boolean booleanProperty(Scriptable obj, String name, boolean def) {
        var value = ScriptableObject.getProperty(obj, name);
        return value == null || value == Scriptable.NOT_FOUND ? def : Context.toBoolean(value);
    }

    /** 从脚本对象读取可空布尔属性 */
    private static Boolean optionalBooleanProperty(Scriptable obj, String name) {
        var value = ScriptableObject.getProperty(obj, name);
        if (value == null || value == Scriptable.NOT_FOUND) {
            return null;
        }
        return Context.toBoolean(value);
    }

    /** 判断脚本对象是否包含指定属性 */
    private static boolean hasProperty(Scriptable obj, String name) {
        var value = ScriptableObject.getProperty(obj, name);
        return value != null && value != Scriptable.NOT_FOUND;
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

    /** 给JS脚本提供HTTP请求、状态存取、编码和哈希等通用工具 */
    private static void installUtilityFunctions(Scriptable scope) {
        // 脚本内的原始HTTP请求入口
        ScriptableObject.putProperty(scope, "httpRequest", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var spec = args.length > 0 ? toRequestSpec(args[0]) : null;
                    if (spec == null) {
                        throw new IllegalArgumentException("httpRequest expects a request object");
                    }
                    var streamBody = Boolean.TRUE.equals(spec.streamFile) ? currentUploadBody() : null;
                    var readBody = spec.readBody == null || spec.readBody;
                    var response = execute(spec, streamBody, readBody);
                    return responseToScriptObject(scope, response);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        // 脚本内把文本编码成Base64
        ScriptableObject.putProperty(scope, "base64Encode", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return base64EncodeText(args.length > 0 ? Context.toString(args[0]) : "");
            }
        });
        // 脚本内把Base64解码回UTF-8文本
        ScriptableObject.putProperty(scope, "base64Decode", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return base64DecodeText(args.length > 0 ? Context.toString(args[0]) : "");
            }
        });
        // 脚本内计算任意算法的十六进制摘要
        ScriptableObject.putProperty(scope, "hashHex", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var algorithm = args.length > 0 ? Context.toString(args[0]) : "MD5";
                var value = args.length > 1 ? Context.toString(args[1]) : "";
                return hashHex(algorithm, value);
            }
        });
        // 脚本内把远端响应流直接写入当前下载目标文件
        ScriptableObject.putProperty(scope, "httpDownload", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var spec = args.length > 0 ? toRequestSpec(args[0]) : null;
                    if (spec == null) {
                        throw new IllegalArgumentException("httpDownload expects a request object");
                    }
                    var target = currentDownloadTarget();
                    var response = execute(spec, null, false);
                    try {
                        if (response.code >= 200 && response.code < 300 && response.response != null && response.response.body() != null) {
                            var parent = target.getParentFile();
                            if (parent != null) {
                                parent.mkdirs();
                            }
                            try (var in = response.response.body().byteStream(); var fos = new FileOutputStream(target)) {
                                copyStream(in, fos);
                            }
                        }
                        return responseToScriptObject(scope, response);
                    } finally {
                        closeQuietly(response);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        // 脚本内读取持久化状态
        ScriptableObject.putProperty(scope, "stateGet", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var key = args.length > 0 ? Context.toString(args[0]) : "";
                var def = args.length > 1 ? Context.toString(args[1]) : "";
                return getScriptState(key, def);
            }
        });
        // 脚本内写入持久化状态
        ScriptableObject.putProperty(scope, "stateSet", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var key = args.length > 0 ? Context.toString(args[0]) : "";
                var value = args.length > 1 ? Context.toString(args[1]) : "";
                setScriptState(key, value);
                return value;
            }
        });
    }

    /** 读取JS脚本持久化状态，比如脚本刷新后的Cookie或token */
    private static synchronized String getScriptState(String key, String def) {
        if (key == null || key.isEmpty()) {
            return def;
        }
        try {
            return loadScriptState().optString(key, def);
        } catch (Exception e) {
            LogHelp.e(TAG, "read custom script state failed", e);
            return def;
        }
    }

    /** 写入JS脚本持久化状态，比如脚本刷新后的Cookie或token */
    private static synchronized void setScriptState(String key, String value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            var cfg = ConfigHelp.load();
            var state = loadScriptState(cfg);
            state.put(key, value == null ? "" : value);
            cfg.put("custom_state_b64", Base64.getEncoder().encodeToString(state.toString().getBytes(StandardCharsets.UTF_8)));
            ConfigHelp.save(cfg);
        } catch (Exception e) {
            LogHelp.e(TAG, "write custom script state failed", e);
        }
    }

    private static JSONObject loadScriptState() {
        return loadScriptState(ConfigHelp.load());
    }

    private static JSONObject loadScriptState(JSONObject cfg) {
        var b64 = cfg.optString("custom_state_b64", "");
        if (b64 == null || b64.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LogHelp.e(TAG, "decode custom script state failed", e);
            return new JSONObject();
        }
    }

    /** 将当前上传上下文的文件流暴露给JS的 httpRequest(streamFile:true) 使用 */
    private static RequestBody currentUploadBody() {
        var upload = sUploadContext.get();
        if (upload == null || upload.localFile == null) {
            throw new IllegalStateException("streamFile requested without an active upload context");
        }
        return new FileRequestBody(upload.localFile, upload.listener, upload.taskId);
    }

    /** 当前下载目标文件，供JS脚本里的 httpDownload 使用 */
    private static File currentDownloadTarget() {
        var download = sDownloadContext.get();
        if (download == null || download.localFile == null) {
            throw new IllegalStateException("httpDownload requested without an active download context");
        }
        return download.localFile;
    }

    /** 把文本编码成Base64 */
    private static String base64EncodeText(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /** 把Base64文本解码成UTF-8字符串 */
    private static String base64DecodeText(String value) {
        try {
            return new String(Base64.getDecoder().decode(value == null ? "" : value), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 计算任意算法的十六进制摘要文本 */
    private static String hashHex(String algorithm, String value) {
        var algo = algorithm == null || algorithm.isEmpty() ? "MD5" : algorithm;
        try {
            var digest = MessageDigest.getInstance(algo);
            var bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            var out = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 将HTTP响应转换为脚本可读对象 */
    private static Scriptable responseToScriptObject(Scriptable scope, ScriptResponse response) {
        var obj = Context.getCurrentContext().newObject(scope);
        ScriptableObject.putProperty(obj, "code", response.code);
        ScriptableObject.putProperty(obj, "body", response.body == null ? "" : response.body);
        var headers = new LinkedHashMap<String, Object>();
        headers.putAll(response.headers);
        ScriptableObject.putProperty(obj, "headers", toNativeObject(scope, headers));
        return obj;
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

    /** 计算文件SHA256 */
    private static String sha256(File file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var buffer = new byte[BUFFER_SIZE];
        try (var in = new java.io.FileInputStream(file)) {
            var read = 0;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        var hash = digest.digest();
        var result = new StringBuilder(hash.length * 2);
        for (var b : hash) {
            result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return result.toString();
    }

    /** 读取raw资源中的默认脚本；无Context时返回短兜底脚本，正常设置页会传入Context */
    private static String readDefaultScript(android.content.Context context) {
        try {
            if (context == null) {
                throw new IllegalStateException("context is null");
            }
            var sourceContext = context.createPackageContext(MODULE_PACKAGE, android.content.Context.CONTEXT_IGNORE_SECURITY);
            try (var is = sourceContext.getResources().openRawResource(R.raw.custom_default)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "read default custom script failed", e);
            return "function testConnection(ctx){return false;}\n";
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
        Boolean streamFile;
        Boolean readBody;
        final Map<String, String> headers = new LinkedHashMap<>();
    }

    /** 当前上传任务上下文，供JS脚本里 httpRequest(streamFile:true) 复用 */
    private static class UploadContext {
        final File localFile;
        final Object listener;
        final String taskId;

        UploadContext(File localFile, Object listener, String taskId) {
            this.localFile = localFile;
            this.listener = listener;
            this.taskId = taskId;
        }
    }

    /** 当前下载任务上下文 */
    private static class DownloadContext {
        final File localFile;

        DownloadContext(File localFile) {
            this.localFile = localFile;
        }
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
