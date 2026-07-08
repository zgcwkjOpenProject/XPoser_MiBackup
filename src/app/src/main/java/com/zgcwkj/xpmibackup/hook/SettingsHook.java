package com.zgcwkj.xpmibackup.hook;

import android.content.Intent;
import android.database.MatrixCursor;
import android.os.Bundle;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 设置APP入口Hook
 * 在小米设置APP中注入"云备份助手"入口，并让智能存储功能始终可见
 *
 * 作用原理：
 * 1. hook MiuiSettings.updateHeaderList → 在"小米澎湃AI"下方插入"云备份助手"入口
 * 2. hook SmartStorageController.getAvailabilityStatus → 强制返回0（功能可用）
 * 3. hook SmartStorageBackupHelper.isSupported → 强制返回true（已支持）
 * 4. hook BackupNasDeviceProvider.query → 返回模拟的NAS设备数据
 */
public class SettingsHook {

    private static final String TAG = "XpMiBackup";

    /**
     * 注册所有设置相关Hook
     * @param lpparam Xposed加载包参数，包含目标APP的ClassLoader和包信息
     */
    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 配置程序入口
        hookUpdateHeaderList(lpparam);
        // 强制支持智能存储备
        hookGetAvailabilityStatus(lpparam);
        hookIsSupported(lpparam);
        // 设备信息
        hookBackupNasDeviceProvider(lpparam);
        hookSmartStorageSummary(lpparam);
        // 进度页面
        hookProgressPageFragmentBase(lpparam);
        hookProgressPageFragment(lpparam);
    }

    /**
     * 拦截MiuiSettings.updateHeaderList()
     * 原始方法根据设备能力过滤设置主页的header列表
     * 在"小米澎湃AI"项后面插入自定义"云备份助手"入口，
     * 点击后跳转到云备份助手配置界面(MainActivity)
     *
     * 注入流程：
     * 1. 遍历header列表，通过title文字匹配找到"小米澎湃AI"的位置
     * 2. 防重复检查，避免多次调用导致重复插入
     * 3. 创建MIUI风格的PreferenceActivity.Header对象（非标准android.preference）
     * 4. 设置标题、图标（使用设置APP内的com_miui_backup图标）、跳转Intent
     * 5. 插入到AI项后面
     */
    private void hookUpdateHeaderList(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.android.settings.MiuiSettings", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "updateHeaderList", List.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        var list = (List) param.args[0];
                        if (list == null || list.isEmpty()) return;

                        // 遍历找"小米澎湃AI"
                        int aiIndex = -1;
                        for (int i = 0; i < list.size(); i++) {
                            var header = list.get(i);
                            var title = XposedHelpers.getObjectField(header, "title");
                            var titleRes = XposedHelpers.getIntField(header, "titleRes");
                            String titleStr = title != null ? title.toString() : "";
                            if (titleStr.isEmpty() && titleRes > 0) {
                                try {
                                    var res = ((android.app.Activity) param.thisObject).getResources();
                                    titleStr = res.getString(titleRes);
                                } catch (Exception ignored) {}
                            }
                            if (titleStr.contains("澎湃")) {
                                aiIndex = i;
                                break;
                            }
                        }
                        if (aiIndex < 0) return;

                        // 防重复插入
                        if (aiIndex + 1 < list.size()) {
                            var next = list.get(aiIndex + 1);
                            var nextTitle = XposedHelpers.getObjectField(next, "title");
                            if (nextTitle != null && nextTitle.toString().equals("云备份助手")) return;
                        }

                        // 创建MIUI Header（必须使用settingslib.miuisettings的Header类，否则ClassCastException）
                        var headerClass = Class.forName(
                            "com.android.settingslib.miuisettings.preference.PreferenceActivity$Header",
                            false, lpparam.classLoader);
                        var header = headerClass.newInstance();
                        XposedHelpers.setObjectField(header, "title", "云备份助手");
                        XposedHelpers.setIntField(header, "iconRes",
                            XposedHelpers.getStaticIntField(
                                XposedHelpers.findClass("com.android.settings.R$drawable", lpparam.classLoader),
                                "com_miui_backup"));
                        var intent = new Intent();
                        intent.setClassName("com.zgcwkj.xpmibackup", "com.zgcwkj.xpmibackup.MainActivity");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        XposedHelpers.setObjectField(header, "intent", intent);

                        list.add(aiIndex + 1, header);
                    }
                });
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
    }

    /**
     * 拦截BackupNasDeviceProvider.query()
     * 原始方法查询已配对的NAS设备列表，无设备时返回空结果
     * 注入后返回模拟的NAS设备数据（设备ID、名称、类型），
     * 使设置界面显示可用的备份目标设备
     */
    private void hookBackupNasDeviceProvider(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.provider.BackupNasDeviceProvider", lpparam.classLoader);
            final var deviceId = com.zgcwkj.comm.ConfigHelp.getString("device_id", "");
            final var deviceName = com.zgcwkj.comm.ConfigHelp.getString("device_name", "");
            XposedHelpers.findAndHookMethod(clazz, "query",
                android.net.Uri.class, String[].class, Bundle.class, android.os.CancellationSignal.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        var cursor = new MatrixCursor(new String[]{"device_id", "device_name", "device_type"});
                        cursor.addRow(new Object[]{deviceId, deviceName, "nas"});
                        var extras = new Bundle();
                        var deviceList = new java.util.HashMap<String, String>();
                        deviceList.put(deviceId, deviceName);
                        extras.putSerializable("key_device_list", deviceList);
                        cursor.setExtras(extras);
                        param.setResult(cursor);
                    }
                });
        } catch (Throwable ignored) {}
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
                    for (int i = 0; i < count; i++) {
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
        } catch (Throwable ignored) {}
    }

    /**
     * 拦截ProgressPageFragmentBase的UI更新
     * 1. 阻止暂停UI显示，避免干扰恢复流程
     * 2. 注册广播接收器监听备份完成/取消信号，触发页面关闭
     * 3. 拦截onResult方法，用户点击"完成"按钮后关闭APP
     */
    private void hookProgressPageFragmentBase(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.activity.ProgressPageFragmentBase", lpparam.classLoader);

            // hook updateNasSuspendUi：阻止暂停UI显示
            XposedHelpers.findAndHookMethod(clazz, "updateNasSuspendUi", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    p.setResult(null);
                }
            });

            // hook onCreateView：注册普通广播接收器，监听finishProgressPage信号
            XposedHelpers.findAndHookMethod(clazz, "onCreateView",
                android.view.LayoutInflater.class, android.view.ViewGroup.class, android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        var fragment = param.thisObject;
                        var activity = (android.app.Activity) XposedHelpers.callMethod(fragment, "getActivity");
                        if (activity == null) return;
                        var filter = new android.content.IntentFilter("com.miui.backup.finishProgressPage");
                        activity.registerReceiver(new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                                try {
                                    XposedHelpers.callMethod(fragment, "onResult", true, new android.content.Intent());
                                } catch (Throwable ignored) {}
                                activity.finish();
                            }
                        }, filter);
                    }
                });

            // hook onResult：用户点击"完成"按钮时关闭APP
            XposedHelpers.findAndHookMethod(clazz, "onResult", boolean.class, android.content.Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    var fragment = param.thisObject;
                    var activity = (android.app.Activity) XposedHelpers.callMethod(fragment, "getActivity");
                    if (activity != null) activity.finish();
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 拦截ProgressPageFragment.onResult()
     * 原方法会先startActivity跳转再finish，直接finish关闭整个APP
     */
    private void hookProgressPageFragment(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var clazz = XposedHelpers.findClass("com.miui.backup.activity.ProgressPageFragment", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "onResult", boolean.class, android.content.Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    var fragment = param.thisObject;
                    var activity = (android.app.Activity) XposedHelpers.callMethod(fragment, "getActivity");
                    if (activity != null) activity.finish();
                    param.setResult(null);
                }
            });
        } catch (Throwable ignored) {}
    }
}
