const {test,after}=require('node:test');const assert=require('node:assert/strict');const path=require('node:path');
const root=path.resolve(__dirname,'..'),config=require('../config/api'),original=config.adminBaseUrl;
after(()=>{config.adminBaseUrl=original;});
const administrator={id:'900',nickname:'审核员',roles:['campus_admin'],campusScopes:['1'],permissions:['identity.review']};
const csrfCookie='ADMIN-XSRF-TOKEN=csrf-fixture; Path=/api/v1/admin; Secure; HttpOnly; SameSite=Strict';
function ok(o,data,cookies=[]){o.success({statusCode:200,data:{success:true,data},cookies,header:{}});}
function fail(o,status=503,code='DEPENDENCY_UNAVAILABLE'){o.success({statusCode:status,data:{success:false,error:{code,message:'请求未完成'},requestId:'test-request'},cookies:[],header:{}});}
function harness(name,custom,confirmed=true){
 for(const f of Object.keys(require.cache))if(f.startsWith(path.join(root,'utils'))||f===path.join(root,'pages',name,name+'.js'))delete require.cache[f];
 config.adminBaseUrl='https://campus.example/api/v1';const calls=[],routes=[],modals=[],toasts=[],storage=new Map();const app={globalData:{userInfo:null}};
 global.getApp=()=>app;global.wx={
 getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),
 showModal(o){modals.push(o);if(o.success)o.success({confirm:confirmed,content:''});},showToast(o){toasts.push(o);},
 reLaunch:o=>routes.push(o.url),redirectTo:o=>routes.push(o.url),navigateTo:o=>routes.push(o.url),switchTab:o=>routes.push(o.url),
 request(o){calls.push(o);queueMicrotask(()=>{
 if(o.url.endsWith('/admin/auth/csrf'))return ok(o,{token:'masked-fixture',headerName:'X-ADMIN-CSRF'},[csrfCookie]);
 if(o.url.endsWith('/admin/auth/login'))return ok(o,{challengeId:'C'.repeat(43),mfaRequired:true,expiresAt:new Date(Date.now()+300000).toISOString()});
 if(o.url.endsWith('/admin/auth/mfa/verify'))return ok(o,{accessToken:'A'.repeat(43),expiresIn:900,admin:administrator,permissions:administrator.permissions},['ADMIN-REFRESH='+'R'.repeat(43)+'; Path=/api/v1/admin/auth; Secure; HttpOnly; SameSite=Strict']);
 if(custom&&custom(o)!==false)return;
 if(o.url.endsWith('/admin/me'))return ok(o,administrator);
 if(o.url.includes('/admin/student-verifications?'))return ok(o,{items:[{id:'51',status:'pending',studentNumberMasked:'20****08',rejectReason:null,createdAt:'2026-10-20T10:00:00Z',version:3,rosterMatched:true}],nextCursor:null,hasMore:false});
 return ok(o,{revoked:true});
 });}};
 let definition;global.Page=d=>{definition=d;};require(path.join(root,'pages',name,name+'.js'));
 const page=Object.assign({},definition,{data:structuredClone(definition.data),setData(patch){Object.assign(this.data,patch);}});
 return{page,calls,routes,modals,toasts,app,storage,student:require('../utils/session'),admin:require('../utils/admin'),identity:()=>require('../utils/identity')};
}
async function authenticate(h){await h.admin.login('teacher-001','test-only-password');await h.admin.verify('123456');}
function student(h){h.student.save({accessToken:'student-access',refreshToken:'student-refresh',expiresIn:900,user:{id:'101'}});h.app.globalData.userInfo={id:'101'};}

