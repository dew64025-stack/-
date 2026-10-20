const { request } = require('../../utils/request');
const { formatDateTime, opportunityCategory } = require('../../utils/format');

Page({
  data: {
    loading: true,
    error: '',
    deadlines: [],
    recommendations: [],
    quickOpportunities: [],
    hotIssues: [],
    nearbyPosts: []
  },

  onLoad() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh(), () => wx.stopPullDownRefresh());
  },

  reload() {
    this.setData({ loading: true, error: '' });
    return Promise.all([
      request('/home/overview?campusCode=demo-univ'),
      request('/opportunities?limit=3&campusCode=demo-univ')
    ]).then(([data, oppPage]) => {
      const map = (items) => (items || []).map((item) => Object.assign({}, item, {
        deadlineText: formatDateTime(item.deadline),
        updatedText: formatDateTime(item.updatedAt)
      }));
      const quickOpportunities = (oppPage.items || []).map((item) => Object.assign({}, item, {
        categoryLabel: opportunityCategory(item.category),
        deadlineText: formatDateTime(item.deadline)
      }));
      this.setData({
        loading: false,
        deadlines: map(data.deadlines),
        recommendations: map(data.recommendations),
        quickOpportunities,
        hotIssues: map(data.hotIssues),
        nearbyPosts: map(data.nearbyPosts)
      });
    }).catch((e) => this.setData({
      loading: false,
      error: (e.error && e.error.code) || 'NETWORK_ERROR'
    }));
  },

  showDeadline(e) {
    const item = this.data.deadlines[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: (item.summary || '') + '\n截止：' + (item.deadlineText || '未设置'), showCancel: false });
  },

  showRecommendation(e) {
    const item = this.data.recommendations[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: (item.summary || '') + '\n来源：' + (item.sourceName || item.sourceType || '未知'), showCancel: false });
  },

  showQuickOpportunity(e) {
    const item = this.data.quickOpportunities[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: (item.organizer ? '主办：' + item.organizer + '\n' : '') + (item.summary || '') + '\n截止：' + (item.deadlineText || '未设置'), showCancel: false });
  },

  showIssue(e) {
    const item = this.data.hotIssues[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: (item.summary || '') + '\n位置：' + (item.locationName || '校园公共区域'), showCancel: false });
  },

  showNearby(e) {
    const item = this.data.nearbyPosts[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: item.summary || '', showCancel: false });
  },

  protoBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.reLaunch({ url: '/pages/index/index' });
  }
});
