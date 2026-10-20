const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');
ui.define({
 data:{detail:null,ratings:[],showDrawer:false,startText:'',endText:''},
 onLoad(o){this.id=o.id;},
 onShow(){this.reload();},
 reload(){return ui.load(this,async()=>{if(!this.id)throw Error('请从互助列表选择内容');const [detail,ratings]=await Promise.all([api.get('/mutual-aid/posts/'+this.id,data.publicQuery()),api.get('/mutual-aid/posts/'+this.id+'/ratings',data.publicQuery({limit:20}))]);return {detail:data.decorate(detail),ratings:data.items(ratings)};});},
 openDrawer(){if(ui.requireLogin())this.setData({showDrawer:true});},
 closeDrawer(){this.setData({showDrawer:false});},
 input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value});},
 toggleFav(){wx.showModal({title:'互助收藏待开放',content:'当前后端尚未接入互助收藏，暂时只能收藏机会项目；站内联系与借用不受影响。',showCancel:false});},
 confirmSubmit(){return ui.action(this,async()=>{const input={};for(const [field,value]of [['startTime',this.data.startText],['endTime',this.data.endText]]){if(value.trim()){const parsed=new Date(value.trim().replace(' ','T'));if(Number.isNaN(parsed.getTime()))throw Error('日期格式为 YYYY-MM-DD HH:mm');input[field]=parsed.toISOString();}}if(input.startTime&&input.endTime&&input.startTime>=input.endTime)throw Error('归还时间应晚于借用时间');const result=await api.post('/mutual-aid/posts/'+this.id+'/borrow-requests',input);this.setData({showDrawer:false});wx.showToast({title:'借用申请已提交'});wx.navigateTo({url:'/pages/chat/chat?contextType=borrow_request&contextId='+result.id});});},
 onChat(){if(ui.requireLogin())wx.navigateTo({url:'/pages/chat/chat?contextType=mutual_aid_post&contextId='+this.id});},
 changeStatus(e){return ui.action(this,async()=>{const action=e.currentTarget.dataset.action;if(!await ui.confirm('更新发布状态','确定执行此操作？'))return;await api.post('/mutual-aid/posts/'+this.id+'/actions',{action,version:this.data.detail.version});await this.reload();});}
});
