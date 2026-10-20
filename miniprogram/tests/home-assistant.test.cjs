const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');

function harness(handler) {
  const storage = new Map(), calls = [], routes = [], toasts = [];
  for (const file of Object.keys(require.cache)) {
    if (file.startsWith(path.join(root, 'utils')) || file === path.join(root, 'pages/home/home.js')) delete require.cache[file];
  }
  global.wx = {
    getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v), removeStorageSync: k => storage.delete(k),
    request(o) { calls.push(o); queueMicrotask(() => handler(o)); },
    showToast: o => toasts.push(o), showModal() {},
    navigateTo: o => routes.push(o.url)
  };
  let definition; global.Page = value => { definition = value; };
  require('../pages/home/home');
  const page = Object.assign({}, definition, {
    data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch); }
  });
  const session = require('../utils/session');
  return { page, session, calls, routes, toasts };
}
const ok = (o, data, statusCode = 200) => o.success({ statusCode, data: { success: true, data } });
const pending = { id: '9007199254740999', jobType: 'ai_assistant', status: 'pending', result: null, error: null, createdAt: '2026-10-20T09:00:00Z', updatedAt: '2026-10-20T09:00:00Z' };
const done = result => Object.assign({}, pending, { status: 'succeeded', result });
const failed = code => Object.assign({}, pending, { status: 'failed', error: { code, message: 'AI 助手执行失败' } });
const loggedIn = h => h.session.save({ accessToken: 'fixture-access', refreshToken: 'fixture-refresh', expiresIn: 900, user: { id: '123' } });
const waitingRequest = () => new Promise(resolve => setImmediate(resolve));

test('首页AI横标展开独立提问区，再次点击可收起且不清空草稿或启动请求', () => {
  const h = harness(() => assert.fail('展开不应请求AI'));
  h.page.setData({ aiInput: '图书馆' });
  h.page.toggleAi();
  assert.equal(h.page.data.aiExpanded, true); assert.equal(h.page.data.aiFocus, true);
  h.page.onAssistantInput({ detail: { value: '申请奖学金需要哪些材料？' } });
  h.page.toggleAi();
  assert.equal(h.page.data.aiExpanded, false);
  assert.equal(h.page.data.aiQuestion, '申请奖学金需要哪些材料？'); assert.equal(h.page.data.aiInput, '图书馆');
  assert.equal(h.calls.length, 0);
});

test('AI空问题在提问区明确提示并聚焦，不静默返回也不发请求', async () => {
  const h = harness(() => assert.fail('空问题不可请求')); loggedIn(h);
  h.page.setData({ aiQuestion: '   ' }); await h.page.askAi();
  assert.match(h.page.data.aiError, /请输入/); assert.equal(h.page.data.aiExpanded, true);
  assert.equal(h.page.data.aiFocus, true); assert.equal(h.calls.length, 0);
});

test('超过2000字的AI问题提前提示，不提交必定失败的请求', async () => {
  const h = harness(() => assert.fail('超长问题不可请求')); loggedIn(h);
  h.page.setData({ aiQuestion: '问'.repeat(2001) }); await h.page.askAi();
  assert.match(h.page.data.aiError, /2000/); assert.equal(h.calls.length, 0);
});

test('未登录时提问会进入微信登录，保留问题且不请求AI', async () => {
  const h = harness(() => assert.fail('访客不可请求AI'));
  h.page.setData({ aiQuestion: '如何报修？' }); await h.page.askAi();
  assert.deepEqual(h.routes, ['/pages/login/login']); assert.match(h.page.data.aiError, /登录/);
  assert.equal(h.page.data.aiQuestion, '如何报修？'); assert.equal(h.calls.length, 0);
});

test('AI使用独立问题提交，等待期间有状态且防重复，只展示服务端实际回答', async () => {
  let submit;
  const h = harness(o => {
    if (o.url.endsWith('/ai/assistant/messages')) submit = o;
    else if (o.url.endsWith('/jobs/9007199254740999')) ok(o, done({ answer: '请查看机会详情中的材料清单。', aiGenerated: true, citations: [], uncertain: true, disclaimer: '以学校或主办方最新通知为准' }));
    else assert.fail('unexpected ' + o.url);
  }); loggedIn(h);
  h.page.setData({ aiInput: '搜索内容不作为AI问题', aiQuestion: '  奖学金怎么申请？  ' });
  const operation = h.page.askAi(); await waitingRequest();
  assert.equal(h.page.data.aiBusy, true); assert.match(h.page.data.aiStatus, /提交|整理|查找/); assert.equal(h.page.data.answer, '');
  await h.page.askAi(); assert.equal(h.calls.length, 1);
  assert.deepEqual(submit.data, { question: '奖学金怎么申请？', scope: 'campus' });
  assert.equal(submit.header.Authorization, 'Bearer fixture-access');
  ok(submit, pending, 202); await operation;
  assert.equal(h.page.data.answer, '请查看机会详情中的材料清单。');
  assert.equal(h.page.data.aiBusy, false); assert.equal(h.page.data.aiError, ''); assert.equal(h.page.data.aiStatus, '');
  assert.equal(h.page.data.aiInput, '搜索内容不作为AI问题');
});

