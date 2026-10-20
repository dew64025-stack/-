const api=require('../../utils/request');const data=require('../../utils/data');const files=require('../../utils/files');const ui=require('../../utils/ui');const consent=require('../../utils/consent');
ui.define({
 data:{materials:[],authorizations:[],aiAuthOn:false,query:'',hasMore:false},
 onShow(){this.reload();},
 reload(){this.cursor=null;return this.fetch(false);},
 fetch(append){if(append&&(this.data.loading||!this.data.hasMore))return;return ui.load(this,async()=>{const [p,authorizations]=await Promise.all([api.get('/materials',{q:this.data.query.trim(),limit:20,cursor:append?this.cursor:undefined},true),api.get('/me/authorizations',null,true)]);this.cursor=p.nextCursor;return {materials:(append?this.data.materials:[]).concat(data.items(p)),authorizations,aiAuthOn:authorizations.some(x=>x.type==='material_ai'&&x.status==='granted')};});},
 onReachBottom(){this.fetch(true);},
 input(e){this.setData({query:e.detail.value});},
 upload(){if(!ui.requireLogin())return;return ui.action(this,async()=>{const file=await files.chooseFile();const attachment=await files.upload(file,'material',()=>!this._disposed);await api.post('/materials',{attachmentId:attachment.attachmentId,tags:[]});await this.reload();wx.showToast({title:'材料已存入保险箱'});});},
 append(e){return ui.action(this,async()=>{const material=await api.get('/materials/'+e.currentTarget.dataset.id,null,true);const file=await files.chooseFile();const attachment=await files.upload(file,'material',()=>!this._disposed);await api.post('/materials/'+material.id+'/versions',{attachmentId:attachment.attachmentId,version:material.version});await this.reload();});},
 preview(e){return ui.action(this,async()=>{const material=await api.get('/materials/'+e.currentTarget.dataset.id,null,true);const version=material.currentVersion;if(!version||!version.attachmentId)throw Error('当前材料尚无法取得预览链接，请更新后端材料版本接口');const result=await api.post('/files/'+version.attachmentId+'/download-url',{});await files.download(result.url,material.fileName);});},
 remove(e){return ui.action(this,async()=>{if(!await ui.confirm('删除材料','确定删除该材料？已被清单或历史记录引用的材料可能无法删除。'))return;const material=this.data.materials.find(x=>x.id===e.currentTarget.dataset.id);await api.del('/materials/'+material.id,{version:material.version});await this.reload();});},
 toggleAuth(e){return ui.action(this,async()=>{if(e.detail.value)await consent.materialAi();else{if(!await ui.confirm('撤回材料 AI 授权','撤回后无法发起新的 AI 材料预检，是否继续？'))return;for(const auth of this.data.authorizations.filter(x=>x.type==='material_ai'&&x.status==='granted'))await api.post('/me/authorizations/'+auth.id+'/revoke',{});}await this.reload();});}
});
