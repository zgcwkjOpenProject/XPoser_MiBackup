# XPoser_MiBackup - 云备份助手

通过 Xposed 模块虚拟一个小米智能存储设备，将小米备份 APP 的备份/恢复流程重定向到自建云端存储（SMB/WebDAV），无需购买小米 NAS 即可实现云端备份。

## 原理

小米备份 APP 通过 DFS（Distributed File System）协议连接小米智能存储设备进行备份。本模块通过 Xposed Hook 注入 `com.miui.backup` 进程，拦截 DFS 连接流程，将其重定向到用户自建的 SMB 或 WebDAV 服务。

```
小米备份 APP → DFS 连接（被拦截）→ Mock CONNECTED 状态
            → 文件上传（被拦截）→ SMB/WebDAV 上传
            → 文件下载（被拦截）→ SMB/WebDAV 下载
```

## 功能

- 在 MIUI 设置中注入「云备份助手」配置入口
- 拦截 DFS 连接，模拟智能存储设备已连接状态
- 备份时将文件上传到 SMB/WebDAV 服务器
- 恢复时从云端下载备份文件
- 自动清理超出数量限制的旧备份
- 支持 SMB/CIFS 和 WebDAV 两种传输协议

# 图片预览

![001](imgs/001.jpg?20260709)

![002](imgs/002.jpg?20260709)

![003](imgs/003.jpg?20260709)

## 环境要求

- Android 9.0+（minSdk 28）
- 已安装 Xposed 框架（LSPatch / LSPosed / EdXposed 等）
- 支持的 Xposed 作用域：`com.android.settings`、`com.miui.backup`

## 编译

需要 JDK 17 和 Android SDK（compileSdk 36）。

```bash
cd src
gradlew assembleDebug
```

编译产物位于 `src/app/build/outputs/apk/debug/`，安装到设备后在 Xposed 管理器中启用模块并重启目标 APP。

## 配置

安装后在 MIUI 设置页面找到「云备份助手」入口，进入配置界面：

**设备配置**

| 配置项 | 说明 |
|---|---|
| 设备名称 | 虚拟设备名称，显示在设置页面 |
| 设备描述 | 设置页面中的描述文字 |
| 上传线程数 | 并发上传线程数，默认 3 |
| 备份路径 | 云端存储的根路径，如 `MIUI/backup` |
| 最大备份数 | 超出时自动清理旧备份，设 0 不清理 |

**服务配置**

| 协议 | 参数 |
|---|---|
| SMB | 服务器地址、端口、共享文件夹、用户名、密码 |
| WebDAV | 服务器地址（需 HTTPS）、用户名、密码 |

配置文件存储在 `/sdcard/MIUI/backup/config.ini`，可手动编辑。

## 项目结构

```
src/app/src/main/java/
├── com/zgcwkj/
│   ├── comm/
│   │   ├── ConfigHelp.java         # 配置文件读写（INI 格式）
│   │   ├── CloudFileHelp.java      # 云存储统一接口（协议路由）
│   │   ├── SmbFileHelp.java        # SMB 协议实现
│   │   └── WebdavFileHelp.java     # WebDAV 协议实现
│   └── xpmibackup/
│       ├── XposedEntry.java        # Xposed 模块入口
│       ├── MainActivity.java       # 主界面（Tab 切换）
│       ├── hook/
│       │   ├── SettingsHook.java   # 设置 APP Hook（注入入口、模拟设备）
│       │   ├── BackupHook.java     # 备份 Hook（拦截上传、重定向到云端）
│       │   └── RestoreHook.java    # 恢复 Hook（拦截下载、从云端读取列表）
│       └── ui/
│           ├── DeviceConfigFragment.java   # 设备配置界面
│           └── ServiceConfigFragment.java  # 服务配置界面
```

## 技术细节

**设置 Hook** (`SettingsHook`)
- `MiuiSettings.updateHeaderList` - 在「小米澎湃AI」下方插入「云备份助手」入口
- `SmartStorageController.getAvailabilityStatus` - 强制返回可用状态
- `BackupNasDeviceProvider.query` - 返回模拟的 NAS 设备数据

**备份 Hook** (`BackupHook`)
- `DeviceConnector.doConnect` - 拦截 DFS 连接，测试云端可达后返回 CONNECTED
- `DistFileClientImpl.upload` - 将 DFS 上传重定向到 SMB/WebDAV（线程池异步执行）
- `NASTransferService.abortNASTransferTask` - 取消备份时清理云端文件

**恢复 Hook** (`RestoreHook`)
- `NasBackupsLoader.doInBackground` - 从云端读取 `descript.xml` 构建恢复列表
- `downloadFromNasTask.run` - 用云端下载替换 DFS 下载
- `NASVFileOperationUtils` - 所有静态方法返回 mock 成功值，避免 DFS 超时

## 依赖

| 库 | 版本 | 用途 |
|---|---|---|
| [Xposed API](https://api.xposed.info/) | 82 | 框架 Hook 能力 |
| [smbj](https://github.com/hierynomus/smbj) | 0.13.0 | SMB/CIFS 协议 |
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0 | HTTP 客户端（WebDAV） |

## 许可证

[Apache License 2.0](LICENSE)
