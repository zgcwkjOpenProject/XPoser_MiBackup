// 移动云盘 139 自定义 HTTP 脚本
//
// 使用方式
// 1. 把 COOKIE 改成自己的 Authorization 值后粘贴到 App 的自定义脚本输入框
// 2. App 只负责把本地文件流交给脚本，并负责通用切片、进度、恢复合并
// 3. 本脚本负责把小米备份的路径语义转换成移动云盘的 fileId 语义
//
// 宿主函数依赖
// httpRequest(spec): 执行 HTTP 请求，返回 { code, body, headers }
// httpDownload(spec): 下载响应流到当前恢复目标文件，返回 { code, body, headers }
// stateGet/stateSet: 持久化保存 Cookie 等状态
// base64Encode/base64Decode: 移动云盘签名和 Basic 解析需要
// hashHex: 移动云盘 mcloud-sign 需要 MD5
//
// 备份路径说明
// 小米备份传入的是路径，例如 MIUI/backup/20260720_210905/descript.xml
// 移动云盘上传和下载需要父目录 fileId，所以脚本会逐级 list 并解析路径
// 上传时目录不存在会自动创建，列表或下载时目录不存在会返回空或报文件缺失
//
// Rhino 兼容说明
// 手机备份进程里的 Rhino 不支持正则表达式，因此脚本里避免使用正则字面量
// 也尽量避免较新的 JS API，保持 ES5 风格

var API_BASE = 'https://personal-kd-njs.yun.139.com';

// Cookie 状态 key，刷新 Cookie 后可以用 stateSet(COOKIE_KEY, newCookie) 保存
var COOKIE_KEY = 'mcloud.cookie';

// 移动云盘 Authorization，占位值需要替换成自己的 Basic ...
var COOKIE = 'Basic replace-with-your-authorization';

// 移动云盘根目录 fileId
var ROOT_PARENT_ID = '/';

// 读取当前 Cookie，优先使用 stateSet 保存过的新值
function cookieValue() {
  if (typeof stateGet === 'function') {
    return stateGet(COOKIE_KEY, COOKIE);
  }
  return COOKIE;
}

// 包装宿主 Base64 编码，便于脚本脱离 App 后在测试 harness 里替换
function base64EncodeText(text) {
  if (typeof base64Encode === 'function') {
    return base64Encode(text || '');
  }
  return '';
}

// 包装宿主 Base64 解码，用于从 Basic Authorization 中取账号
function base64DecodeText(text) {
  if (typeof base64Decode === 'function') {
    return base64Decode(text || '');
  }
  return '';
}

// 包装宿主 hashHex，移动云盘 mcloud-sign 当前只用 MD5
function digestHex(algorithm, text) {
  if (typeof hashHex === 'function') {
    return hashHex(algorithm || 'MD5', text || '');
  }
  return '';
}

