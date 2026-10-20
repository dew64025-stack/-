Page({
  data: {},

  noop() {
    wx.showToast({ title: '演示数据，仅供展示', icon: 'none' });
  },

  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/home/home' });
  }
});
