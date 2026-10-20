const KEY = 'czt.session.v1';
let cached;
let generation = 0;
function get() { if (cached === undefined) cached = wx.getStorageSync(KEY) || null; return cached; }
function save(tokens, rotated = false) {
  if (!rotated) generation++;
  cached = Object.assign({}, get() || {}, tokens, { expiresAt: Date.now() + Number(tokens.expiresIn || 900) * 1000 });
  wx.setStorageSync(KEY, cached);
  return cached;
}
function setUser(user) { if (!get()) return; cached = Object.assign({}, cached, { user }); wx.setStorageSync(KEY, cached); }
function clear() { generation++; cached = null; wx.removeStorageSync(KEY); }
function hasSession() { return !!(get() && get().accessToken); }
module.exports = { get, save, setUser, clear, hasSession, generation: () => generation };
