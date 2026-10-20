const api=require('../../utils/request');const data=require('../../utils/data');const ui=require('../../utils/ui');
ui.define({
 data:{resources:[],slots:[],date:data.localDate(),resource:null},
 onLoad(o){this.resourceId=o.id;this.type=o.type;this.reload();},
 reload(){return ui.load(this,async()=>{const p=await api.get('/resources',data.publicQuery({type:this.type,limit:100}));const resources=data.items(p);let resource=this.resourceId?await api.get('/resources/'+this.resourceId,data.publicQuery()):resources[0];if(resource)this.resourceId=resource.id;const slots=resource?await api.get('/resources/'+resource.id+'/slots',data.publicQuery({date:this.data.date})):[];return {resources,resource,slots:slots.map(data.decorate)};});},
 selectDate(e){this.setData({date:e.detail.value});this.reload();},
 selectResource(e){this.resourceId=e.currentTarget.dataset.id;this.reload();},
 goSeat(e){if(ui.requireLogin())wx.navigateTo({url:'/pages/seatplan/seatplan?slotId='+e.currentTarget.dataset.id+'&resourceId='+this.resourceId});}
});
