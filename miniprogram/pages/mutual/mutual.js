const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
const categories = { '失物招领': 'lost_found', '借用共享': 'borrow', '二手闲置': 'secondhand' };
ui.define({
  data: { cats: ['全部', '失物招领', '借用共享', '二手闲置', '组队搭子'], activeCat: '全部', query: '', posts: [], hasMore: false },
  onShow() { ui.tab(this, 3); this.reload(); },
  reload() { this._cursor = null; return this.fetch(false); },
  fetch(append) { if (append && (this.data.loading || !this.data.hasMore)) return; return ui.load(this, async () => { const generation = this._loadGeneration; const team = this.data.activeCat === '组队搭子'; const page = await api.get(team ? '/teams' : '/mutual-aid/posts', data.publicQuery({ q: this.data.query.trim(), category: categories[this.data.activeCat], cursor: append ? this._cursor : undefined, limit: 20 })); if (this._disposed || generation !== this._loadGeneration) return {}; this._cursor = page.nextCursor; return { posts: (append ? this.data.posts : []).concat(data.items(page)), hasMore: page.hasMore, team }; }); },
  onReachBottom() { return this.fetch(true); },
  selectCat(e) { this.setData({ activeCat: e.currentTarget.dataset.cat }); this.reload(); },
  onSearchInput(e) { this.setData({ query: e.detail.value }); },
  openCommunity() { if (ui.requireLogin()) wx.navigateTo({url:'/pages/community/community'}); },
  publishQuick() { if (ui.requireLogin()) wx.navigateTo({ url: '/pages/postcreate/postcreate' }); },
  openDetail(e) { const page = this.data.team ? 'teams' : 'mutualdetail'; wx.navigateTo({ url: '/pages/' + page + '/' + page + '?id=' + e.currentTarget.dataset.id }); }
});
