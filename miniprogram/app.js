const session = require('./utils/session');
const config = require('./config/api');
App({
  globalData: { apiBase: config.baseUrl, userInfo: null, demoMode: false },
  onLaunch() { const current = session.get(); this.globalData.userInfo = current && current.user || null; }
});
