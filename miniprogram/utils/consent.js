const api = require('./request');
const ui = require('./ui');
const config = require('../config/api');
async function materialAi() {
  const authorizations = await api.get('/me/authorizations', null, true);
  const granted = authorizations.find(x => x.type === 'material_ai' && x.status === 'granted');
  if (granted) return granted.id;
  if (!config.materialAiDocumentVersion || !config.materialAiPolicyText) throw new Error('材料 AI 授权说明尚未发布，暂不能提交 AI 预检');
  if (!await ui.confirm('材料 AI 处理授权', config.materialAiPolicyText)) throw new Error('你尚未同意材料 AI 处理授权');
  const result = await api.post('/me/authorizations', { type: 'material_ai', scope: 'material_precheck', documentVersion: config.materialAiDocumentVersion });
  return result.id;
}
module.exports = { materialAi };
