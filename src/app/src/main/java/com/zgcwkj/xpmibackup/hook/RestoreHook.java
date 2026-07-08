package com.zgcwkj.xpmibackup.hook;

import com.zgcwkj.comm.ConfigHelp;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import android.util.Xml;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;

/**
 * 恢复功能Hook
 * 从云存储读取descript.xml构建恢复列表，恢复时下载.bak文件替换DFS
 */
public class RestoreHook {

    private static final String TAG = "XpMiBackup";

    // loading是我们自己addView叠加的，用弱引用记录"容器+wrapper"，加载完成时直接removeView，
    // 无需遍历DecorView找它。用WeakRef避免持有已销毁的Fragment导致泄漏
    private static volatile java.lang.ref.WeakReference<android.view.ViewGroup> sLoadingContainer;
    private static volatile java.lang.ref.WeakReference<android.view.View> sLoadingView;

    /**
     * 注册所有恢复相关Hook
     */
    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookNASVFileOperationUtils(lpparam);
        hookRestoreLoader(lpparam);
        hookDownloadFromNas(lpparam);
        hookRestoreLoading(lpparam);
    }

    /**
     * 拦截NASVFileOperationUtils所有静态方法
     * DFS服务不存在时这些操作会超时阻塞，hook后同步返回成功值
     */
    private void hookNASVFileOperationUtils(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.dfs.NASVFileOperationUtils", lpparam.classLoader);
            for (var m : clazz.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    final var methodName = m.getName();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        /** 根据方法名返回对应的mock成功值，避免DFS超时阻塞 */
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (methodName.equals("exist") || methodName.equals("rmdir") || methodName.equals("rename") || methodName.equals("delete")) {
                                param.setResult(Boolean.TRUE);
                            } else if (methodName.equals("mkdir")) {
                                param.setResult(0);
                            } else if (methodName.equals("list")) {
                                param.setResult(new java.util.ArrayList());
                            } else if (methodName.equals("getMd5")) {
                                param.setResult("d41d8cd98f00b204e9800998ecf8427e");
                            }
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 拦截NasBackupsLoader.doInBackground，从云端读取XML构建恢复列表
     * 设置descriptor.path为本地临时目录，让bakFilePath指向正确路径
     */
    private void hookRestoreLoader(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.BackupLoader$NasBackupsLoader", lpparam.classLoader);
            var bdClazz = XposedHelpers.findClass("com.miui.backup.data.BackupDescriptor", lpparam.classLoader);

            var buildMethod = bdClazz.getDeclaredMethod("buildVersion2FromStream", org.xmlpull.v1.XmlPullParser.class, String.class);
            buildMethod.setAccessible(true);

            // hook doInBackground：替换原始加载逻辑，从云端读取所有descript.xml构建恢复列表
            XposedHelpers.findAndHookMethod(clazz, "doInBackground", Void[].class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        // 先清理旧备份，再加载列表，避免并发冲突
                        com.zgcwkj.comm.CloudFileHelp.cleanupOldBackups();
                        var xmlEntries = com.zgcwkj.comm.CloudFileHelp.readBackupXmls();
                        var resultList = new ArrayList<>();

                        for (var entry : xmlEntries) {
                            try {
                                var sep = entry.indexOf("|");
                                if (sep < 0) continue;
                                var dirName = entry.substring(0, sep);
                                var xmlContent = entry.substring(sep + 1);

                                // 解析XML构建BackupDescriptor
                                var parser = Xml.newPullParser();
                                parser.setInput(new StringReader(xmlContent));
                                var descriptor = buildMethod.invoke(null, parser, dirName);
                                if (descriptor == null) continue;

                                // 设置恢复文件存储位置
                                var deviceId = ConfigHelp.getString("device_id", "smb_backup");
                                var localDir = ConfigHelp.BACKUP_ROOT + "/AllBackupTemp/" + deviceId + "/" + dirName;
                                XposedHelpers.setObjectField(descriptor, "path", localDir);

                                resultList.add(descriptor);
                            } catch (Exception ignored) {}
                        }

                        param.setResult(resultList);
                        // 加载完成：切回主线程清掉loading提示（无论结果是否为空都需清，否则空列表时一直转圈）
                        clearLoadingOnUi();
                    } catch (Exception e) {
                        var cause = e.getCause() != null ? e.getCause() : e;
                        android.util.Log.e(TAG, "restore load error: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                        param.setResult(new ArrayList<>());
                        clearLoadingOnUi();
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 拦截downloadFromNasTask，用云端替换DFS下载
     * 下载完成后调用onRestoreTransferToNasFinish触发NASTransferService的完成流程
     */
    private void hookDownloadFromNas(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.nas.NASTransferService$downloadFromNasTask", lpparam.classLoader);

            // hook run：替换DFS下载为云端下载（异步执行，避免阻塞调用线程）
            XposedHelpers.findAndHookMethod(clazz, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    var taskItem = XposedHelpers.getObjectField(param.thisObject, "item");
                    var nasService = XposedHelpers.getObjectField(param.thisObject, "nasTransferServiceWeakReference");

                    var bakFilePath = (String) XposedHelpers.getObjectField(taskItem, "bakFilePath");
                    var localFile = new File(bakFilePath);
                    var localDir = localFile.getParentFile();
                    if (localDir == null) {
                        param.setResult(null);
                        return;
                    }

                    var localDirName = localDir.getName();
                    var bakFileName = localFile.getName();
                    var backupPath = ConfigHelp.getString("backup_path", "");
                    var remotePath = backupPath + "/" + localDirName + "/" + bakFileName;

                    if (!localDir.exists()) localDir.mkdirs();

                    // 异步执行云端下载与后续状态回调，避免网络I/O阻塞调用线程
                    new Thread(() -> {
                        try {
                            // 执行云端下载
                            com.zgcwkj.comm.CloudFileHelp.downloadFile(remotePath, bakFilePath);

                            if (new File(bakFilePath).exists()) {
                                // 下载成功：设置任务状态为完成，触发NASTransferService完成回调
                                if (nasService != null) {
                                    var actualService = nasService instanceof java.lang.ref.WeakReference
                                        ? ((java.lang.ref.WeakReference) nasService).get()
                                        : nasService;
                                    if (actualService != null) {
                                        XposedHelpers.setIntField(taskItem, "nasTaskState", 6);
                                        var nasServiceClass = XposedHelpers.findClass("com.miui.backup.nas.NASTransferService", lpparam.classLoader);
                                        var finishMethod = nasServiceClass.getDeclaredMethod("onRestoreTransferToNasFinish",
                                            XposedHelpers.findClass("com.miui.backup.bean.TaskItem", lpparam.classLoader));
                                        finishMethod.setAccessible(true);
                                        finishMethod.invoke(actualService, taskItem);
                                    }
                                }
                            } else {
                                // 下载失败：设置错误状态并通知UI
                                if (nasService != null) {
                                    var actualService = nasService instanceof java.lang.ref.WeakReference
                                        ? ((java.lang.ref.WeakReference) nasService).get()
                                        : nasService;
                                    if (actualService != null) {
                                        XposedHelpers.setIntField(taskItem, "nasTaskState", 9);
                                        XposedHelpers.setIntField(taskItem, "error", 1);
                                        var nasServiceClass = XposedHelpers.findClass("com.miui.backup.nas.NASTransferService", lpparam.classLoader);
                                        var notifyMethod = nasServiceClass.getDeclaredMethod("notifyItemTaskError", String.class, int.class, int.class);
                                        notifyMethod.setAccessible(true);
                                        var packageName = (String) XposedHelpers.getObjectField(taskItem, "packageName");
                                        var feature = XposedHelpers.getIntField(taskItem, "feature");
                                        notifyMethod.invoke(actualService, packageName, feature, 1);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }).start();

                    param.setResult(null);
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 恢复列表加载loading提示
     * 进入恢复页面时显示加载动画；loading是我们自己addView叠加的，
     * 数据加载完成（无论有无数据）由 clearLoadingOnUi() 主动移除，不依赖目标APP方法名
     * 关键：云端无数据时目标APP只显示空态、不会移除我们塞的loading，必须自己清，否则一直转圈
     */
    private void hookRestoreLoading(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.nas.NASRestoreFragment", lpparam.classLoader);

            // hook onViewCreated：在empty tip区域添加旋转圈+文字的加载提示
            XposedHelpers.findAndHookMethod(clazz, "onViewCreated",
                android.view.View.class, android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        var view = (android.view.View) param.args[0];
                        // 容器用根view，loading叠加在最上层，与目标APP的空态/列表互不干扰
                        if (!(view instanceof android.view.ViewGroup)) return;
                        var container = (android.view.ViewGroup) view;
                        var ctx = view.getContext();

                        {
                            // 构造居中的加载指示器（半透明白底遮罩）
                            var wrapper = new android.widget.LinearLayout(ctx);
                            wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
                            wrapper.setGravity(android.view.Gravity.CENTER);

                            var pb = new android.widget.ProgressBar(ctx);
                            pb.setIndeterminate(true);
                            var pbLp = new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                            pbLp.bottomMargin = (int)(16 * ctx.getResources().getDisplayMetrics().density);
                            wrapper.addView(pb, pbLp);

                            var tv = new android.widget.TextView(ctx);
                            tv.setText("正在加载备份列表…");
                            tv.setTextSize(14f);
                            tv.setTextColor(0xFF666666);
                            tv.setGravity(android.view.Gravity.CENTER);
                            wrapper.addView(tv);

                            var wrapperLp = new android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                            wrapper.setPadding(32, 0, 32, 0);
                            container.addView(wrapper, wrapperLp);

                            // 记录引用，加载完成时直接removeView，无需遍历查找
                            sLoadingContainer = new java.lang.ref.WeakReference<>(container);
                            sLoadingView = new java.lang.ref.WeakReference<>(wrapper);
                        }
                    }
                });
        } catch (Throwable ignored) {}
    }

    /** 切回主线程移除loading（doInBackground在后台线程返回，需post到UI线程操作view） */
    private static void clearLoadingOnUi() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            var c = sLoadingContainer != null ? sLoadingContainer.get() : null;
            var v = sLoadingView != null ? sLoadingView.get() : null;
            if (c != null && v != null) c.removeView(v);
            sLoadingContainer = null;
            sLoadingView = null;
        });
    }
}