test('后端异步任务报告密钥缺失时明确提示尚未配置，不把等待文字当回答', async () => {
  const h = harness(o => ok(o, o.url.endsWith('/ai/assistant/messages') ? pending : failed('ASSISTANT_AI_KEY_MISSING'))); loggedIn(h);
  h.page.setData({ aiQuestion: '有哪些校园机会？' }); await h.page.askAi();
  assert.match(h.page.data.aiError, /尚未配置/); assert.match(h.page.data.aiError, /搜索/);
  assert.equal(h.page.data.answer, ''); assert.equal(h.page.data.aiBusy, false); assert.equal(h.page.data.aiStatus, '');
  assert.equal(h.page.data.aiQuestion, '有哪些校园机会？');
});

test('AI提交网络失败保留草稿、显示可重试错误，相同问题重试沿用防重key', async () => {
  const h = harness(o => o.fail({ errMsg: 'fixture network failure' })); loggedIn(h);
  h.page.setData({ aiQuestion: '如何借阅？' }); await h.page.askAi(); await h.page.askAi();
  assert.match(h.page.data.aiError, /网络|服务器/); assert.equal(h.page.data.aiQuestion, '如何借阅？');
  assert.equal(h.page.data.answer, ''); assert.equal(h.page.data.aiBusy, false);
  assert(h.calls[0].header['Idempotency-Key']); assert.equal(h.calls[0].header['Idempotency-Key'], h.calls[1].header['Idempotency-Key']);
});

test('AI任务已经明确失败后手动重试可创建新任务，而不是永远复读旧失败', async () => {
  const h = harness(o => ok(o, o.url.endsWith('/ai/assistant/messages') ? pending : failed('ASSISTANT_AI_KEY_MISSING'))); loggedIn(h);
  h.page.setData({ aiQuestion: '如何报修？' }); await h.page.askAi(); await h.page.askAi();
  const submissions = h.calls.filter(o => o.url.endsWith('/ai/assistant/messages'));
  assert.equal(submissions.length, 2);
  assert.notEqual(submissions[0].header['Idempotency-Key'], submissions[1].header['Idempotency-Key']);
});

test('AI任务缺少有效回答时显示错误，不把JSON或成功状态当作回答', async () => {
  const h = harness(o => ok(o, o.url.endsWith('/ai/assistant/messages') ? pending : done({ unrelated: 'not an answer' }))); loggedIn(h);
  h.page.setData({ aiQuestion: '如何申请？' }); await h.page.askAi();
  assert.equal(h.page.data.answer, ''); assert.match(h.page.data.aiError, /有效回答/); assert.equal(h.page.data.aiBusy, false);
});

test('离开首页后迟到的AI提交结果不再轮询或回填，返回后可查询已接受任务', async () => {
  let submit;
  const h = harness(o => { if (o.url.endsWith('/ai/assistant/messages')) submit = o; else ok(o, done({ answer: '来自已接受任务的回答。' })); }); loggedIn(h);
  h.page.setData({ aiQuestion: '如何报修？' }); const operation = h.page.askAi(); await waitingRequest();
  h.page.onHide(); ok(submit, pending, 202); await operation;
  assert.equal(h.page.data.answer, ''); assert.equal(h.page.data.aiBusy, false); assert.equal(h.calls.length, 1);
  await h.page.askAi(); assert.equal(h.page.data.answer, '来自已接受任务的回答。');
  assert.equal(h.calls.filter(o => o.url.endsWith('/ai/assistant/messages')).length, 1);
});

test('AI处理中执行普通搜索不清除AI状态或混用问题与结果', async () => {
  const h = harness(o => ok(o, { items: [{ id: '8', title: '图书馆', type: 'resource' }], nextCursor: null, hasMore: false }));
  h.page.setData({ aiInput: '图书馆', aiQuestion: '申请材料？', answer: '上一次真实回答', aiBusy: true, aiStatus: '正在整理回答' });
  await h.page.submitAi();
  assert.equal(h.page.data.results[0].id, '8'); assert.equal(h.page.data.searched, true);
  assert.equal(h.page.data.answer, '上一次真实回答'); assert.equal(h.page.data.aiQuestion, '申请材料？'); assert.equal(h.page.data.aiBusy, true);
});

