const admin = require('../../utils/admin');
const identity = require('../../utils/identity');
const ui = require('../../utils/ui');
const data = require('../../utils/data');
const { ApiError, newIdempotencyKey } = require('../../utils/request');
const statusLabels = { pending: '待审核', verified: '已通过', rejected: '已驳回' };
ui.define({
  data: { admin: null, canReview: false, status: 'pending', filters: [{ value: 'pending', label: '待审核' }, { value: 'verified', label: '已通过' }, { value: 'rejected', label: '已驳回' }], applications: [], hasMore: false, selected: null, decision: 'approve', reason: '', identityChecked: false, notice: '' },
  onShow() {
    this._visibilityGeneration = (this._visibilityGeneration || 0) + 1;
    if (!admin.hasSession()) { this.setData({ admin: null, canReview: false, applications: [], selected: null, needsLogin: true, error: '请先完成管理员登录与动态验证码验证' }); return; }
    return this.reload();
  },
  reload() {
    this.cursor = null; this.decisionKey = null;
    this.setData({ applications: [], selected: null, hasMore: false, notice: '' });
    return this.fetch(false);
  },
  fetch(append) {
    if (append && (this.data.loading || !this.data.hasMore)) return;
    return ui.load(this, async () => {
      const generation = this._loadGeneration;
      try {
        const current = await admin.get('/me');
        if (this._disposed || generation !== this._loadGeneration) return {};
        const canReview = Array.isArray(current.permissions) && current.permissions.includes('identity.review');
        if (!canReview) return { admin: current, canReview: false, applications: [], selected: null, hasMore: false };
        const result = await admin.get('/student-verifications', { status: this.data.status, cursor: append ? this.cursor : undefined, limit: 20 });
        if (this._disposed || generation !== this._loadGeneration) return {};
        this.cursor = result.nextCursor;
        const entries = result.items.map(x => Object.assign({}, x, { statusLabel: statusLabels[x.status] || x.status, dateText: data.date(x.createdAt) }));
        const applications = (append ? this.data.applications : []).concat(entries);
        return { admin: current, canReview: true, applications: applications.filter((x, i) => applications.findIndex(y => y.id === x.id) === i), hasMore: result.hasMore };
      } catch (error) {
        if (!this._disposed && generation === this._loadGeneration) this.setData({ applications: [], selected: null, hasMore: false, canReview: false });
        throw error;
      }
    });
  },
  selectStatus(e) {
    const status = e.currentTarget.dataset.status;
    if (this.data.busy || !Object.prototype.hasOwnProperty.call(statusLabels, status)) return;
    this.setData({ status }); return this.reload();
  },
  onReachBottom() { return this.fetch(true); },
  openReview(e) {
    if (this.data.busy || !this.data.canReview) return;
    const selected = this.data.applications[Number(e.currentTarget.dataset.index)];
    if (!selected || selected.status !== 'pending') return;
    this.decisionKey = null;
    this.setData({ selected, decision: 'approve', reason: '', identityChecked: false, notice: '' });
  },
  closeReview() { if (!this.data.busy) this.setData({ selected: null, reason: '', identityChecked: false }); },
  changeDecision(e) { if (!this.data.busy && ['approve', 'reject'].includes(e.detail.value)) this.setData({ decision: e.detail.value }); },
  inputReason(e) { if (!this.data.busy) this.setData({ reason: e.detail.value }); },
  confirmIdentity(e) { if (!this.data.busy) this.setData({ identityChecked: e.detail.value.includes('checked') }); },
  submitDecision() {
    return ui.action(this, async () => {
      const visibility = this._visibilityGeneration || 0;
      const inactive = () => this._disposed || visibility !== (this._visibilityGeneration || 0);
      const selected = this.data.selected, decision = this.data.decision, reason = this.data.reason.trim();
      if (!this.data.canReview || !selected || selected.status !== 'pending') throw new Error('请选择有权限审核的待审申请');
      if (!['approve', 'reject'].includes(decision) || !Number.isInteger(selected.version)) throw new Error('申请状态无效，请刷新列表');
      if (decision === 'approve' && !this.data.identityChecked) throw new Error('请先核验申请人的本人学生身份，名单命中本身不足以通过');
      if (!reason) throw new Error(decision === 'approve' ? '请填写本人身份核验依据' : '请填写具体驳回原因');
      if (reason.length > (decision === 'reject' ? 255 : 512)) throw new Error(decision === 'reject' ? '驳回原因最多255个字符' : '核验依据最多512个字符');
      if (!await ui.confirm(decision === 'approve' ? '确认通过认证' : '确认驳回认证', '申请学号：' + selected.studentNumberMasked + '\n' + reason)) return;
      if (inactive()) return;
      const fingerprint = JSON.stringify([selected.id, selected.version, decision, reason]);
      if (!this.decisionKey || this.decisionKey.fingerprint !== fingerprint) this.decisionKey = { fingerprint, key: newIdempotencyKey() };
      try {
        const result = await admin.post('/student-verifications/' + selected.id + '/decision', { decision, reason, version: selected.version }, this.decisionKey.key);
        if (result.status !== (decision === 'approve' ? 'verified' : 'rejected')) throw new ApiError(0, { code: 'INVALID_RESPONSE', message: '审核响应异常，请刷新确认实际状态' });
        if (inactive()) return;
        await this.reload();
        if (!inactive()) { this.setData({ notice: decision === 'approve' ? '认证已通过，核验依据已记录' : '认证已驳回，原因已记录' }); wx.showToast({ title: decision === 'approve' ? '认证已通过' : '认证已驳回' }); }
      } catch (error) {
        if (inactive()) return;
        if ([401, 403, 409].includes(error.status)) await this.reload();
        throw error;
      }
    });
  },
  goLogin() { return identity.toAdmin(this); },
  switchStudent() { return identity.toStudent(this); },
  onHide() { this._visibilityGeneration = (this._visibilityGeneration || 0) + 1; this._loadGeneration = (this._loadGeneration || 0) + 1; this.setData({ applications: [], selected: null, reason: '', identityChecked: false }); },
  onUnload() { this._disposed = true; }
});
