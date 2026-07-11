package com.zgcwkj.xpmibackup;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.xpmibackup.hook.AIDLHook;
import com.zgcwkj.xpmibackup.hook.AutoBackupHook;
import com.zgcwkj.xpmibackup.hook.BackupHook;
import com.zgcwkj.xpmibackup.hook.SettingsHook;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;

/**
 * Xposed模块入口
 * 设置应用负责显示配置入口，小米备份负责接入DFS AIDL重定向
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "XpMiBackup";
    private static final String TEMP_ROOT = ConfigHelp.BACKUP_ROOT + "/AllBackupTemp";

    /**
     * 根据加载的包名安装对应Hook
     */
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);
        if ("com.android.settings".equals(lpparam.packageName)) {
            new SettingsHook().hook(lpparam);
        } else if ("com.miui.backup".equals(lpparam.packageName)) {
            new BackupHook().hook(lpparam);
            new AutoBackupHook().hook(lpparam);
            new AIDLHook().hook(lpparam);
            cleanupTempDirs();
        }
    }

    /**
     * 清理AllBackupTemp下面上次会话残留的所有临时内容
     */
    private void cleanupTempDirs() {
        try {
            var root = new File(TEMP_ROOT);
            if (!root.exists()) {
                root.mkdirs();
                return;
            }
            if (!root.isDirectory()) {
                return;
            }
            deleteChildren(root);
        } catch (Throwable e) {
            LogHelp.e(TAG, "cleanup temp dirs failed: " + e.getMessage(), e);
        }
    }

    /**
     * 删除目录下所有子项，保留目录自身
     */
    private void deleteChildren(File dir) {
        var children = dir.listFiles();
        if (children != null) {
            for (var child : children) {
                deleteRecursively(child);
            }
        }
    }

    /**
     * 递归删除临时文件或目录
     */
    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            deleteChildren(file);
        }
        if (!file.delete()) {
            LogHelp.e(TAG, "delete temp path failed: " + file.getAbsolutePath());
        }
    }
}
