const BASE_URL = 'http://127.0.0.1:8080/api/v1';

function request(path, options) {
  const opts = options || {};
  const header = { 'Content-Type': 'application/json' };
  if (opts.token) header.Authorization = 'Bearer ' + opts.token;
  if (opts.idempotencyKey) header['Idempotency-Key'] = opts.idempotencyKey;

  return new Promise(function (resolve, reject) {
    wx.request({
      url: BASE_URL + path,
      method: opts.method || 'GET',
      data: opts.data,
      header: header,
      timeout: opts.timeout || 10000,
      success: function (res) {
        const body = res.data || {};
        if (res.statusCode >= 200 && res.statusCode < 300 && body.success) {
          resolve(body.data);
        } else {
          reject({
            status: res.statusCode,
            error: body.error || { code: 'UNKNOWN_ERROR', message: '请求失败' },
            requestId: body.requestId
          });
        }
      },
      fail: function (err) {
        reject({ status: 0, error: { code: 'NETWORK_ERROR', message: '网络连接失败' }, raw: err });
      }
    });
  });
}

function newIdempotencyKey() {
  return 'wx-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
}

module.exports = { request, newIdempotencyKey, BASE_URL };
