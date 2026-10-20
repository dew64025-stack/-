const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
const categories = { '全部': undefined, '奖学金': 'scholarship', '学科竞赛': 'competition', '科研研习': 'research' };
ui.define({
  data: { cats: ['全部', '奖学金', '学科竞赛', '科研研习', '实习志愿'], activeCat: '全部', query: '', opportunities: [], hasMore: false },
  onShow() { ui.tab(this, 1); this.reload(); },
  reload() { this._cursors = {}; return this.fetch(false); },
  fetch(append) {
    if (append && (this.data.loading || !this.data.hasMore)) return;
    return ui.load(this, async () => {
      const generation = this._loadGeneration;
      const cats = this.data.activeCat === '实习志愿' ? ['internship', 'volunteer'] : [categories[this.data.activeCat]];
      const pages = await Promise.all(cats.map(category => append && this._cursors[category] === null ? { items: [], hasMore: false } : api.get('/opportunities', data.publicQuery({ q: this.data.query.trim(), category, limit: 20, cursor: append ? this._cursors[category] : undefined }))));
      if (this._disposed || generation !== this._loadGeneration) return {};
      pages.forEach((p, i) => { this._cursors[cats[i]] = p.hasMore ? p.nextCursor : null; });
      const merged = (append ? this.data.opportunities : []).concat(...pages.map(data.items));
      return { opportunities: merged.filter((x, i) => merged.findIndex(y => y.id === x.id) === i), hasMore: pages.some(p => p.hasMore) };
    });
  },
  onReachBottom() { return this.fetch(true); },
  selectCat(e) { this.setData({ activeCat: e.currentTarget.dataset.cat }); this.reload(); },
  onSearchInput(e) { this.setData({ query: e.detail.value }); },
  onSearch() { this.reload(); },
  openDetail(e) { wx.navigateTo({ url: '/pages/opportunitydetail/opportunitydetail?id=' + e.currentTarget.dataset.id }); }
});
