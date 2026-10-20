const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
function harness(name, handler, confirmed = false) {
  const storage = new Map(), calls = [], routes = [], modals = [];
  for (const file of Object.keys(require.cache)) if (file.startsWith(path.join(root, 'utils')) || file === path.join(root, 'pages', name, name + '.js')) delete require.cache[file];
  global.wx = {
    getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v), removeStorageSync: k => storage.delete(k),
    request(o) { calls.push(o); queueMicrotask(() => handler(o)); },
    login(o) { o.success({ code: 'wechat-test-code' }); },
    showToast() {}, showActionSheet(o) { o.success({tapIndex:0}); }, showModal(o) { modals.push(o); if (o.success) o.success({ confirm: confirmed, content: '' }); },
    navigateTo: o => routes.push(o.url), redirectTo: o => routes.push(o.url), switchTab: o => routes.push(o.url)
  };
  global.getApp = () => ({ globalData: {} });
  let definition; global.Page = d => { definition = d; };
  require(path.join(root, 'pages', name, name + '.js'));
  const page = Object.assign({}, definition, { data: structuredClone(definition.data), setData(patch) { for (const [key, value] of Object.entries(patch)) { const keys = key.split('.'); let node = this.data; for (const k of keys.slice(0, -1)) node = node[k] || (node[k] = {}); node[keys.at(-1)] = value; } } });
  const session = require('../utils/session');
  return { page, session, calls, routes, modals };
}
const ok = (o, data, statusCode = 200) => o.success({ statusCode, data: { success: true, data } });
const fail = (o, statusCode = 409) => o.success({ statusCode, data: { success: false, requestId: 'request-fixture', error: { code: 'STATE_CONFLICT', message: '状态已改变，请刷新' } } });
const loggedIn = h => h.session.save({ accessToken: 'access-fixture', refreshToken: 'refresh-fixture', expiresIn: 900, user: { id: '9007199254740999', phoneBound: false } });

test('微信登录仅提交微信code与校园，成功后进入绑定流程', async () => {
  const h = harness('login', o => ok(o, { accessToken: 'access-fixture', refreshToken: 'refresh-fixture', expiresIn: 900, user: { id: '9007199254740999', phoneBound: false } }));
  h.page.setData({ agreed: true }); await h.page.onWxLogin();
  assert.deepEqual(h.calls[0].data, { code: 'wechat-test-code', campusCode: 'demo-univ' });
  assert.equal(h.calls[0].header.Authorization, undefined);
  assert.equal(h.routes[0], '/pages/phonebind/phonebind');
  assert.equal(h.session.get().user.id, '9007199254740999');
});
test('未同意协议时不发起微信换码', async () => {
  const h = harness('login', () => assert.fail('must not request'));
  await h.page.onWxLogin(); assert.equal(h.calls.length, 0);
});
test('手机号授权被拒绝时不伪造绑定成功', async () => {
  const h = harness('phonebind', () => assert.fail('must not request')); loggedIn(h);
  await h.page.onWechatBind({ detail: { errMsg: 'denied' } });
  assert.equal(h.calls.length, 0); assert.equal(h.page.data.phoneMasked, '');
});
test('手机号绑定只传授权code，随后重新读取本人信息', async () => {
  const h = harness('phonebind', o => ok(o, o.url.endsWith('/me') ? { id: '1', phoneBound: true, phoneMasked: '138****1111' } : { phoneBound: true, phoneMasked: '138****1111' })); loggedIn(h);
  await h.page.onWechatBind({ detail: { code: 'phone-code-fixture' } });
  assert.deepEqual(h.calls[0].data, { phoneCode: 'phone-code-fixture' });
  assert.equal(h.page.data.phoneBound, true);
});
test('学生认证显示服务端pending，不自动改成认证通过', async () => {
  const h = harness('verify', o => ok(o, o.url.endsWith('/me') ? { id: '1', verificationStatus: 'pending' } : { id: '2', status: 'pending', studentNumberMasked: '2024****08' })); loggedIn(h);
  h.page.setData({ studentId: '20241008', agreed: true }); await h.page.handlePostVerify();
  assert.deepEqual(h.calls[0].data, { studentNumber: '20241008' }); assert.equal(h.page.data.verification.status, 'pending');
});
test('注销申请取消确认时不发送请求', async () => {
  const h = harness('settings', () => assert.fail('must not request')); loggedIn(h);
  await h.page.deleteAccount(); assert.equal(h.calls.length, 0);
});
test('注销确认只创建服务端申请，不直接清除账号会话', async () => {
  const h = harness('settings', o => ok(o, o.url.endsWith('/me/privacy-settings') ? { version: 0 } : o.url.includes('/me/data-requests') ? { items: [] } : o.url.endsWith('/me/authorizations') ? [] : { id: '9', status: 'pending' }, o.url.endsWith('/me/account-deletion') ? 202 : 200), true); loggedIn(h);
  await h.page.deleteAccount(); assert.deepEqual(h.calls[0].data, { confirm: true }); assert(h.session.hasSession());
});
test('预约冲突不显示成功，手动重试沿用同一幂等key', async () => {
  const h = harness('reserveconfirm', o => fail(o)); loggedIn(h); h.page.slotId = '31'; h.page.key = 'stable-reservation-key'; h.page.setData({ isAgreed: true, seatNo: 3 });
  await h.page.submit(); await h.page.submit();
  assert.equal(h.routes.length, 0); assert.equal(h.page.data.submitted, false);
  assert.equal(h.calls[0].header['Idempotency-Key'], h.calls[1].header['Idempotency-Key']);
  assert.deepEqual(h.calls[0].data, { slotId: '31', seatNo: 3 });
});
test('消息失败时保留输入，不插入本地假消息，重试沿用clientMessageId', async () => {
  const h = harness('chat', o => fail(o, 503)); loggedIn(h); h.page.id = '12'; h.page.setData({ inputValue: '你好' });
  await h.page.send(); await h.page.send();
  assert.equal(h.page.data.messages.length, 0); assert.equal(h.page.data.inputValue, '你好');
  assert.equal(h.calls[0].data.clientMessageId, h.calls[1].data.clientMessageId);
});

