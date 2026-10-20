const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
const endpoints = { todos: '/me/todos', reservations: '/me/reservations', workorders: '/me/work-orders', posts: '/me/mutual-aid/posts', favorites: '/me/favorites', inbox: '/conversations', notifications: '/me/notifications' };
const titles = { todos: '我的待办', reservations: '我的预约', workorders: '我的工单', posts: '我的发布', favorites: '我的收藏', inbox: '消息与通知', notifications: '通知' };
ui.define({
  data: { entries: [], notifications: [], hasMore: false },
  onLoad(options) { this.type = endpoints[options.type] ? options.type : 'todos'; wx.setNavigationBarTitle({ title: titles[this.type] }); },
  onShow() { this.reload(); },
  reload() { this.cursor = null; return this.fetch(false); },
  fetch(append) { if (append && (!this.data.hasMore || this.data.loading)) return; return ui.load(this, async () => { const p = await api.get(endpoints[this.type], { limit: 20, cursor: append ? this.cursor : undefined }, true); this.cursor = p.nextCursor; const entries = data.items(p).map(x => Object.assign(x, { titleText: x.title || x.resource && x.resource.name || x.description || x.target && x.target.title || '业务记录', subtitle: x.lastMessage && x.lastMessage.content || (x.status ? x.statusLabel : '') || x.summary || '' })); const notifications = this.type === 'inbox' ? data.items(await api.get('/me/notifications', { limit: 20 }, true)) : []; return { entries: (append ? this.data.entries : []).concat(entries), notifications, hasMore: p.hasMore }; }); },
  onReachBottom() { this.fetch(true); },
  open(e) { const item = this.data.entries[e.currentTarget.dataset.index]; let page; let id = item.id; if (this.type === 'inbox') page = 'chat'; else if (this.type === 'reservations' || item.type === 'reservation') { page = 'voucher'; id = item.targetId || item.id; } else if (this.type === 'workorders' || item.type === 'workorder') { page = 'workorder'; id = item.targetId || item.id; } else if (this.type === 'posts') page = 'mutualdetail'; else if (this.type === 'favorites' || item.type === 'opportunity') { page = 'opportunitydetail'; id = item.targetId || item.id; } if (page) wx.navigateTo({ url: '/pages/' + page + '/' + page + '?id=' + id }); else wx.showModal({ title: item.titleText, content: item.subtitle || item.action || '请前往对应业务页面处理', showCancel: false }); },
  readNotification(e) { return ui.action(this, async () => { const item = this.data.notifications[e.currentTarget.dataset.index]; await api.post('/notifications/' + item.id + '/read', {}); wx.showModal({ title: item.title, content: item.content, showCancel: false }); await this.reload(); }); }
});
