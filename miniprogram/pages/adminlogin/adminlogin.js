const admin = require('../../utils/admin');
const student = require('../../utils/session');
const identity = require('../../utils/identity');
const ui = require('../../utils/ui');
const data = require('../../utils/data');
ui.define({
  data: { stage: 'password', username: '', password: '', code: '', expiresText: '', secureReady: false, studentActive: false },
  onLoad() {
    if (admin.hasSession()) { wx.reLaunch({ url: '/pages/adminreview/adminreview' }); return; }
    this.refreshConfig();
  },
  onShow() { this.refreshConfig(); },
  refreshConfig() { this.setData({ secureReady: admin.secureConfigured(), studentActive: student.hasSession() }); },
  input(e) {
    const field = e.currentTarget.dataset.field;
    if (!['username', 'password', 'code'].includes(field) || this.data.busy) return;
    this.setData({ [field]: e.detail.value });
  },
  submitPassword() {
    return ui.action(this, async () => {
      const password = this.data.password; this.setData({ password: '', code: '' });
      try {
        const result = await admin.login(this.data.username, password);
        if (!this._disposed) this.setData({ stage: 'mfa', expiresText: data.date(result.expiresAt) });
      } catch (error) {
        if (this._disposed) return;
        this.setData({ stage: 'password', expiresText: '' }); throw error;
      }
    });
  },
  submitMfa() {
    return ui.action(this, async () => {
      const code = this.data.code; this.setData({ code: '' });
      if (!/^[0-9]{6}$/.test(code)) throw new Error('请输入6位动态验证码');
      try {
        await admin.verify(code);
        if (!this._disposed) wx.reLaunch({ url: '/pages/adminreview/adminreview' });
      } catch (error) {
        if (this._disposed) return;
        this.setData({ stage: 'password', expiresText: '' });
        throw new Error(ui.errorText(error) + '\n请重新输入工号和密码后再验证。');
      }
    });
  },
  restartPassword() { if (this.data.busy) return; admin.clear(); this.setData({ stage: 'password', password: '', code: '', expiresText: '' }); },
  switchAdmin() { return identity.toAdmin(this); },
  switchStudent() { return identity.toStudent(this); },
  goLogin() { this.restartPassword(); },
  onHide() { this.setData({ password: '', code: '' }); },
  onUnload() { this._disposed = true; if (!admin.hasSession()) admin.clear(); }
});
