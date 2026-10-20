const { request } = require('../../utils/request');

Page({
  data: {
    backendStatus: '正在检查后端连接…',
    backendOk: false
  },

  onLoad() {
    request('/campuses')
      .then((campuses) => {
        const names = (campuses || []).map((item) => item.name).join('、');
        this.setData({
          backendOk: true,
          backendStatus: names ? `后端连接成功：${names}` : '后端连接成功（暂无校园数据）'
        });
      })
      .catch((e) => {
        this.setData({
          backendOk: false,
          backendStatus: `后端未连接：${e.error && e.error.code ? e.error.code : 'NETWORK_ERROR'}`
        });
      });
  },

  openPage(e) {
    wx.navigateTo({ url: '/' + e.currentTarget.dataset.path });
  }
});
