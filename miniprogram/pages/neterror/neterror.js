Page({
  data: {
    retrying: false,
    retryText: '重试网络连接'
  },

  onRetry() {
    if (this.data.retrying) return;
    this.setData({ retrying: true, retryText: '正在重新握手校园网关...' });
    setTimeout(() => {
      this.setData({ retrying: false, retryText: '重试网络连接' });
    }, 1400);
  },

  onDraft() {
    wx.showToast({ title: '演示：离线草稿箱', icon: 'none' });
  },

  noop() {
    wx.showToast({ title: '演示数据，仅供展示', icon: 'none' });
  },

  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/home/home' });
  }
});
