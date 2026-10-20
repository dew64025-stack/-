const api=require('../../utils/request');const data=require('../../utils/data');const files=require('../../utils/files');const ui=require('../../utils/ui');
ui.define({
 data:{detail:null,category:'dorm',location:'',description:'',urgency:'normal',images:[],score:5,comment:''},
 onLoad(o){this.id=o.id;this.submitKey=api.newIdempotencyKey();this.setData({category:o.category==='facility'?'facility':'dorm'});if(this.id)this.reload();},
 reload(){return ui.load(this,async()=>{if(!this.id)return {};const detail=await api.get('/work-orders/'+this.id,null,true);return {detail:data.decorate(detail)};});},
 input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value});this.submitKey=api.newIdempotencyKey();},
 toggleUrgency(e){this.setData({urgency:e.detail.value?'urgent':'normal'});this.submitKey=api.newIdempotencyKey();},
 addImage(){if(!ui.requireLogin())return;return ui.action(this,async()=>{if(this.data.images.length>=4)throw Error('最多上传4张图片');const image=await files.chooseImage();const f=await files.upload(image,'workorder',()=>!this._disposed);this.setData({images:this.data.images.concat({id:f.attachmentId,path:image.path})});this.submitKey=api.newIdempotencyKey();});},
 submit(){if(!ui.requireLogin())return;return ui.action(this,async()=>{if(!this.data.description.trim())throw Error('请描述报修问题');const result=await api.post('/work-orders',{category:this.data.category,location:this.data.location.trim()||undefined,description:this.data.description.trim(),urgency:this.data.urgency,attachmentIds:this.data.images.map(x=>x.id)},true,this.submitKey);this.id=result.id;await this.reload();wx.showToast({title:'报修已提交'});});},
 rate(e){this.setData({score:Number(e.detail.value)+1});},
 submitRating(){return ui.action(this,async()=>{await api.post('/work-orders/'+this.id+'/ratings',{score:this.data.score,comment:this.data.comment.trim()||undefined});wx.showToast({title:'评价已提交'});await this.reload();});}
});
