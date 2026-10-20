const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');
ui.define({
 data:{seats:[],seatId:null,availability:null,resource:{}},
 onLoad(o){this.slotId=o.slotId;this.resourceId=o.resourceId;this.reload();},
 reload(){return ui.load(this,async()=>{if(!this.slotId)throw Error('请先选择预约时段');const availability=await api.get('/resource-slots/'+this.slotId+'/availability',null,true);const resource=await api.get('/resources/'+availability.slot.resourceId,data.publicQuery());this.resourceId=resource.id;const available=new Set(availability.availableSeatNos);const nums=availability.reservationMode==='exclusive'?[0]:Array.from({length:availability.slot.capacity||resource.capacity||0},(_,i)=>i+1);return {availability,resource,seats:nums.map(n=>({id:n,label:n===0?'整间 / 整台':String(n),status:available.has(n)?'available':'occupied'})),seatId:null};});},
 selectSeat(e){const id=Number(e.currentTarget.dataset.id);const seat=this.data.seats.find(x=>x.id===id);if(!seat||seat.status==='occupied')return;this.setData({seatId:id,seats:this.data.seats.map(x=>Object.assign({},x,{status:x.status==='occupied'?'occupied':x.id===id?'selected':'available'}))});},
 confirmSeat(){if(this.data.seatId===null)return wx.showToast({title:'请选择可用座位或名额',icon:'none'});wx.navigateTo({url:'/pages/reserveconfirm/reserveconfirm?slotId='+this.slotId+'&seatNo='+this.data.seatId});}
});