// 生成签名里使用的随机串
function randomString(length) {
  var chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  var out = '';
  for (var i = 0; i < length; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return out;
}

// 生成移动云盘签名需要的本地时间字符串
function currentTimestamp() {
  var d = new Date();
  var pad = function (n) {
    n = String(n);
    return n.length < 2 ? '0' + n : n;
  };
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
    pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

// 从 Basic Authorization 中解析手机号账号
function decodeBasicAccount(auth) {
  var value = String(auth || '').trim();
  if (value.toLowerCase().indexOf('basic ') === 0) {
    value = value.substring(6).trim();
  }
  var decoded = base64DecodeText(value);
  var parts = decoded.split(':');
  return parts.length > 1 ? parts[1] : '';
}

// file/create 需要 commonAccountInfo，否则某些账号会返回参数错误
function injectCommonAccount(body, auth) {
  var account = decodeBasicAccount(auth || cookieValue());
  if (!account) {
    return body;
  }
  var json = parseJson(body, null);
  if (!json) {
    return body;
  }
  if (!json.commonAccountInfo) {
    json.commonAccountInfo = {};
  }
  if (!json.commonAccountInfo.account) {
    json.commonAccountInfo.account = account;
  }
  if (!json.commonAccountInfo.accountType) {
    json.commonAccountInfo.accountType = 1;
  }
  return JSON.stringify(json);
}

// 计算移动云盘 mcloud-sign
// 算法来自抓包复现：body URL 编码后逐字符排序，再 Base64 + MD5，并混入时间随机串
function mcloudSign(body) {
  var encoded = encodeURIComponent(body || '');
  var chars = encoded.split('');
  chars.sort();
  var sorted = chars.join('');
  var base64 = base64EncodeText(sorted);
  var md5 = digestHex('MD5', base64);
  var ts = currentTimestamp();
  var rand = randomString(16);
  var timeMd5 = digestHex('MD5', ts + ':' + rand);
  return digestHex('MD5', md5 + timeMd5).toUpperCase();
}

// 构造移动云盘 API 请求头
// 这些 x-yun-* 头来自移动云盘客户端抓包，缺失时部分接口会拒绝
function apiHeaders(extra, contentType) {
  var headers = {
    'x-yun-op-type': '1',
    'x-yun-sub-op-type': '100',
    'x-yun-api-version': 'v1',
    'x-yun-client-info': '6|127.0.0.1|1|12.1.0|realme|RMX5060|BCFF2BBA6881DD8E4971803C63DDB5E4|02-00-00-00-00-00|android 15|1264X2592|zh||||032|0|',
    'x-yun-app-channel': '10000023',
    'x-yun-channel-source': '10000034',
    'x-yun-module-type': '100',
    'x-yun-svc-type': '1',
    'x-huawei-channelSrc': '10000034',
    'CMS-DEVICE': 'default',
    'Authorization': cookieValue(),
    'Accept': 'application/json, text/plain, */*',
    'Content-Type': contentType || 'application/json;charset=UTF-8',
    'Connection': 'Keep-Alive',
    'Origin': 'https://yun.139.com',
    'Referer': 'https://yun.139.com/shareweb/',
    'Caller': 'web',
    'Mcloud-Route': '001',
    'User-Agent': 'okhttp/4.12.0'
  };
  if (extra) {
    Object.keys(extra).forEach(function (key) {
      headers[key] = extra[key];
    });
  }
  return headers;
}

// 构造直传对象存储的 PUT 请求头
function uploadHeaders(extra) {
  var headers = {
    'Content-Type': 'application/octet-stream',
    'Accept': '*/*'
  };
  if (extra) {
    Object.keys(extra).forEach(function (key) {
      headers[key] = extra[key];
    });
  }
  return headers;
}

// 调用移动云盘 JSON API
// path 是 /hcy/file/list、/hcy/file/create 等相对路径
// 返回 httpRequest 的响应对象 { code, body, headers }
function callApi(method, path, body, extraHeaders) {
  var finalBody = body;
  if (path === '/hcy/file/create') {
    finalBody = injectCommonAccount(finalBody);
  }
  var headers = apiHeaders(extraHeaders, finalBody !== undefined && finalBody !== null ? 'application/json;charset=UTF-8' : undefined);
  headers['mcloud-sign'] = mcloudSign(finalBody || '');
  var spec = {
    method: method,
    url: API_BASE + path,
    headers: headers
  };
  if (finalBody !== undefined && finalBody !== null) {
    spec.body = finalBody;
  }
  return httpRequest(spec);
}

// 调用 create 接口返回的直传 uploadUrl
// streamFile=true 时请求体由宿主直接读取当前上传文件
function callDirect(method, url, body, extraHeaders, streamFile) {
  var spec = {
    method: method,
    url: url,
    headers: uploadHeaders(extraHeaders)
  };
  if (body !== undefined && body !== null) {
    spec.body = body;
  }
  if (streamFile) {
    spec.streamFile = true;
  }
  return httpRequest(spec);
}

// 安全 JSON 解析，失败时返回 fallback，避免列表接口异常导致脚本崩溃
function parseJson(text, fallback) {
  try {
    return JSON.parse(text || '');
  } catch (e) {
    return fallback || null;
  }
}

// 规范化路径
// 不使用正则，兼容备份进程中的 Rhino
function trimSlashes(path) {
  var value = String(path || '').split('\\').join('/');
  while (value.charAt(0) === '/') {
    value = value.substring(1);
  }
  while (value.length > 0 && value.charAt(value.length - 1) === '/') {
    value = value.substring(0, value.length - 1);
  }
  return value;
}

// 返回路径的父目录；没有父目录时返回移动云盘根 fileId
function pathParent(path) {
  var value = trimSlashes(path);
  var index = value.lastIndexOf('/');
  if (index < 0) {
    return ROOT_PARENT_ID;
  }
  return value.substring(0, index);
}

// 返回路径最后一级名称
function pathName(path) {
  var value = trimSlashes(path);
  var index = value.lastIndexOf('/');
  return index < 0 ? value : value.substring(index + 1);
}

// 构造移动云盘分页列目录请求体
function buildListBody(parentFileId, pageCursor) {
  return JSON.stringify({
    imageThumbnailStyleList: ['Small', 'Large'],
    orderBy: 'updated_at',
    orderDirection: 'DESC',
    pageInfo: { pageCursor: pageCursor || '', pageSize: 1000 },
    parentFileId: parentFileId || ROOT_PARENT_ID
  });
}

// 列出单页目录内容
function listPage(parentFileId, pageCursor) {
  return callApi('POST', '/hcy/file/list', buildListBody(parentFileId, pageCursor));
}

// 分页收集目录下所有条目
function collectEntries(parentFileId) {
  var items = [];
  var cursor = '';
  for (var guard = 0; guard < 100; guard++) {
    var res = listPage(parentFileId, cursor);
    var json = parseJson(res && res.body, {});
    var data = json && json.data ? json.data : {};
    var pageItems = data.items || [];
    for (var i = 0; i < pageItems.length; i++) {
      items.push(pageItems[i]);
    }
    cursor = data.nextPageCursor || '';
    if (!cursor) {
      break;
    }
  }
  return items;
}

// 兼容不同接口返回字段，统一取条目名称
function entryName(item) {
  return item && (item.fileName || item.name || '') || '';
}

// 兼容不同接口返回字段，统一取条目 fileId
function entryId(item) {
  return item && (item.fileId || item.id || '') || '';
}

// 判断条目是否为目录
function isFolder(item) {
  if (!item) {
    return false;
  }
  return item.type === 'folder' || item.fileType === 'folder' || item.category === 'folder';
}

// 在指定父目录 fileId 下查找子项
function findChild(parentFileId, name) {
  var items = collectEntries(parentFileId || ROOT_PARENT_ID);
  for (var i = 0; i < items.length; i++) {
    var item = items[i] || {};
    if (entryName(item) === name) {
      return item;
    }
  }
  return null;
}

// 构造创建目录请求体
// 目录不能带 fileRenameMode，否则移动云盘会报“目录类型不支持当前命名模式”
function buildCreateFolderBody(parentFileId, name) {
  return JSON.stringify({
    type: 'folder',
    name: name || '',
    parentFileId: parentFileId || ROOT_PARENT_ID,
    localCreatedAt: new Date().toISOString()
  });
}

// 创建目录并返回新目录 fileId
function createFolder(parentFileId, name) {
  var res = callApi('POST', '/hcy/file/create', buildCreateFolderBody(parentFileId, name));
  if (res.code < 200 || res.code >= 300) {
    throw new Error('create folder failed: HTTP ' + res.code);
  }
  var json = parseJson(res.body, {});
  if (!json.success) {
    throw new Error('create folder failed: ' + (json.message || 'unknown'));
  }
  var data = json.data || {};
  var id = data.fileId || data.id || '';
  if (!id) {
    throw new Error('create folder failed: missing fileId');
  }
  return id;
}

// 把小米备份路径解析成移动云盘目录 fileId
// createMissing=true 用于上传，会自动创建缺失目录
// createMissing=false 用于列表/下载探测，缺失或目标不是目录时返回空字符串
function resolvePath(path, createMissing) {
  var value = trimSlashes(path);
  if (!value) {
    return ROOT_PARENT_ID;
  }
  var parts = value.split('/');
  var parentId = ROOT_PARENT_ID;
  for (var i = 0; i < parts.length; i++) {
    var name = parts[i];
    if (!name) {
      continue;
    }
    var child = findChild(parentId, name);
    if (!child) {
      if (!createMissing) {
        return '';
      }
      parentId = createFolder(parentId, name);
      continue;
    }
    if (!isFolder(child) && !createMissing) {
      return '';
    }
    if (!isFolder(child)) {
      throw new Error('path is not folder: ' + parts.slice(0, i + 1).join('/'));
    }
    parentId = entryId(child);
    if (!parentId) {
      throw new Error('missing folder id: ' + name);
    }
  }
  return parentId;
}

// 根据父路径和文件名查找文件或目录条目
function findEntry(parentPath, targetName) {
  var parentId = resolvePath(parentPath, false);
  if (!parentId) {
    return null;
  }
  var items = collectEntries(parentId);
  for (var i = 0; i < items.length; i++) {
    var item = items[i] || {};
    if (entryName(item) === targetName) {
      return item;
    }
  }
  return null;
}

// 从列表条目里尽量取下载地址；没有时再调用 getDownloadUrl
function pickDownloadUrl(item) {
  if (!item) {
    return '';
  }
  if (item.downloadUrl) {
    return item.downloadUrl;
  }
  if (item.cdnUrl) {
    return item.cdnUrl;
  }
  if (item.url) {
    return item.url;
  }
  var thumbs = item.thumbnailUrls || [];
  for (var i = 0; i < thumbs.length; i++) {
    if (thumbs[i] && thumbs[i].url) {
      return thumbs[i].url;
    }
  }
  return '';
}

// 测试连接
// 必须返回 true 或 false，不能把 httpRequest 响应对象直接返回给 Java
function testConnection(ctx) {
  var response = listPage(ROOT_PARENT_ID, '');
  var json = parseJson(response.body, {});
  return response.code >= 200 && response.code < 400 && json.success !== false;
}

// 列目录
// ctx.remoteDir 是小米备份传入的路径，本函数返回标准条目数组
// 每项必须包含 name、size、directory、modifiedTime
function listEntries(ctx) {
  var parentId = resolvePath(ctx.remoteDir || '', false);
  if (!parentId) {
    return [];
  }
  var items = collectEntries(parentId);
  return items.map(function (item) {
    return {
      fileId: entryId(item),
      name: entryName(item),
      size: item.size || 0,
      directory: isFolder(item),
      modifiedTime: Date.parse(item.updatedAt || '') || Date.now()
    };
  });
}

// 构造创建文件请求体
// parentFileId 必须是 resolvePath 得到的移动云盘目录 fileId
// contentHash 使用宿主传入的 SHA256，用于秒传和 complete
function buildCreateBody(ctx, parentFileId) {
  return JSON.stringify({
    fileRenameMode: 'auto_rename',
    contentType: 'application/oct-stream',
    type: 'file',
    name: ctx.fileName || '',
    size: ctx.fileSize || 0,
    contentHashAlgorithm: ctx.contentHashAlgorithm || 'SHA256',
    contentHash: ctx.contentHash || '',
    partInfos: [{
      parallelHashCtx: { partOffset: 0 },
      partNumber: 1,
      partSize: ctx.fileSize || 0
    }],
    parentFileId: parentFileId || ROOT_PARENT_ID,
    parallelUpload: true,
    localCreatedAt: new Date().toISOString()
  });
}

// 上传文件
// 1. 解析或创建父目录
// 2. 调 file/create 获取 fileId、uploadId、uploadUrl
// 3. rapidUpload=true 表示秒传成功，无需 PUT 和 complete
// 4. 普通上传用 uploadUrl PUT 当前文件流，再调用 file/complete
// 返回 { handled: true } 表示脚本已完成全部上传流程
function uploadFile(ctx) {
  var parentId = resolvePath(ctx.remoteDir || '', true);
  var createRes = callApi('POST', '/hcy/file/create', buildCreateBody(ctx, parentId));
  if (createRes.code < 200 || createRes.code >= 300) {
    throw new Error('create failed: HTTP ' + createRes.code);
  }
  var createJson = parseJson(createRes.body, {});
  if (!createJson.success) {
    throw new Error('create failed: ' + (createJson.message || 'unknown'));
  }
  var createData = createJson.data || {};
  if (!createData.fileId) {
    throw new Error('missing fileId');
  }
  if (createData.rapidUpload) {
    return { handled: true };
  }

  var part = (createData.partInfos && createData.partInfos[0]) || {};
  var uploadUrl = part.uploadUrl || part.cdnUploadUrl;
  if (!uploadUrl) {
    throw new Error('missing uploadUrl: ' + createRes.body);
  }
  if (!createData.uploadId) {
    throw new Error('missing uploadId: ' + createRes.body);
  }
  var putRes = callDirect('PUT', uploadUrl, null, {
    'Content-Type': 'application/octet-stream'
  }, true);
  if (putRes.code < 200 || putRes.code >= 300) {
    throw new Error('upload failed: HTTP ' + putRes.code);
  }

  var completeRes = callApi('POST', '/hcy/file/complete', JSON.stringify({
    fileId: createData.fileId,
    uploadId: createData.uploadId,
    contentHash: ctx.contentHash || '',
    contentHashAlgorithm: ctx.contentHashAlgorithm || 'SHA256'
  }));
  if (completeRes.code < 200 || completeRes.code >= 300) {
    throw new Error('complete failed: HTTP ' + completeRes.code);
  }
  var completeJson = parseJson(completeRes.body, {});
  if (!completeJson.success) {
    throw new Error('complete failed: ' + (completeJson.message || 'unknown'));
  }
  return { handled: true };
}

// 下载文件
// 1. 把 ctx.remotePath 拆成父路径和文件名
// 2. 通过列表找到 fileId
// 3. 取已有下载地址或调用 getDownloadUrl
// 4. 用 httpDownload 把响应流写入 ctx.localPath
function downloadFile(ctx) {
  var remotePath = trimSlashes(ctx.remotePath || '');
  var name = pathName(remotePath);
  var parentPath = pathParent(remotePath);
  if (!name) {
    return { handled: true };
  }

  var entry = findEntry(parentPath, name);
  if (!entry || !entry.fileId) {
    throw new Error('missing file entry: ' + remotePath);
  }

  var downloadUrl = pickDownloadUrl(entry);
  if (!downloadUrl) {
    var urlRes = callApi('POST', '/hcy/file/getDownloadUrl', JSON.stringify({
      fileId: entry.fileId
    }));
    if (urlRes.code >= 200 && urlRes.code < 300) {
      var urlJson = parseJson(urlRes.body, {});
      var urlData = urlJson.data || {};
      downloadUrl = urlData.downloadURL || urlData.cdnUrl || urlData.url || '';
    }
  }
  if (!downloadUrl) {
    throw new Error('missing download url');
  }

  var downloadRes = httpDownload({
    method: 'GET',
    url: downloadUrl
  });
  if (downloadRes.code < 200 || downloadRes.code >= 300) {
    throw new Error('download failed: HTTP ' + downloadRes.code);
  }
  return { handled: true };
}

// 删除路径
// 移动云盘这里使用 batchTrash，把文件或目录移入回收站
// 找不到目标时按删除成功处理，便于清理旧备份重试
function deletePath(ctx) {
  var remotePath = trimSlashes(ctx.remotePath || '');
  if (!remotePath) {
    return { handled: true };
  }
  var name = pathName(remotePath);
  var parentPath = pathParent(remotePath);
  var entry = findEntry(parentPath, name);
  if (!entry || !entry.fileId) {
    return { handled: true };
  }
  var trashRes = callApi('POST', '/hcy/recyclebin/batchTrash', JSON.stringify({
    fileIds: [entry.fileId]
  }));
  if (trashRes.code < 200 || trashRes.code >= 300) {
    throw new Error('delete failed: HTTP ' + trashRes.code);
  }
  return { handled: true };
}
