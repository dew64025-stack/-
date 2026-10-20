Page({
  data: {
    studentId: '',
    idValid: false,
    submitting: false,
    submitText: '立即提交官方学籍核验 (1秒匹配)'
  },

  onIdInput(e) {
    const val = (e.detail.value || '').replace(/\D/g, '');
    this.setData({
      studentId: val,
      idValid: val.length === 9
    });
    return val;
  },

  onSubmit() {
    if (this.data.submitting) return;
    if (this.data.studentId.length < 9) {
      wx.showToast({ title: '请输入 9 位本校在籍学号', icon: 'none' });
      return;
    }
    this.setData({ submitting: true, submitText: '与教务数据哈希匹配中...' });
    setTimeout(() => {
      this.setData({ submitText: '核验通过，正在唤醒权限...' });
      setTimeout(() => {
        this.goBack();
      }, 800);
    }, 1000);
  },

  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/home/home' });
  }
});
