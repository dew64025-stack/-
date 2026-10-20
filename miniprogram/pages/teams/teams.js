const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
ui.define({
  data: { teams: [], detail: null },
  onLoad(options) { this.id = options.id; this.opportunityId = options.opportunityId; this.reload(); },
  reload() { return ui.load(this, async () => this.id ? { detail: await api.get('/teams/' + this.id, data.publicQuery()) } : { teams: data.items(await api.get('/teams', data.publicQuery({ opportunityId: this.opportunityId, limit: 100 }))) }); },
  open(e) { wx.navigateTo({ url: '/pages/teams/teams?id=' + e.currentTarget.dataset.id }); },
  apply() { if (!ui.requireLogin()) return; return ui.action(this, async () => { const message = await ui.prompt('申请加入队伍', '介绍你的技能与期望承担的任务'); if (message === null) return; const roles = this.data.detail.openRoles || []; let openRoleId; if (roles.length) { const index = await ui.choose(roles.map(x => x.role)); if (index < 0) return; openRoleId = roles[index].id; } await api.post('/team-applications', { teamId: this.id, message: message || undefined, openRoleId }); wx.showToast({ title: '入队申请已提交' }); }); },
  create() { if (!ui.requireLogin()) return; return ui.action(this, async () => { const title = await ui.prompt('创建队伍', '队伍名称'); if (!title) return; const goal = await ui.prompt('队伍目标', '介绍计划与招募需求'); if (goal === null) return; const team = await api.post('/teams', { title, goal: goal || undefined, opportunityId: this.opportunityId, openRoles: [] }); wx.redirectTo({ url: '/pages/teams/teams?id=' + team.id }); }); },
  contact() { if (ui.requireLogin()) wx.navigateTo({ url: '/pages/chat/chat?contextType=team&contextId=' + this.id }); }
});
