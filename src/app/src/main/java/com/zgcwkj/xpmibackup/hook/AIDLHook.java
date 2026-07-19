package com.zgcwkj.xpmibackup.hook;

import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.ResultReceiver;
import com.zgcwkj.comm.CloudFileHelp;
import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.LogHelp;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将小米DFS AIDL调用重定向到SMB/WebDAV
 * Hook停留在公开DFS服务边界，备份和恢复仍走小米自己的SDK包装层，避免直接Hook备份应用里的混淆业务函数
 */
public class AIDLHook {
    private static final String DESCRIPTOR = "com.xiaomi.dist.file.client.common.IDistFileClientKit";
    private static final String DFS_PACKAGE = "com.milink.service";
    private static final String DFS_SERVICE = "com.xiaomi.dist.file.client.core.DistFileClientService";
    private static final String KEY_CODE = "code";
    private static final String KEY_DATA = "data";
    private static final String KEY_MESSAGE = "message";
    private static final String TAG = "XpMiBackup";
    private static final String TEMP_BACKUP_ROOT = "/sdcard/MIUI/backup/AllBackupTemp/";
    private static final String DFS_ROOT_PATH = "";
    private static volatile IBinder mockBinder;
    private static volatile ExecutorService uploadExecutor;

    /**
     * 获取上传线程池，允许通过配置控制并发上传数量
     */
    private static ExecutorService getUploadExecutor() {
        if (uploadExecutor == null || uploadExecutor.isShutdown()) {
            uploadExecutor = Executors.newFixedThreadPool(ConfigHelp.getInt("upload_threads", 3));
        }
        return uploadExecutor;
    }

    /**
     * 在独立后台线程执行普通DFS模拟回调，避免阻塞AIDL调用线程
     */
    private static void runAsync(String name, Runnable runnable) {
        new Thread(runnable, "XpMiBackup-" + name).start();
    }