test('发布日期和图片改变后使用新提交key，同一内容失败重试复用key', async () => {
  const h=harness('postcreate',o=>fail(o,503));loggedIn(h);h.page.onLoad({});
  h.page.setData({categoryIndex:1,titleText:'寻找水杯',descText:'教学楼遗失',exchangeArea:'一教',agreed:true,images:[{id:'7',path:'local'}]});
  await h.page.submitPostToast();await h.page.submitPostToast();
  h.page.date({currentTarget:{dataset:{field:'occurredDate'}},detail:{value:'2026-10-19'}});await h.page.submitPostToast();
  h.page.removePhoto({currentTarget:{dataset:{id:'7'}}});await h.page.submitPostToast();
  const keys=h.calls.map(o=>o.header['Idempotency-Key']);
  assert.equal(keys[0],keys[1]);assert.notEqual(keys[1],keys[2]);assert.notEqual(keys[2],keys[3]);
});
test('报修修改紧急程度后不能复用旧载荷的提交key', async () => {
  const h=harness('workorder',o=>fail(o,503));loggedIn(h);h.page.onLoad({});
  h.page.setData({description:'漏水'});await h.page.submit();
  h.page.toggleUrgency({detail:{value:true}});await h.page.submit();
  assert.notEqual(h.calls[0].header['Idempotency-Key'],h.calls[1].header['Idempotency-Key']);
  assert.equal(h.calls[1].data.urgency,'urgent');
});
test('快速切换机会分类时，旧响应不能覆盖新分类分页游标',async()=>{
  const pending=[];const h=harness('opportunity',o=>pending.push(o));
  const first=h.page.reload();await Promise.resolve();
  h.page.setData({activeCat:'奖学金'});const second=h.page.reload();await Promise.resolve();
  ok(pending[1],{items:[{id:'2',title:'新分类'}],hasMore:true,nextCursor:'scholarship-next'});await second;
  ok(pending[0],{items:[{id:'1',title:'旧分类'}],hasMore:true,nextCursor:'old-next'});await first;
  assert.equal(h.page.data.opportunities[0].id,'2');
  assert.equal(h.page._cursors.scholarship,'scholarship-next');assert.equal(h.page._cursors.undefined,undefined);
});
test('快速切换互助分类时，旧响应不能覆盖新分类分页游标',async()=>{
  const pending=[];const h=harness('mutual',o=>pending.push(o));
  const first=h.page.reload();await Promise.resolve();
  h.page.setData({activeCat:'失物招领'});const second=h.page.reload();await Promise.resolve();
  ok(pending[1],{items:[{id:'2',title:'新分类'}],hasMore:true,nextCursor:'lost-next'});await second;
  ok(pending[0],{items:[{id:'1',title:'旧分类'}],hasMore:true,nextCursor:'old-next'});await first;
  assert.equal(h.page.data.posts[0].id,'2');assert.equal(h.page._cursor,'lost-next');
});

test('聊天刷新不会丢失已加载的更早消息及其下一页游标',async()=>{
 let latest=0;
 const h=harness('chat',o=>{
  if(o.url.includes('/read'))return ok(o,{read:true});
  if(o.url.includes('cursor=older'))return ok(o,{items:[{id:'1',sender:{id:'other',nickname:'同学'},content:'早前消息',createdAt:'2026-10-19T10:00:00Z'}],hasMore:true,nextCursor:'oldest'});
  latest++;return ok(o,{items:[{id:latest>1?'3':'2',sender:{id:'other',nickname:'同学'},content:'最新消息',createdAt:'2026-10-20T10:00:00Z'}],hasMore:true,nextCursor:'older'});
 });loggedIn(h);h.page.id='12';
 await h.page.reload();await h.page.older();await h.page.reload();
 assert.deepEqual(h.page.data.messages.map(x=>x.id),['1','2','3']);assert.equal(h.page.cursor,'oldest');
});

test('后端尚未接入的互助收藏只解释限制，不伪造成功或发必败请求',async()=>{
 const h=harness('mutualdetail',o=>fail(o,400));loggedIn(h);h.page.id='8';
 await h.page.toggleFav();assert.equal(h.calls.length,0);assert.match(h.modals[0].content,/尚未接入/);
});
test('会话列表展示最新消息，而不是缺省的未提供',async()=>{
 const h=harness('activity',o=>ok(o,{items:o.url.includes('/me/notifications')?[]:[{id:'12',title:'教材咨询',lastMessage:{id:'13',content:'你好，请问教材还在吗',sender:{id:'other',nickname:'同学'},createdAt:'2026-10-20T10:00:00Z'}}],nextCursor:null,hasMore:false}));
 loggedIn(h);h.page.type='inbox';await h.page.reload();assert.equal(h.page.data.entries[0].subtitle,'你好，请问教材还在吗');
});
