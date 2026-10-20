const api = require('../../utils/request');
const session = require('../../utils/session');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
const identity = require('../../utils/identity');
ui.define({
  data: { loggedIn: false, user: {}, profile: {}, dashboard: {}, materials: [], growth: [], credit: [] },
  onShow() { ui.tab(this, 4); this.reload(); },
  reload() {
    const loggedIn = session.hasSession(); this.setData({ loggedIn });
    if (!loggedIn) { this.setData({ user: {}, profile: {}, dashboard: {}, materials: [], growth: [], credit: [] }); return; }
    return ui.load(this, async () => {
      const [dashboard, profile, materials, growth, credit] = await Promise.all([api.get('/me/dashboard', null, true), api.get('/me/profile', null, true), api.get('/materials', { limit: 3 }, true), api.get('/me/growth-records', { limit: 4 }, true), api.get('/me/credit-events', { limit: 5 }, true)]);
      session.setUser(dashboard.user);
      return { dashboard, user: Object.assign({}, dashboard.user, { verificationLabel: data.label(dashboard.user.verificationStatus) }), profile, materials: data.items(materials), growth: data.items(growth), credit: data.items(credit) };
    });
  },
  openAdmin() { return identity.toAdmin(this); },
  openBorrows() { if (ui.requireLogin()) wx.navigateTo({url:'/pages/borrows/borrows'}); },
  openSettings() { wx.navigateTo({ url: '/pages/settings/settings' }); },
  openVault() { if (ui.requireLogin()) wx.navigateTo({ url: '/pages/vault/vault' }); },
  goVerify() { if (ui.requireLogin()) wx.navigateTo({ url: '/pages/verify/verify' }); },
  goPhone() { if (ui.requireLogin()) wx.navigateTo({ url: '/pages/phonebind/phonebind' }); },
  openItems(e) { if (ui.requireLogin()) wx.navigateTo({ url: '/pages/activity/activity?type=' + e.currentTarget.dataset.type }); },
  editProfile() {
    if (!ui.requireLogin()) return;
    return ui.action(this, async () => {
      const nickname = await ui.prompt('编辑昵称', '输入新昵称，最多32字符');
      if (nickname === null) return;
      if (!nickname || nickname.length > 32) throw new Error('昵称应为1—32个字符');
      await api.post('/me/update', { nickname, version: this.data.user.version }); await this.reload();
    });
  },
  dispute(e) {
    return ui.action(this, async () => { const reason = await ui.prompt('申请信用事件复核', '请输入具体原因'); if (!reason) return; await api.post('/me/credit-events/' + e.currentTarget.dataset.id + '/disputes', { reason }); wx.showToast({ title: '复核申请已提交' }); });
  }
});
