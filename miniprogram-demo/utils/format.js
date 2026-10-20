function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

function formatDateTime(value) {
  if (!value) return '';
  const d = new Date(value);
  if (isNaN(d.getTime())) return String(value);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
    ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function opportunityCategory(value) {
  return ({
    scholarship: '奖学金',
    competition: '学科竞赛',
    research: '科研研习',
    internship: '实习',
    volunteer: '志愿'
  })[value] || (value || '机会');
}

function mutualCategory(value) {
  return ({
    lost_found: '失物招领',
    secondhand: '二手闲置',
    borrow: '借用共享',
    textbook: '教材循环',
    skill: '技能互助',
    team: '组队搭子'
  })[value] || (value || '互助');
}

module.exports = { formatDateTime, opportunityCategory, mutualCategory };
