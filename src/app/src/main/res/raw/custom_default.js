// 自定义 HTTP 存储脚本示例
//
// 使用方式
// 1. 服务器地址、Token、认证方式、路径规则都写在这个 JS 里，界面只需要粘贴脚本
// 2. 插件负责读取本地文件、上传进度、下载落盘，以及按配置切片和恢复合并
// 3. 脚本负责把小米备份传入的远端路径转换成你的服务端接口请求
//
// 宿主提供的函数
// httpRequest(spec) 执行普通 HTTP 请求，并返回 { code, body, headers }
// httpDownload(spec) 执行下载请求，并把响应流写入 ctx.localPath 指向的文件，返回 { code, body, headers }
// stateGet(key, defaultValue) 读取持久化脚本状态，适合保存刷新后的 cookie/token
// stateSet(key, value) 写入持久化脚本状态
// base64Encode(text) 把 UTF-8 文本编码为 Base64
// base64Decode(text) 把 Base64 解码为 UTF-8 文本
// hashHex(algorithm, text) 计算文本摘要，例如 hashHex('MD5', text)、hashHex('SHA-256', text)
//
// 请求对象 spec 字段
// method: HTTP 方法，默认 GET
// url: 完整 URL，必填
// headers: 请求头对象，可选
// body: 文本请求体，可选
// streamFile: true 表示把当前上传文件作为请求体发送，只能在 uploadFile(ctx) 中使用
// readBody: false 表示不读取响应体，适合上传文件或大响应
//
// ctx 公共字段
// ctx.backupPath: 设置页里的备份根路径，例如 MIUI/backup
// ctx.remoteDir: listEntries/uploadFile 使用的远端目录路径
// ctx.remotePath: uploadFile/downloadFile/deletePath 使用的远端完整文件或目录路径
// ctx.localPath: downloadFile 的本地目标文件路径
// ctx.fileName: uploadFile 的文件名
// ctx.fileSize: uploadFile 的文件大小，单位 byte
// ctx.contentHash: uploadFile 的 SHA-256 文件摘要
// ctx.contentHashAlgorithm: 当前固定为 SHA256
//
// 切片说明
// 切片大小填 0 时 uploadFile(ctx) 只会收到原文件
// 启用切片时 Cloud 层会把大文件拆成 .part00000 等文件，并额外上传 .mibak.json
// 脚本不需要理解切片格式，把收到的每个 ctx.remotePath 当普通文件上传即可
//
// 返回值约定
// testConnection(ctx) 必须自己判断连接是否成功并返回 true 或 false
// listEntries(ctx) 可以直接返回条目数组，也可以返回请求对象并由 parseList(ctx, response) 解析
// uploadFile/downloadFile/deletePath 可以返回请求对象，也可以自己调用 httpRequest/httpDownload 后返回 { handled: true }

// 服务端根地址，示例会把所有远端路径拼到这个地址后面
var SERVER = 'https://192.168.1.100:8080/backup';

// Token 可以直接写死，也可以刷新后用 stateSet 保存
var TOKEN_KEY = 'demo.token';
var TOKEN = 'replace-with-your-token';

// 拼接 URL，并逐段 encode 远端路径，避免中文或空格路径出错
function joinUrl(base, path) {
  base = String(base || '');
  path = String(path || '');
  while (base.length > 0 && base.charAt(base.length - 1) === '/') {
    base = base.substring(0, base.length - 1);
  }
  while (path.charAt(0) === '/') {
    path = path.substring(1);
  }
  return base + '/' + path.split('/').map(encodeURIComponent).join('/');
}

// 读取当前认证 token，优先使用 stateSet 保存的新值
function token() {
  return stateGet(TOKEN_KEY, TOKEN);
}

// 示例刷新 token 后可调用此函数持久保存
function rememberToken(value) {
  stateSet(TOKEN_KEY, value || '');
}

// 构造认证头，实际项目可替换为 Cookie、Basic、签名头等
function auth() {
  return token() ? { 'Authorization': 'Bearer ' + token() } : {};
}

// 示例签名函数，使用宿主提供的 hashHex
function sign(text) {
  return hashHex('MD5', text || '');
}

// Base64 编码示例
function pack(text) {
  return base64Encode(text || '');
}

// Base64 解码示例
function unpack(text) {
  return base64Decode(text || '');
}