test('登录页提供独立管理员入口，访客可进入且不会伪造学生身份',async()=>{
 const h=harness('login');await h.page.onAdminLogin();assert.deepEqual(h.routes,['/pages/adminlogin/adminlogin']);assert.equal(h.student.hasSession(),false);
});
test('取消身份切换不发送退出请求，不清除学生会话',async()=>{
 const h=harness('profile',null,false);student(h);await h.page.openAdmin();assert.equal(h.calls.length,0);assert.equal(h.routes.length,0);assert.equal(h.student.hasSession(),true);
});
test('确认切换后真实退出学生会话，再清缓存并重建管理员页面栈',async()=>{
 const h=harness('profile');student(h);await h.page.openAdmin();
 assert(h.calls.some(o=>o.url.endsWith('/auth/logout')&&!o.url.includes('/admin/')));assert.equal(h.student.hasSession(),false);assert.equal(h.app.globalData.userInfo,null);
 assert.deepEqual(h.routes,['/pages/adminlogin/adminlogin']);
});
test('学生退出503时不能假装切换成功或丢掉可重试会话',async()=>{
 const h=harness('profile',o=>{if(o.url.endsWith('/auth/logout')){fail(o);return;}return false;});student(h);await h.page.openAdmin();
 assert.equal(h.routes.length,0);assert.equal(h.student.hasSession(),true);assert.equal(h.modals.at(-1).title,'操作未完成');
});
test('管理员HTTP未配置时，界面提示阻止提交密码',async()=>{
 const h=harness('adminlogin');config.adminBaseUrl='';h.page.onLoad();h.page.setData({username:'teacher-001',password:'test-only-password'});await h.page.submitPassword();
 assert.equal(h.page.data.secureReady,false);assert.equal(h.calls.length,0);assert.equal(h.admin.hasSession(),false);
});
test('管理员登录分两步，密码阶段只显示验证码，MFA成功才进入工作台',async()=>{
 const h=harness('adminlogin');h.page.onLoad();h.page.setData({username:'teacher-001',password:'test-only-password'});await h.page.submitPassword();
 assert.equal(h.page.data.stage,'mfa');assert.equal(h.page.data.password,'');assert.equal(h.routes.length,0);assert.equal(h.admin.hasSession(),false);
 h.page.setData({code:'123456'});await h.page.submitMfa();assert.equal(h.page.data.code,'');assert.equal(h.admin.hasSession(),true);assert.deepEqual(h.routes,['/pages/adminreview/adminreview']);
 assert.equal(h.page.data.challengeId,undefined);assert.equal(h.page.data.accessToken,undefined);
});
test('离开管理员登录页会清除敏感输入和未完成挑战',async()=>{
 const h=harness('adminlogin');h.page.onLoad();h.page.setData({username:'teacher-001',password:'test-only-password'});await h.page.submitPassword();h.page.setData({code:'123456'});h.page.onHide();h.page.onUnload();
 assert.equal(h.page.data.password,'');assert.equal(h.page.data.code,'');assert.equal(h.admin.hasSession(),false);await assert.rejects(h.admin.verify('123456'));
});
test('未登录不能读管理队列，直接进入页面只显示管理员登录提示',async()=>{
 const h=harness('adminreview');await h.page.onShow();assert.equal(h.calls.length,0);assert.equal(h.page.data.needsLogin,true);assert.equal(h.page.data.applications.length,0);
});
test('工作台先读取服务器权限，没有审核权限不请求申请队列',async()=>{
 const h=harness('adminreview',o=>{if(o.url.endsWith('/admin/me')){ok(o,Object.assign({},administrator,{permissions:[]}));return;}return false;});await authenticate(h);await h.page.onShow();
 assert.equal(h.page.data.canReview,false);assert.equal(h.page.data.applications.length,0);assert.equal(h.calls.filter(o=>o.url.includes('/admin/student-verifications')).length,0);
});
test('工作台读取真实脱敏申请与版本，名单命中不等于自动通过',async()=>{
 const h=harness('adminreview');await authenticate(h);await h.page.onShow();
 assert.equal(h.page.data.canReview,true);assert.equal(h.page.data.applications[0].studentNumberMasked,'20****08');assert.equal(h.page.data.applications[0].version,3);
 assert.equal(h.calls.filter(o=>o.url.endsWith('/decision')).length,0);
});
test('未确认本人核验或缺原因时，前端不会发送通过审核',async()=>{
 const h=harness('adminreview');await authenticate(h);await h.page.onShow();h.page.openReview({currentTarget:{dataset:{index:0}}});
 h.page.setData({reason:'仅名单命中',identityChecked:false});await h.page.submitDecision();h.page.setData({identityChecked:true,reason:''});await h.page.submitDecision();
 assert.equal(h.calls.filter(o=>o.url.endsWith('/decision')).length,0);assert.equal(h.toasts.length,0);
});
test('通过与驳回的请求都携带原因和真实version，不传角色/校园/手机号',async()=>{
 const decisions=[];const h=harness('adminreview',o=>{if(o.url.endsWith('/decision')){decisions.push(o);ok(o,{id:'51',status:o.data.decision==='approve'?'verified':'rejected',studentNumberMasked:'20****08',version:4});return;}return false;});
 await authenticate(h);await h.page.onShow();h.page.openReview({currentTarget:{dataset:{index:0}}});h.page.setData({identityChecked:true,reason:'已线下核验本人学生身份'});await h.page.submitDecision();
 assert.deepEqual(decisions[0].data,{decision:'approve',reason:'已线下核验本人学生身份',version:3});
 await h.page.reload();h.page.openReview({currentTarget:{dataset:{index:0}}});h.page.changeDecision({detail:{value:'reject'}});h.page.setData({reason:'请向学校补充可信身份凭据'});await h.page.submitDecision();
 assert.deepEqual(decisions[1].data,{decision:'reject',reason:'请向学校补充可信身份凭据',version:3});
});
test('审核503不显示成功，同一决定手动重试沿用防重key',async()=>{
 const decisions=[];const h=harness('adminreview',o=>{if(o.url.endsWith('/decision')){decisions.push(o);fail(o);return;}return false;});
 await authenticate(h);await h.page.onShow();h.page.openReview({currentTarget:{dataset:{index:0}}});h.page.setData({identityChecked:true,reason:'已核验本人'});await h.page.submitDecision();await h.page.submitDecision();
 assert.equal(decisions.length,2);assert.equal(decisions[0].header['Idempotency-Key'],decisions[1].header['Idempotency-Key']);assert.equal(h.toasts.length,0);assert.equal(h.page.data.selected.status,'pending');
});
test('管理员切回学生端先退出管理员并清会话，再重建学生登录页',async()=>{
 const h=harness('adminreview');await authenticate(h);await h.page.switchStudent();assert.equal(h.admin.hasSession(),false);assert.equal(h.student.hasSession(),false);assert.deepEqual(h.routes,['/pages/login/login']);
 assert(h.calls.some(o=>o.url.endsWith('/admin/auth/logout')));
});

