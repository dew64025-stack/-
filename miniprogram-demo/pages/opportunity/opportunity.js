const { request } = require('../../utils/request');
const { formatDateTime, opportunityCategory } = require('../../utils/format');

Page({
  data: {
    loading: true,
    error: '',
    opportunities: []
  },

  onLoad() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh(), () => wx.stopPullDownRefresh());
  },

  reload() {
    this.setData({ loading: true, error: '' });
    return request('/opportunities?limit=20&campusCode=demo-univ')
      .then((page) => {
        const opportunities = (page.items || []).map((item) => Object.assign({}, item, {
          categoryLabel: opportunityCategory(item.category),
          deadlineText: formatDateTime(item.deadline)
        }));
        this.setData({ loading: false, opportunities });
      })
      .catch((e) => this.setData({
        loading: false,
        error: (e.error && e.error.code) || 'NETWORK_ERROR'
      }));
  },

  showOpportunity(e) {
    const item = this.data.opportunities[e.currentTarget.dataset.index];
    wx.showModal({
      title: item.title,
      content: (item.organizer ? '主办：' + item.organizer + '\n' : '') +
        (item.summary || '') + '\n截止：' + (item.deadlineText || '未设置'),
      showCancel: false
    });
  },

  protoBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.reLaunch({ url: '/pages/index/index' });
  }
});
