const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
ui.define({
  data: { studentId: '', agreed: false, verification: null, user: {} },
  onShow() { this.reload(); },
  reload() { return ui.load(this, async () => { const user = await data.refreshUser(); let verification = null; try { verification = await api.get('/me/student-verification', null, true); } catch (e) { if (e.status !== 404) throw e; } return { user, verification: verification && Object.assign({}, verification, { statusLabel: data.label(verification.status) }) }; }); },
  onStudentIdInput(e) { this.setData({ studentId: e.detail.value }); },
  onAgreeChange(e) { this.setData({ agreed: e.detail.value.includes('agree') }); },
  handlePostVerify() {
    if (!this.data.agreed) return wx.showToast({ title: '请先确认所填信息真实', icon: 'none' });
    if (!this.data.studentId.trim() || this.data.studentId.trim().length > 32) return wx.showToast({ title: '请输入完整学号（最多32字符）', icon: 'none' });
    return ui.action(this, async () => { const result = await api.post('/student-verifications', { studentNumber: this.data.studentId.trim() }); this.setData({ verification: Object.assign({}, result, { statusLabel: data.label(result.status) }) }); await data.refreshUser(); wx.showToast({ title: '认证申请已提交', icon: 'none' }); });
  },
  handleSkipVerify() { wx.switchTab({ url: '/pages/home/home' }); },
  goPhone() { wx.navigateTo({ url: '/pages/phonebind/phonebind' }); }
});
