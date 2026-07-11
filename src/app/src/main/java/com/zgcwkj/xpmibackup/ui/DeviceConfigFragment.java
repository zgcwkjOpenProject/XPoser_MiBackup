package com.zgcwkj.xpmibackup.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.zgcwkj.xpmibackup.R;
import com.zgcwkj.comm.LogHelp;

/**
 * 设备配置界面
 * 管理设备名称、备份路径、最大备份数、设置页面描述文字的读写
 */
public class DeviceConfigFragment extends Fragment {

    private static final String TAG = "XpMiBackup";
    private EditText etDeviceName, etBackupPath, etMaxBackups, etSettingsSummary;
    private Switch swLogEnabled;

    /**
     * 创建设备配置界面视图
     * 绑定输入框控件，加载已有配置，注册保存按钮点击事件
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_device_config, container, false);
        etDeviceName = view.findViewById(R.id.et_device_name);
        etSettingsSummary = view.findViewById(R.id.et_device_describe);
        etBackupPath = view.findViewById(R.id.et_backup_path);
        etMaxBackups = view.findViewById(R.id.et_backup_max);
        swLogEnabled = view.findViewById(R.id.sw_log_enabled);
        var btnSave = view.findViewById(R.id.btn_save);

        // 加载配置
        loadConfig();

        // 点击事件
        btnSave.setOnClickListener(v -> {
            saveConfig();
            Toast.makeText(getActivity(), "配置已保存", Toast.LENGTH_SHORT).show();
        });

        // 底部链接
        var tvFooter = (TextView) view.findViewById(R.id.tv_footer);
        tvFooter.setOnClickListener(v ->{
            var uri = Uri.parse(getString(R.string.author_url));
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        });

        return view;
    }

    /**
     * 从配置文件读取所有配置项，填充到输入框
     */
    private void loadConfig() {
        // 读取配置
        var cfg = com.zgcwkj.comm.ConfigHelp.load();
        // 显示到页面
        etDeviceName.setText(cfg.optString("device_name", ""));
        etSettingsSummary.setText(cfg.optString("device_describe", ""));
        etBackupPath.setText(cfg.optString("backup_path", ""));
        etMaxBackups.setText(cfg.optString("backup_max", "5"));
        swLogEnabled.setChecked("true".equalsIgnoreCase(cfg.optString("log_enabled", "false")));
    }

    /**
     * 将输入框内容保存到配置文件
     */
    private void saveConfig() {
        try {
            var cfg = com.zgcwkj.comm.ConfigHelp.load();
            var name = etDeviceName.getText().toString().trim();
            var describe = etSettingsSummary.getText().toString().trim();
            var path = etBackupPath.getText().toString().trim();
            var count = etMaxBackups.getText().toString().trim();
            cfg.put("device_id", generateDeviceId(name));
            cfg.put("device_name", name);
            cfg.put("device_describe", describe);
            cfg.put("backup_path", path);
            cfg.put("backup_max", count);
            cfg.put("log_enabled", swLogEnabled.isChecked() ? "true" : "false");
            com.zgcwkj.comm.ConfigHelp.save(cfg);
        } catch (Exception e) {
            LogHelp.e(TAG, "save device config failed: " + e.getMessage(), e);
        }
    }

    /**
     * 生成设备ID：对设备名称取MD5后取前6位
     */
    private String generateDeviceId(String name) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            var hash = md.digest(name.getBytes("UTF-8"));
            var sb = new StringBuilder();
            for (var i = 0; i < 3; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return name;
        }
    }
}
