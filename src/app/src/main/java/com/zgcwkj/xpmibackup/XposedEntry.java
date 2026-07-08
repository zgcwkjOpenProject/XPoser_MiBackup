package com.zgcwkj.xpmibackup;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.xpmibackup.hook.SettingsHook;
import com.zgcwkj.xpmibackup.hook.BackupHook;
import com.zgcwkj.xpmibackup.hook.RestoreHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed模块入口
 * 当目标APP加载时由框架调用handleLoadPackage，注册所有Hook
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "XpMiBackup";

    /**
     * 框架回调：目标APP进程启动时触发
     * 按顺序注册设置入口Hook、备份Hook、恢复Hook
     */
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log(TAG + ": Loaded in " + lpparam.packageName);
        // android.util.Log.e("XpMiBackup", "Loaded in " + lpparam.packageName);
        // 清理AllBackupTemp下的空目录
        if ("com.miui.backup".equals(lpparam.packageName)) cleanupTempDirs();
        // 设置APP：注入"云备份助手"入口，启用智能存储功能
        new SettingsHook().hook(lpparam);
        // 备份APP：拦截DFS连接、上传重定向到云端
        new BackupHook().hook(lpparam);
        // 备份APP：拦截恢复列表加载和下载流程
        new RestoreHook().hook(lpparam);
    }

    /**
     * 清理AllBackupTemp下的空目录
     * 递归删除所有空文件夹，有文件的目录保留
     */
    private void cleanupTempDirs() {
        try {
            var root = new java.io.File(ConfigHelp.BACKUP_ROOT + "/AllBackupTemp");
            if (!root.exists() || !root.isDirectory()) return;
            cleanEmptyDirs(root);
        } catch (Throwable ignored) {}
    }

    /** 递归删除空目录：先处理子目录，再判断当前目录是否为空 */
    private void cleanEmptyDirs(java.io.File dir) {
        var children = dir.listFiles();
        if (children != null) {
            for (var child : children) {
                if (child.isDirectory()) {
                    cleanEmptyDirs(child);
                }
            }
        }
        // 重新列出（子目录可能已被删除）
        children = dir.listFiles();
        if (dir.equals(new java.io.File(ConfigHelp.BACKUP_ROOT + "/AllBackupTemp"))) return;
        if (children == null || children.length == 0) {
            dir.delete();
        }
    }
}
