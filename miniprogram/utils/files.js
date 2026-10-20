const api = require('./request');
const config = require('../config/api');
const mimeTypes = { pdf: 'application/pdf', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' };
function chooseFile() { return new Promise((resolve, reject) => wx.chooseMessageFile({ count: 1, type: 'file', success: r => resolve(r.tempFiles[0]), fail: () => reject(new Error('未选择文件')) })); }
function chooseImage() { return new Promise((resolve, reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], success: r => { const f = r.tempFiles[0]; resolve({ path: f.tempFilePath, size: f.size, name: f.tempFilePath.split('/').pop() }); }, fail: () => reject(new Error('未选择图片')) })); }
function uploadUrl(url) {
  const origin = config.baseUrl.match(/^https?:\/\/[^/]+/)[0];
  const resolved = url.startsWith('http') ? url : url.startsWith('/api/') ? origin + url : config.baseUrl + url;
  if (!resolved.startsWith(origin + '/')) throw new Error('文件上传地址与业务服务器不一致');
  return resolved;
}
async function upload(file, purpose, active) {
  const fileName = file.name || file.path.split('/').pop();
  const mimeType = mimeTypes[fileName.split('.').pop().toLowerCase()];
  if (!mimeType) throw new Error('请选择 PDF、DOCX、PNG 或 JPG 文件');
  const material = purpose === 'material';
  const intent = await api.post(material ? '/materials/upload-intents' : '/files/upload-intents', Object.assign({ fileName, mimeType, sizeBytes: file.size }, material ? {} : { purpose }));
  if (file.size > intent.maxSizeBytes) throw new Error('文件超过服务器允许的大小');
  await new Promise((resolve, reject) => wx.uploadFile({ url: uploadUrl(intent.uploadUrl), filePath: file.path, name: 'file', header: { Authorization: 'Upload ' + intent.uploadToken }, success(res) {
    let body; try { body = typeof res.data === 'string' ? JSON.parse(res.data) : res.data; } catch (_) { return reject(new Error('上传响应异常')); }
    if (res.statusCode >= 200 && res.statusCode < 300 && body.success) resolve(body.data);
    else reject(new api.ApiError(res.statusCode, body.error || { message: '文件上传失败' }, body.requestId));
  }, fail: () => reject(new Error('文件上传中断，请重新选择后重试')) }));
  const completed = await api.post('/files/uploads/' + intent.uploadId + '/complete', {});
  for (let i = 0; i < 60; i++) {
    if (active && !active()) throw new Error('已离开页面，上传文件的扫描仍在继续');
    const metadata = await api.get('/files/' + completed.attachmentId, null, true);
    if (metadata.scanStatus === 'clean') return Object.assign({}, metadata, { attachmentId: completed.attachmentId });
    if (['infected', 'failed', 'rejected', 'error'].includes(metadata.scanStatus)) throw new Error('文件安全检查未通过，请更换文件');
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error('文件仍在扫描，请稍后重试');
}
async function download(url, fileName) {
  const path = await new Promise((resolve, reject) => wx.downloadFile({ url, success: r => r.statusCode === 200 ? resolve(r.tempFilePath) : reject(new Error('下载链接已失效，请重新获取')), fail: () => reject(new Error('下载失败，请检查下载域名配置')) }));
  const extension = (fileName || '').split('.').pop().toLowerCase();
  if (['png', 'jpg', 'jpeg'].includes(extension)) await new Promise((resolve, reject) => wx.previewImage({ current: path, urls: [path], success: resolve, fail: () => reject(new Error('图片预览失败，请重新获取文件')) }));
  else if (extension === 'zip') {
    if (!wx.shareFileMessage) throw new Error('当前微信版本不支持导出文件，请升级后重试');
    await new Promise((resolve, reject) => wx.shareFileMessage({ filePath: path, fileName, success: resolve, fail: () => reject(new Error('文件分享未完成，请重试')) }));
  } else await new Promise((resolve, reject) => wx.openDocument({ filePath: path, fileType: ['pdf', 'docx'].includes(extension) ? extension : undefined, showMenu: true, success: resolve, fail: () => reject(new Error('当前文件类型暂不支持预览')) }));
}
module.exports = { chooseFile, chooseImage, upload, download };
