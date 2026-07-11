package com.zgcwkj.xpmibackup.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import android.widget.Button;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.xpmibackup.R;

/**
 * 服务配置界面
 * 管理云端传输协议及连接参数的读写
 * SMB参数：服务器地址、端口、共享文件夹、用户名、密码
 * WebDAV参数：服务器地址(URL)、用户名、密码
 * 保存时先测试连接，连接成功才保存
 */
public class ServiceConfigFragment extends Fragment {

    private static final String TAG = "XpMiBackup";
    private RadioGroup rgProtocol;
    private RadioButton rbSmb, rbWebdav;
    private LinearLayout panelSmb, panelWebdav;
    private EditText etUploadThreads;
    private EditText etSmbServer, etSmbPort, etSmbShare, etSmbUser, etSmbPass;
    private EditText etWebdavUrl, etWebdavUser, etWebdavPass;
    private Button btnSave;
    private LinearLayout testingPanel;

    /**
     * 创建服务配置界面视图
     * 绑定协议切换、SMB/WebDAV参数输入框，加载已有配置
     * 保存按钮点击后先测试连接再保存
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_service_config, container, false);

        // 界面控件元素
        rgProtocol = view.findViewById(R.id.rg_protocol);
        rbSmb = view.findViewById(R.id.rb_smb);
        rbWebdav = view.findViewById(R.id.rb_webdav);
        panelSmb = view.findViewById(R.id.panel_smb);
        panelWebdav = view.findViewById(R.id.panel_webdav);
        etUploadThreads = view.findViewById(R.id.et_upload_threads);
        etSmbServer = view.findViewById(R.id.et_smb_server);
        etSmbPort = view.findViewById(R.id.et_smb_port);
        etSmbShare = view.findViewById(R.id.et_smb_share);
        etSmbUser = view.findViewById(R.id.et_smb_user);
        etSmbPass = view.findViewById(R.id.et_smb_pass);
        etWebdavUrl = view.findViewById(R.id.et_webdav_url);
        etWebdavUser = view.findViewById(R.id.et_webdav_user);
        etWebdavPass = view.findViewById(R.id.et_webdav_pass);
        btnSave = view.findViewById(R.id.btn_save);
        testingPanel = view.findViewById(R.id.testing_panel);

        // 加载配置
        loadConfig();

        // 协议切换时显示/隐藏对应的配置面板
        rgProtocol.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_smb) {
                panelSmb.setVisibility(View.VISIBLE);
                panelWebdav.setVisibility(View.GONE);
            } else {
                panelSmb.setVisibility(View.GONE);
                panelWebdav.setVisibility(View.VISIBLE);
            }
        });

        // 保存按钮：先保存再测试连接
        btnSave.setOnClickListener(v -> testAndSave());

        // 底部链接
        var tvFooter = (TextView) view.findViewById(R.id.tv_footer);
        tvFooter.setOnClickListener(v -> {
            var uri = Uri.parse(getString(R.string.author_url));
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        });

        return view;
    }

    /**
     * 先写入临时配置，测试连接，成功则保存，失败则提示检查参数
     * 后台线程执行网络测试，主线程更新UI
     */
    private void testAndSave() {
        // 禁用按钮，显示转圈
        btnSave.setEnabled(false);
        btnSave.setText("测试连接中…");
        testingPanel.setVisibility(View.VISIBLE);

        new Thread(() -> {
            // 保存到配置文件（testConnection会读取配置）
            saveConfig();
            var ok = com.zgcwkj.comm.CloudFileHelp.testConnection();

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                testingPanel.setVisibility(View.GONE);
                btnSave.setEnabled(true);
                btnSave.setText("保存配置");

                if (ok) {
                    Toast.makeText(getActivity(), "连接成功，配置已保存", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getActivity(), "连接失败，请检查参数", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /**
     * 从配置文件读取协议类型和连接参数，填充到对应控件
     */
    private void loadConfig() {
        // 读取配置
        var cfg = com.zgcwkj.comm.ConfigHelp.load();
        // 全局变量
        var protocol = cfg.optString("protocol", "smb");
        rbSmb.setChecked("smb".equals(protocol));
        rbWebdav.setChecked("webdav".equals(protocol));
        panelSmb.setVisibility("smb".equals(protocol) ? View.VISIBLE : View.GONE);
        panelWebdav.setVisibility("webdav".equals(protocol) ? View.VISIBLE : View.GONE);
        etUploadThreads.setText(cfg.optString("upload_threads", "3"));
        // SMB配置
        etSmbServer.setText(cfg.optString("smb_server", ""));
        etSmbPort.setText(String.valueOf(cfg.optInt("smb_port", 445)));
        etSmbShare.setText(cfg.optString("smb_share", ""));
        etSmbUser.setText(cfg.optString("smb_user", ""));
        etSmbPass.setText(cfg.optString("smb_pass", ""));
        // WebDAV配置
        etWebdavUrl.setText(cfg.optString("webdav_url", ""));
        etWebdavUser.setText(cfg.optString("webdav_user", ""));
        etWebdavPass.setText(cfg.optString("webdav_pass", ""));
    }

    /**
     * 将当前选中的协议和连接参数保存到配置文件
     * 参数用于CloudFileHelp建立连接时读取
     */
    private void saveConfig() {
        try {
            var cfg = com.zgcwkj.comm.ConfigHelp.load();
            cfg.put("protocol", rbSmb.isChecked() ? "smb" : "webdav");
            cfg.put("upload_threads", etUploadThreads.getText().toString().trim());
            cfg.put("smb_server", etSmbServer.getText().toString().trim());
            cfg.put("smb_port", Integer.parseInt(etSmbPort.getText().toString().trim()));
            cfg.put("smb_share", etSmbShare.getText().toString().trim());
            cfg.put("smb_user", etSmbUser.getText().toString().trim());
            cfg.put("smb_pass", etSmbPass.getText().toString());
            cfg.put("webdav_url", etWebdavUrl.getText().toString().trim());
            cfg.put("webdav_user", etWebdavUser.getText().toString().trim());
            cfg.put("webdav_pass", etWebdavPass.getText().toString());
            com.zgcwkj.comm.ConfigHelp.save(cfg);
        } catch (Exception e) {
            LogHelp.e(TAG, "save service config failed: " + e.getMessage(), e);
        }
    }
}
