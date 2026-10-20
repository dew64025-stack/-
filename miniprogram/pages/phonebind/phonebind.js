const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
ui.define({
  data: { phoneMasked: '', wechatBinding: false },
  onShow() { this.reload(); },
  reload() { return ui.load(this, async () => { const user = await data.refreshUser(); return { phoneMasked: user.phoneMasked || '', phoneBound: user.phoneBound }; }); },
  onWechatBind(e) {
    if (!e.detail.code) return wx.showToast({ title: '请允许微信手机号授权后重试', icon: 'none' });
    return ui.action(this, async () => {
      this.setData({ wechatBinding: true });
      try { const result = await api.post('/auth/phone/bind', { phoneCode: e.detail.code }); await data.refreshUser(); this.setData(result); wx.showToast({ title: '手机号已绑定' }); }
      finally { this.setData({ wechatBinding: false }); }
    });
  },
  goVerify() { wx.navigateTo({ url: '/pages/verify/verify' }); },
  onSkip() { wx.switchTab({ url: '/pages/home/home' }); }
});
