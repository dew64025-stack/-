const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');
ui.define({
 data:{entries:[],detail:null,comments:[],reply:'',hasMore:false},
 onLoad(o){this.id=o.id;},
 onShow(){this.reload();},
 reload(){this.cursor=null;return this.fetch(false);},
 fetch(append){return ui.load(this,async()=>{if(this.id){const [detail,comments]=await Promise.all([api.get('/community/posts/'+this.id,null,true),api.get('/community/posts/'+this.id+'/comments',{limit:100},true)]);return {detail:data.decorate(detail),comments:data.items(comments)};}const p=await api.get('/community/posts',{limit:20,cursor:append?this.cursor:undefined},true);this.cursor=p.nextCursor;return {entries:(append?this.data.entries:[]).concat(data.items(p)),hasMore:p.hasMore};});},
 onReachBottom(){if(this.data.hasMore&&!this.data.loading)this.fetch(true);},
 open(e){wx.navigateTo({url:'/pages/community/community?id='+e.currentTarget.dataset.id});},
 input(e){this.setData({reply:e.detail.value});},
 comment(){return ui.action(this,async()=>{const content=this.data.reply.trim();if(!content)throw Error('请输入回复内容');await api.post('/community/posts/'+this.id+'/comments',{content});this.setData({reply:''});await this.reload();});},
 create(){if(ui.requireLogin())wx.navigateTo({url:'/pages/postcreate/postcreate?category=anonymous'});},
 remove(){return ui.action(this,async()=>{if(!await ui.confirm('删除交流帖','确定删除本人发布的内容？'))return;await api.del('/community/posts/'+this.id,{version:this.data.detail.version});ui.goBack();});},
 removeComment(e){return ui.action(this,async()=>{if(!await ui.confirm('删除回复','确定删除这条回复？'))return;await api.del('/community/comments/'+e.currentTarget.dataset.id);await this.reload();});}
});
