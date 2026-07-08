package com.zgcwkj.xpmibackup.hook;

import com.zgcwkj.comm.ConfigHelp;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 备份功能Hook
 * 拦截小米备份APP的DFS连接、NAS传输、文件上传等流程
 * 将备份重定向到云端存储
 */
public class BackupHook {

    private static final String TAG = "XpMiBackup";

    /** 当前备份的云端目录路径（backup_path/timestamp） */
    private static volatile String sCurrentRemoteDir;

    /** 有界线程池：从配置读取上传线程数，默认3 */
    private static volatile java.util.concurrent.ExecutorService uploadExecutor;

    private static java.util.concurrent.ExecutorService getUploadExecutor() {
        if (uploadExecutor == null || uploadExecutor.isShutdown()) {
            var threads = com.zgcwkj.comm.ConfigHelp.getInt("upload_threads", 3);
            uploadExecutor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        }
        return uploadExecutor;
    }

    /** 注册所有备份相关Hook */
    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookDeviceConnector(lpparam);
        hookDistFileClientService(lpparam);
        hookNASTransferService(lpparam);
        hookDistFileClientImpl(lpparam);
        hookNotificationFilter(lpparam);
    }

    /**
     * 拦截DFS连接流程 - 用真实连接验证NAS状态
     * 先尝试握手，成功则模拟CONNECTED，失败则放行原方法（会走到连接失败逻辑）
     */
    private void hookDeviceConnector(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.xiaomi.dist.file.client.kit.connect.DeviceConnector", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "doConnect", new XC_MethodHook() {
                /** 拦截DFS连接，测试云端连通性 */
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 测试云端连接是否可达
                    if (!com.zgcwkj.comm.CloudFileHelp.testConnection()) return;
                    // 阻止原始doConnect执行
                    param.setResult(null);
                    // 构造mock客户端并设置连接状态为CONNECTED
                    var connector = param.thisObject;
                    var deviceId = (String) XposedHelpers.getObjectField(connector, "mDeviceId");
                    var clientImplClass = XposedHelpers.findClass("com.xiaomi.dist.file.client.kit.manager.DistFileClientImpl", lpparam.classLoader);
                    var mockClient = XposedHelpers.newInstance(clientImplClass, deviceId);
                    var stateClass = XposedHelpers.findClass("com.xiaomi.dist.file.client.kit.connect.DeviceConnector$ConnectionState", lpparam.classLoader);
                    var connectedState = Enum.valueOf((Class<Enum>) stateClass, "CONNECTED");
                    var stateRef = (java.util.concurrent.atomic.AtomicReference) XposedHelpers.getObjectField(connector, "mConnectionState");
                    stateRef.set(connectedState);
                    var clientRef = (java.util.concurrent.atomic.AtomicReference) XposedHelpers.getObjectField(connector, "mClientRef");
                    clientRef.set(mockClient);
                    // 触发连接成功回调链
                    var notifyMethod = clazz.getDeclaredMethod("notifyConnectSuccess");
                    notifyMethod.setAccessible(true);
                    notifyMethod.invoke(connector);
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 拦截DistFileClientService单例 - 强制设置NAS连接状态
     * mock mDeviceInfo(PathInfo)、mDistFileClient(DistFileClientImpl)等字段
     */
    private void hookDistFileClientService(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.dfs.DistFileClientService", lpparam.classLoader);

            // hook notifyNASConnectSuccess：连接成功时mock PathInfo和DistFileClientImpl
            XposedHelpers.findAndHookMethod(clazz, "notifyNASConnectSuccess", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    var instance = param.thisObject;
                    XposedHelpers.setObjectField(instance, "mNASisConnected", true);
                    // 若mDeviceInfo为空，通过Unsafe分配mock PathInfo填充磁盘信息
                    var deviceInfo = XposedHelpers.getObjectField(instance, "mDeviceInfo");
                    if (deviceInfo == null) {
                        try {
                            var pathInfoClass = XposedHelpers.findClass("com.xiaomi.dist.file.client.common.model.PathInfo", lpparam.classLoader);
                            var unsafeClass = Class.forName("sun.misc.Unsafe");
                            var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
                            unsafeField.setAccessible(true);
                            var unsafe = unsafeField.get(null);
                            var mockPathInfo = XposedHelpers.callMethod(unsafe, "allocateInstance", pathInfoClass);
                            var f1 = pathInfoClass.getDeclaredField("mAbsolutePath");
                            f1.setAccessible(true); f1.set(mockPathInfo, ConfigHelp.getString("backup_path", ""));
                            var f2 = pathInfoClass.getDeclaredField("mDiskName");
                            f2.setAccessible(true); f2.set(mockPathInfo, ConfigHelp.getString("device_id", ""));
                            var f3 = pathInfoClass.getDeclaredField("mTotalSize");
                            f3.setAccessible(true); f3.setLong(mockPathInfo, 10000000000000L);
                            var f4 = pathInfoClass.getDeclaredField("mAvailableSize");
                            f4.setAccessible(true); f4.setLong(mockPathInfo, 9000000000000L);
                            var f5 = pathInfoClass.getDeclaredField("mType");
                            f5.setAccessible(true); f5.setInt(mockPathInfo, 0);
                            var f6 = pathInfoClass.getDeclaredField("mShowPathInfo");
                            f6.setAccessible(true); f6.setBoolean(mockPathInfo, true);
                            var f7 = pathInfoClass.getDeclaredField("mSubPaths");
                            f7.setAccessible(true); f7.set(mockPathInfo, new java.util.ArrayList());
                            XposedHelpers.setObjectField(instance, "mDeviceInfo", mockPathInfo);
                        } catch (Exception ignored) {}
                    }
                    // 若mDistFileClient为空，创建mock实例
                    var distFileClient = XposedHelpers.getObjectField(instance, "mDistFileClient");
                    if (distFileClient == null) {
                        var id = (String) XposedHelpers.getObjectField(instance, "mDeviceId");
                        if (id != null) {
                            var clientImplClass = XposedHelpers.findClass("com.xiaomi.dist.file.client.kit.manager.DistFileClientImpl", lpparam.classLoader);
                            var mockClient = XposedHelpers.newInstance(clientImplClass, id);
                            XposedHelpers.setObjectField(instance, "mDistFileClient", mockClient);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 拦截NASTransferService静态方法
     * 阻止"暂时无法备份"和"NAS服务占用"的提示，abort时清理云端文件
     */
    private void hookNASTransferService(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.nas.NASTransferService", lpparam.classLoader);

            // hook isNASServiceWorkingFromPreference：始终返回false，表示NAS服务空闲
            XposedHelpers.findAndHookMethod(clazz, "isNASServiceWorkingFromPreference", android.content.Context.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    p.setResult(false);
                }
            });

            // hook isNASTransferServiceRunningByNASApp：始终返回false，表示NAS应用未占用
            XposedHelpers.findAndHookMethod(clazz, "isNASTransferServiceRunningByNASApp", android.content.Context.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    p.setResult(false);
                }
            });

            // hook abortNASTransferTask：取消备份时删除云端文件、清除通知、通知UI刷新
            XposedHelpers.findAndHookMethod(clazz, "abortNASTransferTask", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    var remoteDir = sCurrentRemoteDir;
                    if (remoteDir != null && !remoteDir.isEmpty()) {
                        sCurrentRemoteDir = null;
                        // 异步删除云端目录，避免阻塞主线程
                        new Thread(() -> com.zgcwkj.comm.CloudFileHelp.deleteRemoteDir(remoteDir)).start();
                    }
                    // 清除所有备份相关通知
                    var service = param.thisObject;
                    var nmField = clazz.getDeclaredField("mNotificationManager");
                    nmField.setAccessible(true);
                    var nm = nmField.get(service);
                    if (nm != null) {
                        var cancelMethod = nm.getClass().getMethod("cancel", int.class);
                        var rStringClass = param.thisObject.getClass().getClassLoader().loadClass("com.miui.backup.R$string");
                        cancelMethod.invoke(nm, XposedHelpers.getStaticIntField(rStringClass, "apps_and_data_item_desc"));
                        cancelMethod.invoke(nm, XposedHelpers.getStaticIntField(rStringClass, "auto_backup_all_user_apps"));
                        cancelMethod.invoke(nm, XposedHelpers.getStaticIntField(rStringClass, "apps_item_desc"));
                    }
                    // 发送广播通知进度页面关闭
                    var ctx = ((android.app.Service) service).getApplicationContext();
                    ctx.sendBroadcast(new android.content.Intent("com.miui.backup.finishProgressPage"));
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 拦截DistFileClientImpl.upload - 将DFS上传重定向到云存储
     * 通过线程池异步上传文件，保留原有DFS回调链驱动状态机
     */
    private void hookDistFileClientImpl(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.xiaomi.dist.file.client.kit.manager.DistFileClientImpl", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "upload",
                String.class,
                XposedHelpers.findClass("com.xiaomi.dist.file.client.kit.manager.VFile", lpparam.classLoader),
                String.class, long.class,
                XposedHelpers.findClass("com.xiaomi.dist.file.client.common.IFileOperationProgressListener", lpparam.classLoader),
                int.class,
                new XC_MethodHook() {
                    /** 拦截DFS上传，异步执行云端上传并返回mock成功结果 */
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        var localPath = (String) param.args[0];
                        var progressListener = param.args[4];
                        // 从本地路径提取时间戳子目录名，拼接云端上传目标路径
                        var localFile = new java.io.File(localPath);
                        var localFileName = localFile.getParentFile().getName();
                        // 上传到的云端远端目录
                        sCurrentRemoteDir = ConfigHelp.getString("backup_path", "") + "/" + localFileName;
                        var taskId = "task_" + System.currentTimeMillis();
                        // 异步执行上传，通过DFS回调链驱动状态机推进
                        getUploadExecutor().execute(() -> com.zgcwkj.comm.CloudFileHelp.uploadWithProgress(localPath, progressListener, sCurrentRemoteDir, taskId));
                        // 返回mock的AsyncResult让uploadTask正常继续
                        var asyncResultClass = XposedHelpers.findClass("com.xiaomi.dist.file.client.kit.utils.AsyncResult", lpparam.classLoader);
                        var asyncResult = XposedHelpers.newInstance(asyncResultClass);
                        XposedHelpers.callMethod(asyncResult, "success", taskId);
                        param.setResult(asyncResult);
                    }
                });
        } catch (Throwable ignored) {}
    }

    /**
     * 兜底：直接拦截"备份暂停"通知的发布
     * R8可能混淆了内部类导致$1 hook静默失败
     */
    private void hookNotificationFilter(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var rStringClass = lpparam.classLoader.loadClass("com.miui.backup.R$string");
            final int suspendId = XposedHelpers.getStaticIntField(rStringClass, "auto_backup_all_user_apps");
            var nmClass = Class.forName("android.app.NotificationManager");

            // hook notify(int, Notification)：过滤掉"备份暂停"通知ID
            XposedHelpers.findAndHookMethod(nmClass, "notify",
                int.class, android.app.Notification.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if ((int) param.args[0] == suspendId) {
                            param.setResult(null);
                        }
                    }
                });
        } catch (Throwable ignored) {}
    }
}
