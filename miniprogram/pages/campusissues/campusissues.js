const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');
ui.define({
 data:{entries:[],detail:null,crowdMode:false,creating:false,title:'',description:'',categoryIndex:0,categories:['维修建议','食堂','快递','其他'],markers:[]},
 onLoad(o){this.id=o.id;this.setData({crowdMode:o.mode==='crowd'});this.reload();},
 reload(){return ui.load(this,async()=>{if(this.id)return {detail:data.decorate(await api.get('/campus-issues/'+this.id,data.publicQuery()))};const p=await api.get(this.data.crowdMode?'/crowd/observations':'/campus-issues',data.publicQuery({limit:100}));return {entries:data.items(p).map(x=>Object.assign(x,{levelLabel:data.label(x.crowdLevel)}))};});},
 open(e){wx.navigateTo({url:'/pages/campusissues/campusissues?id='+e.currentTarget.dataset.id});},
 startCreate(){if(ui.requireLogin())this.setData({creating:!this.data.creating});},
 input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value});},
 category(e){this.setData({categoryIndex:Number(e.detail.value)});},
 create(){return ui.action(this,async()=>{if(!this.data.title.trim())throw Error('请输入议题标题');const result=await api.post('/campus-issues',{title:this.data.title.trim(),category:['repair','canteen','express','other'][this.data.categoryIndex],description:this.data.description.trim()||undefined,attachmentIds:[]});this.setData({creating:false,title:'',description:''});wx.showModal({title:'建议已提交',content:'当前状态：'+data.label(result.status),showCancel:false});await this.reload();});},
 support(e){if(!ui.requireLogin())return;return ui.action(this,async()=>{if(e.currentTarget.dataset.cancel)await api.del('/campus-issues/'+this.id+'/vote');else await api.put('/campus-issues/'+this.id+'/vote',{value:1});await this.reload();});},
 follow(e){if(!ui.requireLogin())return;return ui.action(this,async()=>{const p='/campus-issues/'+this.id+'/follows';if(e.currentTarget.dataset.cancel)await api.del(p);else await api.post(p,{});await this.reload();});},
 evidence(){if(!ui.requireLogin())return;return ui.action(this,async()=>{const content=await ui.prompt('补充证据','请描述补充情况（最多512字）');if(!content)return;await api.post('/campus-issues/'+this.id+'/evidences',{content,attachmentIds:[]});wx.showToast({title:'证据已提交审核'});});},
 observe(){if(!ui.requireLogin())return;return ui.action(this,async()=>{const type=await ui.choose(['食堂','快递点']);if(type<0)return;const name=await ui.prompt('观测点名称','例如：第一食堂');if(!name)return;const level=await ui.choose(['较空闲','一般','拥挤']);if(level<0)return;await api.post('/crowd/observations',{locationType:['canteen','express'][type],locationName:name,crowdLevel:['low','medium','high'][level]});await this.reload();});},
 showMap(){return ui.action(this,async()=>{const result=await api.get('/campus-issues/map',data.publicQuery({limit:100}));this.setData({markers:result.points.map((p,i)=>({id:i,latitude:p.latitude,longitude:p.longitude,title:p.title,callout:{content:p.title,display:'BYCLICK'}}))});if(!result.points.length)wx.showToast({title:'暂无带位置的公开议题',icon:'none'});});}
});
