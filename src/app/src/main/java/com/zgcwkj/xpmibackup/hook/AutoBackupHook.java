package com.zgcwkj.xpmibackup.hook;

import android.app.AlertDialog;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.widget.Toast;
import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.LogHelp;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.TreeMap;

/**
 * 处理小米备份的 NAS 自动备份入口和调度配置。
 */
public class AutoBackupHook {
    private static final String TAG = "XpMiBackup";
    private static final String STATION_PACKAGE = "com.xiaomi.station";
    private static final String STATION_SWITCH_URI = "content://com.xiaomi.station.settingsprovider/switch";
    private static final String AUTO_BACKUP_SERVICE_CLASS = "com.miui.backup.auto.AutoBackupService";
    private static final String PREF_NAME_SUFFIX = "_preferences";
    private static final String PREF_KEY_DATE = "local_auto_backup_date";
    private static final String PREF_KEY_TIME = "local_auto_backup_time";
    private static final String PREF_KEY_HOUR = "local_auto_backup_time_hour";
    private static final String PREF_KEY_MINUTE = "local_auto_backup_time_minute";
    private static final String PREF_KEY_NAS_TASK = "pref_nas_auto_backup_task";
    private static final String PREF_KEY_NAS_DEVICE_ID = "pref_nas_auto_backup_device_id";
    private static final String PREF_KEY_NAS_DEVICE_NAME = "pref_nas_auto_backup_device_name";
    private static final String PREF_KEY_NAS_PACKAGES = "pref_nas_auto_backup_pkgfeatures";
    private static final String KEY_NAS_AUTO_BACKUP = "xpmibackup_nas_auto_backup";
    private static final String KEY_NAS_AUTO_BACKUP_DATE = "xpmibackup_nas_auto_backup_date";
    private static final String KEY_NAS_AUTO_BACKUP_TIME = "xpmibackup_nas_auto_backup_time";
    private static final String KEY_NAS_AUTO_BACKUP_ITEMS = "xpmibackup_nas_auto_backup_items";
    private static final int NAS_AUTO_BACKUP_DAYS_ALL = 127;
    private static final int NAS_AUTO_BACKUP_HOUR = 0;
    private static final int NAS_AUTO_BACKUP_MINUTE = 30;
    private static final int NAS_AUTO_BACKUP_JOB_ID = 103;
    private static final long SCHEDULE_REFRESH_DELAY_MS = 500L;
    private static final int[] DAY_MAP = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
    private static final String[] DAY_KEYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static WeakReference<Object> nasSwitchPreferenceRef = new WeakReference<>(null);
    private static WeakReference<Object> nasDatePreferenceRef = new WeakReference<>(null);
    private static WeakReference<Object> nasTimePreferenceRef = new WeakReference<>(null);
    private static Context appContext;
    private static boolean sharedPreferencesHooked;

