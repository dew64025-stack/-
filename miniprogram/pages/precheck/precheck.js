const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');const consent=require('../../utils/consent');
ui.define({
 data:{checklist:null,materials:[],report:null},
 onLoad(o){this.opportunityId=o.opportunityId;this.checklistId=o.checklistId;},
 onShow(){this.reload();},
 reload(){return ui.load(this,async()=>{if(!this.checklistId&&!this.opportunityId)throw Error('请从机会详情进入材料清单');const checklist=this.checklistId?await api.get('/checklists/'+this.checklistId,null,true):await api.put('/opportunities/'+this.opportunityId+'/checklist',{});this.checklistId=checklist.id;this.opportunityId=checklist.opportunityId;const materials=await api.get('/materials',{limit:100},true);let report=null;try{report=await api.get('/checklists/'+this.checklistId+'/prechecks/latest',null,true);}catch(e){if(e.status!==404)throw e;}return {checklist,materials:data.items(materials),report};});},
 bindItem(e){return ui.action(this,async()=>{const material=this.data.materials[Number(e.detail.value)];if(!material||!material.currentVersion)throw Error('所选材料没有可用版本');await api.post('/checklists/'+this.checklistId+'/items/'+e.currentTarget.dataset.id+'/update',{materialVersionId:material.currentVersion.id,version:this.data.checklist.version});await this.reload();});},
 clearItem(e){return ui.action(this,async()=>{await api.post('/checklists/'+this.checklistId+'/items/'+e.currentTarget.dataset.id+'/update',{materialVersionId:null,version:this.data.checklist.version});await this.reload();});},
 recheck(){return ui.action(this,async()=>{const aiConsentId=await consent.materialAi();const job=await api.post('/checklists/'+this.checklistId+'/prechecks',{version:this.data.checklist.version,aiConsentId});this.setData({report:{status:'processing',results:[]}});await data.pollJob(job,()=>!this._disposed);await this.reload();});},
 openVault(){wx.navigateTo({url:'/pages/vault/vault'});},
 markSubmitted(){return ui.action(this,async()=>{if(!await ui.confirm('标记已提交','请确认你已通过学校要求的正式渠道提交材料。该操作仅记录提交状态。'))return;await api.put('/opportunities/'+this.opportunityId+'/submission',{submittedAt:new Date().toISOString()});wx.showToast({title:'已记录提交状态'});});}
});
