const config = require('../config/api');
const student = require('./session');
const { ApiError, newIdempotencyKey } = require('./request');

// 管理凭据只保留在本次运行内存，不写入 wx Storage 或页面数据。
let session = null;
let challenge = null;
let csrf = null;
let generation = 0;
let boundBase = '';
let bootstrapping = null;
let refreshing = null;
const cookies = new Map();
const cookiePaths = { 'ADMIN-XSRF-TOKEN': '/api/v1/admin', 'ADMIN-REFRESH': '/api/v1/admin/auth' };
const write = method => !['GET', 'HEAD'].includes(method);
const failure = (status, code, message) => new ApiError(status, { code, message });
const unauthenticated = () => failure(401, 'UNAUTHENTICATED', '管理员会话已失效，请重新登录');
function base() {
  const value = (config.adminBaseUrl || config.baseUrl || '').replace(/\/$/, '');
  if (!/^https:\/\/[a-zA-Z0-9.-]+(?::[0-9]{1,5})?\/api\/v1$/.test(value)) {
    throw failure(0, 'ADMIN_HTTPS_REQUIRED', '管理员连接需要 HTTPS，请先配置安全的管理接口地址');
  }
  if (boundBase && boundBase !== value) throw failure(0, 'ADMIN_SERVER_CHANGED', '管理服务器地址已改变，请退出并重新登录');
  return value;
}
function secureConfigured() { try { base(); return true; } catch (_) { return false; } }
function clear() { generation++; session = null; challenge = null; csrf = null; boundBase = ''; cookies.clear(); bootstrapping = null; refreshing = null; }
function hasSession() { return !!session; }
function current() { return session ? JSON.parse(JSON.stringify({ admin: session.admin, permissions: session.permissions, expiresAt: session.expiresAt })) : null; }
function assertGeneration(expected) { if (expected !== generation) throw unauthenticated(); }
function pathCheck(path) {
  if (typeof path !== 'string' || !/^\/[a-zA-Z0-9_/-]+$/.test(path) || path.includes('//') || path.includes('..')) throw new Error('不支持的管理接口路径');
}
function collectCookies(response) {
  const header = response.header || {};
  let values = response.cookies;
  if (!Array.isArray(values) || !values.length) {
    const raw = Object.keys(header).filter(k => k.toLowerCase() === 'set-cookie').flatMap(k => Array.isArray(header[k]) ? header[k] : [header[k]]);
    values = raw.flatMap(x => String(x).split(/,\s*(?=ADMIN-(?:XSRF-TOKEN|REFRESH)=)/));
  }
  for (const value of values) {
    const parts = String(value).split(';').map(x => x.trim());
    const first = parts.shift(); const index = first.indexOf('=');
    if (index < 1) continue;
    const name = first.slice(0, index); const token = first.slice(index + 1);
    if (!Object.prototype.hasOwnProperty.call(cookiePaths, name)) continue;
    const secure = parts.some(x => /^secure$/i.test(x));
    const httpOnly = parts.some(x => /^httponly$/i.test(x));
    const cookiePath = (parts.find(x => /^path=/i.test(x)) || '').slice(5);
    const domain = parts.find(x => /^domain=/i.test(x));
    if (!secure || !httpOnly || cookiePath !== cookiePaths[name] || domain) continue;
    if (!token || parts.some(x => /^max-age=0$/i.test(x))) { cookies.delete(name); continue; }
    if (/^[A-Za-z0-9_.~%+/=-]{1,4096}$/.test(token)) cookies.set(name, token);
  }
}
function cookieHeader(path) {
  const names = ['ADMIN-XSRF-TOKEN'];
  if (path === '/auth/token/refresh') names.push('ADMIN-REFRESH');
  return names.filter(name => cookies.has(name)).map(name => name + '=' + cookies.get(name)).join('; ');
}
function raw(path, options, token, expected) {
  pathCheck(path); assertGeneration(expected); const origin = base();
  const method = options.method || 'GET';
  const query = Object.keys(options.query || {}).filter(k => options.query[k] !== undefined && options.query[k] !== null && options.query[k] !== '')
    .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(String(options.query[k]))).join('&');
  const header = { 'Content-Type': 'application/json' };
  if (token) header.Authorization = 'Bearer ' + token;
  if (write(method)) {
    if (!csrf || !cookies.has('ADMIN-XSRF-TOKEN')) throw failure(0, 'ADMIN_CSRF_INVALID', '管理员安全校验未完成，请重新登录');
    header['X-ADMIN-CSRF'] = csrf;
    header['Idempotency-Key'] = options.idempotencyKey || newIdempotencyKey();
  }
  const cookie = cookieHeader(path); if (cookie) header.Cookie = cookie;
  return new Promise((resolve, reject) => wx.request({
    url: origin + '/admin' + path + (query ? '?' + query : ''), method, header, data: options.data, timeout: config.timeout,
    success(response) {
      try {
        assertGeneration(expected); base();
        collectCookies(response);
        const body = response.data || {};
        if (response.statusCode >= 200 && response.statusCode < 300 && body.success === true) resolve(body.data);
        else reject(new ApiError(response.statusCode, body.error || { code: 'INVALID_RESPONSE', message: '管理接口响应异常' }, body.requestId));
      } catch (error) { reject(error); }
    },
    fail() { reject(expected === generation ? failure(0, 'NETWORK_ERROR', '无法连接管理服务器，请检查 HTTPS 地址和网络') : unauthenticated()); }
  }));
}
async function bootstrap(expected) {
  assertGeneration(expected); base();
  if (csrf && cookies.has('ADMIN-XSRF-TOKEN')) return;
  if (bootstrapping) return bootstrapping;
  const pending = raw('/auth/csrf', {}, null, expected).then(result => {
    assertGeneration(expected);
    if (!result || result.headerName !== 'X-ADMIN-CSRF' || typeof result.token !== 'string' || !result.token || /[\r\n]/.test(result.token) || result.token.length > 4096 || !cookies.has('ADMIN-XSRF-TOKEN')) {
      throw failure(0, 'ADMIN_CSRF_INVALID', '未取得匹配的管理员安全凭证，请检查 HTTPS 连接');
    }
    csrf = result.token;
  }).finally(() => { if (bootstrapping === pending) bootstrapping = null; });
  bootstrapping = pending; return pending;
}
function save(result, previous) {
  if (!result || typeof result.accessToken !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(result.accessToken) || !result.admin || !/^[1-9][0-9]{0,18}$/.test(result.admin.id)
      || !Array.isArray(result.admin.campusScopes) || !Array.isArray(result.permissions) || !result.permissions.every(x => typeof x === 'string')
      || !Number.isFinite(result.expiresIn) || result.expiresIn <= 0 || !cookies.has('ADMIN-REFRESH')) throw failure(0, 'INVALID_RESPONSE', '管理员会话响应不完整，请重新登录');
  if (previous && (previous.admin.id !== result.admin.id || JSON.stringify(previous.admin.campusScopes) !== JSON.stringify(result.admin.campusScopes))) throw unauthenticated();
  session = { accessToken: result.accessToken, admin: result.admin, permissions: result.permissions.slice(), expiresAt: Date.now() + result.expiresIn * 1000 };
  return current();
}
async function login(username, password) {
  base();
  if (student.hasSession()) throw failure(409, 'ADMIN_SWITCH_REQUIRED', '请先退出当前学生身份，再登录管理员');
  if (typeof username !== 'string' || !username.trim() || username.trim().length > 64 || typeof password !== 'string' || !password.trim()) throw new Error('请填写工号账号和密码');
  let size; try { size = encodeURIComponent(password).replace(/%[A-Fa-f0-9]{2}|./g, 'x').length; } catch (_) { throw new Error('密码格式无效'); }
  if (size > 72) throw new Error('密码不得超过72个UTF-8字节');
  clear(); boundBase = base(); const expected = generation;
  try {
    await bootstrap(expected);
    const result = await raw('/auth/login', { method: 'POST', data: { username: username.trim(), password } }, null, expected);
    assertGeneration(expected);
    if (!result || result.mfaRequired !== true || typeof result.challengeId !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(result.challengeId) || !(Date.parse(result.expiresAt) > Date.now())) throw failure(0, 'INVALID_RESPONSE', '未取得有效的动态验证码挑战，请重新登录');
    challenge = { id: result.challengeId, expiresAt: result.expiresAt };
    return { expiresAt: result.expiresAt };
  } catch (error) { if (expected === generation) clear(); throw error; }
}
async function verify(code) {
  base();
  if (!challenge || Date.parse(challenge.expiresAt) <= Date.now()) throw failure(401, 'ADMIN_CHALLENGE_EXPIRED', '登录验证已过期，请重新输入工号和密码');
  if (typeof code !== 'string' || !/^[0-9]{6}$/.test(code)) throw new Error('请输入6位动态验证码');
  const expected = generation; const pending = challenge; challenge = null;
  try {
    await bootstrap(expected);
    const result = await raw('/auth/mfa/verify', { method: 'POST', data: { challengeId: pending.id, code } }, null, expected);
    assertGeneration(expected); return save(result);
  } catch (error) { if (expected === generation) clear(); throw error; }
}
async function refresh(expected) {
  assertGeneration(expected);
  if (!session || !cookies.has('ADMIN-REFRESH')) throw unauthenticated();
  if (refreshing) return refreshing;
  const previous = session;
  const pending = (async () => {
    await bootstrap(expected);
    const result = await raw('/auth/token/refresh', { method: 'POST', data: {} }, null, expected);
    assertGeneration(expected); return save(result, previous);
  })().catch(error => { if (error.status === 401 && expected === generation) clear(); throw error; })
    .finally(() => { if (refreshing === pending) refreshing = null; });
  refreshing = pending; return pending;
}
async function request(path, options = {}) {
  pathCheck(path); base();
  const expected = options.generation === undefined ? generation : options.generation;
  assertGeneration(expected); if (!session) throw unauthenticated();
  const opts = Object.assign({}, options, { method: (options.method || 'GET').toUpperCase() });
  if (write(opts.method) && !opts.idempotencyKey) opts.idempotencyKey = newIdempotencyKey();
  if (session.expiresAt <= Date.now() + 10000) await refresh(expected);
  assertGeneration(expected);
  if (write(opts.method)) await bootstrap(expected);
  assertGeneration(expected); const token = session.accessToken;
  try { return await raw(path, opts, token, expected); }
  catch (error) {
    assertGeneration(expected);
    if (error.status !== 401) throw error;
    if (opts.retried) { if (session && session.accessToken === token) clear(); throw error; }
    if (!session) throw error;
    if (session.accessToken === token) await refresh(expected);
    return request(path, Object.assign({}, opts, { retried: true, generation: expected }));
  }
}
async function logout() { if (!session) { clear(); return; } await request('/auth/logout', { method: 'POST', data: {} }); clear(); }
module.exports = { login, verify, get: (path, query) => request(path, { query }), post: (path, data, idempotencyKey) => request(path, { method: 'POST', data, idempotencyKey }), current, hasSession, clear, logout, secureConfigured };