    /**
     * 安装 NAS 自动备份相关 Hook。
     */
    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookAutoBackupPreferenceWrites();
        hookNasAutoBackupAvailability(lpparam);
        hookMoreSettingsNasAutoBackup(lpparam);
        hookNasAutoBackupReschedule(lpparam);
    }

    /**
     * 放开 NAS 自动备份依赖的小米智能存储开关和包存在性检查。
     */
    private void hookNasAutoBackupAvailability(XC_LoadPackage.LoadPackageParam lpparam) {
        hookStationSwitchQueryBundle();
        hookStationSwitchQueryLegacy();
        hookStationPackageCheck(lpparam, "p");
        hookStationPackageCheck(lpparam, "isAppInstalled");
    }

    /**
     * 监听原生自动备份日期和时间写入，写入完成后刷新 NAS 自动备份调度。
     */
    private static synchronized void hookAutoBackupPreferenceWrites() {
        if (sharedPreferencesHooked) {
            return;
        }
        try {
            var clazz = Class.forName("android.app.SharedPreferencesImpl$EditorImpl");
            XposedHelpers.findAndHookMethod(clazz, "putInt", String.class, int.class, new XC_MethodHook() {
                /**
                 * 原生控件保存日期或时间后，延迟重排 NAS Job，确保偏好已经落盘。
                 */
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    var key = (String) param.args[0];
                    if (isAutoBackupScheduleKey(key)) {
                        refreshNasAutoBackupScheduleDelayed();
                    }
                }
            });
            sharedPreferencesHooked = true;
        } catch (Throwable e) {
            logError("AutoBackupHook: hook auto backup preference writes failed", e);
        }
    }

    /**
     * 拦截新版 ContentResolver 查询智能存储开关，返回已开启状态。
     */
    private void hookStationSwitchQueryBundle() {
        try {
            XposedHelpers.findAndHookMethod(android.content.ContentResolver.class, "query",
                Uri.class, String[].class, Bundle.class, CancellationSignal.class,
                new XC_MethodHook() {
                    /**
                     * 命中智能存储开关 URI 时，返回带 switch_on=true 的空游标。
                     */
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        setStationSwitchResult(param, (Uri) param.args[0]);
                    }
                });
        } catch (Throwable e) {
            logError("AutoBackupHook: hook bundle station switch query failed", e);
        }
    }

    /**
     * 拦截旧版 ContentResolver 查询智能存储开关，返回已开启状态。
     */
    private void hookStationSwitchQueryLegacy() {
        try {
            XposedHelpers.findAndHookMethod(android.content.ContentResolver.class, "query",
                Uri.class, String[].class, String.class, String[].class, String.class,
                new XC_MethodHook() {
                    /**
                     * 命中智能存储开关 URI 时，返回带 switch_on=true 的空游标。
                     */
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        setStationSwitchResult(param, (Uri) param.args[0]);
                    }
                });
        } catch (Throwable e) {
            logError("AutoBackupHook: hook legacy station switch query failed", e);
        }
    }

    /**
     * 为智能存储开关查询构造统一的返回游标。
     */
    private static void setStationSwitchResult(XC_MethodHook.MethodHookParam param, Uri uri) {
        if (uri == null || !STATION_SWITCH_URI.equals(uri.toString())) {
            return;
        }
        var cursor = new MatrixCursor(new String[]{"switch_on"});
        var extras = new Bundle();
        extras.putBoolean("switch_on", true);
        cursor.setExtras(extras);
        param.setResult(cursor);
    }

    /**
     * 让 NAS 自动备份执行前的 com.xiaomi.station 安装检查通过。
     */
    private void hookStationPackageCheck(XC_LoadPackage.LoadPackageParam lpparam, String methodName) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.utils.AppUtils", lpparam.classLoader);
            if (XposedHelpers.findMethodExactIfExists(clazz, methodName, Context.class, String.class) == null) {
                return;
            }
            XposedHelpers.findAndHookMethod(clazz, methodName, Context.class, String.class, new XC_MethodHook() {
                /**
                 * 仅放行智能存储包名，其它包存在性检查保持原逻辑。
                 */
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    var packageName = (String) param.args[1];
                    if (STATION_PACKAGE.equals(packageName)) {
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable e) {
            logError("AutoBackupHook: hook station package check " + methodName + " failed", e);
        }
    }

    /**
     * 在 NAS 自动备份任务执行结束后重新安排下一次任务。
     */
    private void hookNasAutoBackupReschedule(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass(AUTO_BACKUP_SERVICE_CLASS, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "onStartJob", JobParameters.class, new XC_MethodHook() {
                /**
                 * 原生 JobService 返回后，根据当前配置安排下一次 NAS 自动备份。
                 */
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    var context = (Context) param.thisObject;
                    saveAppContext(context);
                    if (isNasAutoBackupEnabled(context)) {
                        refreshNasAutoBackupSchedule(context);
                    }
                }
            });
        } catch (Throwable e) {
            logError("AutoBackupHook: hook NAS auto backup reschedule failed", e);
        }
    }

    /**
     * 在备份应用更多设置里追加 NAS 自动备份配置入口。
     */
    private void hookMoreSettingsNasAutoBackup(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.settings.MoreSettingsFragment", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "onCreatePreferences", Bundle.class, String.class, new XC_MethodHook() {
                /**
                 * 原生设置加载完成后追加 NAS 自动备份分组。
                 */
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    addNasAutoBackupPreferences(param.thisObject, lpparam);
                }
            });
        } catch (Throwable e) {
            logError("AutoBackupHook: hook more settings NAS auto backup failed", e);
        }
    }

    /**
     * 创建 NAS 自动备份开关和说明项，并添加到当前 PreferenceScreen。
     */
    private static void addNasAutoBackupPreferences(Object fragment, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var context = (Context) XposedHelpers.callMethod(fragment, "getActivity");
            if (context == null) {
                return;
            }
            saveAppContext(context);
            var screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            if (screen == null || XposedHelpers.callMethod(fragment, "findPreference", KEY_NAS_AUTO_BACKUP) != null) {
                return;
            }

            var category = createPreference("androidx.preference.PreferenceCategory", context, lpparam);
            XposedHelpers.callMethod(category, "setTitle", "智能存储自动备份");
            XposedHelpers.callMethod(screen, "addPreference", category);

            var enabled = isNasAutoBackupEnabled(context);
            var checkBox = createPreference("androidx.preference.CheckBoxPreference", context, lpparam);
            XposedHelpers.callMethod(checkBox, "setKey", KEY_NAS_AUTO_BACKUP);
            XposedHelpers.callMethod(checkBox, "setTitle", "开启 NAS 自动备份");
            XposedHelpers.callMethod(checkBox, "setSummary", buildNasAutoBackupSummary(context, enabled));
            XposedHelpers.callMethod(checkBox, "setChecked", enabled);
            XposedHelpers.callMethod(checkBox, "setOnPreferenceChangeListener", createNasAutoBackupChangeListener(lpparam));
            XposedHelpers.callMethod(category, "addPreference", checkBox);

            var date = createPreference("com.miui.backup.widget.ValuePreference", context, lpparam);
            XposedHelpers.callMethod(date, "setKey", KEY_NAS_AUTO_BACKUP_DATE);
            XposedHelpers.callMethod(date, "setTitle", "备份日期");
            setPreferenceValue(date, buildDateText(context));
            callOptional(date, "B", true);
            XposedHelpers.callMethod(date, "setOnPreferenceClickListener", createPreferenceClickListener(lpparam, preference -> {
                openLocalDatePreference(fragment);
                return true;
            }));
            XposedHelpers.callMethod(category, "addPreference", date);

            var time = createPreference("com.miui.backup.widget.ValuePreference", context, lpparam);
            XposedHelpers.callMethod(time, "setKey", KEY_NAS_AUTO_BACKUP_TIME);
            XposedHelpers.callMethod(time, "setTitle", "备份时间");
            setPreferenceValue(time, buildTimeText(context));
            callOptional(time, "B", true);
            XposedHelpers.callMethod(time, "setOnPreferenceClickListener", createPreferenceClickListener(lpparam, preference -> {
                openLocalTimePreference(fragment);
                return true;
            }));
            XposedHelpers.callMethod(category, "addPreference", time);
            saveNasAutoBackupPreferenceRefs(checkBox, date, time);

            var items = createPreference("com.miui.backup.widget.ValuePreference", context, lpparam);
            XposedHelpers.callMethod(items, "setKey", KEY_NAS_AUTO_BACKUP_ITEMS);
            XposedHelpers.callMethod(items, "setTitle", "备份项目");
            setPreferenceValue(items, buildItemsText(context));
            callOptional(items, "B", true);
            XposedHelpers.callMethod(items, "setOnPreferenceClickListener", createPreferenceClickListener(lpparam, preference -> {
                showItemsDialog((Context) XposedHelpers.callMethod(preference, "getContext"), preference);
                return true;
            }));
            XposedHelpers.callMethod(category, "addPreference", items);
        } catch (Throwable e) {
            logError("AutoBackupHook: add NAS auto backup preferences failed", e);
        }
    }

    /**
     * 使用目标应用类加载器创建 Preference 对象。
     */
    private static Object createPreference(String className, Context context, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var clazz = XposedHelpers.findClass(className, lpparam.classLoader);
        return clazz.getConstructor(Context.class).newInstance(context);
    }

    /**
     * 创建通用 Preference 点击监听。
     */
    private static Object createPreferenceClickListener(XC_LoadPackage.LoadPackageParam lpparam, PreferenceClickHandler handler) throws Exception {
        var listenerClass = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceClickListener", lpparam.classLoader);
        var invocationHandler = (InvocationHandler) (proxy, method, args) -> {
            if (!"onPreferenceClick".equals(method.getName())) {
                return null;
            }
            return handler.onClick(args[0]);
        };
        return Proxy.newProxyInstance(lpparam.classLoader, new Class[]{listenerClass}, invocationHandler);
    }

    /**
     * 创建 NAS 自动备份开关监听，开关变化时写入原生偏好并调度 NAS Job。
     */
    private static Object createNasAutoBackupChangeListener(XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var listenerClass = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceChangeListener", lpparam.classLoader);
        return Proxy.newProxyInstance(lpparam.classLoader, new Class[]{listenerClass}, (proxy, method, args) -> {
            if (!"onPreferenceChange".equals(method.getName())) {
                return null;
            }
            var preference = args[0];
            var enabled = ((Boolean) args[1]).booleanValue();
            var context = (Context) XposedHelpers.callMethod(preference, "getContext");
            var success = enabled ? enableNasAutoBackup(context) : disableNasAutoBackup(context);
            if (success) {
                XposedHelpers.callMethod(preference, "setSummary", buildNasAutoBackupSummary(context, enabled));
            }
            Toast.makeText(context, success ? "NAS 自动备份已更新" : "NAS 自动备份设置失败", Toast.LENGTH_SHORT).show();
            return success;
        });
    }

    /**
     * 启用 NAS 自动备份，默认每天 00:30 备份所有第三方应用。
     */
    private static boolean enableNasAutoBackup(Context context) {
        try {
            var deviceId = ConfigHelp.getString("device_id", "");
            var deviceName = ConfigHelp.getString("device_name", "");
            if (deviceId.isEmpty() || deviceName.isEmpty()) {
                LogHelp.e(TAG, "AutoBackupHook: enable NAS auto backup failed: device info is empty");
                return false;
            }
            writeNasAutoBackupBasePreferences(context, true, deviceId, deviceName);
            refreshNasAutoBackupSchedule(context);
            return true;
        } catch (Throwable e) {
            logError("AutoBackupHook: enable NAS auto backup failed", e);
            return false;
        }
    }

    /**
     * 写入 NAS 自动备份基础偏好键，让 AutoBackupService 继续走原生 NAS 任务链路。
     */
    private static void writeNasAutoBackupBasePreferences(Context context, boolean enabled, String deviceId, String deviceName) {
        var prefs = getAutoBackupPreferences(context);
        var editor = prefs.edit()
            .putBoolean(PREF_KEY_NAS_TASK, enabled)
            .putString(PREF_KEY_NAS_DEVICE_ID, deviceId)
            .putString(PREF_KEY_NAS_DEVICE_NAME, deviceName);
        if (!prefs.contains(PREF_KEY_DATE)) {
            editor.putInt(PREF_KEY_DATE, NAS_AUTO_BACKUP_DAYS_ALL);
        }
        if (!prefs.contains(PREF_KEY_HOUR)) {
            editor.putInt(PREF_KEY_HOUR, NAS_AUTO_BACKUP_HOUR);
        }
        if (!prefs.contains(PREF_KEY_MINUTE)) {
            editor.putInt(PREF_KEY_MINUTE, NAS_AUTO_BACKUP_MINUTE);
        }
        if (prefs.getStringSet(PREF_KEY_NAS_PACKAGES, null) == null) {
            editor.putStringSet(PREF_KEY_NAS_PACKAGES, collectUserPackages(context));
        }
        editor.apply();
    }

    /**
     * 关闭 NAS 自动备份，并取消备份应用的 NAS 自动备份 Job。
     */
    private static boolean disableNasAutoBackup(Context context) {
        try {
            getAutoBackupPreferences(context).edit().putBoolean(PREF_KEY_NAS_TASK, false).apply();
            cancelNasAutoBackupJob(context);
            return true;
        } catch (Throwable e) {
            logError("AutoBackupHook: disable NAS auto backup failed", e);
            return false;
        }
    }

    /**
     * 根据当前开关和配置刷新 NAS 自动备份调度。
     */
    private static void refreshNasAutoBackupSchedule(Context context) {
        if (!isNasAutoBackupEnabled(context) || getAutoBackupDate(context) == 0 || getNasBackupPackages(context).isEmpty()) {
            cancelNasAutoBackupJob(context);
            return;
        }
        scheduleNasAutoBackupJob(context);
    }

    /**
     * 缓存备份应用上下文，供原生偏好写入回调异步刷新调度使用。
     */
    private static void saveAppContext(Context context) {
        appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
    }

    /**
     * 缓存虚拟 NAS 自动备份 Preference 引用，用于原生控件修改后同步刷新显示。
     */
    private static void saveNasAutoBackupPreferenceRefs(Object switchPreference, Object datePreference, Object timePreference) {
        nasSwitchPreferenceRef = new WeakReference<>(switchPreference);
        nasDatePreferenceRef = new WeakReference<>(datePreference);
        nasTimePreferenceRef = new WeakReference<>(timePreference);
    }

    /**
     * 判断偏好键是否会影响自动备份调度。
     */
    private static boolean isAutoBackupScheduleKey(String key) {
        return PREF_KEY_DATE.equals(key) || PREF_KEY_HOUR.equals(key) || PREF_KEY_MINUTE.equals(key);
    }

    /**
     * 延迟刷新 NAS 自动备份调度，等待原生控件完成偏好写入。
     */
    private static void refreshNasAutoBackupScheduleDelayed() {
        if (appContext == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                refreshNasAutoBackupSchedule(appContext);
                refreshNasAutoBackupPreferenceViews(appContext);
            } catch (Throwable e) {
                logError("AutoBackupHook: delayed refresh NAS auto backup schedule failed", e);
            }
        }, SCHEDULE_REFRESH_DELAY_MS);
    }

    /**
     * 刷新虚拟 NAS 自动备份日期、时间和开关说明。
     */
    private static void refreshNasAutoBackupPreferenceViews(Context context) {
        var datePreference = nasDatePreferenceRef.get();
        if (datePreference != null) {
            setPreferenceValue(datePreference, buildDateText(context));
        }
        var timePreference = nasTimePreferenceRef.get();
        if (timePreference != null) {
            setPreferenceValue(timePreference, buildTimeText(context));
        }
        var switchPreference = nasSwitchPreferenceRef.get();
        if (switchPreference != null) {
            XposedHelpers.callMethod(switchPreference, "setSummary", buildNasAutoBackupSummary(context, isNasAutoBackupEnabled(context)));
        }
    }

    /**
     * 调度备份应用原生 NAS 自动备份 Job。
     */
    private static void scheduleNasAutoBackupJob(Context context) {
        cancelNasAutoBackupJob(context);
        var scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            throw new IllegalStateException("JobScheduler is null");
        }
        var delay = calculateNextDelayMillis(getAutoBackupDate(context), getAutoBackupHour(context), getAutoBackupMinute(context));
        var component = new ComponentName(context.getPackageName(), AUTO_BACKUP_SERVICE_CLASS);
        var builder = new JobInfo.Builder(NAS_AUTO_BACKUP_JOB_ID, component)
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay)
            .setPersisted(true);
        var result = scheduler.schedule(builder.build());
        if (result != JobScheduler.RESULT_SUCCESS) {
            throw new IllegalStateException("schedule NAS job failed: " + result);
        }
    }

    /**
     * 取消备份应用原生 NAS 自动备份 Job。
     */
    private static void cancelNasAutoBackupJob(Context context) {
        var scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(NAS_AUTO_BACKUP_JOB_ID);
        }
    }

    /**
     * 计算下一次按所选日期和时间触发距离当前时间的毫秒数。
     */
    private static long calculateNextDelayMillis(int days, int hour, int minute) {
        var now = Calendar.getInstance();
        var best = Long.MAX_VALUE;
        for (var offset = 0; offset < 8; offset++) {
            var candidate = (Calendar) now.clone();
            candidate.add(Calendar.DAY_OF_YEAR, offset);
            if (!isDaySelected(days, candidate.get(Calendar.DAY_OF_WEEK))) {
                continue;
            }
            candidate.set(Calendar.HOUR_OF_DAY, hour);
            candidate.set(Calendar.MINUTE, minute);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            var delay = candidate.getTimeInMillis() - now.getTimeInMillis();
            if (delay > 0 && delay < best) {
                best = delay;
            }
        }
        if (best == Long.MAX_VALUE) {
            throw new IllegalStateException("no selected backup day");
        }
        return best;
    }

    /**
     * 判断当前星期是否被选中。
     */
    private static boolean isDaySelected(int days, int calendarDay) {
        for (var i = 0; i < DAY_MAP.length; i++) {
            if (DAY_MAP[i] == calendarDay) {
                return (days & (1 << i)) != 0;
            }
        }
        return false;
    }

    /**
     * 读取备份应用原生偏好文件。
     */
    private static android.content.SharedPreferences getAutoBackupPreferences(Context context) {
        return context.getSharedPreferences(context.getPackageName() + PREF_NAME_SUFFIX, Context.MODE_MULTI_PROCESS);
    }

    /**
     * 读取 NAS 自动备份开关状态。
     */
    private static boolean isNasAutoBackupEnabled(Context context) {
        return getAutoBackupPreferences(context).getBoolean(PREF_KEY_NAS_TASK, false);
    }

    /**
     * 读取自动备份日期编码。
     */
    private static int getAutoBackupDate(Context context) {
        return getAutoBackupPreferences(context).getInt(PREF_KEY_DATE, NAS_AUTO_BACKUP_DAYS_ALL);
    }

    /**
     * 读取自动备份小时。
     */
    private static int getAutoBackupHour(Context context) {
        return getAutoBackupPreferences(context).getInt(PREF_KEY_HOUR, NAS_AUTO_BACKUP_HOUR);
    }

    /**
     * 读取自动备份分钟。
     */
    private static int getAutoBackupMinute(Context context) {
        return getAutoBackupPreferences(context).getInt(PREF_KEY_MINUTE, NAS_AUTO_BACKUP_MINUTE);
    }

    /**
     * 读取 NAS 自动备份应用包名。
     */
    private static HashSet<String> getNasBackupPackages(Context context) {
        var packages = getAutoBackupPreferences(context).getStringSet(PREF_KEY_NAS_PACKAGES, null);
        return packages == null ? new HashSet<>() : new HashSet<>(packages);
    }

    /**
     * 生成 NAS 自动备份状态说明。
     */
    private static String buildNasAutoBackupSummary(Context context, boolean enabled) {
        return enabled ? "已开启，" + buildDateText(context) + " " + buildTimeText(context) + "，" + buildItemsText(context) : "关闭后不再调度 NAS 自动备份";
    }

    /**
     * 生成日期显示文本。
     */
    private static String buildDateText(Context context) {
        var days = getAutoBackupDate(context);
        if (days == 0) {
            return "从不";
        }
        if (days == NAS_AUTO_BACKUP_DAYS_ALL) {
            return "每天";
        }
        var names = new ArrayList<String>();
        for (var i = 0; i < DAY_KEYS.length; i++) {
            if ((days & (1 << i)) != 0) {
                names.add(DAY_KEYS[i]);
            }
        }
        return android.text.TextUtils.join("、", names);
    }

    /**
     * 生成时间显示文本。
     */
    private static String buildTimeText(Context context) {
        var calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, getAutoBackupHour(context));
        calendar.set(Calendar.MINUTE, getAutoBackupMinute(context));
        return DateFormat.format(DateFormat.is24HourFormat(context) ? "kk:mm" : "h:mm aa", calendar).toString();
    }

    /**
     * 生成项目显示文本。
     */
    private static String buildItemsText(Context context) {
        var count = getNasBackupPackages(context).size();
        return count > 0 ? "应用数 " + count : "未选择应用";
    }

    /**
     * 设置 ValuePreference 的右侧值，失败时退回摘要。
     */
    private static void setPreferenceValue(Object preference, String value) {
        try {
            XposedHelpers.callMethod(preference, "setValue", value);
        } catch (Throwable e) {
            XposedHelpers.callMethod(preference, "setSummary", value);
        }
    }

    /**
     * 打开原生本地自动备份日期控件，让 NAS 日期和本地日期共用同一套 UI 与偏好。
     */
    private static void openLocalDatePreference(Object fragment) {
        try {
            var preference = XposedHelpers.callMethod(fragment, "findPreference", PREF_KEY_DATE);
            if (preference != null) {
                XposedHelpers.callMethod(preference, "onPreferenceClick", preference);
            }
        } catch (Throwable e) {
            logError("AutoBackupHook: open local date preference failed", e);
        }
    }

    /**
     * 打开原生本地自动备份时间控件，让 NAS 时间和本地时间共用同一套 UI 与偏好。
     */
    private static void openLocalTimePreference(Object fragment) {
        try {
            var preference = XposedHelpers.callMethod(fragment, "findPreference", PREF_KEY_TIME);
            if (preference != null) {
                XposedHelpers.callMethod(fragment, "onPreferenceClick", preference);
            }
        } catch (Throwable e) {
            logError("AutoBackupHook: open local time preference failed", e);
        }
    }

    /**
     * 显示备份项目选择弹窗并保存包名集合。
     */
    private static void showItemsDialog(Context context, Object preference) {
        try {
            var appMap = collectUserPackageLabels(context);
            var labels = appMap.keySet().toArray(new String[0]);
            var packages = appMap.values().toArray(new String[0]);
            var selectedPackages = getNasBackupPackages(context);
            var checked = new boolean[packages.length];
            for (var i = 0; i < packages.length; i++) {
                checked[i] = selectedPackages.contains(packages[i]);
            }
            var dialog = new AlertDialog.Builder(context)
                .setTitle("备份项目")
                .setMultiChoiceItems(labels, checked, (dialogInterface, which, isChecked) -> {
                    checked[which] = isChecked;
                    updateSelectAllButtonText((AlertDialog) dialogInterface, checked);
                })
                .setPositiveButton("确定", (dialogInterface, which) -> saveItemsSelection(context, preference, packages, checked))
                .setNegativeButton("取消", null)
                .setNeutralButton("全选", null)
                .show();
            updateSelectAllButtonText(dialog, checked);
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view -> toggleAllItems(dialog, checked));
        } catch (Throwable e) {
            logError("AutoBackupHook: show items dialog failed", e);
        }
    }

    /**
     * 在备份项目弹窗内切换全选状态，只更新勾选状态，不关闭弹窗。
     */
    private static void toggleAllItems(AlertDialog dialog, boolean[] checked) {
        setAllItemsChecked(dialog, checked, !isAllItemsChecked(checked));
        updateSelectAllButtonText(dialog, checked);
    }

    /**
     * 设置备份项目弹窗内所有项目的勾选状态。
     */
    private static void setAllItemsChecked(AlertDialog dialog, boolean[] checked, boolean selected) {
        var listView = dialog.getListView();
        for (var i = 0; i < checked.length; i++) {
            checked[i] = selected;
            listView.setItemChecked(i, selected);
        }
    }

    /**
     * 判断备份项目弹窗内是否已经全选。
     */
    private static boolean isAllItemsChecked(boolean[] checked) {
        if (checked.length == 0) {
            return false;
        }
        for (var item : checked) {
            if (!item) {
                return false;
            }
        }
        return true;
    }

    /**
     * 根据当前勾选状态更新备份项目弹窗全选按钮文字。
     */
    private static void updateSelectAllButtonText(AlertDialog dialog, boolean[] checked) {
        var button = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (button != null) {
            button.setText(isAllItemsChecked(checked) ? "取消全选" : "全选");
        }
    }

    /**
     * 保存项目选择并刷新调度。
     */
    private static void saveItemsSelection(Context context, Object preference, String[] packages, boolean[] checked) {
        var selected = new HashSet<String>();
        for (var i = 0; i < packages.length; i++) {
            if (checked[i]) {
                selected.add(packages[i]);
            }
        }
        if (selected.isEmpty()) {
            Toast.makeText(context, "至少选择一个应用", Toast.LENGTH_SHORT).show();
            return;
        }
        getAutoBackupPreferences(context).edit().putStringSet(PREF_KEY_NAS_PACKAGES, selected).apply();
        setPreferenceValue(preference, buildItemsText(context));
        refreshNasAutoBackupSchedule(context);
    }

    /**
     * 收集所有第三方应用包名，作为 NAS 自动备份默认项目。
     */
    private static HashSet<String> collectUserPackages(Context context) {
        var packages = new HashSet<String>();
        var packageManager = context.getPackageManager();
        for (var info : packageManager.getInstalledApplications(0)) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !context.getPackageName().equals(info.packageName)) {
                packages.add(info.packageName);
            }
        }
        return packages;
    }

    /**
     * 收集第三方应用的显示名称和包名。
     */
    private static TreeMap<String, String> collectUserPackageLabels(Context context) {
        var packages = new TreeMap<String, String>();
        var packageManager = context.getPackageManager();
        for (var info : packageManager.getInstalledApplications(0)) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || context.getPackageName().equals(info.packageName)) {
                continue;
            }
            var label = String.valueOf(packageManager.getApplicationLabel(info));
            packages.put(label + "\n" + info.packageName, info.packageName);
        }
        return packages;
    }

    /**
     * 调用可选方法，兼容不同 Preference 实现。
     */
    private static void callOptional(Object target, String methodName, Object... args) {
        try {
            XposedHelpers.callMethod(target, methodName, args);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 统一记录 NAS 自动备份 Hook 异常。
     */
    private static void logError(String message, Throwable e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }

    /**
     * Preference 点击回调。
     */
    private interface PreferenceClickHandler {
        /**
         * 处理 Preference 点击事件。
         */
        boolean onClick(Object preference);
    }
}
