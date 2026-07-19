// 自定义 HTTP 存储脚本示例
// 服务器地址、Token、认证方式、路径规则都写在这里，不需要在界面单独填写
// 插件负责读取文件、按界面配置的切片大小生成内部文件、回调进度和合并分片
// 启用切片时 uploadFile(ctx) 会收到普通文件、part 文件和 mibak.json 文件
// 切片大小填 0 时 uploadFile(ctx) 只会收到原文件
// 脚本只需要返回 { method, url, headers, body } 这样的 HTTP 请求描述

var SERVER = 'https://192.168.1.100:8080/backup';
var TOKEN = 'replace-with-your-token';

function joinUrl(base, path) {
  base = String(base || '').replace(/\/+$/, '');
  path = String(path || '').replace(/^\/+/, '');
  return base + '/' + path.split('/').map(encodeURIComponent).join('/');
}

function auth() {
  return TOKEN ? { 'Authorization': 'Bearer ' + TOKEN } : {};
}

// 测试连接，只要 HTTP 状态码是 2xx/3xx，插件就认为连接成功
function testConnection(ctx) {
  return { method: 'GET', url: SERVER, headers: auth() };
}

// 列目录，服务端示例返回 JSON 数组
// [{"name":"20260719_010203","size":0,"directory":true,"modifiedTime":1784394123000}]
function listEntries(ctx) {
  return { method: 'GET', url: joinUrl(SERVER, ctx.remoteDir) + '?list=1', headers: auth() };
}

function parseList(ctx, response) {
  return JSON.parse(response.body || '[]');
}

// 上传普通文件，切片细节由插件变成普通文件名后再调用这里
function uploadFile(ctx) {
  return { method: 'PUT', url: joinUrl(SERVER, ctx.remotePath), headers: auth() };
}

function downloadFile(ctx) {
  return { method: 'GET', url: joinUrl(SERVER, ctx.remotePath), headers: auth() };
}

// 清理旧备份目录，服务端可以在这个 DELETE 中递归删除目录和内部文件
function deletePath(ctx) {
  return { method: 'DELETE', url: joinUrl(SERVER, ctx.remotePath), headers: auth() };
}
