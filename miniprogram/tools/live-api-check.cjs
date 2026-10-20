const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const store = new Map();
let requests = 0;
global.wx = {
  getStorageSync: k => store.get(k), setStorageSync: (k, v) => store.set(k, v), removeStorageSync: k => store.delete(k),
  request(options) { requests++; fetch(options.url, { method: options.method, headers: options.header, body: ['GET','HEAD'].includes(options.method) ? undefined : JSON.stringify(options.data || {}), signal: AbortSignal.timeout(options.timeout || 15000) }).then(async res => options.success({ statusCode: res.status, data: await res.json() })).catch(() => options.fail({})); },
  showModal(o) { if (o.success) o.success({ confirm: false }); }, showToast() {}, navigateTo() {}, switchTab() {}
};
function page(name) {
  let config; global.Page = value => { config = value; };
  const file = path.join(root, 'pages', name, name + '.js'); delete require.cache[require.resolve(file)]; require(file);
  const instance = Object.assign({}, config, { data: structuredClone(config.data), setData(patch) { for (const [key, value] of Object.entries(patch)) { const fields = key.split('.'); let target = this.data; for (const k of fields.slice(0, -1)) target = target[k] || (target[k] = {}); target[fields.at(-1)] = value; } } });
  return instance;
}
(async () => {
  const api = require('../utils/request');
  const campuses = await api.get('/campuses', null, false); assert(campuses.length); store.set('czt.campusCode', campuses[0].code);
  const home = page('home'); await home.reload(); assert.equal(home.data.error, ''); home.setData({ aiInput: '教材' }); await home.submitAi(); assert(Array.isArray(home.data.results));
  const opportunity = page('opportunity'); await opportunity.reload(); assert.equal(opportunity.data.error, ''); assert(opportunity.data.opportunities.length); const id = opportunity.data.opportunities[0].id; assert.equal(typeof id, 'string');
  const detail = page('opportunitydetail'); detail.id = id; await detail.reload(); assert.equal(detail.data.error, ''); assert.equal(detail.data.detail.id, id);
  opportunity.setData({ query: 'frontend-live-check-no-such-opportunity' }); await opportunity.reload(); assert.equal(opportunity.data.opportunities.length, 0);
  const mutual = page('mutual'); await mutual.reload(); assert.equal(mutual.data.error, ''); assert(mutual.data.posts.length);
  const post = page('mutualdetail'); post.id = mutual.data.posts[0].id; await post.reload(); assert.equal(post.data.error, '');
  const service = page('service'); await service.reload(); assert.equal(service.data.error, ''); assert(service.data.resources.length); assert.equal(service.data.workOrders.length, 0);
  const reserve = page('seatreserve'); await reserve.reload(); assert.equal(reserve.data.error, ''); assert(reserve.data.resource); assert(Array.isArray(reserve.data.slots));
  const profile = page('profile'); await profile.reload(); assert.equal(profile.data.loggedIn, false); assert.equal(profile.data.materials.length, 0);
  const settings = page('settings'); const before = requests; await settings.reload(); assert.equal(settings.data.needsLogin, true); assert.equal(requests, before);
  const response = await fetch(require('../config/api').baseUrl + '/me'); assert.equal(response.status, 401);
  console.log('PASS: live backend public controllers, search empty state, string IDs, guest isolation and protected API 401 (' + requests + ' wx.request calls). No test accounts or data were created.');
})().catch(error => { console.error(error); process.exitCode = 1; });
