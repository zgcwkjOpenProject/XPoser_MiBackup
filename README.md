# XPoser_MiBackup - 云备份助手

通过 Xposed 模块虚拟小米智能存储设备，将小米备份 App 的 DFS 存储流程重定向到自建 SMB 或 WebDAV 服务，实现备份与恢复数据的云端存储。

## 原理

小米备份 App 会通过 DFS 服务连接小米智能存储设备，并通过 AIDL 接口执行目录查询、文件上传、文件下载和进度回调。本模块注入 `com.android.settings` 与 `com.miui.backup` 进程，在设置页展示配置入口，并在备份 App 的 DFS/AIDL 边界将文件操作改由 SMB/WebDAV 完成。

```text
小米备份 App
  -> 查询智能存储设备：返回虚拟设备
  -> 连接 DFS 服务：模拟在线和已连接
  -> DFS AIDL 上传：写入 SMB/WebDAV
  -> DFS AIDL 下载：从 SMB/WebDAV 读取
  -> 进度与完成回调：回传给小米备份原流程
```

主要 Hook 边界在 DFS AIDL、设置页入口和明确的备份 UI/服务事件，尽量避免直接依赖混淆业务函数。

## 功能

- 在系统设置中注入“云备份助手”配置入口。
- 模拟小米智能存储设备在线状态。
- 将备份文件上传重定向到 SMB/WebDAV。
- 将恢复文件下载重定向到 SMB/WebDAV。
- 恢复列表从云端备份目录读取 `descript.xml` 构建。
- 备份路径自动去掉 `.AllBackup` 等 DFS 虚拟目录片段。
- 备份完成后自动清理超出保留数量的旧备份。
- 备份中途取消时清理云端半成品目录。
- 修正备份进度页完成按钮和暂停通知等界面行为。

## 环境要求

- Android 9.0+（minSdk 28）
- 已安装并启用 Xposed/LSPosed 等框架。
- 作用域至少包含：
  - `com.android.settings`
  - `com.miui.backup`
- 调试包可直接安装使用；本项目不处理签名问题。

# 图片预览

![001](imgs/001.jpg?20260709)

![002](imgs/002.jpg?20260709)

![003](imgs/003.jpg?20260709)

## 配置

配置入口：系统设置 -> 云备份助手。

配置文件：

```text
/sdcard/MIUI/backup/config.ini
```

常用配置：

| 配置项 | 说明 |
| --- | --- |
| `protocol` | 存储协议，`smb` 或 `webdav` |
| `backup_path` | 云端备份根目录，默认 `MIUI/backup` |
| `upload_threads` | 并发上传线程数，默认 `3` |
| `backup_max` | 最大保留备份数，`0` 表示不自动清理 |
| `device_name` | 设置页中展示的虚拟设备名称 |
| `device_desc` | 设置页中展示的虚拟设备描述 |

SMB 配置：

| 配置项 | 说明 |
| --- | --- |
| `smb_server` | SMB 服务器地址 |
| `smb_port` | SMB 端口，默认 `445` |
| `smb_share` | SMB 共享名 |
| `smb_user` | SMB 用户名 |
| `smb_pass` | SMB 密码 |

WebDAV 配置：

| 配置项 | 说明 |
| --- | --- |
| `webdav_url` | WebDAV 根地址 |
| `webdav_user` | WebDAV 用户名 |
| `webdav_pass` | WebDAV 密码 |

## 项目结构

```text
app/src/main/java/com/zgcwkj/
  comm/
    ConfigHelp.java       配置文件读写
    CloudFileHelp.java    SMB/WebDAV 统一入口
    SmbFileHelp.java      SMB 实现
    WebdavFileHelp.java   WebDAV 实现
  xpmibackup/
    XposedEntry.java      Xposed 入口
    MainActivity.java     配置界面 Activity
    hook/
      SettingsHook.java   设置页入口和虚拟设备展示
      AIDLHook.java       DFS AIDL 重定向
      BackupHook.java     备份 App 页面、通知、焦点和取消处理
    ui/
      DeviceConfigFragment.java   虚拟设备配置
      ServiceConfigFragment.java  SMB/WebDAV 服务配置
```

## Hook 说明

### SettingsHook

负责设置 App 中的入口展示和虚拟设备数据。

- 注入“云备份助手”配置入口。
- 让智能存储相关入口显示为可用。
- 向备份 App 返回虚拟设备信息。

### AIDLHook

负责 DFS AIDL 层的核心重定向。

- 模拟 DFS 服务查询、绑定和连接状态。
- 拦截上传调用，将 `ParcelFileDescriptor` 中的数据写入临时文件，再上传到 SMB/WebDAV。
- 拦截下载调用，从 SMB/WebDAV 下载后写回 `ParcelFileDescriptor`。
- 统一处理远端路径，去掉 `.AllBackup`、`.AppBackup` 等 DFS 虚拟目录片段。
- 从云端 `descript.xml` 构建恢复列表，避免 DFS list 慢或不返回导致页面空等。
- 在上传完成 `end` 标记文件后触发旧备份清理。

### BackupHook

负责备份 App 的 UI 和服务行为修正。

- 修正完成按钮点击后仍回到密码界面的问题。
- 仅处理连接断开导致的暂停通知，避免误清理其他备份通知。
- 同步 NAS 任务开始事件，让备份进度页保持当前项焦点并滚动。
- 备份中途取消时删除本次已经上传到云端的半成品目录。

## 编译

需要 JDK 17 和 Android SDK（compileSdk 36）。

```bash
cd src
gradlew assembleDebug
```

编译产物位于 `src/app/build/outputs/apk/debug/`，安装到设备后在 Xposed 管理器中启用模块并重启目标 APP。

## 依赖

| 库 | 版本 | 用途 |
|---|---|---|
| [Xposed API](https://api.xposed.info/) | 82 | 框架 Hook 能力 |
| [smbj](https://github.com/hierynomus/smbj) | 0.13.0 | SMB/CIFS 协议 |
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0 | HTTP 客户端（WebDAV） |

## 许可证

[Apache License 2.0](LICENSE)
