const api = require('../../utils/request');
const session = require('../../utils/session');
const data = require('../../utils/data');
const files = require('../../utils/files');
const ui = require('../../utils/ui');
ui.define({
  data: { authorizations: [], privacy: {}, requests: [] },
  onShow() { this.reload(); },
  reload() { return ui.load(this, async () => { const [authorizations, privacy, requests] = await Promise.all([api.get('/me/authorizations', null, true), api.get('/me/privacy-settings', null, true), api.get('/me/data-requests', { limit: 20 }, true)]); return { authorizations: authorizations.map(x => Object.assign(data.decorate(x), { typeLabel: ({ phone: '手机号', material_ai: '材料 AI', privacy: '隐私政策', subscription: '订阅消息', location: '位置' })[x.type] || x.type })), privacy, requests: data.items(requests).map(x => Object.assign(x, { untilText: data.date(x.coolingOffUntil), canCancel: x.type === 'deletion' && x.status === 'pending' && new Date(x.coolingOffUntil).getTime() > Date.now() })) }; }); },
  revoke(e) { return ui.action(this, async () => { if (!await ui.confirm('撤回授权', '撤回后，依赖该授权的相关功能将不可用，是否继续？')) return; await api.post('/me/authorizations/' + e.currentTarget.dataset.id + '/revoke', {}); await this.reload(); }); },
  changePrivacy(e) { const field = e.currentTarget.dataset.field; return ui.action(this, async () => { try { const privacy = await api.post('/me/privacy-settings/update', { [field]: e.detail.value, version: this.data.privacy.version }); this.setData({ privacy }); } finally { await this.reload(); } }); },
  exportData() { return ui.action(this, async () => { await api.post('/me/data-exports', {}); await this.reload(); wx.showToast({ title: '导出申请已提交' }); }); },
  deleteAccount() { return ui.action(this, async () => { if (!await ui.confirm('申请注销账号', '注销申请会进入学校配置的冷静期，之后可能清除个人数据；未完成业务或依法保留的记录按服务端规则处理。确定申请？')) return; await api.post('/me/account-deletion', { confirm: true }); await this.reload(); }); },
  cancelRequest(e) { return ui.action(this, async () => { if (!await ui.confirm('撤销注销申请', '确定在冷静期内撤销本次申请？')) return; await api.post('/me/data-requests/' + e.currentTarget.dataset.id + '/cancel', {}); await this.reload(); }); },
  requestDetail(e) { return ui.action(this, async () => { const result = await api.get('/me/data-requests/' + e.currentTarget.dataset.id, null, true); if (result.result && result.result.downloadUrl) await files.download(result.result.downloadUrl, '材智通个人数据.zip'); else wx.showModal({ title: data.label(result.status), content: result.retentionBasis || result.result && (result.result.message || result.result.failureReason) || '请求仍在处理中，请稍后查看', showCancel: false }); }); },
  logout() { return ui.action(this, async () => { if (!await ui.confirm('退出登录', '确定退出当前微信会话？')) return; const current = session.get(); if (current) await api.post('/auth/logout', { refreshToken: current.refreshToken }, false); session.clear(); getApp().globalData.userInfo = null; wx.switchTab({ url: '/pages/profile/profile' }); }); }
});
