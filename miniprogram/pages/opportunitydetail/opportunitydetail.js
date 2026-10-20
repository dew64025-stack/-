const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
ui.define({
  data: { detail: null, eligibility: null },
  onLoad(options) { this.id = options.id; this.reload(); },
  reload() { return ui.load(this, async () => { if (!this.id) throw new Error('请从机会列表选择项目'); const detail = await api.get('/opportunities/' + this.id, data.publicQuery()); return { detail: data.decorate(detail) }; }); },
  favorite() { if (!ui.requireLogin()) return; return ui.action(this, async () => { const p = '/favorites/opportunity/' + this.id; const result = await (this.data.detail.isFavorited ? api.del(p) : api.put(p, {})); this.setData({ 'detail.isFavorited': result.favorited }); }); },
  checkEligibility() { if (!ui.requireLogin()) return; return ui.action(this, async () => { const job = await api.post('/opportunities/' + this.id + '/eligibility-checks', {}); await data.pollJob(job, () => !this._disposed); const eligibility = await api.get('/opportunities/' + this.id + '/eligibility-checks/latest', null, true); this.setData({ eligibility }); }); },
  checklist() { if (ui.requireLogin()) wx.navigateTo({ url: '/pages/precheck/precheck?opportunityId=' + this.id }); },
  teams() { wx.navigateTo({ url: '/pages/teams/teams?opportunityId=' + this.id }); },
  reminder() { if (!ui.requireLogin()) return; return ui.action(this, async () => { const value = await ui.prompt('设置提醒时间', '格式：2026-10-21 09:00'); if (!value) return; const time = new Date(value.replace(' ', 'T')); if (Number.isNaN(time.getTime()) || time <= new Date()) throw new Error('请输入有效的未来时间'); await api.post('/reminders', { targetType: 'opportunity', targetId: this.id, remindAt: time.toISOString() }); wx.showToast({ title: '提醒已创建' }); }); },
  openReference() { const url = this.data.detail.officialReference || this.data.detail.notice && this.data.detail.notice.rawUrl; if (url) wx.setClipboardData({ data: url }); else wx.showToast({ title: '发布方未提供原文链接', icon: 'none' }); }
});
