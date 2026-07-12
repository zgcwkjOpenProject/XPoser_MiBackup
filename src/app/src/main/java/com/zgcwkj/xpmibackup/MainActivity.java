package com.zgcwkj.xpmibackup;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.TextView;

/**
 * 云备份助手主界面
 * 底部Tab切换：设备配置 / 服务配置
 * 通过Xposed Hook注入到小米设置"小米澎湃AI"下方，点击跳转至此
 */
public class MainActivity extends Activity {

    /** 底部Tab图标和文字控件，用于切换时更新选中/未选中颜色 */
    private TextView tabDeviceIcon, tabDeviceText, tabServiceIcon, tabServiceText;

    /**
     * 初始化界面：绑定Tab控件，注册切换事件，检查文件管理权限
     * 未授权时跳转系统设置页面，授权后首次打开默认显示设备配置Tab
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getResources().getColor(R.color.primary));
        setContentView(R.layout.activity_main);

        tabDeviceIcon = findViewById(R.id.tab_device_icon);
        tabDeviceText = findViewById(R.id.tab_device_text);
        tabServiceIcon = findViewById(R.id.tab_service_icon);
        tabServiceText = findViewById(R.id.tab_service_text);

        findViewById(R.id.tab_device).setOnClickListener(v -> switchTab("device"));
        findViewById(R.id.tab_service).setOnClickListener(v -> switchTab("service"));

        // 顶部"备份"按钮：跳转到小米备份APP的智能存储(NAS)备份页面(NASHomeActivity)
        // 用 intent action 跳转，action 名不被混淆，比直接指定类名更稳
        // 必须传 deviceId/deviceName：NASHomeActivity.onCreate 会读这两个 extra，
        // deviceId 传给 DistFileClientService.setDeviceId，缺失则连接时 "device id is null" 失败
        findViewById(R.id.btn_open_backup).setOnClickListener(v -> {
            var deviceId = com.zgcwkj.comm.ConfigHelp.getString("device_id", "");
            var deviceName = com.zgcwkj.comm.ConfigHelp.getString("device_name", "");
            if (deviceId.isEmpty()) {
                android.widget.Toast.makeText(this, R.string.toast_device_id_required,
                    android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            var intent = new Intent("miui.intent.backup.NAS_HOME_ACTIVITY");
            intent.putExtra("deviceId", deviceId);
            intent.putExtra("deviceName", deviceName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Exception e) {
                android.widget.Toast.makeText(this, R.string.toast_backup_app_missing,
                    android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        // 状态栏占位：动态设置空白View高度为状态栏高度
        var statusBarRes = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (statusBarRes > 0) {
            var spacer = findViewById(R.id.status_bar_spacer);
            spacer.getLayoutParams().height = getResources().getDimensionPixelSize(statusBarRes);
        }

        // 检查文件管理权限，未授权则跳转系统设置页面
        if (!Environment.isExternalStorageManager()) {
            var intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        if (savedInstanceState == null) switchTab("device");
    }

    /**
     * 界面恢复时检查权限和Fragment状态
     * 用户从权限设置页面返回后，若已授权且Fragment未加载，则自动加载默认Tab
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (Environment.isExternalStorageManager() && ((android.view.ViewGroup) findViewById(R.id.fragment_container)).getChildCount() == 0) {
            switchTab("device");
        }
    }

    /**
     * 切换底部Tab对应的Fragment
     * @param tab "device"=设备配置，"service"=服务配置
     * 切换时更新Tab图标和文字颜色（选中=主题色，未选中=灰色）
     */
    private void switchTab(String tab) {
        var fragment = (android.app.Fragment) null;
        if ("service".equals(tab)) {
            fragment = new com.zgcwkj.xpmibackup.ui.ServiceConfigFragment();
            tabDeviceIcon.setTextColor(getResources().getColor(R.color.text_disabled));
            tabDeviceText.setTextColor(getResources().getColor(R.color.text_disabled));
            tabServiceIcon.setTextColor(getResources().getColor(R.color.primary));
            tabServiceText.setTextColor(getResources().getColor(R.color.primary));
        } else {
            fragment = new com.zgcwkj.xpmibackup.ui.DeviceConfigFragment();
            tabDeviceIcon.setTextColor(getResources().getColor(R.color.primary));
            tabDeviceText.setTextColor(getResources().getColor(R.color.primary));
            tabServiceIcon.setTextColor(getResources().getColor(R.color.text_disabled));
            tabServiceText.setTextColor(getResources().getColor(R.color.text_disabled));
        }
        var ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.fragment_container, fragment);
        ft.commit();
    }
}
