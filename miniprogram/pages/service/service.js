const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
const session = require('../../utils/session');
ui.define({
  data: { resources: [], crowd: [], workOrders: [], issues: [], loggedIn: false },
  onShow() { ui.tab(this, 2); this.reload(); },
  reload() { return ui.load(this, async () => { const loggedIn = session.hasSession(); const [resources,crowd,issues,orders] = await Promise.all([api.get('/resources', data.publicQuery({limit:5})),api.get('/crowd/observations',data.publicQuery({limit:4})),api.get('/campus-issues',data.publicQuery({limit:3})),loggedIn?api.get('/me/work-orders',{limit:3},true):{items:[]}]); return {resources:data.items(resources),crowd:data.items(crowd).map(x=>Object.assign(x,{levelLabel:data.label(x.crowdLevel)})),issues:data.items(issues),workOrders:data.items(orders),loggedIn}; }); },
  onQuick(e) { const name=e.currentTarget.dataset.name; const routes={'宿舍报修':'workorder?category=dorm','公共设施':'workorder?category=facility','图书馆选座':'seatreserve?type=library_seat','自习教室':'seatreserve?type=classroom','食堂拥堵':'campusissues?mode=crowd','问题广场':'campusissues'}; const route=routes[name]; if(route){const parts=route.split('?');wx.navigateTo({url:'/pages/'+parts[0]+'/'+parts[0]+(parts[1]?'?'+parts[1]:'')});} },
  openResource(e) { wx.navigateTo({url:'/pages/seatreserve/seatreserve?id='+e.currentTarget.dataset.id}); },
  openOrder(e) { wx.navigateTo({url:'/pages/workorder/workorder?id='+e.currentTarget.dataset.id}); },
  openIssue(e) { wx.navigateTo({url:'/pages/campusissues/campusissues?id='+e.currentTarget.dataset.id}); }
});