test('修改AI问题后清除旧回答和旧错误，不误认为它们属于新问题', () => {
  const h = harness(() => assert.fail('编辑不应请求AI'));
  h.page.setData({ answer: '旧回答', aiError: '旧错误' });
  h.page.onAssistantInput({ detail: { value: '新问题' } });
  assert.equal(h.page.data.answer, ''); assert.equal(h.page.data.aiError, ''); assert.equal(h.page.data.aiQuestion, '新问题');
});

test('退出后微信保留首页实例时，重新显示首页清掉旧账号的AI问题和回答，但保留公开搜索', async () => {
  const h = harness(o => ok(o, { deadlines: [], recommendations: [], hotIssues: [], nearbyPosts: [] })); loggedIn(h);
  h.page.onShow(); await waitingRequest();
  h.page.setData({ aiInput: '图书馆', aiQuestion: '旧账号的问题', answer: '旧账号的回答', aiExpanded: true });
  h.page.onHide(); h.session.clear(); h.page.onShow(); await waitingRequest();
  assert.equal(h.page.data.aiQuestion, ''); assert.equal(h.page.data.answer, ''); assert.equal(h.page.data.aiExpanded, false);
  assert.equal(h.page.data.aiInput, '图书馆');
});

test('换账号后不复用旧账号已接受的AI任务，重新输入会提交新任务而非反复404', async () => {
  let switched = false;
  const h = harness(o => {
    if (new URL(o.url).pathname.endsWith('/home/overview')) return ok(o, { deadlines: [], recommendations: [], hotIssues: [], nearbyPosts: [] });
    if (o.url.endsWith('/ai/assistant/messages')) return ok(o, Object.assign({}, pending, { id: switched ? '456' : '123' }), 202);
    if (o.url.endsWith('/jobs/123')) {
      if (!switched) return o.fail({});
      return o.success({ statusCode: 404, data: { success: false, error: { code: 'NOT_FOUND', message: '未找到该任务' } } });
    }
    if (o.url.endsWith('/jobs/456')) return ok(o, Object.assign(done({ answer: '新账号实际任务的回答。' }), { id: '456' }));
    assert.fail('unexpected ' + o.url);
  }); loggedIn(h);
  h.page.setData({ aiQuestion: '如何报修？' }); await h.page.askAi();
  h.page.onHide(); h.session.clear(); switched = true;
  h.session.save({ accessToken: 'fixture-user-b', refreshToken: 'fixture-refresh-b', expiresIn: 900, user: { id: '456' } });
  h.page.onShow(); await waitingRequest();
  h.page.onAssistantInput({ detail: { value: '如何报修？' } }); await h.page.askAi();
  const submissions = h.calls.filter(o => o.url.endsWith('/ai/assistant/messages'));
  assert.equal(submissions.length, 2); assert.notEqual(submissions[0].header['Idempotency-Key'], submissions[1].header['Idempotency-Key']);
  assert.equal(h.page.data.answer, '新账号实际任务的回答。');
});

test('访客从AI入口完成首次登录返回时，保留尚未提交的提问草稿', async () => {
  const h = harness(o => ok(o, { deadlines: [], recommendations: [], hotIssues: [], nearbyPosts: [] }));
  h.page.setData({ aiQuestion: '如何申请奖学金？' }); await h.page.askAi(); h.page.onHide();
  loggedIn(h); h.page.onShow(); await waitingRequest();
  assert.equal(h.page.data.aiQuestion, '如何申请奖学金？'); assert.equal(h.page.data.aiExpanded, true);
  assert.equal(h.page.data.aiError, ''); assert.equal(h.page.data.answer, '');
});

test('AI提问时微信会话刷新也已失效，结束等待并显示登录提示，不永久卡在回答中', async () => {
  const h = harness(o => o.success({ statusCode: 401, data: { success: false, error: { code: 'UNAUTHENTICATED', message: '请先使用微信登录' } } })); loggedIn(h);
  h.page.setData({ aiQuestion: '如何申请？' }); await h.page.askAi();
  assert.equal(h.session.hasSession(), false); assert.equal(h.page.data.aiBusy, false);
  assert.match(h.page.data.aiError, /登录/); assert.equal(h.page.data.answer, '');
});