// 只构造请求对象，不立即发起请求
// 适合 listEntries/uploadFile/downloadFile/deletePath 交给 Java 统一执行
function requestSpec(method, url, body, extraHeaders, streamFile, readBody) {
  var headers = auth();
  if (extraHeaders) {
    Object.keys(extraHeaders).forEach(function (key) {
      headers[key] = extraHeaders[key];
    });
  }
  var spec = {
    method: method,
    url: url,
    headers: headers
  };
  if (body !== undefined && body !== null) {
    spec.body = body;
  }
  if (streamFile) {
    spec.streamFile = true;
  }
  if (readBody === false) {
    spec.readBody = false;
  }
  return spec;
}

// 立即发起请求
// 适合 testConnection，或者脚本需要多步接口调用时使用
function request(method, url, body, extraHeaders, streamFile, readBody) {
  return httpRequest(requestSpec(method, url, body, extraHeaders, streamFile, readBody));
}

// 测试连接
// 必须返回 true 或 false，哪些 HTTP 状态码或业务码算成功由脚本自己决定
function testConnection(ctx) {
  var response = request('GET', SERVER, null);
  return response.code >= 200 && response.code < 400;
}

// 列目录
// ctx.remoteDir 是要列出的远端目录
// 这里返回请求对象，Java 执行后会把响应交给 parseList
// 服务端示例返回 JSON 数组
// [{"name":"20260719_010203","size":0,"directory":true,"modifiedTime":1784394123000}]
function listEntries(ctx) {
  return requestSpec('GET', joinUrl(SERVER, ctx.remoteDir) + '?list=1', null);
}

// 解析 listEntries 返回的响应
// 必须返回数组，每项字段为 name、size、directory、modifiedTime
function parseList(ctx, response) {
  return JSON.parse(response.body || '[]');
}

// 上传文件
// ctx.remotePath 是完整远端路径，ctx.remoteDir 是父目录
// streamFile=true 表示请求体使用当前上传文件流
// readBody=false 表示不读取上传响应体，减少内存占用
function uploadFile(ctx) {
  return requestSpec(
    'PUT',
    joinUrl(SERVER, ctx.remotePath),
    null,
    {
      'X-File-Hash': sign((ctx.fileName || '') + ':' + (ctx.contentHash || '')),
      'X-Local-Created-At': new Date().toISOString()
    },
    true,
    false
  );
}

// 下载文件
// 返回请求对象时 Java 会流式下载到 ctx.localPath
// 如果服务端需要先换取临时下载地址，也可以在这里调用 httpDownload 并返回 { handled: true }
function downloadFile(ctx) {
  return requestSpec('GET', joinUrl(SERVER, ctx.remotePath), null);
}

// 删除远端路径
// Cloud 层会在清理旧备份时传入备份目录路径
// 服务端如果支持目录递归删除，可以直接 DELETE 目录
function deletePath(ctx) {
  return requestSpec('DELETE', joinUrl(SERVER, ctx.remotePath), null);
}

// 网络请求示例
// 下面代码默认注释，不会执行；需要时复制到对应函数中使用
//
// GET 请求示例
// var getResponse = httpRequest({
//   method: 'GET',
//   url: 'https://example.com/ping',
//   headers: {
//     'Authorization': 'Bearer your-token',
//     'Accept': 'application/json'
//   }
// });
// var getJson = JSON.parse(getResponse.body || '{}');
//
// POST JSON 请求示例
// var postResponse = httpRequest({
//   method: 'POST',
//   url: 'https://example.com/api/check',
//   headers: {
//     'Content-Type': 'application/json',
//     'Authorization': 'Bearer your-token'
//   },
//   body: JSON.stringify({
//     path: ctx.backupPath
//   })
// });
//
// 上传当前文件流示例，只能放在 uploadFile(ctx) 里使用
// var uploadResponse = httpRequest({
//   method: 'PUT',
//   url: 'https://example.com/upload/' + encodeURIComponent(ctx.remotePath),
//   headers: {
//     'Content-Type': 'application/octet-stream',
//     'Authorization': 'Bearer your-token'
//   },
//   streamFile: true,
//   readBody: false
// });
//
// 下载到当前恢复目标文件示例，只能放在 downloadFile(ctx) 里使用
// var downloadResponse = httpDownload({
//   method: 'GET',
//   url: 'https://example.com/download/' + encodeURIComponent(ctx.remotePath),
//   headers: {
//     'Authorization': 'Bearer your-token'
//   }
// });
