const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');const files=require('../../utils/files');const session=require('../../utils/session');
ui.define({
 data:{categories:['借用共享','失物招领','教材循环','二手闲置','技能交换','匿名交流'],categoryIndex:0,titleText:'',descText:'',exchangeArea:'',price:'',itemType:'',offeredSkills:'',wantedSkills:'',conditionIndex:2,conditions:['全新','近新','良好','使用过'],kindIndex:0,images:[],occurredDate:data.localDate(),occurredTime:'12:00',agreed:false},
 onLoad(options){const user=session.get()&&session.get().user;this.draftKey='czt.postDraft.'+(user?user.id:'guest');const draft=wx.getStorageSync(this.draftKey);if(draft)this.setData(draft);if(options.category==='anonymous')this.setData({categoryIndex:5});this.postKey=api.newIdempotencyKey();},
 input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value});this.postKey=api.newIdempotencyKey();},
 picker(e){this.setData({[e.currentTarget.dataset.field]:Number(e.detail.value)});this.postKey=api.newIdempotencyKey();},
 date(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value});this.postKey=api.newIdempotencyKey();},
 agree(e){this.setData({agreed:e.detail.value.includes('agree')});},
 addPhoto(){if(!ui.requireLogin())return;return ui.action(this,async()=>{if(this.data.images.length>=4)throw Error('最多4张图片');const image=await files.chooseImage();const attachment=await files.upload(image,'mutual_aid',()=>!this._disposed);this.setData({images:this.data.images.concat({id:attachment.attachmentId,path:image.path})});this.postKey=api.newIdempotencyKey();});},
 removePhoto(e){this.setData({images:this.data.images.filter(x=>x.id!==e.currentTarget.dataset.id)});this.postKey=api.newIdempotencyKey();},
 saveDraftToast(){const fields=['categoryIndex','titleText','descText','exchangeArea','price','itemType','offeredSkills','wantedSkills','conditionIndex','kindIndex','images','occurredDate','occurredTime'];const draft={};fields.forEach(k=>draft[k]=this.data[k]);wx.setStorageSync(this.draftKey,draft);wx.showToast({title:'草稿已保存在本机'});},
 submitPostToast(){if(!ui.requireLogin())return;return ui.action(this,async()=>{const v=this.data;const title=v.titleText.trim(),content=v.descText.trim();if(!title||!content)throw Error('请填写标题和具体说明');if(!v.agreed)throw Error('请先同意校园互助规则');let result;
 if(v.categoryIndex===5){result=await api.post('/community/posts',{title,content},true,this.postKey);wx.removeStorageSync(this.draftKey);wx.redirectTo({url:'/pages/community/community?id='+result.id});return;}
 const category=['borrow','lost_found','textbook','secondhand','skill'][v.categoryIndex];const condition=['new','like_new','good','used'][v.conditionIndex];let detail;
 if(category==='borrow')detail={kind:v.kindIndex===0?'offer':'request',itemName:v.itemType.trim()||title};
 if(category==='lost_found'){if(!v.exchangeArea.trim())throw Error('请填写丢失或拾到的地点');detail={kind:v.kindIndex===0?'lost':'found',occurredAt:new Date(v.occurredDate+'T'+v.occurredTime).toISOString(),place:v.exchangeArea.trim()};}
 if(category==='textbook')detail={bookTitle:v.itemType.trim()||title,condition};
 if(category==='secondhand'){if(!v.itemType.trim())throw Error('请填写物品类型');detail={itemType:v.itemType.trim(),condition};}
 if(category==='skill')detail={offeredSkills:v.offeredSkills.split(/[,，]/).map(x=>x.trim()).filter(Boolean),wantedSkills:v.wantedSkills.split(/[,，]/).map(x=>x.trim()).filter(Boolean)};
 const price=v.price.trim();if(price&&!/^[0-9]{1,8}(\.[0-9]{1,2})?$/.test(price))throw Error('价格最多两位小数');result=await api.post('/mutual-aid/posts',{category,title,content,detail,attachmentIds:v.images.map(x=>x.id),price:['secondhand','textbook'].includes(category)?price||undefined:undefined,exchangeArea:v.exchangeArea.trim()||undefined},true,this.postKey);wx.removeStorageSync(this.draftKey);wx.redirectTo({url:'/pages/mutualdetail/mutualdetail?id='+result.id});});}
});
