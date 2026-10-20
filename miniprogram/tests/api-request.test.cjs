const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');

function harness(handler) {
  const store = new Map();
  const calls = [];
  global.wx = {
    getStorageSync: key => store.get(key),
    setStorageSync: (key, value) => store.set(key, value),
    removeStorageSync: key => store.delete(key),
    request: options => {
      calls.push(options);
      queueMicrotask(() => handler(options));
    }
  };
  for (const file of ['utils/request.js', 'utils/session.js']) delete require.cache[path.join(root, file)];
  return { calls, session: require('../utils/session'), request: require('../utils/request').request };
}
const ok = (options, data) => options.success({ statusCode: 200, data: { success: true, data } });

test('private request without session is rejected before network', async () => {
  const h = harness(() => assert.fail('must not issue request'));
  await assert.rejects(h.request('/me', { auth: true }), e => e.code === 'UNAUTHENTICATED');
  assert.equal(h.calls.length, 0);
});

test('concurrent 401 requests rotate once and retain original write idempotency keys', async () => {
  let refreshes = 0;
  const h = harness(o => {
    if (o.url.endsWith('/auth/token/refresh')) { refreshes++; return ok(o, { accessToken: 'fresh', refreshToken: 'next', expiresIn: 900 }); }
    if (o.header.Authorization === 'Bearer old') return o.success({ statusCode: 401, data: { success: false, error: { code: 'UNAUTHENTICATED', message: 'expired' } } });
    ok(o, { updated: true });
  });
  h.session.save({ accessToken: 'old', refreshToken: 'refresh', expiresIn: 900 });
  const results = await Promise.all([h.request('/test/a', { method: 'POST', auth: true, data: {} }), h.request('/test/b', { method: 'POST', auth: true, data: {} })]);
  assert.equal(refreshes, 1);
  assert.equal(results[0].updated, true);
  const writes = h.calls.filter(x => !x.url.endsWith('/auth/token/refresh'));
  for (const endpoint of ['/test/a', '/test/b']) {
    const pair = writes.filter(x => x.url.endsWith(endpoint));
    assert.equal(pair.length, 2);
    assert.equal(pair[0].header['Idempotency-Key'], pair[1].header['Idempotency-Key']);
    assert.match(pair[0].header['Idempotency-Key'], /^[\x21-\x7e]{8,128}$/);
  }
});

test('503 refresh failure preserves session rather than silently logging out', async () => {
  const h = harness(o => o.success({ statusCode: o.url.endsWith('/auth/token/refresh') ? 503 : 401, data: { success: false, error: { code: 'DEPENDENCY_UNAVAILABLE', message: 'temporarily unavailable' } } }));
  h.session.save({ accessToken: 'old', refreshToken: 'refresh', expiresIn: 900 });
  await assert.rejects(h.request('/me', { auth: true }), e => e.status === 503);
  assert.equal(h.session.get().refreshToken, 'refresh');
});

test('business field errors and request ID survive the request boundary', async () => {
  const fields = [{ field: 'studentNumber', message: 'invalid' }];
  const h = harness(o => o.success({ statusCode: 400, data: { success: false, requestId: 'r-test', error: { code: 'VALIDATION_FAILED', message: 'check input', fieldErrors: fields } } }));
  await assert.rejects(h.request('/student-verifications', { method: 'POST' }), e => e.code === 'VALIDATION_FAILED' && e.requestId === 'r-test' && e.fieldErrors[0].field === 'studentNumber');
});

test('public calls omit credentials and URL encode query without coercing IDs to numbers', async () => {
  const h = harness(o => ok(o, { id: '9223372036854775806' }));
  const result = await h.request('/search', { query: { q: '材料 & 比赛', unused: undefined }, auth: false });
  assert.equal(h.calls[0].header.Authorization, undefined);
  assert.match(h.calls[0].url, /q=%E6%9D%90%E6%96%99%20%26%20%E6%AF%94%E8%B5%9B/);
  assert.equal(result.id, '9223372036854775806');
});

test('refresh后再次401应结束失效会话且最多重试一次',async()=>{
 const h=harness(o=>o.url.endsWith('/auth/token/refresh')?ok(o,{accessToken:'fresh',refreshToken:'next',expiresIn:900}):o.success({statusCode:401,data:{success:false,error:{code:'UNAUTHENTICATED',message:'invalid'}}}));
 h.session.save({accessToken:'old',refreshToken:'previous',expiresIn:900});
 await assert.rejects(h.request('/me',{auth:true}),e=>e.status===401);
 assert.equal(h.calls.length,3);assert.equal(h.session.hasSession(),false);
});

test('账号切换后旧账号401请求不能用新账号令牌重放',async()=>{
 const pending=[];const h=harness(o=>pending.push(o));
 h.session.save({accessToken:'account-A',refreshToken:'refresh-A',expiresIn:900,user:{id:'A'}});
 const old=h.request('/community/posts',{method:'POST',auth:true,data:{title:'A的草稿',content:'仅属于A'}});const rejected=assert.rejects(old,e=>e.status===401);
 await Promise.resolve();h.session.clear();h.session.save({accessToken:'account-B',refreshToken:'refresh-B',expiresIn:900,user:{id:'B'}});
 pending[0].success({statusCode:401,data:{success:false,error:{code:'UNAUTHENTICATED',message:'expired'}}});
 await new Promise(resolve=>setImmediate(resolve));
 if(pending[1])ok(pending[1],{id:'99'});
 await rejected;assert.equal(h.calls.length,1);assert.equal(h.session.get().user.id,'B');
});
test('账号切换后旧账号成功响应也不能回填到新会话',async()=>{
 const pending=[];const h=harness(o=>pending.push(o));
 h.session.save({accessToken:'account-A',refreshToken:'refresh-A',expiresIn:900,user:{id:'A'}});
 const old=h.request('/me',{auth:true});await Promise.resolve();
 h.session.clear();h.session.save({accessToken:'account-B',refreshToken:'refresh-B',expiresIn:900,user:{id:'B'}});
 ok(pending[0],{id:'A',nickname:'A的私密资料'});await assert.rejects(old,e=>e.status===401);assert.equal(h.session.get().user.id,'B');
});
