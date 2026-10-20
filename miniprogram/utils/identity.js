const student = require('./session');
const studentApi = require('./request');
const admin = require('./admin');
const ui = require('./ui');
function clearStudent() { student.clear(); const app = getApp(); if (app && app.globalData) app.globalData.userInfo = null; }
async function revokeStudent() {
  const current = student.get();
  if (!current || !current.accessToken) return;
  try { await studentApi.post('/auth/logout', current.refreshToken ? { refreshToken: current.refreshToken } : {}, !current.refreshToken); }
  catch (error) { if (error.status !== 401) throw error; }
  clearStudent();
}
function toAdmin(page) {
  return ui.action(page, async () => {
    if (student.hasSession()) {
      if (!admin.secureConfigured()) {
        wx.reLaunch({ url: '/pages/adminlogin/adminlogin' }); return;
      }
      if (!await ui.confirm('切换管理员身份', '切换会退出当前学生账号，并清空当前页面。是否继续？')) return;
      await revokeStudent();
    }
    if (page._disposed) return;
    if (admin.hasSession()) { wx.reLaunch({ url: '/pages/adminreview/adminreview' }); return; }
    admin.clear(); clearStudent();
    wx.reLaunch({ url: '/pages/adminlogin/adminlogin' });
  });
}
function toStudent(page) {
  return ui.action(page, async () => {
    if (admin.hasSession()) {
      if (!await ui.confirm('切换学生身份', '切换会退出当前管理员会话，回到微信登录。是否继续？')) return;
      try { await admin.logout(); } catch (error) { if (error.status !== 401) throw error; }
    }
    if (page._disposed) return;
    if (!admin.hasSession() && student.hasSession()) {
      admin.clear(); wx.reLaunch({ url: '/pages/profile/profile' }); return;
    }
    admin.clear(); clearStudent();
    wx.reLaunch({ url: '/pages/login/login' });
  });
}
module.exports = { toAdmin, toStudent };
