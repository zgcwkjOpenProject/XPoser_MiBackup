# 自定义 HTTP 脚本

这个目录用于存放可以粘贴到 App 自定义 HTTP 配置里的 JS 脚本。

- `example.js`：通用 HTTP 服务示例
- `yun139.js`：移动云盘 139 示例

App 内置默认模板在 `src/app/src/main/res/raw/custom_default.js`，用于设置页首次打开时展示。`plugins` 目录下的脚本不打包进 App，适合单独维护、测试和复制到手机上使用

## 使用方式

在设置页选择自定义 HTTP，把完整 JS 脚本粘贴到脚本输入框。服务器地址、Token、Cookie、签名和刷新逻辑都写在 JS 里，界面不会再单独提供这些输入项。

脚本内容会保存到 `/sdcard/MIUI/backup/config.ini` 的 `custom_script_b64`。脚本需要持久化刷新后的 Cookie 或 Token 时，使用 `stateGet` 和 `stateSet`

不要把真实 Cookie、Token、手机号或抓包内容提交到公开仓库。发布示例脚本时请使用占位值

## 切片规则

切片大小由设置页的 `chunk_size_mb` 控制：

| 值 | 行为 |
| --- | --- |
| `0` | 不切片，只上传原文件 |
| `> 0` | 大于该大小的文件会在 Cloud 层切片 |

启用切片时，Cloud 层会生成：

```text
原文件.part00000
原文件.part00001
原文件.mibak.json
```

脚本不需要理解切片格式。对脚本来说，`uploadFile(ctx)` 和 `downloadFile(ctx)` 收到的都是普通远端路径，可能是原文件，也可能是内部 part 或 manifest 文件。恢复时优先读取 manifest 并合并分片，manifest 不存在时会按旧版未切片文件读取。

## 脚本函数

脚本可实现这些函数：

```js
function testConnection(ctx) { return true; }
function listEntries(ctx) { return { method, url, headers, body }; }
function parseList(ctx, response) { return [{ name, size, directory, modifiedTime }]; }
function uploadFile(ctx) { return { method, url, headers }; }
function downloadFile(ctx) { return { method, url, headers }; }
function deletePath(ctx) { return { method, url, headers }; }
```

| 函数 | 说明 |
| --- | --- |
| `testConnection(ctx)` | 测试连接，必须返回 `true` 或 `false` |
| `listEntries(ctx)` | 列出 `ctx.remoteDir`，可直接返回条目数组，也可返回请求对象并配合 `parseList` |
| `parseList(ctx, response)` | 把 HTTP 响应转换为 `[{ name, size, directory, modifiedTime }]` |
| `uploadFile(ctx)` | 上传 `ctx.remotePath` 对应的单个普通文件，可返回请求对象，也可自行上传后返回 `{ handled: true }` |
| `downloadFile(ctx)` | 下载 `ctx.remotePath` 到当前恢复目标，可返回请求对象，也可调用 `httpDownload` 后返回 `{ handled: true }` |
| `deletePath(ctx)` | 删除 `ctx.remotePath`，可返回请求对象，也可自行删除后返回 `{ handled: true }` |

`uploadFile`、`downloadFile` 和 `deletePath` 有两种写法：

```js
// 写法一：返回请求对象，由 Java 执行单个 HTTP 请求
function downloadFile(ctx) {
  return {
    method: 'GET',
    url: 'https://example.com/files/' + encodeURIComponent(ctx.remotePath),
    headers: { Authorization: 'Bearer token' }
  };
}

// 写法二：脚本自己完成多步流程，最后返回 handled
function downloadFile(ctx) {
  var response = httpDownload({
    method: 'GET',
    url: 'https://example.com/files/' + encodeURIComponent(ctx.remotePath),
    headers: { Authorization: 'Bearer token' }
  });
  return { handled: response.code >= 200 && response.code < 300 };
}
```

## ctx 字段

