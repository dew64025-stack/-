const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');
ui.define({
 data:{side:'borrower',entries:[]},
 onShow(){this.reload();},
 reload(){return ui.load(this,async()=>({entries:data.items(await api.get('/me/borrow-requests',{side:this.data.side,limit:100},true))}));},
 side(e){this.setData({side:e.currentTarget.dataset.side});this.reload();},
 transition(e){return ui.action(this,async()=>{const action=e.currentTarget.dataset.action;if(!await ui.confirm('更新借用状态','请在完成对应线下步骤后确认。交接与归还需要双方确认。'))return;await api.post('/borrow-requests/'+e.currentTarget.dataset.id+'/actions',{action});await this.reload();});},
 contact(e){wx.navigateTo({url:'/pages/chat/chat?contextType=borrow_request&contextId='+e.currentTarget.dataset.id});},
 rate(e){return ui.action(this,async()=>{const score=await ui.choose(['1 星','2 星','3 星','4 星','5 星']);if(score<0)return;const comment=await ui.prompt('借用履约评价','填写具体评价（可选）');if(comment===null)return;await api.post('/mutual-aid/posts/'+e.currentTarget.dataset.post+'/ratings',{score:score+1,comment:comment||undefined});wx.showToast({title:'评价已提交'});});}
});
