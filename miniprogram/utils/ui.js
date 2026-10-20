const session = require('./session');
function errorText(error) { const fields = (error.fieldErrors || []).map(x => x.message).filter(Boolean).join('；'); return (fields || error.message || '加载失败，请稍后重试') + (error.requestId ? '\n请求编号：' + error.requestId : ''); }
function showError(error) { wx.showModal({ title: error.code === 'UNAUTHENTICATED' ? '请先登录' : '操作未完成', content: errorText(error), showCancel: false }); }
function requireLogin() { if (session.hasSession()) return true; wx.navigateTo({ url: '/pages/login/login' }); return false; }
function confirm(title, content) { return new Promise(resolve => wx.showModal({ title, content, success: r => resolve(r.confirm), fail: () => resolve(false) })); }
function prompt(title, placeholder) { return new Promise(resolve => wx.showModal({ title, editable: true, placeholderText: placeholder || '', success: r => resolve(r.confirm ? (r.content || '').trim() : null), fail: () => resolve(null) })); }
function choose(items) { return new Promise(resolve => { if (!items.length) return resolve(-1); wx.showActionSheet({ itemList: items.slice(0, 6), success: r => resolve(r.tapIndex), fail: () => resolve(-1) }); }); }
async function action(page, operation) { if (page.data.busy) return; page.setData({ busy: true }); try { return await operation(); } catch (error) { showError(error); } finally { if (!page._disposed) page.setData({ busy: false }); } }
async function load(page, operation) {
  const generation = (page._loadGeneration || 0) + 1; page._loadGeneration = generation;
  page.setData({ loading: true, error: '', needsLogin: false });
  try { const patch = await operation(); if (!page._disposed && generation === page._loadGeneration) page.setData(Object.assign({ loading: false }, patch || {})); }
  catch (error) { if (!page._disposed && generation === page._loadGeneration) page.setData({ loading: false, error: errorText(error), needsLogin: error.status === 401 }); }
}
function goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.switchTab({ url: '/pages/home/home' }); }
const common = { goBack, goLogin() { wx.navigateTo({ url: '/pages/login/login' }); }, onUnload() { this._disposed = true; } };
function define(definition) { const config = Object.assign({}, common, definition); config.data = Object.assign({ loading: false, error: '', busy: false, needsLogin: false }, definition.data || {}); Page(config); }
function tab(page, selected) { const bar = typeof page.getTabBar === 'function' && page.getTabBar(); if (bar) bar.setData({ selected }); }
module.exports = { define, load, action, showError, errorText, requireLogin, confirm, prompt, choose, tab, goBack };
