const config = require('../config/api');
const session = require('./session');
let refreshing = null;
let sequence = 0;
class ApiError extends Error {
  constructor(status, error, requestId) {
    super(error.message || '请求失败，请重试');
    this.status = status; this.code = error.code || 'REQUEST_FAILED';
    this.fieldErrors = error.fieldErrors || []; this.retryable = !!error.retryable; this.requestId = requestId || '';
  }
}
function key() { return 'wx-' + Date.now().toString(36) + '-' + (++sequence).toString(36) + '-' + Math.random().toString(36).slice(2, 12); }
function queryString(query) {
  return Object.keys(query || {}).filter(k => query[k] !== undefined && query[k] !== null && query[k] !== '')
    .flatMap(k => (Array.isArray(query[k]) ? query[k] : [query[k]]).map(v => encodeURIComponent(k) + '=' + encodeURIComponent(String(v)))).join('&');
}
function raw(path, options, token) {
  const query = queryString(options.query);
  const header = { 'Content-Type': 'application/json' };
  if (token) header.Authorization = 'Bearer ' + token;
  if (options.idempotencyKey) header['Idempotency-Key'] = options.idempotencyKey;
  return new Promise((resolve, reject) => wx.request({
    url: config.baseUrl + path + (query ? (path.includes('?') ? '&' : '?') + query : ''),
    method: options.method || 'GET', data: options.data, header, timeout: options.timeout || config.timeout,
    success(res) {
      const body = res.data || {};
      if (res.statusCode >= 200 && res.statusCode < 300 && body.success === true) resolve(body.data);
      else reject(new ApiError(res.statusCode, body.error || { code: 'INVALID_RESPONSE', message: '服务响应异常' }, body.requestId));
    },
    fail() { reject(new ApiError(0, { code: 'NETWORK_ERROR', message: '无法连接服务器，请检查网络和后端地址' })); }
  }));
}
function unauthenticated() { return new ApiError(401, { code: 'UNAUTHENTICATED', message: '请先使用微信登录' }); }
function refresh() {
  if (refreshing) return refreshing;
  const previous = session.get();
  const generation = session.generation();
  if (!previous || !previous.refreshToken) return Promise.reject(unauthenticated());
  refreshing = raw('/auth/token/refresh', { method: 'POST', data: { refreshToken: previous.refreshToken } }).then(tokens => {
    if (generation !== session.generation() || !session.get() || session.get().refreshToken !== previous.refreshToken) throw unauthenticated();
    return session.save(tokens, true);
  }).catch(error => {
    if (error.status === 401 && generation === session.generation() && session.get() && session.get().refreshToken === previous.refreshToken) session.clear();
    throw error;
  }).finally(() => { refreshing = null; });
  return refreshing;
}
async function request(path, options) {
  const opts = Object.assign({}, options);
  const generation = opts.sessionGeneration === undefined ? session.generation() : opts.sessionGeneration;
  if (opts.auth !== false && generation !== session.generation()) throw unauthenticated();
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('://')) throw new Error('API path must be relative');
  opts.method = (opts.method || 'GET').toUpperCase();
  if (!['GET', 'HEAD'].includes(opts.method) && !opts.idempotencyKey) opts.idempotencyKey = key();
  if (opts.auth === true && !session.hasSession()) throw unauthenticated();
  let current = opts.auth === false ? null : session.get();
  if (current && current.refreshToken && current.expiresAt <= Date.now() + 10000) current = await refresh();
  if (current && generation !== session.generation()) throw unauthenticated();
  const sentToken = current && current.accessToken;
  try {
    const result = await raw(path, opts, sentToken);
    if (sentToken && generation !== session.generation()) throw unauthenticated();
    return result;
  }
  catch (error) {
    if (sentToken && generation !== session.generation()) throw unauthenticated();
    if (error.status === 401 && opts.retried && sentToken && session.get() && session.get().accessToken === sentToken) session.clear();
    if (error.status !== 401 || opts.auth === false || !sentToken || opts.retried) throw error;
    const latest = session.get();
    if (!latest) throw error;
    if (latest.accessToken === sentToken) await refresh();
    return request(path, Object.assign({}, opts, { retried: true, sessionGeneration: generation }));
  }
}
const get = (path, query, auth) => request(path, { query, auth });
const post = (path, data, auth = true, idempotencyKey) => request(path, { method: 'POST', data: data || {}, auth, idempotencyKey });
const put = (path, data, auth = true) => request(path, { method: 'PUT', data: data || {}, auth });
const del = (path, query, auth = true) => request(path, { method: 'DELETE', query, auth });
module.exports = { request, get, post, put, del, ApiError, newIdempotencyKey: key, refresh };
