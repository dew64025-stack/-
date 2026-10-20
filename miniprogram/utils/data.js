const api = require('./request');
const config = require('../config/api');
const session = require('./session');
const labels = { scholarship: '奖学金', competition: '学科竞赛', research: '科研研习', internship: '实习', volunteer: '志愿活动', lost_found: '失物招领', secondhand: '二手闲置', textbook: '教材循环', skill: '技能交换', borrow: '借用共享', pending: '待处理', approved: '已通过', verified: '已认证', rejected: '已驳回', unverified: '未认证', submitted: '已提交', processing: '处理中', completed: '已完成', cancelled: '已取消', confirmed: '已确认', checked_in: '已签到', granted: '已授权', revoked: '已撤回', succeeded: '已完成', failed: '失败', active: '可用', clean: '安全扫描通过', quarantined: '隔离扫描中', infected: '安全扫描不通过', low: '较空闲', medium: '一般', high: '拥挤', awaiting_rating: '待评价', reviewing: '审核中', accepted: '已受理', closed: '已结案', manual_review: '待人工复核' };
function label(value) { return labels[value] || value || '未提供'; }
function date(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '未设置'; }
function localDate(offset = 0) { const d = new Date(); d.setDate(d.getDate() + offset); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
function decorate(item) { return Object.assign({}, item, { categoryLabel: label(item.category || item.type), statusLabel: label(item.status), dateText: date(item.deadline || item.createdAt || item.observedAt || item.occurredAt), priceText: item.price == null ? '面议 / 免费' : '¥' + item.price }); }
function items(page) { return (page && page.items || []).map(decorate); }
function campus() { return wx.getStorageSync('czt.campusCode') || config.defaultCampusCode; }
function publicQuery(query) { return Object.assign({ campusCode: campus() }, query || {}); }
async function pollJob(job, active) {
  for (let i = 0; i < 90; i++) {
    if (active && !active()) throw new Error('已离开页面，任务仍可在稍后查询');
    const current = i === 0 ? job : await api.get('/jobs/' + job.id, null, true);
    if (current.status === 'succeeded') return current.result;
    if (['failed', 'cancelled'].includes(current.status)) throw Object.assign(new Error(current.error && current.error.message || '任务执行失败'), current.error || {});
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  throw new Error('任务仍在处理中，请稍后刷新查看');
}
async function refreshUser() { const user = await api.get('/me', null, true); session.setUser(user); return user; }
module.exports = { label, date, localDate, decorate, items, campus, publicQuery, pollJob, refreshUser };
