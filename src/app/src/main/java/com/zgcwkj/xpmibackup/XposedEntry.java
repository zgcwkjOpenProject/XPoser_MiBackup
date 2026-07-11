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

/**
 * Xposed模块入口。
 * 设置应用负责显示配置入口，小米备份负责接入DFS AIDL重定向。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "XpMiBackup";
    private static final String TEMP_ROOT = ConfigHelp.BACKUP_ROOT + "/AllBackupTemp";

    /**
     * 根据加载的包名安装对应Hook。
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
     * 清理AllBackupTemp下面残留的空任务目录。
     * 非空目录保留，避免误删未完成的备份或恢复临时文件。
     */
    private void cleanupTempDirs() {
        try {
            var root = new java.io.File(TEMP_ROOT);
            if (!root.exists() || !root.isDirectory()) {
                return;
            }
            cleanBlankDirs(root, root);
        } catch (Throwable e) {
            LogHelp.e(TAG, "cleanup temp dirs failed: " + e.getMessage(), e);
        }
    }

    /**
     * 自底向上删除空内容目录，并保留AllBackupTemp根目录。
     */
    private boolean cleanBlankDirs(java.io.File dir, java.io.File root) {
        var children = dir.listFiles();
        if (children != null) {
            for (var child : children) {
                if (child.isDirectory()) {
                    cleanBlankDirs(child, root);
                } else {
                    deleteBlankFile(child);
                }
            }
        }

        children = dir.listFiles();
        var blank = children == null || children.length == 0;
        if (!dir.equals(root) && blank) {
            return dir.delete();
        }
        return blank;
    }

    /**
     * 删除0字节临时文件，让只剩空文件的任务目录也能被清掉。
     */
    private void deleteBlankFile(java.io.File file) {
        if (file != null && file.isFile() && file.length() == 0) {
            file.delete();
        }
    }
}
