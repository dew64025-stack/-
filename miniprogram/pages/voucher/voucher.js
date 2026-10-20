const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');
ui.define({
 data:{reservation:null},
 onLoad(o){this.id=o.id;},
 onShow(){this.reload();},
 reload(){return ui.load(this,async()=>{if(!this.id)throw Error('请从我的预约中选择记录');const reservation=await api.get('/reservations/'+this.id,null,true);return {reservation:Object.assign(data.decorate(reservation),{pendingExpiresText:data.date(reservation.pendingExpiresAt)})};});},
 copyCode(){if(this.data.reservation)wx.setClipboardData({data:this.data.reservation.id});},
 cancelReservation(){return ui.action(this,async()=>{if(!await ui.confirm('取消预约','确定取消该预约？'))return;await api.post('/reservations/'+this.id+'/cancel',{version:this.data.reservation.version,reason:'学生主动取消'});await this.reload();});},
 checkIn(){return ui.action(this,async()=>{const token=await new Promise((resolve,reject)=>wx.scanCode({onlyFromCamera:true,success:r=>resolve(r.result.trim()),fail:()=>reject(Error('未取得现场签到凭证'))}));if(!token||token.length>128)throw Error('请扫描学校提供的有效签到凭证');await api.post('/reservations/'+this.id+'/check-in',{version:this.data.reservation.version,checkInToken:token});await this.reload();wx.showToast({title:'签到成功'});});}
});
