package com.zgcwkj.xpmibackup.hook;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import com.zgcwkj.comm.CloudFileHelp;
import com.zgcwkj.comm.LogHelp;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 处理小米备份应用自身页面行为，不放入AIDL重定向逻辑。
 */
public class BackupHook {
    private static final String TAG = "XpMiBackup";
    private static final int NOTIFICATION_ID_WORKING = 2131886134;
    private static final int NOTIFICATION_ID_FINISH = 2131886135;
    private static final int NOTIFICATION_ID_SUSPEND = 2131886137;
    private static final ThreadLocal<Boolean> closingProgressPage = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Set<String> activeBackupDirs = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final WeakHashMap<Object, Object> nasRecyclerViews = new WeakHashMap<>();

    /**
     * 安装备份应用页面相关Hook。
     */
    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookFinishButtonClick();
        hookNasTransferAdapterScroll(lpparam);
        hookSuspendNotification(lpparam);
        hookBackupNotifications();
        hookForegroundNotificationStop();
        hookNasAbort(lpparam);
    }

    /**
     * 按关闭按钮的资源ID包装点击事件，直接关闭任务栈，避免原逻辑跳回密码页。
     */
    private void hookFinishButtonClick() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "setOnClickListener", View.OnClickListener.class, new XC_MethodHook() {
                /**
                 * 关闭按钮绑定监听时替换为代理监听，避免依赖混淆方法名。
                 */
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    var view = (View) param.thisObject;
                    var listener = (View.OnClickListener) param.args[0];
                    if (listener != null && isCloseButton(view) && !(listener instanceof CloseClickListener)) {
                        param.args[0] = new CloseClickListener();
                    }
                }
            });
        } catch (Throwable e) {
            logError("BackupHook: hook finish button click failed", e);
        }
    }

    /**
     * 判断当前View是否为备份进度页的完成或退出按钮。
     */
    private static boolean isCloseButton(View view) {
        if (view == null || view.getId() == View.NO_ID) {
            return false;
        }
        try {
            var name = view.getResources().getResourceEntryName(view.getId());
            return "button_finish".equals(name) || "button_exit".equals(name);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Hook NAS进度列表适配器，让当前备份项开始时滚动到对应位置。
     */
    private void hookNasTransferAdapterScroll(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var adapterClass = XposedHelpers.findClass("com.miui.backup.adapter.NASTransferAdapter", lpparam.classLoader);
            var recyclerViewClass = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", lpparam.classLoader);
            hookRecyclerViewSetAdapter(adapterClass, recyclerViewClass);
            hookNasTransferListener(lpparam);
            hookAdapterItemStart(adapterClass);
        } catch (Throwable e) {
            logError("BackupHook: hook NAS transfer adapter scroll failed", e);
        }
    }

    /**
     * 从RecyclerView设置Adapter的公开入口记录NAS列表，兼容新版Adapter没有重写附着方法的情况。
     */
    private void hookRecyclerViewSetAdapter(Class<?> adapterClass, Class<?> recyclerViewClass) {
        try {
            var recyclerAdapterClass = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$Adapter", recyclerViewClass.getClassLoader());
            XposedHelpers.findAndHookMethod(recyclerViewClass, "setAdapter", recyclerAdapterClass, new XC_MethodHook() {
                /**
                 * RecyclerView绑定NAS Adapter时保存映射，其它Adapter直接忽略。
                 */
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    var adapter = param.args[0];
                    if (adapterClass.isInstance(adapter)) {
                        synchronized (nasRecyclerViews) {
                            nasRecyclerViews.put(adapter, param.thisObject);
                        }
                    }
                }
            });
        } catch (Throwable e) {
            logError("BackupHook: hook RecyclerView setAdapter failed", e);
        }
    }

    /**
     * Hook进度页接收NAS开始事件的公开回调，确保UI线程更新后再尝试滚动当前项。
     */
    private void hookNasTransferListener(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var listenerClass = XposedHelpers.findClass("com.miui.backup.activity.ProgressPageFragmentBase$1", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(listenerClass, "onItemTaskStart", String.class, Integer.TYPE, new XC_MethodHook() {
                /**
                 * 原回调会投递UI更新，这里再投递一次滚动，避免刷新和滚动顺序相反。
                 */
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    postScrollAllNasRecycler((String) param.args[0], ((Integer) param.args[1]).intValue());
                }
            });
        } catch (Throwable e) {
            logError("BackupHook: hook NAS transfer listener failed", e);
        }
    }

    /**
     * Hook旧版公开itemStart开始事件方法，作为监听回调之外的滚动兜底。
     */
    private void hookAdapterItemStart(Class<?> adapterClass) {
        try {
            XposedHelpers.findAndHookMethod(adapterClass, "itemStart", String.class, Integer.TYPE, new XC_MethodHook() {
                /**
                 * 原方法更新状态后滚动到当前任务位置。
                 */
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    scrollNasRecyclerToTask(param.thisObject, (String) param.args[0], ((Integer) param.args[1]).intValue());
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 根据任务包名和feature查找列表下标，并让RecyclerView滚动过去。
     */
    private static void scrollNasRecyclerToTask(Object adapter, String packageName, int feature) {
        try {
            var recyclerView = getNasRecyclerView(adapter);
            var index = findAdapterTaskIndex(adapter, packageName, feature);
            if (recyclerView != null && index >= 0) {
                scrollRecyclerToPosition(recyclerView, index);
            }
        } catch (Throwable e) {
            logError("BackupHook: scroll NAS recycler failed", e);
        }
    }

    /**
     * 对当前页面所有NAS列表投递滚动任务，用于监听回调先于Adapter刷新完成的场景。
     */
    private static void postScrollAllNasRecycler(String packageName, int feature) {
        synchronized (nasRecyclerViews) {
            for (var adapter : nasRecyclerViews.keySet()) {
                scrollNasRecyclerToTask(adapter, packageName, feature);
            }
        }
    }

    /**
     * 在RecyclerView消息队列中执行滚动，确保notifyItemChanged之后布局状态稳定。
     */
    private static void scrollRecyclerToPosition(Object recyclerView, int index) {
        if (recyclerView instanceof View) {
            ((View) recyclerView).post(new Runnable() {
                /**
                 * 让RecyclerView平滑滚动到当前备份项位置。
                 */
                @Override
                public void run() {
                    try {
                        XposedHelpers.callMethod(recyclerView, "smoothScrollToPosition", index);
                    } catch (Throwable e) {
                        logError("BackupHook: run NAS recycler scroll failed", e);
                    }
                }
            });
            return;
        }
        XposedHelpers.callMethod(recyclerView, "smoothScrollToPosition", index);
    }

    /**
     * 读取Adapter当前附着的RecyclerView。
     */
    private static Object getNasRecyclerView(Object adapter) {
        synchronized (nasRecyclerViews) {
            return nasRecyclerViews.get(adapter);
        }
    }

    /**
     * 从NASTransferAdapter数据列表中查找任务下标。
     */
    private static int findAdapterTaskIndex(Object adapter, String packageName, int feature) throws Exception {
        var data = findTaskList(adapter);
        if (data == null) {
            return -1;
        }
        for (var i = 0; i < data.size(); i++) {
            var taskItem = data.get(i);
            if (packageName.equals(readField(taskItem, "packageName")) && ((Integer) readField(taskItem, "feature")).intValue() == feature) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 兼容旧版mDataList和新版混淆字段，找出Adapter里的TaskItem列表。
     */
    private static List<?> findTaskList(Object adapter) throws Exception {
        for (var fieldName : new String[]{"mDataList", "f1679f"}) {
            try {
                var value = readField(adapter, fieldName);
                if (value instanceof List) {
                    return (List<?>) value;
                }
            } catch (Throwable ignored) {}
        }
        for (var field : adapter.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            var value = field.get(adapter);
            if (value instanceof List) {
                return (List<?>) value;
            }
        }
        return null;
    }

    /**
     * 代理关闭按钮点击，直接关闭整个任务栈。
     */
    private static class CloseClickListener implements View.OnClickListener {
        /**
         * 点击完成或退出按钮后关闭任务栈。
         */
        @Override
        public void onClick(View view) {
            var activity = findActivity(view == null ? null : view.getContext());
            cancelBackupNotifications(activity);
            launchHome(activity);
            closeProgressTask(activity);
        }
    }

    /**
     * 只替换连接断开导致的暂停通知，保留其它备份通知。
     */
    private void hookSuspendNotification(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var notificationUtilsClass = XposedHelpers.findClass("com.miui.backup.NotificationUtils", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(notificationUtilsClass, "d", Context.class, Class.class, Integer.TYPE, new XC_MethodHook() {
                /**
                 * 连接断开的暂停通知会误导用户，这里换回当前进度通知。
                 */
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    var reason = ((Integer) param.args[2]).intValue();
                    if (reason == 11) {
                        param.setResult(buildCurrentProgressNotification(notificationUtilsClass, (Context) param.args[0], (Class<?>) param.args[1]));
                    }
                }
            });
        } catch (Throwable e) {
            logError("BackupHook: hook suspend notification failed", e);
        }
    }

    /**
     * Hook通知发送入口，拦截恢复完成和连接断开的暂停通知。
     */
    private void hookBackupNotifications() {
        try {
            var hook = new XC_MethodHook() {
                /**
                 * 小米备份进程发送通知前，按通知ID和内容判断是否需要拦截。
                 */
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    var id = ((Integer) param.args[param.args.length - 2]).intValue();
                    var notification = (Notification) param.args[param.args.length - 1];
                    if (shouldDropBackupNotification(id, notification)) {
                        cancelBackupNotifications((NotificationManager) param.thisObject);
                        param.setResult(null);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(NotificationManager.class, "notify", Integer.TYPE, Notification.class, hook);
            XposedHelpers.findAndHookMethod(NotificationManager.class, "notify", String.class, Integer.TYPE, Notification.class, hook);
        } catch (Throwable e) {
            logError("BackupHook: hook backup notifications failed", e);
        }
    }

    /**
     * Hook前台服务退出入口，清掉恢复结束后残留的前台服务通知。
     */
    private void hookForegroundNotificationStop() {
        try {
            var hook = new XC_MethodHook() {
                /**
                 * 服务退出前台状态后再取消通知，避免运行过程中误清前台服务通知。
                 */
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof Service) {
                        cancelBackupNotifications((Service) param.thisObject);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(Service.class, "stopForeground", Boolean.TYPE, hook);
            XposedHelpers.findAndHookMethod(Service.class, "stopForeground", Integer.TYPE, hook);
        } catch (Throwable e) {
            logError("BackupHook: hook foreground notification stop failed", e);
        }
    }

    /**
     * 判断当前通知是否应该被拦截。
     */
    private static boolean shouldDropBackupNotification(int id, Notification notification) {
        if (id == NOTIFICATION_ID_SUSPEND) {
            return true;
        }
        return id == NOTIFICATION_ID_FINISH && isRestoreNotification(notification);
    }

    /**
     * 通过标题和内容判断是否为恢复结果通知，避免误拦备份完成通知。
     */
    private static boolean isRestoreNotification(Notification notification) {
        if (notification == null) {
            return false;
        }
        var text = readNotificationText(notification.extras, "android.title") + "\n"
                + readNotificationText(notification.extras, "android.text") + "\n"
                + readNotificationText(notification.extras, "android.bigText");
        var lower = text.toLowerCase(java.util.Locale.ROOT);
        return text.contains("恢复") || text.contains("還原") || text.contains("恢復") || lower.contains("restore") || lower.contains("restored");
    }

    /**
     * 从通知附加数据里读取文本字段。
     */
    private static String readNotificationText(Bundle extras, String key) {
        if (extras == null) {
            return "";
        }
        var value = extras.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Hook NAS取消入口，备份中途取消时清理已经写到云端的半成品目录。
     */
    private void hookNasAbort(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookStubAbortMethods(lpparam);
        } catch (Throwable e) {
            logError("BackupHook: hook NAS abort failed", e);
        }
    }

    /**
     * Hook旧版/新版INASTransferService Stub里的取消方法。
     */
    private void hookStubAbortMethods(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var stubClass = XposedHelpers.findClass("com.miui.backup.nas.NASTransferService$NASTransferServiceStub", lpparam.classLoader);
            hookNoArgMethod(stubClass, "abortNASTask");
            hookNoArgMethod(stubClass, "stopNasTransferTask");
        } catch (Throwable e) {
            logError("BackupHook: hook NAS abort stub failed", e);
        }
    }

    /**
     * 对无参数取消方法安装前置清理逻辑。
     */
    private void hookNoArgMethod(Class<?> clazz, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodHook() {
                /**
                 * 原备份取消逻辑执行前先删除云端半成品，避免任务ID映射随后被清空。
                 */
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    cleanupActiveBackupDirsAsync();
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 复用备份应用自己的通知构建方法，避免返回空通知导致系统notify异常。
     */
    private static Notification buildCurrentProgressNotification(Class<?> notificationUtilsClass, Context context, Class<?> targetClass) {
        try {
            return (Notification) XposedHelpers.callStaticMethod(notificationUtilsClass, "e", context, targetClass);
        } catch (Throwable e) {
            logError("BackupHook: build current progress notification failed", e);
            return new Notification.Builder(context).setSmallIcon(android.R.drawable.stat_sys_upload_done).build();
        }
    }

    /**
     * 在AIDL上传开始时同步通知进度页当前备份项，恢复列表自动滚动到正在处理的项目。
     */
    public static void notifyNasItemTaskStart(Object progressListener, String taskId) {
        if (progressListener == null || taskId == null) {
            return;
        }
        try {
            var service = findNasTransferService(progressListener);
            if (service == null) {
                return;
            }
            var taskItem = findTaskItem(service.getClass().getClassLoader(), taskId);
            if (taskItem == null) {
                return;
            }
            var packageName = (String) readField(taskItem, "packageName");
            var feature = ((Integer) readField(taskItem, "feature")).intValue();
            invokeNasItemTaskStart(service, packageName, feature);
        } catch (Throwable e) {
            logError("BackupHook: notify NAS item task start failed", e);
        }
    }

    /**
     * 记录本次备份已经创建或即将创建的云端目录，取消时用于清理残留。
     */
    public static void recordActiveBackupDir(String remoteDir) {
        if (remoteDir == null || remoteDir.isEmpty()) {
            return;
        }
        activeBackupDirs.add(remoteDir);
    }

    /**
     * 正常完成后清空取消清理队列，避免后续误删已经成功的备份。
     */
    public static void clearActiveBackupDirs() {
        activeBackupDirs.clear();
    }

    /**
     * 异步删除本次备份已经上传到云端的半成品目录。
     */
    private static void cleanupActiveBackupDirsAsync() {
        var dirs = snapshotActiveBackupDirs();
        if (dirs.isEmpty()) {
            return;
        }
        activeBackupDirs.clear();
        new Thread(new Runnable() {
            /**
             * 在后台执行云端删除，避免阻塞小米备份自己的取消流程。
             */
            @Override
            public void run() {
                for (var dir : dirs) {
                    CloudFileHelp.deleteRemoteDir(dir);
                }
            }
        }).start();
    }

    /**
     * 拷贝当前待清理目录集合，避免删除时持有集合锁。
     */
    private static Set<String> snapshotActiveBackupDirs() {
        synchronized (activeBackupDirs) {
            return new LinkedHashSet<>(activeBackupDirs);
        }
    }

    /**
     * 从IFileOperationProgressListener内部类取回外部NASTransferService实例。
     */
    private static Object findNasTransferService(Object progressListener) throws IllegalAccessException {
        for (var field : progressListener.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            var value = field.get(progressListener);
            if (value != null && "com.miui.backup.nas.NASTransferService".equals(value.getClass().getName())) {
                return value;
            }
        }
        return null;
    }

    /**
     * 根据DFS任务ID从NASBackupDataCenter中找到对应的TaskItem。
     */
    private static Object findTaskItem(ClassLoader classLoader, String taskId) throws Exception {
        var dataCenterClass = XposedHelpers.findClass("com.miui.backup.nas.NASBackupDataCenter", classLoader);
        var dataCenter = getDataCenterInstance(dataCenterClass);
        if (dataCenter == null) {
            return null;
        }
        var index = getTaskIndex(dataCenter, taskId);
        if (index < 0) {
            return null;
        }
        return getTaskItem(dataCenter, index);
    }

    /**
     * 兼容旧版getInstance和新版混淆后的单例方法。
     */
    private static Object getDataCenterInstance(Class<?> dataCenterClass) throws Exception {
        try {
            return XposedHelpers.callStaticMethod(dataCenterClass, "getInstance");
        } catch (Throwable ignored) {
            return XposedHelpers.callStaticMethod(dataCenterClass, "i");
        }
    }

    /**
     * 兼容旧版SafeTaskIdMapGet和新版混淆后的任务ID索引查询方法。
     */
    private static int getTaskIndex(Object dataCenter, String taskId) throws Exception {
        for (var methodName : new String[]{"SafeTaskIdMapGet", "b"}) {
            try {
                return ((Integer) XposedHelpers.callMethod(dataCenter, methodName, taskId)).intValue();
            } catch (Throwable ignored) {}
        }
        for (var method : dataCenter.getClass().getDeclaredMethods()) {
            if (isIntMethod(method, String.class)) {
                method.setAccessible(true);
                return ((Integer) method.invoke(dataCenter, taskId)).intValue();
            }
        }
        return -1;
    }

    /**
     * 兼容旧版getItemByIndexSafe和新版混淆后的TaskItem查询方法。
     */
    private static Object getTaskItem(Object dataCenter, int index) throws Exception {
        for (var methodName : new String[]{"getItemByIndexSafe", "j"}) {
            try {
                return XposedHelpers.callMethod(dataCenter, methodName, index);
            } catch (Throwable ignored) {}
        }
        for (var method : dataCenter.getClass().getDeclaredMethods()) {
            if (isTaskItemMethod(method)) {
                method.setAccessible(true);
                return method.invoke(dataCenter, index);
            }
        }
        return null;
    }

    /**
     * 判断方法是否是单个指定参数并返回int。
     */
    private static boolean isIntMethod(Method method, Class<?> argType) {
        var types = method.getParameterTypes();
        return method.getReturnType() == Integer.TYPE && types.length == 1 && types[0] == argType;
    }

    /**
     * 判断方法是否是按索引返回TaskItem。
     */
    private static boolean isTaskItemMethod(Method method) {
        var types = method.getParameterTypes();
        return types.length == 1 && types[0] == Integer.TYPE && "com.miui.backup.bean.TaskItem".equals(method.getReturnType().getName());
    }

    /**
     * 调用NASTransferService自己的开始事件广播，让进度页按原逻辑滚动到当前项。
     */
    private static void invokeNasItemTaskStart(Object service, String packageName, int feature) throws Exception {
        if (packageName == null) {
            return;
        }
        for (var methodName : new String[]{"notifyItemTaskStart", "n1"}) {
            try {
                XposedHelpers.callMethod(service, methodName, packageName, feature);
                return;
            } catch (Throwable ignored) {}
        }
        for (var method : service.getClass().getDeclaredMethods()) {
            if (method.getReturnType() == Void.TYPE && isMethod(method, String.class, Integer.TYPE)) {
                method.setAccessible(true);
                method.invoke(service, packageName, feature);
                return;
            }
        }
    }

    /**
     * 判断方法参数是否完全匹配。
     */
    private static boolean isMethod(Method method, Class<?>... expectedTypes) {
        var types = method.getParameterTypes();
        if (types.length != expectedTypes.length) {
            return false;
        }
        for (var i = 0; i < types.length; i++) {
            if (types[i] != expectedTypes[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读取对象字段，优先按名称读取，避免依赖字段顺序。
     */
    private static Object readField(Object instance, String fieldName) throws Exception {
        var field = findField(instance.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(instance);
    }

    /**
     * 在类层级中查找字段。
     */
    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        var current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /**
     * 把进度页任务退到后台，不销毁Activity，避免ProgressPageFragmentBase.onDestroy清理通知。
     */
    private static void closeProgressTask(Activity activity) {
        if (activity == null || Boolean.TRUE.equals(closingProgressPage.get())) {
            return;
        }
        closingProgressPage.set(Boolean.TRUE);
        try {
            activity.moveTaskToBack(true);
        } catch (Throwable e) {
            logError("BackupHook: move ProgressPageActivity task to back failed", e);
        } finally {
            closingProgressPage.set(Boolean.FALSE);
        }
    }

    /**
     * 只清理小米备份自己的进度、完成和暂停通知，不影响系统其它通知。
     */
    private static void cancelBackupNotifications(Context context) {
        if (context == null) {
            return;
        }
        try {
            var manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            cancelBackupNotifications(manager);
        } catch (Throwable e) {
            logError("BackupHook: cancel backup notifications failed", e);
        }
    }

    /**
     * 只清理小米备份自己的固定通知ID。
     */
    private static void cancelBackupNotifications(NotificationManager manager) {
        if (manager == null) {
            return;
        }
        manager.cancel(NOTIFICATION_ID_WORKING);
        manager.cancel(NOTIFICATION_ID_FINISH);
        manager.cancel(NOTIFICATION_ID_SUSPEND);
    }

    /**
     * 先回到桌面，避免原完成逻辑跳到备份密码界面。
     */
    private static void launchHome(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            var intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable e) {
            logError("BackupHook: launch home failed", e);
        }
    }

    /**
     * 从View的Context链里查找宿主Activity。
     */
    private static Activity findActivity(Context context) {
        var current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    /**
     * 统一记录异常日志，正常路径不输出日志。
     */
    private static void logError(String message, Throwable e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
}
