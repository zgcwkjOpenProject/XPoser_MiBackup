package com.zgcwkj.xpmibackup.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.xpmibackup.R;

import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 设置APP入口Hook
 * 在小米设置APP中注入"云备份助手"入口，并让智能存储功能始终可见
 *
 * 作用原理：
 * 1. hook MiuiSettings.updateHeaderList → 在"我的设备"下方插入"云备份助手"入口
 * 2. hook SmartStorageController.getAvailabilityStatus → 强制返回0（功能可用）
 * 3. hook SmartStorageBackupHelper.isSupported → 强制返回true（已支持）
 * 4. hook BackupNasDeviceProvider.query → 返回模拟的NAS设备数据
 * 5. hook SmartStorageController.addDevicePreference → 替换设备描述文字
 */
public class SettingsHook {

    private static final String TAG = "XpMiBackup";
    private static final String MODULE_PACKAGE = "com.zgcwkj.xpmibackup";
    private static final String MODULE_ACTIVITY = "com.zgcwkj.xpmibackup.MainActivity";

    /**
     * 注册所有设置相关Hook
     * @param lpparam Xposed加载包参数，包含目标APP的ClassLoader和包信息
     */
    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 配置程序入口
        hookUpdateHeaderList(lpparam);
        hookHeaderIconSize(lpparam);
        // 强制支持智能存储备份
        hookGetAvailabilityStatus(lpparam);
        hookIsSupported(lpparam);
        // 设备信息
        hookBackupNasDeviceProvider(lpparam);
        hookSmartStorageSummary(lpparam);
    }

    /**
     * 拦截MiuiSettings.updateHeaderList()
     * 原始方法根据设备能力过滤设置主页的header列表
     * 在"我的设备"项后面插入自定义"云备份助手"入口，
     * 点击后跳转到云备份助手配置界面(MainActivity)
     *
     * 注入流程：
     * 1. 遍历header列表，通过title文字匹配找到"我的设备"的位置
     * 2. 防重复检查，避免多次调用导致重复插入
     * 3. 创建MIUI风格的PreferenceActivity.Header对象（非标准android.preference）
     * 4. 设置标题、设置APP图标资源、跳转Intent
     * 5. 插入到"我的设备"项后面
     */
    private void hookUpdateHeaderList(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.android.settings.MiuiSettings", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "updateHeaderList", List.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            var list = headerList(param.args[0]);
                            if (list == null || list.isEmpty()) {
                                return;
                            }

                            var myDeviceIndex = findMyDeviceIndex(param.thisObject, list);
                            if (myDeviceIndex < 0) {
                                return;
                            }

                            if (myDeviceIndex + 1 < list.size() && isOurHeader(list.get(myDeviceIndex + 1))) {
                                return;
                            }

                            var title = entryTitle(param.thisObject);
                            var iconRes = settingsAppIconRes(param.thisObject);
                            if (title == null || title.isEmpty() || iconRes <= 0) {
                                return;
                            }

                            // 创建MIUI Header（必须使用settingslib.miuisettings的Header类，否则ClassCastException）
                            var headerClass = Class.forName(
                                "com.android.settingslib.miuisettings.preference.PreferenceActivity$Header",
                                false,
                                lpparam.classLoader
                            );
                            var header = headerClass.getDeclaredConstructor().newInstance();
                            XposedHelpers.setObjectField(header, "title", title);
                            XposedHelpers.setIntField(header, "iconRes", iconRes);

                            var intent = new Intent();
                            intent.setClassName(MODULE_PACKAGE, MODULE_ACTIVITY);
                            XposedHelpers.setObjectField(header, "intent", intent);

                            list.add(myDeviceIndex + 1, header);
                        } catch (Throwable e) {
                            logError("settings header inject failed", e);
                        }
                    }
                });
        } catch (Throwable e) {
            logError("hookUpdateHeaderList failed", e);
        }
    }

    /**
     * 对齐设置主页Header图标尺寸
     * 部分系统会把外部注入的图标按异常尺寸显示，这里复用设置页自己的mNormalIconSize
     */
    private void hookHeaderIconSize(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.android.settings.MiuiSettings$HeaderAdapter", lpparam.classLoader);
            XposedBridge.hookAllMethods(clazz, "setIcon", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.args == null || param.args.length < 2 || !isOurHeader(param.args[1])) {
                            return;
                        }
                        var icon = (ImageView) XposedHelpers.getObjectField(param.args[0], "icon");
                        if (icon == null || icon.getLayoutParams() == null) {
                            return;
                        }
                        applySettingsEntryIcon(icon);
                        var iconSize = settingsHeaderIconSize(param.thisObject, icon);
                        if (iconSize <= 0) {
                            return;
                        }
                        var lp = icon.getLayoutParams();
                        lp.width = iconSize;
                        lp.height = iconSize;
                        icon.setLayoutParams(lp);
                        icon.setMinimumWidth(iconSize);
                        icon.setMinimumHeight(iconSize);
                        icon.setMaxWidth(iconSize);
                        icon.setMaxHeight(iconSize);
                        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    } catch (Throwable e) {
                        logError("settings header icon size adjust failed", e);
                    }
                }
            });
        } catch (Throwable e) {
            logError("hookHeaderIconSize failed", e);
        }
    }

    /**
     * 使用设置入口图标作为Header图标
     */
    private void applySettingsEntryIcon(ImageView icon) {
        try {
            var context = icon.getContext().createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            icon.setImageDrawable(context.getDrawable(R.drawable.ic_misettings));
            icon.setPadding(0, 0, 0, 0);
        } catch (Throwable e) {
            logError("load settings entry icon failed", e);
        }
    }

    /**
     * 读取设置主页普通Header图标大小
     */
    private int settingsHeaderIconSize(Object adapter, ImageView icon) {
        var normalIconSize = normalHeaderIconSize(adapter);
        if (normalIconSize > 0) {
            return normalIconSize;
        }
        var drawable = icon.getDrawable();
        if (drawable != null) {
            return Math.max(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        }
        return 0;
    }

    /**
     * 读取设置主页HeaderAdapter对应的mNormalIconSize字段
     */
    private int normalHeaderIconSize(Object adapter) {
        try {
            var owner = XposedHelpers.getSurroundingThis(adapter);
            return XposedHelpers.getIntField(owner, "mNormalIconSize");
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getIntField(adapter, "mNormalIconSize");
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 查找我的设备入口位置
     */
    private int findMyDeviceIndex(Object owner, List<?> list) {
        for (var i = 0; i < list.size(); i++) {
            var title = headerTitle(owner, list.get(i));
            var lowerTitle = title.toLowerCase(Locale.ROOT);
            if (title.contains("我的设备") || lowerTitle.contains("my device")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 将反射拿到的Header列表转换成可插入的列表
     */
    @SuppressWarnings("unchecked")
    private List<Object> headerList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return null;
    }

    /**
     * 判断是否为模块入口
     */
    private boolean isOurHeader(Object header) {
        try {
            var intent = XposedHelpers.getObjectField(header, "intent");
            if (intent instanceof Intent) {
                var component = ((Intent) intent).getComponent();
                return component != null
                    && MODULE_PACKAGE.equals(component.getPackageName())
                    && MODULE_ACTIVITY.equals(component.getClassName());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 读取 Header 标题
     */
    private String headerTitle(Object owner, Object header) {
        try {
            var title = XposedHelpers.getObjectField(header, "title");
            var titleStr = title != null ? title.toString() : "";
            if (!titleStr.isEmpty()) {
                return titleStr;
            }
        } catch (Throwable ignored) {
        }
        try {
            var titleRes = XposedHelpers.getIntField(header, "titleRes");
            if (titleRes > 0 && owner instanceof Activity) {
                return ((Activity) owner).getResources().getString(titleRes);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 获取当前语言入口标题
     */
    private String entryTitle(Object owner) {
        if (owner instanceof Context) {
            try {
                var context = ((Context) owner).createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
                return context.getString(R.string.settings_name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 读取设置APP图标资源
     */
    private int settingsAppIconRes(Object owner) {
        if (owner instanceof Context) {
            try {
                var iconRes = ((Context) owner).getApplicationInfo().icon;
                if (iconRes > 0) {
                    return iconRes;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /**
     * 拦截SmartStorageController.getAvailabilityStatus()
     * 原始方法会检查设备是否支持智能存储，非小米NAS设备返回"不可用"
     * 强制返回0表示"功能可用"，使设置中始终显示智能存储备份入口
     */
    private void hookGetAvailabilityStatus(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.android.settings.backup.SmartStorageController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "getAvailabilityStatus", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(0);
                }
            });
        } catch (Throwable e) {
            logError("hookGetAvailabilityStatus failed", e);
        }
    }

    /**
     * 拦截SmartStorageBackupHelper.isSupported()
     * 原始方法检查当前设备是否支持智能存储备份功能
     * 强制返回true，跳过设备兼容性检查
     */
    private void hookIsSupported(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.android.settings.backup.SmartStorageBackupHelper", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "isSupported", android.content.Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(true);
                }
            });
        } catch (Throwable e) {
            logError("hookIsSupported failed", e);
        }
    }

    /**
     * 拦截BackupNasDeviceProvider.query()
     * 原始方法查询已配对的NAS设备列表，无设备时返回空结果
     * 注入后返回模拟的NAS设备数据（设备ID、名称、类型），
     * 使设置界面显示可用的备份目标设备
     */
    private void hookBackupNasDeviceProvider(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClassIfExists("com.miui.backup.provider.BackupNasDeviceProvider", lpparam.classLoader);
            if (clazz == null) {
                LogHelp.w(TAG, "BackupNasDeviceProvider not found, skip provider hook");
                return;
            }
            final var deviceId = com.zgcwkj.comm.ConfigHelp.getString("device_id", "");
            final var deviceName = com.zgcwkj.comm.ConfigHelp.getString("device_name", "");
            XposedHelpers.findAndHookMethod(clazz, "query",
                android.net.Uri.class, String[].class, Bundle.class, android.os.CancellationSignal.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        var cursor = new android.database.MatrixCursor(new String[]{"device_id", "device_name", "device_type"});
                        cursor.addRow(new Object[]{deviceId, deviceName, "nas"});
                        var extras = new Bundle();
                        var deviceList = new java.util.HashMap<String, String>();
                        deviceList.put(deviceId, deviceName);
                        extras.putSerializable("key_device_list", deviceList);
                        cursor.setExtras(extras);
                        param.setResult(cursor);
                    }
                });
        } catch (Throwable e) {
            logError("hookBackupNasDeviceProvider failed", e);
        }
    }

    /**
     * 拦截SmartStorageController.addDevicePreference()
     * 替换设置页面中智能存储设备的描述文字为自定义文案
     */
    private void hookSmartStorageSummary(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.android.settings.backup.SmartStorageController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "addDevicePreference", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    var controller = param.thisObject;
                    // 通过反射拿到mSmartStorageCategory字段
                    var categoryField = clazz.getDeclaredField("mSmartStorageCategory");
                    categoryField.setAccessible(true);
                    var category = categoryField.get(controller);
                    if (category == null) return;
                    // 遍历刚添加的Preference，替换summary
                    var deviceId = (String) param.args[0];
                    var key = "smart_storage_title_" + deviceId.replace(".", "_");
                    var getCount = category.getClass().getMethod("getPreferenceCount");
                    var getPref = category.getClass().getMethod("getPreference", int.class);
                    var count = (int) getCount.invoke(category);
                    for (var i = 0; i < count; i++) {
                        var pref = getPref.invoke(category, i);
                        var prefKey = (String) pref.getClass().getMethod("getKey").invoke(pref);
                        var summary = com.zgcwkj.comm.ConfigHelp.getString("device_describe", "");
                        if (key.equals(prefKey) && !summary.isEmpty()) {
                            pref.getClass().getMethod("setSummary", CharSequence.class).invoke(pref, summary);
                            break;
                        }
                    }
                }
            });
        } catch (Throwable e) {
            logError("hookSmartStorageSummary failed", e);
        }
    }

    private static void logError(String message, Throwable e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
}
