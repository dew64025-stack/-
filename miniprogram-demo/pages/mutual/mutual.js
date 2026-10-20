const { request } = require('../../utils/request');
const { formatDateTime, mutualCategory } = require('../../utils/format');

Page({
  data: {
    loading: true,
    error: '',
    posts: []
  },

  onLoad() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh(), () => wx.stopPullDownRefresh());
  },

  reload() {
    this.setData({ loading: true, error: '' });
    return request('/mutual-aid/posts?limit=20&campusCode=demo-univ')
      .then((page) => {
        const posts = (page.items || []).map((item) => Object.assign({}, item, {
          categoryLabel: mutualCategory(item.category),
          priceText: item.price ? '¥' + item.price : '免费/面议',
          createdText: formatDateTime(item.createdAt)
        }));
        this.setData({ loading: false, posts });
      })
      .catch((e) => this.setData({
        loading: false,
        error: (e.error && e.error.code) || 'NETWORK_ERROR'
      }));
  },

  showPost(e) {
    const item = this.data.posts[e.currentTarget.dataset.index];
    wx.showModal({
      title: item.title,
      content: (item.content || '') + '\n地点：' + (item.exchangeArea || '待定') +
        '\n价格：' + item.priceText + '\n发布：' + (item.createdText || ''),
      showCancel: false
    });
  },

  protoBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.reLaunch({ url: '/pages/index/index' });
  }
});