| 字段 | 说明 |
| --- | --- |
| `backupPath` | 备份根目录配置 |
| `remoteDir` | 当前要列出的远端目录，或上传文件所在目录 |
| `remotePath` | 当前要上传、下载或删除的远端路径 |
| `fileName` | 当前上传文件名 |
| `fileSize` | 当前上传文件大小 |
| `contentHash` | 当前上传文件的 SHA256 |
| `contentHashAlgorithm` | 当前上传文件哈希算法，当前为 `SHA256` |
| `localPath` | 当前下载目标路径 |

## 请求对象

返回给 Java 执行的 HTTP 请求对象格式：

```js
{
  method: 'GET',
  url: 'https://example.com/api',
  headers: {
    Authorization: 'Bearer token'
  },
  body: '',
  streamFile: false,
  readBody: true
}
```

| 字段 | 说明 |
| --- | --- |
| `method` | HTTP 方法，默认 `GET` |
| `url` | 完整请求地址 |
| `headers` | 请求头对象 |
| `body` | 文本请求体 |
| `streamFile` | 上传当前文件时设为 `true`，Java 会把当前本地文件作为请求体流式上传 |
| `readBody` | 是否读取响应体，默认 `true`；大文件流式下载场景可设为 `false` |

## 宿主函数

脚本里可直接调用这些通用辅助函数：

```js
httpRequest({ method, url, headers, body, streamFile, readBody })
httpDownload({ method, url, headers, body })
stateGet(key, defaultValue)
stateSet(key, value)
base64Encode(text)
base64Decode(text)
hashHex(algorithm, text)
```

| 函数 | 说明 |
| --- | --- |
| `httpRequest(spec)` | 发起普通 HTTP 请求，返回 `{ code, body, headers }`；上传当前文件时设置 `streamFile: true` |
| `httpDownload(spec)` | 发起 HTTP 请求，并把响应流写入当前下载目标文件，返回 `{ code, body, headers }` |
| `stateGet(key, defaultValue)` | 读取脚本持久化状态，适合保存刷新后的 Cookie 或 Token |
| `stateSet(key, value)` | 写入脚本持久化状态 |
| `base64Encode(text)` | 把 UTF-8 文本编码为 Base64 |
| `base64Decode(text)` | 把 Base64 解码为 UTF-8 文本 |
| `hashHex(algorithm, text)` | 计算文本摘要，例如 `MD5`、`SHA-1`、`SHA-256` |

## 最小示例

```js
var SERVER = 'https://example.com/backup';
var TOKEN = 'replace-with-your-token';

function joinUrl(base, path) {
  base = trimRightSlash(base);
  path = trimLeftSlash(path);
  return base + '/' + path.split('/').map(encodeURIComponent).join('/');
}

function trimLeftSlash(value) {
  value = String(value || '');
  while (value.length > 0 && value.charAt(0) === '/') {
    value = value.substring(1);
  }
  return value;
}

function trimRightSlash(value) {
  value = String(value || '');
  while (value.length > 0 && value.charAt(value.length - 1) === '/') {
    value = value.substring(0, value.length - 1);
  }
  return value;
}

function authHeaders() {
  return { Authorization: 'Bearer ' + stateGet('token', TOKEN) };
}

function testConnection(ctx) {
  var response = httpRequest({
    method: 'GET',
    url: SERVER,
    headers: authHeaders()
  });
  return response.code >= 200 && response.code < 400;
}

function listEntries(ctx) {
  return {
    method: 'GET',
    url: joinUrl(SERVER, ctx.remoteDir) + '?list=1',
    headers: authHeaders()
  };
}

function parseList(ctx, response) {
  return JSON.parse(response.body || '[]');
}

function uploadFile(ctx) {
  return {
    method: 'PUT',
    url: joinUrl(SERVER, ctx.remotePath),
    headers: authHeaders(),
    streamFile: true
  };
}

function downloadFile(ctx) {
  return {
    method: 'GET',
    url: joinUrl(SERVER, ctx.remotePath),
    headers: authHeaders()
  };
}

function deletePath(ctx) {
  return {
    method: 'DELETE',
    url: joinUrl(SERVER, ctx.remotePath),
    headers: authHeaders()
  };
}
```
