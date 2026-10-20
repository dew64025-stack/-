const api = require('../../utils/request');
const data = require('../../utils/data');
const ui = require('../../utils/ui');
const session = require('../../utils/session');
function syncAssistantSession(page) {
  const generation = session.generation();
  if (page._aiSessionGeneration !== undefined && page._aiSessionGeneration !== generation) {
    const draft = page._aiGuestDraft ? page.data.aiQuestion : '';
    page._aiGeneration = (page._aiGeneration || 0) + 1; page._aiRequest = null; page._aiGuestDraft = false;
    page.setData({ aiQuestion: draft, answer: '', aiError: '', aiStatus: '', aiDisclaimer: '', aiBusy: false, aiFocus: false, aiExpanded: !!draft });
  }
  page._aiSessionGeneration = generation;
}
ui.define({
  data: { aiInput: '', aiBtnText: '搜索', deadlines: [], recommendations: [], hotIssues: [], nearbyPosts: [], results: [], answer: '', aiExpanded: false, aiFocus: false, aiQuestion: '', aiBusy: false, aiStatus: '', aiError: '', aiDisclaimer: '' },
  onShow() { syncAssistantSession(this); ui.tab(this, 0); this.reload(); },
  onHide() { this._aiGeneration = (this._aiGeneration || 0) + 1; this.setData({ aiBusy: false, aiStatus: '', aiFocus: false }); },
  reload() { return ui.load(this, async () => { const result = await api.get('/home/overview', data.publicQuery()); const patch = {}; for (const name of ['deadlines','recommendations','hotIssues','nearbyPosts']) patch[name] = (result[name] || []).map(data.decorate); return patch; }); },
  onQuickTap(e) { this.setData({ aiInput: e.currentTarget.dataset.q }); this.submitAi(); },
  onAiInput(e) { this.setData({ aiInput: e.detail.value }); },
  submitAi() { const q = this.data.aiInput.trim(); if (!q) return wx.showToast({ title: '请输入校园问题', icon: 'none' }); return ui.action(this, async () => { const result = await api.get('/search', data.publicQuery({ q, limit: 20 })); this.setData({ results: data.items(result), searched: true }); }); },
  toggleAi() { const expanded = !this.data.aiExpanded; this.setData({ aiExpanded: expanded, aiFocus: expanded && !this.data.aiBusy }); },
  onAssistantInput(e) { syncAssistantSession(this); if (!this.data.aiBusy) this.setData({ aiQuestion: e.detail.value, aiError: '', answer: '', aiDisclaimer: '' }); },
  async askAi() {
    syncAssistantSession(this);
    if (this.data.aiBusy) return;
    const question = this.data.aiQuestion.trim();
    this.setData({ aiExpanded: true });
    if (!question || question.length > 2000) {
      this.setData({ aiError: !question ? '请输入你想了解的校园问题。' : '问题请控制在 2000 字以内。', aiFocus: true }); return;
    }
    if (!ui.requireLogin()) { this._aiGuestDraft = true; this.setData({ aiError: '请先使用微信登录，再向 AI 助手提问。' }); return; }
    this._aiGuestDraft = false;
    if (!this._aiRequest || this._aiRequest.question !== question) this._aiRequest = { question, key: api.newIdempotencyKey(), job: null };
    const request = this._aiRequest;
    const generation = (this._aiGeneration || 0) + 1; this._aiGeneration = generation;
    const identity = session.generation();
    const active = () => !this._disposed && this._aiGeneration === generation && session.generation() === identity;
    this.setData({ aiBusy: true, aiFocus: false, aiError: '', answer: '', aiDisclaimer: '', aiStatus: '正在提交问题…' });
    try {
      if (!request.job) request.job = await api.post('/ai/assistant/messages', { question, scope: 'campus' }, true, request.key);
      if (!active()) return;
      if (!request.job || typeof request.job.id !== 'string' || !request.job.id) throw new Error('AI 服务没有返回有效任务，请稍后重试。');
      this.setData({ aiStatus: '正在查找校园资料并整理回答…' });
      const result = await data.pollJob(request.job, active);
      if (!active()) return;
      const answer = result && typeof result.answer === 'string' ? result.answer.trim() : '';
      if (!answer) throw Object.assign(new Error('助手没有返回有效回答，请稍后重试。'), { code: 'AI_EMPTY_ANSWER' });
      this._aiRequest = null;
      this.setData({ answer, aiDisclaimer: typeof result.disclaimer === 'string' ? result.disclaimer : '以学校或主办方最新通知为准' });
    } catch (error) {
      if (!active()) return;
      if (/^ASSISTANT_/.test(error.code || '') || error.code === 'AI_EMPTY_ANSWER') this._aiRequest = null;
      const messages = {
        ASSISTANT_AI_KEY_MISSING: 'AI 服务尚未配置。请联系管理员在后端配置 AI 服务，暂时可使用顶部搜索查找校园信息。',
        AI_KEY_MISSING: 'AI 服务尚未配置。请联系管理员在后端配置 AI 服务，暂时可使用顶部搜索查找校园信息。',
        ASSISTANT_AI_UNAUTHORIZED: 'AI 服务授权无效，请联系管理员检查后端配置；顶部搜索仍可使用。',
        ASSISTANT_AI_PAYMENT: 'AI 服务余额不足，请联系管理员处理；顶部搜索仍可使用。',
        ASSISTANT_AI_TIMEOUT: 'AI 服务响应超时，请稍后重试；顶部搜索仍可使用。'
      };
      this.setData({ aiError: messages[error.code] ? messages[error.code] + (error.requestId ? '\n请求编号：' + error.requestId : '') : ui.errorText(error) });
    } finally {
      if (active()) this.setData({ aiBusy: false, aiStatus: '' });
      else if (!this._disposed && this._aiGeneration === generation && session.generation() !== identity) {
        syncAssistantSession(this);
        if (!session.hasSession()) this.setData({ aiExpanded: true, aiError: '登录已过期，请重新登录后提问。' });
      }
    }
  },
  openTarget(e) {
    const type = e.currentTarget.dataset.type, id = e.currentTarget.dataset.id;
    const routes = { opportunity: 'opportunitydetail', mutual_aid: 'mutualdetail', mutual_aid_post: 'mutualdetail', post: 'mutualdetail', campus_issue: 'campusissues', issue: 'campusissues', reservation: 'voucher', resource: 'seatreserve', team: 'teams' };
    if (routes[type]) wx.navigateTo({ url: '/pages/' + routes[type] + '/' + routes[type] + '?id=' + encodeURIComponent(id) });
    else wx.showToast({ title: '请从对应业务入口查看该内容', icon: 'none' });
  },
  moreOpportunities() { wx.switchTab({ url: '/pages/opportunity/opportunity' }); }
});