test('后台处理完成后才返回的审核结果，不能把隐藏页面的申请重新填回',async()=>{
 let pending;const h=harness('adminreview',o=>{if(o.url.endsWith('/decision')){pending=o;return;}return false;});await authenticate(h);await h.page.onShow();h.page.openReview({currentTarget:{dataset:{index:0}}});h.page.setData({identityChecked:true,reason:'已核验本人'});
 const task=h.page.submitDecision();await new Promise(r=>setImmediate(r));assert(pending);h.page.onHide();ok(pending,{id:'51',status:'verified',version:4});await task;
 assert.equal(h.page.data.applications.length,0);assert.equal(h.page.data.selected,null);assert.equal(h.toasts.length,0);
});
test('审核版本冲突会刷新真实队列，不展示通过成功',async()=>{
 let changed=false;const h=harness('adminreview',o=>{if(o.url.endsWith('/decision')){changed=true;fail(o,409,'VERSION_CONFLICT');return;}if(changed&&o.url.includes('/admin/student-verifications?')){ok(o,{items:[],hasMore:false,nextCursor:null});return;}return false;});
 await authenticate(h);await h.page.onShow();h.page.openReview({currentTarget:{dataset:{index:0}}});h.page.setData({identityChecked:true,reason:'已核验本人'});await h.page.submitDecision();
 assert.equal(h.page.data.applications.length,0);assert.equal(h.toasts.length,0);assert.equal(h.modals.at(-1).title,'操作未完成');
});
test('人工审核通过后学生端回读verified，显示已认证而不是原始英文状态',async()=>{
 const h=harness('verify',o=>{if(o.url.endsWith('/me')){ok(o,{id:'101',phoneBound:true,verificationStatus:'verified'});return;}if(o.url.endsWith('/me/student-verification')){ok(o,{id:'51',status:'verified',studentNumberMasked:'20****08',version:4});return;}return false;});student(h);await h.page.reload();
 assert.equal(h.page.data.verification.statusLabel,'已认证');assert.equal(h.page.data.verification.id,'51');
});

test('HTTPS未配置时学生查看管理员入口，不先退出已有学生身份',async()=>{
 const h=harness('profile');student(h);config.adminBaseUrl='';await h.page.openAdmin();
 assert.equal(h.calls.length,0);assert.equal(h.student.hasSession(),true);assert.deepEqual(h.routes,['/pages/adminlogin/adminlogin']);
});
test('从未完成切换的管理员页返回学生端，保留原会话并回到我的',async()=>{
 const h=harness('adminlogin');student(h);h.page.onLoad();await h.page.switchStudent();
 assert.equal(h.calls.length,0);assert.equal(h.student.hasSession(),true);assert.deepEqual(h.routes,['/pages/profile/profile']);
});