    /**
     * 安装DFS服务发现、绑定和低层Binder兜底Hook
     */
    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookQueryIntentServices(lpparam);
        hookBindService(lpparam);
        hookBinderProxyTransact(lpparam);
    }

    /**
     * 伪造DFS服务查询结果，让SDK认为设备上存在米联DFS服务
     */
    private void hookQueryIntentServices(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Class.forName("android.app.ApplicationPackageManager"), "queryIntentServices", new Object[]{Intent.class, Integer.TYPE, new XC_MethodHook() {
                /**
                 * 在DFS服务查询前直接返回伪造的服务信息
                 */
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                    var intent = (Intent) param.args[0];
                    if (intent != null && "com.xiaomi.dist.file.client.action.MANAGER".equals(intent.getAction())) {
                        var ri = new ResolveInfo();
                        ri.serviceInfo = new ServiceInfo();
                        ri.serviceInfo.packageName = AIDLHook.DFS_PACKAGE;
                        ri.serviceInfo.name = AIDLHook.DFS_SERVICE;
                        var list = new ArrayList<>();
                        list.add(ri);
                        param.setResult(list);
                    }
                }
            }});
        } catch (Throwable e) {
            logError("AIDLHook: hook queryIntentServices failed", e);
        }
    }

    /**
     * 将真实DFS服务绑定替换为进程内模拟Binder
     */
    private void hookBindService(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(ContextWrapper.class, "bindService", new Object[]{Intent.class, Integer.TYPE, Executor.class, ServiceConnection.class, new BindServiceHook(lpparam)});
        } catch (Throwable e) {
            logError("AIDLHook: hook bindService failed", e);
        }
    }

    /**
     * 处理ContextWrapper.bindService，把DFS服务连接回调切到模拟Binder
     */
    class BindServiceHook extends XC_MethodHook {
        final XC_LoadPackage.LoadPackageParam val$lpparam;

        /**
         * 保存当前备份应用的类加载器参数
         */
        BindServiceHook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
            this.val$lpparam = loadPackageParam;
        }

        /**
         * 拦截DFS服务绑定，并异步触发onServiceConnected
         */
        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
            var intent = (Intent) param.args[0];
            var comp = intent.getComponent();
            if (comp != null && AIDLHook.DFS_PACKAGE.equals(comp.getPackageName()) && AIDLHook.DFS_SERVICE.equals(comp.getClassName())) {
                var connection = (ServiceConnection) param.args[3];
                var executor = (Executor) param.args[2];
                AIDLHook.mockBinder = AIDLHook.this.createMockBinder(this.val$lpparam);
                executor.execute(new Runnable() {
                    /**
                     * 在调用方指定线程里派发服务已连接回调
                     */
                    @Override
                    public final void run() {
                        AIDLHook.BindServiceHook.notifyServiceConnected(connection);
                    }
                });
                param.setResult(true);
            }
        }

        /**
         * 调用原ServiceConnection回调，让SDK继续走正常AIDL初始化
         */
        static void notifyServiceConnected(ServiceConnection connection) {
            try {
                connection.onServiceConnected(new ComponentName(AIDLHook.DFS_PACKAGE, AIDLHook.DFS_SERVICE), AIDLHook.mockBinder);
            } catch (Exception e) {
                AIDLHook.logError("AIDLHook: onServiceConnected failed", e);
            }
        }
    }

    /**
     * 兜底短路部分SDK直接发出的one-way Binder调用，避免真实DFS服务缺失导致异常
     */
    private void hookBinderProxyTransact(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Class.forName("android.os.BinderProxy"), "transact", new Object[]{Integer.TYPE, Parcel.class, Parcel.class, Integer.TYPE, new XC_MethodHook() {
                /**
                 * 只处理one-way DFS事务，同步事务交回系统Binder正常处理
                 */
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                    var code = ((Integer) param.args[0]).intValue();
                    var flags = ((Integer) param.args[3]).intValue();
                    if (flags != 1) {
                        return;
                    }
                    var binder = (IBinder) param.thisObject;
                    try {
                        if (AIDLHook.DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                            switch (code) {
                                case 1:
                                case 6:
                                case 7:
                                case 9:
                                case 16:
                                case 22:
                                    param.setResult(true);
                                    break;
                            }
                        }
                    } catch (Exception e) {
                        AIDLHook.logError("AIDLHook: BinderProxy.transact inspect failed", e);
                    }
                }
            }});
        } catch (Throwable e) {
            logError("AIDLHook: hook BinderProxy.transact failed", e);
        }
    }

    /**
     * 创建本地Binder，并用动态代理实现IDistFileClientKit
     */
    public IBinder createMockBinder(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var allocateMethod = unsafeClass.getMethod("allocateInstance", Class.class);
        var binderClass = Class.forName("android.os.Binder");
        var binderInstance = allocateMethod.invoke(unsafe, binderClass);
        var ifaceClass = XposedHelpers.findClass(DESCRIPTOR, lpparam.classLoader);
        var ifaceProxy = Proxy.newProxyInstance(lpparam.classLoader, new Class[]{ifaceClass}, new InvocationHandler() {
            /**
             * 将AIDL接口调用统一派发到签名匹配逻辑
             */
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                return AIDLHook.this.handleAidlMethod(method, args, lpparam);
            }
        });
        var attachMethod = binderClass.getDeclaredMethod("attachInterface", IInterface.class, String.class);
        attachMethod.setAccessible(true);
        attachMethod.invoke(binderInstance, ifaceProxy, DESCRIPTOR);
        mockBinder = (IBinder) binderInstance;
        return mockBinder;
    }

    /**
     * 按参数签名分发AIDL方法，同时兼容旧版可读方法名和新版混淆方法名
     */
    public Object handleAidlMethod(Method method, Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        var name = method.getName();
        if ("asBinder".equals(name)) {
            return mockBinder;
        }
        if (matches(method, String.class, byName("IConnectionListener"))) {
            mockConnect(args, lpparam);
            return null;
        }
        if (matches(method, String.class)) {
            return null;
        }
        if (matches(method, String.class, String.class)) {
            return null;
        }
        if (matches(method, byName("DeviceFilter"), byName("IDeviceStateListener"))) {
            mockDeviceStateListener(args, lpparam);
            return null;
        }
        if (matches(method, byName("IDeviceStateListener"))) {
            mockDeviceStateListener(args, lpparam);
            return null;
        }
        if (matches(method, byName("DeviceFilter"), ResultReceiver.class)) {
            mockGetDeviceList(args, lpparam);
            return null;
        }
        if (matches(method, String.class, ResultReceiver.class)) {
            mockGetSharePathInfo(args, lpparam);
            return null;
        }
        if (matches(method, String.class, String.class, ResultReceiver.class)) {
            handleResultReceiverMethod(name, args, lpparam);
            return null;
        }
        if (matches(method, String.class, String.class, ParcelFileDescriptor.class, String.class, Long.TYPE, byName("IFileOperationProgressListener"), Integer.TYPE)) {
            if ("w1".equals(name) || "upload".equals(name)) {
                mockUpload(args, lpparam);
                return null;
            }
            if ("G0".equals(name) || "download".equals(name)) {
                mockDownload(args, lpparam);
                return null;
            }
            mockUpload(args, lpparam);
            return null;
        }
        if (matches(method, String.class, String.class, String.class, String.class, byName("IFileOperationProgressListener"))) {
            mockRemoteToRemoteOk(args);
            return null;
        }
        LogHelp.e(TAG, "AIDLCall: unhandled " + name + signatureOf(method));
        return null;
    }

    /**
     * 远端到远端的复制/移动调用先返回成功，避免旧文件迁移路径误报失败
     */
    private void mockRemoteToRemoteOk(Object[] args) {
        var taskId = (String) args[1];
        var listener = args[4];
        invokeProgress(listener, "Y0", new Class[]{String.class}, taskId);
        notifyProgressFinish(listener, taskId, 0, "success");
    }

    /**
     * 模拟DFS连接调用，并把耗时连接测试放到后台线程
     */
    private void mockConnect(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var deviceId = (String) args[0];
        var listener = args[1];
        runAsync("connect", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockConnectResult(listener, deviceId, lpparam);
            }
        });
    }

    /**
     * 模拟DFS连接结果，并在连接成功后初始化备份应用内部DFS状态
     */
    public void sendMockConnectResult(Object listener, String deviceId, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var connected = CloudFileHelp.testConnection();
            if (listener != null) {
                if (connected) {
                    invokeConnection(listener, deviceId, 0, true);
                } else {
                    invokeConnection(listener, deviceId, 1200, false);
                }
            }
            if (connected) {
                ensureDfsServiceReady(deviceId, lpparam);
                keepDfsConnected(lpparam);
            }
        } catch (Exception e) {
            logError("mock connect failed", e);
        }
    }

    /**
     * 模拟获取设备列表调用
     */
    private void mockGetDeviceList(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var receiver = args[1];
        runAsync("device-list", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockDeviceList(receiver, lpparam);
            }
        });
    }

    /**
     * 构造设备列表回调，供小米备份页面展示远端设备
     */
    public void sendMockDeviceList(Object receiver, XC_LoadPackage.LoadPackageParam lpparam) {
        if (receiver != null) {
            try {
                var bundle = new Bundle();
                bundle.putInt(KEY_CODE, 0);
                bundle.putString(KEY_MESSAGE, "success");
                var list = new ArrayList<Parcelable>();
                list.add(createDeviceInfo(lpparam));
                bundle.putParcelableArrayList(KEY_DATA, list);
                invokeReceiverSend(receiver, 0, bundle);
                ensureDfsServiceReady(getMockDeviceId(), lpparam);
            } catch (Exception e) {
                logError("mock getDeviceList failed", e);
            }
        }
    }

    /**
     * 模拟注册设备状态监听调用
     */
    private void mockDeviceStateListener(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var listener = args.length == 1 ? args[0] : args[1];
        runAsync("device-state", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockDeviceState(listener, lpparam);
            }
        });
    }

    /**
     * 发送模拟设备在线回调，并补齐内部DFS初始化
     */
    public void sendMockDeviceState(Object listener, XC_LoadPackage.LoadPackageParam lpparam) {
        if (listener != null) {
            try {
                invokeDeviceState(listener, "K", createDeviceInfo(lpparam));
                ensureDfsServiceReady(getMockDeviceId(), lpparam);
            } catch (Exception e) {
                logError("mock device state failed", e);
            }
        }
    }

    /**
     * 模拟远端目录列表调用
     */
    private void mockList(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var remotePath = (String) args[1];
        var receiver = args[2];
        runAsync("list", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockList(remotePath, receiver, lpparam);
            }
        });
    }

    /**
     * 从SMB/WebDAV读取目录，并转换成小米NAS恢复列表需要的结果
     */
    public void sendMockList(String remotePath, Object receiver, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var remoteDir = normalizeRemotePath(remotePath);
            var entries = CloudFileHelp.listEntries(remoteDir);
            var aidlDir = normalizeAidlListPath(remotePath);
            entries = normalizeListEntries(entries, aidlDir);
            if (receiver != null) {
                var bundle = new Bundle();
                bundle.putInt(KEY_CODE, 0);
                bundle.putString(KEY_MESSAGE, "success");
                bundle.putParcelable(KEY_DATA, createSmbFileBatchResult(entries, aidlDir, lpparam));
                invokeReceiverSend(receiver, 0, bundle);
            }
        } catch (Exception e) {
            logError("mock list failed", e);
            sendEmptyList(receiver, lpparam);
        }
    }

    /**
     * 列表读取失败时返回空列表，避免恢复页面一直空等
     */
    private void sendEmptyList(Object receiver, XC_LoadPackage.LoadPackageParam lpparam) {
        if (receiver == null) {
            return;
        }
        try {
            var bundle = new Bundle();
            bundle.putInt(KEY_CODE, 0);
            bundle.putString(KEY_MESSAGE, "success");
            bundle.putParcelable(KEY_DATA, createSmbFileBatchResult(new ArrayList<>(), ".AllBackup", lpparam));
            invokeReceiverSend(receiver, 0, bundle);
        } catch (Exception e) {
            logError("send empty list failed", e);
        }
    }

    /**
     * 模拟上传文件调用，新版w1和旧版upload同签名方法都会走这里
     */
    private void mockUpload(Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        var taskId = (String) args[1];
        var pfd = (ParcelFileDescriptor) args[2];
        var aidlPath = (String) args[3];
        var listener = args[5];
        var lp = lpparam;
        if (pfd == null) {
            return;
        }
        getUploadExecutor().execute(new Runnable() {
            /**
             * 在线程池中执行上传，避免多个大文件串行阻塞
             */
            @Override
            public final void run() {
                AIDLHook.this.runMockUpload(pfd, aidlPath, listener, taskId, lp);
            }
        });
    }

    /**
     * 执行上传并保证文件描述符最终关闭
     */
    public void runMockUpload(ParcelFileDescriptor pfd, String aidlPath, Object listener, String taskId, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            keepDfsConnected(lpparam);
            uploadViaFd(pfd, aidlPath, listener, taskId);
            keepDfsConnected(lpparam);
        } catch (Exception e) {
            logError("mock upload failed", e);
            notifyProgressFinish(listener, taskId, -1, e.getMessage());
        } catch (Throwable th) {
            throw th;
        } finally {
            closeQuietly(pfd);
        }
    }

    /**
     * 模拟下载文件调用，新版G0和旧版download同签名方法都会走这里
     */
    private void mockDownload(Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        var taskId = (String) args[1];
        var pfd = (ParcelFileDescriptor) args[2];
        var aidlPath = (String) args[3];
        final long startPos = ((Long) args[4]).longValue();
        var listener = args[5];
        final int flags = ((Integer) args[6]).intValue();
        var lp = lpparam;
        if (pfd == null) {
            return;
        }
        runAsync("download", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.runMockDownload(taskId, aidlPath, startPos, flags, pfd, listener, lp);
            }
        });
    }

    /**
     * 执行下载并把完成或失败状态回调给小米备份
     */
    public void runMockDownload(String taskId, String aidlPath, long startPos, int flags, ParcelFileDescriptor pfd, Object listener, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            BackupHook.clearActiveBackupDirs();
            keepDfsConnected(lpparam);
            downloadViaFd(aidlPath, pfd, listener, taskId);
            keepDfsConnected(lpparam);
            notifyProgressFinish(listener, taskId, 0, "success");
        } catch (Exception e) {
            logError("mock download failed: path=" + aidlPath + ", start=" + startPos + ", flags=" + flags, e);
            notifyProgressFinish(listener, taskId, -1, e.getMessage());
        } catch (Throwable th) {
            throw th;
        } finally {
            closeQuietly(pfd);
        }
    }

    /**
     * 模拟文件存在性检查调用
     */
    private void mockExists(Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        var receiver = args[2];
        runAsync("exists", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockExists(receiver);
            }
        });
    }

    /**
     * 返回存在结果；这里保持成功，交给后续读写流程验证真实文件
     */
    public void sendMockExists(Object receiver) {
        if (receiver != null) {
            try {
                var bundle = new Bundle();
                bundle.putInt(KEY_CODE, 0);
                bundle.putString(KEY_MESSAGE, "success");
                bundle.putInt(KEY_DATA, 1);
                invokeReceiverSend(receiver, 0, bundle);
            } catch (Exception e) {
                logError("mock exists failed", e);
            }
        }
    }

    /**
     * 模拟获取共享路径信息调用
     */
    private void mockGetSharePathInfo(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var receiver = args[1];
        runAsync("share-path", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockSharePathInfo(receiver, lpparam);
            }
        });
    }

    /**
     * 构造共享目录信息，供小米备份显示远端容量
     */
    public void sendMockSharePathInfo(Object receiver, XC_LoadPackage.LoadPackageParam lpparam) {
        if (receiver != null) {
            try {
                var bundle = new Bundle();
                bundle.putInt(KEY_CODE, 0);
                bundle.putString(KEY_MESSAGE, "success");
                var list = new ArrayList<Parcelable>();
                list.add(createPathInfo(lpparam));
                bundle.putParcelableArrayList(KEY_DATA, list);
                invokeReceiverSend(receiver, 0, bundle);
            } catch (Exception e) {
                logError("mock getSharePathInfo failed", e);
            }
        }
    }

    /**
     * 处理exists、list以及其他带ResultReceiver的两路径调用
     */
    private void handleResultReceiverMethod(String name, Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        if ("i0".equals(name) || "list".equals(name)) {
            mockList(args, lpparam);
        } else if ("f0".equals(name) || "exists".equals(name)) {
            mockExists(args, lpparam);
        } else {
            mockSimpleOk(args, name);
        }
    }

    /**
     * 模拟不需要额外业务数据的成功回调调用
     */
    private void mockSimpleOk(Object[] args, final String operation) {
        var receiver = args[2];
        runAsync("simple-ok", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendSimpleOk(receiver, operation);
            }
        });
    }

    /**
     * 为mkdir、delete、cancel等不需要真实数据的调用返回成功
     */
    public void sendSimpleOk(Object receiver, String operation) {
        if (receiver != null) {
            try {
                var bundle = new Bundle();
                bundle.putInt(KEY_CODE, 0);
                bundle.putString(KEY_MESSAGE, "success");
                bundle.putInt(KEY_DATA, 0);
                invokeReceiverSend(receiver, 0, bundle);
            } catch (Exception e) {
                logError("mock " + operation + " failed", e);
            }
        }
    }

    /**
     * 把DFS虚拟路径转换为真实SMB/WebDAV远端路径
     */
    private static String normalizeRemotePath(String aidlPath) {
        if (aidlPath == null || aidlPath.isEmpty()) {
            return "";
        }
        var backupPath = ConfigHelp.getString("backup_path", "MIUI/backup");
        var path = aidlPath;
        if (path.startsWith(backupPath + "/")) {
            path = path.substring(backupPath.length() + 1);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        var firstSlash = path.indexOf('/');
        if (firstSlash > 0 && !path.startsWith(".AllBackup") && !path.startsWith(".AppBackup")) {
            path = path.substring(firstSlash + 1);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (".AllBackup".equals(path)) {
            return backupPath;
        }
        if (path.startsWith(".AllBackup/")) {
            path = path.substring(".AllBackup/".length());
        }
        if (path.startsWith("AllBackup/")) {
            path = path.substring("AllBackup/".length());
        }
        if ("AllBackup".equals(path)) {
            return backupPath;
        }
        if (path.startsWith(".AppBackup/")) {
            path = path.substring(".AppBackup/".length());
        }
        var path2 = cleanRemotePathSegments(path);
        return path2.isEmpty() ? backupPath : backupPath + "/" + path2;
    }

    /**
     * 把调用方传入路径转换回DFS列表展示路径
     */
    private static String normalizeAidlListPath(String aidlPath) {
        if (aidlPath == null || aidlPath.isEmpty()) {
            return ".AllBackup";
        }
        var path = aidlPath.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        var firstSlash = path.indexOf('/');
        if (firstSlash > 0 && !path.startsWith(".AllBackup") && !path.startsWith(".AppBackup")) {
            path = path.substring(firstSlash + 1);
        }
        if (path.startsWith(".AppBackup")) {
            path = ".AllBackup" + path.substring(".AppBackup".length());
        }
        if (path.startsWith("AllBackup")) {
            path = ".AllBackup" + path.substring("AllBackup".length());
        }
        if (!path.startsWith(".AllBackup")) {
            path = ".AllBackup";
        }
        return path;
    }

    /**
     * 移除DFS虚拟目录，保留备份数据里的真实隐藏文件
     */
    private static String cleanRemotePathSegments(String path) {
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
     * 从远端文件路径中取父目录
     */
    private static String extractRemoteDir(String remotePath) {
        var lastSlash = remotePath.lastIndexOf('/');
        return lastSlash > 0 ? remotePath.substring(0, lastSlash) : remotePath;
    }

    /**
     * 从远端文件路径中取文件名
     */
    private static String extractFileName(String remotePath) {
        var lastSlash = remotePath.lastIndexOf('/');
        return lastSlash >= 0 ? remotePath.substring(lastSlash + 1) : remotePath;
    }

    /**
     * 把远端目录条目整理成小米NAS恢复加载器期望的形态
     */
    private static List<CloudFileHelp.RemoteEntry> normalizeListEntries(List<CloudFileHelp.RemoteEntry> entries, String aidlDir) {
        var normalized = new ArrayList<CloudFileHelp.RemoteEntry>();
        addDfsListPadding(normalized);
        for (var entry : entries) {
            var looksLikeBackupDir = ".AllBackup".equals(aidlDir)
                && entry.name != null
                && entry.name.matches("\\d{8}_\\d{6}");
            var displayName = decodeDfsListName(entry.name);
            normalized.add(new CloudFileHelp.RemoteEntry(displayName, entry.size, entry.directory || looksLikeBackupDir, entry.modifiedTime));
        }
        return normalized;
    }

    /**
     * 解码WebDAV返回的百分号编码名称
     */
    private static String decodeDfsListName(String name) {
        // WebDAV的href可能带百分号编码，DFS调用方比较的是解码后的显示名称
        if (name == null || name.indexOf('%') < 0) {
            return name;
        }
        var decoded = new StringBuilder();
        var bytes = new ByteArrayOutputStream();
        for (var i = 0; i < name.length(); i++) {
            var ch = name.charAt(i);
            if (ch == '%' && i + 2 < name.length()) {
                var hi = Character.digit(name.charAt(i + 1), 16);
                var lo = Character.digit(name.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            appendDecodedBytes(decoded, bytes);
            decoded.append(ch);
        }
        appendDecodedBytes(decoded, bytes);
        return decoded.toString();
    }

    /**
     * 将暂存的UTF-8字节追加到解码结果
     */
    private static void appendDecodedBytes(StringBuilder decoded, ByteArrayOutputStream bytes) {
        if (bytes.size() == 0) {
            return;
        }
        decoded.append(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        bytes.reset();
    }

    /**
     * 添加DFS列表占位项，兼容小米NAS加载器跳过前两条记录的行为
     */
    private static void addDfsListPadding(List<CloudFileHelp.RemoteEntry> entries) {
        var now = System.currentTimeMillis();
        entries.add(new CloudFileHelp.RemoteEntry("__dfs_skip_0", 0, true, now));
        entries.add(new CloudFileHelp.RemoteEntry("__dfs_skip_1", 0, true, now));
    }

    /**
     * 使用目标应用类加载器构造SmbFileBatchResult
     */
    private Parcelable createSmbFileBatchResult(List<CloudFileHelp.RemoteEntry> entries, String aidlDir, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var smbFiles = new ArrayList<Parcelable>();
        for (var entry : entries) {
            smbFiles.add(createSmbFile(entry, aidlDir, lpparam));
        }
        var clazz = XposedHelpers.findClass("com.xiaomi.dist.file.client.common.model.SmbFileBatchResult", lpparam.classLoader);
        var parcel = Parcel.obtain();
        try {
            parcel.writeInt(1);
            parcel.writeInt(1);
            parcel.writeTypedList(smbFiles);
            parcel.setDataPosition(0);
            var creator = (Parcelable.Creator) clazz.getField("CREATOR").get(null);
            return (Parcelable) creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * 使用Parcel构造小米SDK里的SmbFile对象
     */
    private Parcelable createSmbFile(CloudFileHelp.RemoteEntry entry, String aidlDir, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var clazz = XposedHelpers.findClass("com.xiaomi.dist.file.client.common.model.SmbFile", lpparam.classLoader);
        var parcel = Parcel.obtain();
        try {
            var now = System.currentTimeMillis();
            var modified = entry.modifiedTime > 0 ? entry.modifiedTime : now;
            var directory = entry.directory || ".AllBackup".equals(aidlDir);
            parcel.writeString(entry.name);
            parcel.writeLong(entry.size);
            parcel.writeLong(modified);
            parcel.writeLong(modified);
            parcel.writeString(directory ? "inode/directory" : "application/octet-stream");
            parcel.writeString(entry.name);
            parcel.writeString(entry.name);
            parcel.writeInt(0);
            parcel.writeInt(0);
            parcel.writeInt(directory ? 16 : 128);
            parcel.writeLong(modified);
            parcel.writeParcelable(null, 0);
            parcel.setDataPosition(0);
            var creator = (Parcelable.Creator) clazz.getField("CREATOR").get(null);
            return (Parcelable) creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * 构造模拟设备信息，保持设备ID和页面入口传参一致
     */
    private Parcelable createDeviceInfo(XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var clazz = XposedHelpers.findClass("com.xiaomi.dist.file.client.common.model.DeviceInfo", lpparam.classLoader);
        var parcel = Parcel.obtain();
        try {
            parcel.writeString(ConfigHelp.getString("device_id", "zgcwkj"));
            parcel.writeString(ConfigHelp.getString("device_name", "Remote Backup"));
            parcel.writeInt(1);
            parcel.writeInt(128);
            parcel.writeInt(1);
            parcel.setDataPosition(0);
            var creator = (Parcelable.Creator) clazz.getField("CREATOR").get(null);
            return (Parcelable) creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * 构造共享路径信息，供备份页面展示远端空间
     */
    private static Parcelable createPathInfo(XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var clazz = XposedHelpers.findClass("com.xiaomi.dist.file.client.common.model.PathInfo", lpparam.classLoader);
        var parcel = Parcel.obtain();
        try {
            parcel.writeInt(1);
            parcel.writeString(ConfigHelp.getString("device_name", "Remote Backup"));
            parcel.writeString(DFS_ROOT_PATH);
            parcel.writeLong(1099511627776L);
            parcel.writeLong(1099511627776L);
            parcel.writeByte((byte) 1);
            parcel.writeTypedList(new ArrayList());
            parcel.writeBundle(new Bundle());
            parcel.setDataPosition(0);
            var creator = (Parcelable.Creator) clazz.getField("CREATOR").get(null);
            return (Parcelable) creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * 从ParcelFileDescriptor读取备份文件并上传到SMB/WebDAV
     */
    private void uploadViaFd(ParcelFileDescriptor pfd, String aidlPath, Object listener, String taskId) throws Exception {
        var remotePath = normalizeRemotePath(aidlPath);
        var remoteDir = extractRemoteDir(remotePath);
        var fileName = extractFileName(remotePath);
        var tmpFile = new File(TEMP_BACKUP_ROOT + taskId + "/" + fileName);
        tmpFile.getParentFile().mkdirs();
        try {
            BackupHook.recordActiveBackupDir(remoteDir);
            notifyProgressStart(listener, taskId);
            try (var is = new FileInputStream(pfd.getFileDescriptor()); var os = new FileOutputStream(tmpFile)) {
                copyStream(is, os);
            }
            CloudFileHelp.uploadWithProgress(tmpFile.getAbsolutePath(), listener, remoteDir, taskId);
            if (isBackupEndFile(fileName)) {
                CloudFileHelp.cleanupOldBackups();
                BackupHook.clearActiveBackupDirs();
            }
        } finally {
            deleteTempFile(tmpFile);
            deleteEmptyDir(tmpFile.getParentFile());
        }
    }

    /**
     * 判断当前上传文件是否为一次备份完成标记
     */
    private static boolean isBackupEndFile(String fileName) {
        return "end".equals(fileName);
    }

    /**
     * 提前通知小米备份当前任务已开始，避免本地临时文件阶段列表没有焦点
     */
    private static void notifyProgressStart(Object listener, String taskId) {
        invokeProgress(listener, "Y0", new Class[]{String.class}, taskId);
        BackupHook.notifyNasItemTaskStart(listener, taskId);
    }

    /**
     * 从SMB/WebDAV下载文件并写入ParcelFileDescriptor
     */
    private void downloadViaFd(String aidlPath, ParcelFileDescriptor pfd, Object listener, String taskId) throws Exception {
        var remotePath = normalizeRemotePath(aidlPath);
        var tmpFile = new File(TEMP_BACKUP_ROOT + taskId + "_download_tmp");
        tmpFile.getParentFile().mkdirs();
        try {
            var result = CloudFileHelp.downloadFile(remotePath, tmpFile.getAbsolutePath());
            if (result != null && result.startsWith("ERROR:")) {
                throw new IllegalStateException(result);
            }
            try (var is = new FileInputStream(tmpFile); var os = new FileOutputStream(pfd.getFileDescriptor())) {
                copyStream(is, os);
            }
        } finally {
            deleteTempFile(tmpFile);
        }
    }

    /**
     * 拷贝流内容，调用方负责关闭输入输出流
     */
    private static void copyStream(java.io.InputStream is, java.io.OutputStream os) throws Exception {
        var buf = new byte[1048576];
        while (true) {
            var len = is.read(buf);
            if (len == -1) {
                return;
            }
            os.write(buf, 0, len);
        }
    }

    /**
     * 删除临时文件，删除失败时只记录异常
     */
    private static void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            logError("delete temp file failed", new IllegalStateException(file.getAbsolutePath()));
        }
    }

    /**
     * 删除空临时目录，目录仍有内容时保持不动
     */
    private static void deleteEmptyDir(File dir) {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            var children = dir.list();
            if ((children == null || children.length == 0) && !dir.delete()) {
                logError("delete temp dir failed", new IllegalStateException(dir.getAbsolutePath()));
            }
        }
    }

    /**
     * 触发备份应用自己的DFS服务初始化，补齐deviceId和PathInfo
     */
    private void ensureDfsServiceReady(String deviceId, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var serviceClass = XposedHelpers.findClass("com.miui.backup.dfs.DistFileClientService", lpparam.classLoader);
            var instance = getDfsServiceInstance(serviceClass);
            if (instance == null) {
                LogHelp.e(TAG, "ensureDfsServiceReady failed: DistFileClientService instance is null");
                return;
            }
            setDfsDeviceId(serviceClass, instance, deviceId);
            keepDfsConnected(serviceClass, instance);
            ensureDistFileClient(serviceClass, instance, deviceId, lpparam);
            ensureDfsTempPath(serviceClass, instance, deviceId);
            ensureDfsDeviceInfo(serviceClass, instance, lpparam);
            ensureRestoreDescriptors(serviceClass, instance);
            startDfsInit(serviceClass, instance);
        } catch (Exception e) {
            logError("ensureDfsServiceReady failed", e);
        }
    }

    /**
     * 让备份应用内部DFS服务保持已连接状态，避免误入断开暂停分支
     */
    private void keepDfsConnected(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var serviceClass = XposedHelpers.findClass("com.miui.backup.dfs.DistFileClientService", lpparam.classLoader);
            var instance = getDfsServiceInstance(serviceClass);
            if (instance != null) {
                keepDfsConnected(serviceClass, instance);
            }
        } catch (Exception e) {
            logError("keepDfsConnected failed", e);
        }
    }

    /**
     * 通过字段类型兼容新版混淆名和旧版明文字段名
     */
    private static void keepDfsConnected(Class<?> serviceClass, Object instance) throws Exception {
        for (var field : serviceClass.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType() == Boolean.TYPE) {
                field.setBoolean(instance, true);
            } else if (field.getType() == Integer.TYPE && ("f1750e".equals(field.getName()) || "mConnectType".equals(field.getName()))) {
                field.setInt(instance, 0);
            }
        }
    }

    /**
     * 旧版备份会直接读取DistFileClientService.mDistFileClient，这里补齐SDK自己的客户端实例
     */
    private static void ensureDistFileClient(Class<?> serviceClass, Object instance, String deviceId, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var field = findFieldByTypeName(serviceClass, "com.xiaomi.dist.file.client.kit.manager.IDistFileClient");
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        if (field.get(instance) != null) {
            return;
        }
        var implClass = XposedHelpers.findClass("com.xiaomi.dist.file.client.kit.manager.DistFileClientImpl", lpparam.classLoader);
        var constructor = implClass.getConstructor(String.class);
        field.set(instance, constructor.newInstance(deviceId));
    }

    /**
     * 旧版恢复列表会同步检查mTempPath，这里直接补齐它要解析的本地临时目录
     */
    private static void ensureDfsTempPath(Class<?> serviceClass, Object instance, String deviceId) throws Exception {
        var field = findFieldByName(serviceClass, "mTempPath");
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        var tempPath = TEMP_BACKUP_ROOT + deviceId;
        field.set(instance, tempPath);
        var tempDir = new File(tempPath);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            logError("create restore temp dir failed", new IllegalStateException(tempPath));
        }
    }

    /**
     * 旧版恢复列表会同步检查mDeviceInfo，这里补齐共享路径信息避免页面直接返回空列表
     */
    private static void ensureDfsDeviceInfo(Class<?> serviceClass, Object instance, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var field = findFieldByTypeName(serviceClass, "com.xiaomi.dist.file.client.common.model.PathInfo");
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        if (field.get(instance) == null) {
            field.set(instance, createPathInfo(lpparam));
        }
    }

    /**
     * 把云端descript.xml预下载到备份应用自己的临时目录，恢复列表仍走原生BackupDescriptor解析
     */
    private static void ensureRestoreDescriptors(Class<?> serviceClass, Object instance) throws Exception {
        var field = findFieldByName(serviceClass, "mTempPath");
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        var tempPath = (String) field.get(instance);
        if (tempPath == null || tempPath.isEmpty()) {
            return;
        }
        var result = CloudFileHelp.listAndDownloadXml(tempPath);
        if (result != null && result.startsWith("ERROR:")) {
            throw new IllegalStateException(result);
        }
    }

    /**
     * 按字段类型名查找字段，兼容旧版明文字段名和新版混淆字段名
     */
    private static java.lang.reflect.Field findFieldByTypeName(Class<?> clazz, String typeName) {
        var current = clazz;
        while (current != null) {
            for (var field : current.getDeclaredFields()) {
                if (typeName.equals(field.getType().getName())) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 按字段名查找字段，只用于旧版公开明文字段
     */
    private static java.lang.reflect.Field findFieldByName(Class<?> clazz, String name) {
        var current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 同时兼容新版混淆单例n()和旧版可读getInstance()
     */
    private static Object getDfsServiceInstance(Class<?> serviceClass) throws Exception {
        for (var methodName : new String[]{"n", "getInstance"}) {
            try {
                var method = serviceClass.getDeclaredMethod(methodName, new Class[0]);
                method.setAccessible(true);
                return method.invoke(null, new Object[0]);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /**
     * 设置备份应用内部DFS服务持有的设备ID
     */
    private static void setDfsDeviceId(Class<?> serviceClass, Object instance, String deviceId) throws Exception {
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IllegalArgumentException("deviceId is empty");
        }
        for (var methodName : new String[]{"I", "setDeviceId"}) {
            try {
                var method = serviceClass.getDeclaredMethod(methodName, String.class);
                method.setAccessible(true);
                method.invoke(instance, deviceId);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (var field : serviceClass.getDeclaredFields()) {
            if (field.getType() == String.class) {
                field.setAccessible(true);
                if (field.get(instance) == null) {
                    field.set(instance, deviceId);
                    return;
                }
            }
        }
        throw new NoSuchMethodException("No DistFileClientService deviceId setter found");
    }

    /**
     * 启动小米自己的DFS初始化流程，让它正常创建IDistFileClient和PathInfo
     */
    private static void startDfsInit(Class<?> serviceClass, Object instance) throws Exception {
        for (var methodName : new String[]{"r", "initDistFileClientService"}) {
            try {
                var method = serviceClass.getDeclaredMethod(methodName, new Class[0]);
                method.setAccessible(true);
                method.invoke(instance, new Object[0]);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("No DistFileClientService init method found");
    }

    /**
     * 读取当前配置里的模拟设备ID
     */
    private static String getMockDeviceId() {
        return ConfigHelp.getString("device_id", "zgcwkj");
    }

    /**
     * 标记按类简单名匹配参数类型
     */
    private static String byName(String simpleName) {
        return simpleName;
    }

    /**
     * 比较方法参数签名，支持Class精确匹配和简单类名后缀匹配
     */
    private static boolean matches(Method method, Object... types) {
        var params = method.getParameterTypes();
        if (params.length != types.length) {
            return false;
        }
        for (var i = 0; i < params.length; i++) {
            var expected = types[i];
            if (expected instanceof String) {
                if (!params[i].getName().endsWith("." + expected)) {
                    return false;
                }
            } else if ((expected instanceof Class) && params[i] != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生成方法参数签名文本，用于异常日志定位未覆盖的AIDL方法
     */
    private static String signatureOf(Method method) {
        var sb = new StringBuilder("(");
        var params = method.getParameterTypes();
        for (var i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    /**
     * 调用DFS连接监听器，兼容新旧方法名和不同参数数量
     */
    private void invokeConnection(Object listener, String deviceId, int code, boolean success) {
        var methodNames = success ? new String[]{"D1", "onSuccess"} : new String[]{"o1", "X", "onFailed"};
        for (var methodName : methodNames) {
            try {
                var method = listener.getClass().getMethod(methodName, String.class, Integer.TYPE);
                method.invoke(listener, deviceId, Integer.valueOf(code));
                return;
            } catch (Exception e) {
                try {
                    var method2 = listener.getClass().getMethod(methodName, String.class);
                    method2.invoke(listener, deviceId);
                    return;
                } catch (Exception e2) {
                    try {
                        var method3 = listener.getClass().getMethod(methodName, new Class[0]);
                        method3.invoke(listener, new Object[0]);
                        return;
                    } catch (Exception e3) {
                    }
                }
            }
        }
        LogHelp.e(TAG, "invokeConnection failed: no callback method matched, success=" + success);
    }

    /**
     * 调用IDeviceStateListener在线回调，兼容旧版可读名和新版混淆名
     */
    private void invokeDeviceState(Object listener, String methodName, Parcelable deviceInfo) {
        try {
            for (var method : listener.getClass().getMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == 1) {
                    method.invoke(listener, deviceInfo);
                    return;
                }
            }
            for (var method2 : listener.getClass().getMethods()) {
                if (method2.getParameterCount() == 1 && method2.getParameterTypes()[0].getName().endsWith(".DeviceInfo") && method2.getReturnType() == Void.TYPE) {
                    method2.invoke(listener, deviceInfo);
                    return;
                }
            }
            LogHelp.e(TAG, "invokeDeviceState failed: no callback method matched");
        } catch (Exception e) {
            logError("invokeDeviceState failed", e);
        }
    }

    /**
     * 调用ResultReceiver.send返回AIDL异步结果
     */
    private void invokeReceiverSend(Object receiver, int resultCode, Bundle bundle) {
        if (receiver == null) {
            return;
        }
        try {
            var sendMethod = receiver.getClass().getMethod("send", Integer.TYPE, Bundle.class);
            sendMethod.invoke(receiver, Integer.valueOf(resultCode), bundle);
        } catch (Exception e) {
            logError("invokeReceiverSend failed", e);
        }
    }

    /**
     * 先按可读名调用进度回调，再按签名匹配混淆版本
     */
    static void invokeProgress(Object obj, String method, Class<?>[] types, Object... args) {
        if (obj == null) {
            return;
        }
        try {
            obj.getClass().getMethod(method, types).invoke(obj, args);
        } catch (Exception e) {
            try {
                for (var m : obj.getClass().getMethods()) {
                    if (m.getParameterCount() == types.length) {
                        var match = true;
                        var i = 0;
                        while (true) {
                            if (i >= types.length) {
                                break;
                            }
                            if (!m.getParameterTypes()[i].equals(types[i])) {
                                match = false;
                                break;
                            }
                            i++;
                        }
                        if (match && m.getReturnType().equals(Void.TYPE)) {
                            m.invoke(obj, args);
                            return;
                        }
                    }
                }
            } catch (Exception e2) {
                logError("invokeProgress fallback failed", e2);
            }
        }
    }

    /**
     * 通知单个文件传输完成或失败
     */
    static void notifyProgressFinish(Object listener, String taskId, int code, String msg) {
        invokeProgress(listener, "l0", new Class[]{String.class, Integer.TYPE, String.class}, taskId, Integer.valueOf(code), msg);
    }

    /**
     * 安静关闭ParcelFileDescriptor，失败时只记录异常
     */
    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            if (pfd != null) {
                pfd.close();
            }
        } catch (Exception e) {
            logError("close ParcelFileDescriptor failed", e);
        }
    }

    /**
     * 统一记录异常日志；正常路径不输出日志，避免刷屏
     */
    private static void logError(String message, Throwable e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
}
