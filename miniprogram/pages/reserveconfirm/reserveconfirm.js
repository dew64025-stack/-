const api=require('../../utils/request');const ui=require('../../utils/ui');
ui.define({
 data:{isAgreed:false,availability:null,seatNo:null,submitted:false},
 onLoad(o){this.slotId=o.slotId;this.setData({seatNo:Number(o.seatNo)});this.key=api.newIdempotencyKey();this.reload();},
 reload(){return ui.load(this,async()=>{if(!this.slotId||!Number.isInteger(this.data.seatNo))throw Error('预约参数不完整，请重新选座');return {availability:await api.get('/resource-slots/'+this.slotId+'/availability',null,true)};});},
 toggleAgree(e){this.setData({isAgreed:e.detail.value.includes('agree')});},
 submit(){if(!this.data.isAgreed)return wx.showToast({title:'请先同意预约履约规则',icon:'none'});return ui.action(this,async()=>{const reservation=await api.post('/reservations',{slotId:this.slotId,seatNo:this.data.seatNo},true,this.key);this.setData({submitted:true});wx.redirectTo({url:'/pages/voucher/voucher?id='+reservation.id});});}
});
