const api = require('../../utils/request');
const session = require('../../utils/session');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
const identity = require('../../utils/identity');
const admin = require('../../utils/admin');
ui.define({
  data: { agreed: false, wxLoginLoading: false, guestText: '以访客身份浏览', campusName: '请选择学校', campuses: [] },
  onLoad() { if (admin.hasSession()) { wx.reLaunch({ url: '/pages/adminreview/adminreview' }); return; } this.reload(); },
  reload() { return ui.load(this, async () => { const campuses = await api.get('/campuses', null, false); const selected = campuses.find(x => x.code === data.campus()) || campuses[0]; if (selected) wx.setStorageSync('czt.campusCode', selected.code); return { campuses, campusName: selected ? selected.name : '暂无开放校园' }; }); },
  toggleAgree() { this.setData({ agreed: !this.data.agreed }); },
  onAdminLogin() { return identity.toAdmin(this); },
  onWxLogin() {
    if (admin.hasSession()) { wx.reLaunch({ url: '/pages/adminreview/adminreview' }); return; }
    if (!this.data.agreed) return wx.showToast({ title: '请先同意服务协议和隐私政策', icon: 'none' });
    return ui.action(this, async () => {
      this.setData({ wxLoginLoading: true });
      try {
        const code = await new Promise((resolve, reject) => wx.login({ success: r => r.code ? resolve(r.code) : reject(new Error('未取得微信登录凭证')), fail: () => reject(new Error('微信登录暂不可用')) }));
        const result = await api.post('/auth/wechat/login', { code, campusCode: data.campus() }, false);
        session.save(result);
        getApp().globalData.userInfo = result.user;
        if (!result.user.phoneBound) wx.redirectTo({ url: '/pages/phonebind/phonebind' });
        else wx.switchTab({ url: '/pages/profile/profile' });
      } finally { this.setData({ wxLoginLoading: false }); }
    });
  },
  onGuest() { wx.switchTab({ url: '/pages/home/home' }); },
  async onSwitchCampus() {
    if (session.hasSession()) return wx.showToast({ title: '请先退出登录再切换学校', icon: 'none' });
    const index = await ui.choose(this.data.campuses.map(x => x.name));
    if (index >= 0) { const campus = this.data.campuses[index]; wx.setStorageSync('czt.campusCode', campus.code); this.setData({ campusName: campus.name }); }
  },
  noop() { wx.showModal({ title: '服务与隐私', content: '仅在你授权后获取微信登录凭证与手机号。你可以在“我的—设置”查看授权、申请导出个人数据和注销账号。', showCancel: false }); }
});
