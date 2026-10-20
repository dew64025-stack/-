const {test,after}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const config=require('../config/api');
const original={baseUrl:config.baseUrl,adminBaseUrl:config.adminBaseUrl};
after(()=>{Object.assign(config,original);});
const ACCESS='A'.repeat(43),NEXT='B'.repeat(43),REFRESH='R'.repeat(43);
const identity={id:'900',nickname:'本校审核员',roles:['campus_admin'],campusScopes:['1'],permissions:['identity.review']};
const csrfCookie='ADMIN-XSRF-TOKEN=csrf-raw-fixture; Path=/api/v1/admin; Secure; HttpOnly; SameSite=Strict';
const refreshCookie='ADMIN-REFRESH='+REFRESH+'; Path=/api/v1/admin/auth; Secure; HttpOnly; SameSite=Strict';
function ok(o,data,cookies=[]){o.success({statusCode:200,data:{success:true,data,requestId:'fixture-id'},cookies,header:{}});}
function bad(o,status,code='UNAUTHENTICATED'){o.success({statusCode:status,data:{success:false,error:{code,message:'请求未完成',retryable:status===503},requestId:'fixture-error'},cookies:[],header:{}});}
function harness(custom){
 const storage=new Map(),calls=[],writes=[];
 for(const file of Object.keys(require.cache))if(file.startsWith(path.join(root,'utils')))delete require.cache[file];
 config.adminBaseUrl='https://campus.example/api/v1';
 global.wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>{storage.set(k,v);writes.push([k,v]);},removeStorageSync:k=>storage.delete(k),request(o){calls.push(o);queueMicrotask(()=>{
   if(o.url.endsWith('/admin/auth/csrf'))return ok(o,{token:'masked-csrf-fixture',headerName:'X-ADMIN-CSRF'},[csrfCookie]);
   if(o.url.endsWith('/admin/auth/login'))return ok(o,{challengeId:'C'.repeat(43),mfaRequired:true,expiresAt:new Date(Date.now()+300000).toISOString()});
   if(o.url.endsWith('/admin/auth/mfa/verify'))return ok(o,{accessToken:ACCESS,expiresIn:900,refreshToken:null,refreshExpiresIn:604800,admin:identity,permissions:identity.permissions},[refreshCookie]);
   if(custom)return custom(o);
   return ok(o,{accepted:true});
 });}};
 return{calls,writes,storage,admin:require('../utils/admin'),student:require('../utils/session')};
}
async function authenticate(h){await h.admin.login('teacher-001','test-only-password');await h.admin.verify('123456');}

test('HTTP地址禁止发送工号密码和管理Cookie',async()=>{
 const h=harness();config.adminBaseUrl='';config.baseUrl='http://127.0.0.1:8080/api/v1';
 await assert.rejects(h.admin.login('teacher-001','test-only-password'),e=>e.code==='ADMIN_HTTPS_REQUIRED');
 assert.equal(h.calls.length,0);assert.equal(h.admin.hasSession(),false);
});
test('密码通过仅得到挑战，不创建管理员会话，登录不携带学生Bearer',async()=>{
 const h=harness();const result=await h.admin.login('teacher-001','test-only-password');
 assert.equal(h.admin.hasSession(),false);assert.equal(h.admin.current(),null);assert(result.expiresAt);
 const login=h.calls.find(o=>o.url.endsWith('/auth/login'));
 assert.deepEqual(login.data,{username:'teacher-001',password:'test-only-password'});
 assert.equal(login.header.Authorization,undefined);assert.equal(login.header['X-ADMIN-CSRF'],'masked-csrf-fixture');
 assert.equal(login.header.Cookie,'ADMIN-XSRF-TOKEN=csrf-raw-fixture');assert.equal(h.writes.length,0);
});
test('已有学生会话时必须先显式切换身份，不能悄悄并用两套会话',async()=>{
 const h=harness();h.student.save({accessToken:'student-fixture',refreshToken:'student-refresh',user:{id:'101'}});
 await assert.rejects(h.admin.login('teacher-001','test-only-password'),e=>e.code==='ADMIN_SWITCH_REQUIRED');
 assert.equal(h.calls.length,0);assert.equal(h.student.hasSession(),true);
});
test('MFA校验通过后才保存管理员状态，Cookie和令牌不落地Storage',async()=>{
 const h=harness();await authenticate(h);assert.equal(h.admin.hasSession(),true);
 const mfa=h.calls.find(o=>o.url.endsWith('/mfa/verify'));
 assert.deepEqual(mfa.data,{challengeId:'C'.repeat(43),code:'123456'});assert.equal(mfa.header.Authorization,undefined);
 const current=h.admin.current();assert.equal(current.admin.id,'900');assert.deepEqual(current.permissions,['identity.review']);
 assert.equal(current.accessToken,undefined);assert.equal(current.refreshToken,undefined);assert.equal(h.writes.length,0);
 current.permissions.push('forged');assert.deepEqual(h.admin.current().permissions,['identity.review']);
});
test('未开始密码挑战或验证码不为6位时，不请求MFA',async()=>{
 const h=harness();await assert.rejects(h.admin.verify('123456'));assert.equal(h.calls.length,0);
 await h.admin.login('teacher-001','test-only-password');const before=h.calls.length;await assert.rejects(h.admin.verify('12abc6'));assert.equal(h.calls.length,before);
});
test('CSRF响应缺少匹配安全Cookie时不能提交密码',async()=>{
 const h=harness();global.wx.request=o=>{h.calls.push(o);queueMicrotask(()=>ok(o,{token:'masked',headerName:'X-ADMIN-CSRF'},[]));};
 await assert.rejects(h.admin.login('teacher-001','test-only-password'),e=>e.code==='ADMIN_CSRF_INVALID');
 assert.equal(h.calls.length,1);
});
test('审核写请求携带独立Bearer、CSRF与稳定幂等key，但不带刷新Cookie',async()=>{
 const h=harness();await authenticate(h);
 await h.admin.post('/student-verifications/12/decision',{decision:'approve',reason:'已核验',version:2},'review-fixture-stable-key');
 const o=h.calls.at(-1);assert.equal(o.header.Authorization,'Bearer '+ACCESS);assert.equal(o.header['X-ADMIN-CSRF'],'masked-csrf-fixture');
 assert.equal(o.header.Cookie,'ADMIN-XSRF-TOKEN=csrf-raw-fixture');assert.equal(o.header['Idempotency-Key'],'review-fixture-stable-key');
 assert.equal(h.student.hasSession(),false);
});
test('并发401只刷新一次，刷新用Cookie不带过期Bearer，写重试沿用key',async()=>{
 let refreshed=0;const h=harness(o=>{
  if(o.url.endsWith('/auth/token/refresh')){refreshed++;assert.equal(o.header.Authorization,undefined);assert(o.header.Cookie.includes('ADMIN-REFRESH='+REFRESH));return ok(o,{accessToken:NEXT,expiresIn:900,admin:identity,permissions:identity.permissions},['ADMIN-REFRESH='+'S'.repeat(43)+'; Path=/api/v1/admin/auth; Secure; HttpOnly; SameSite=Strict']);}
  if(o.header.Authorization==='Bearer '+ACCESS)return bad(o,401);
  ok(o,{accepted:true});
 });await authenticate(h);
 await Promise.all([h.admin.post('/student-verifications/12/decision',{decision:'approve',reason:'核验',version:0},'parallel-review-a'),h.admin.get('/me')]);
 assert.equal(refreshed,1);const pair=h.calls.filter(o=>o.url.endsWith('/student-verifications/12/decision'));
 assert.equal(pair.length,2);assert.equal(pair[0].header['Idempotency-Key'],pair[1].header['Idempotency-Key']);
});
test('刷新临时503不清理管理员状态，也不伪造审核成功',async()=>{
 const h=harness(o=>o.url.endsWith('/auth/token/refresh')?bad(o,503,'DEPENDENCY_UNAVAILABLE'):bad(o,401));await authenticate(h);
 await assert.rejects(h.admin.get('/me'),e=>e.status===503);assert.equal(h.admin.hasSession(),true);
});
test('退出后迟到的旧响应不能恢复管理员资料，也不能刷新或重放',async()=>{
 const pending=[];const h=harness(o=>pending.push(o));await authenticate(h);
 const result=h.admin.get('/me');const rejected=assert.rejects(result,e=>e.status===401);
 await new Promise(resolve=>setImmediate(resolve));h.admin.clear();bad(pending[0],401);await rejected;
 assert.equal(h.admin.hasSession(),false);assert.equal(h.calls.filter(o=>o.url.endsWith('/auth/token/refresh')).length,0);
});
test('只允许管理相对路径，拒绝越界或任意URL且不发送令牌',async()=>{
 const h=harness();await authenticate(h);const before=h.calls.length;
 for(const p of ['https://evil.example/','//evil.example/','/../me','/auth/../me','/me?leak=1'])await assert.rejects(h.admin.get(p));
 assert.equal(h.calls.length,before);
});
test('管理员退出后清除内存会话和Cookie，不能继续请求队列',async()=>{
 const h=harness();await authenticate(h);await h.admin.logout();assert.equal(h.admin.hasSession(),false);
 const before=h.calls.length;await assert.rejects(h.admin.get('/student-verifications',{status:'pending'}),e=>e.status===401);assert.equal(h.calls.length,before);
});

test('重新登录后旧成功响应不能回填，也不能清掉新管理员会话',async()=>{
 const pending=[];const h=harness(o=>pending.push(o));await authenticate(h);
 const old=h.admin.get('/me');const rejected=assert.rejects(old,e=>e.status===401);await new Promise(r=>setImmediate(r));
 h.admin.clear();await authenticate(h);ok(pending[0],Object.assign({},identity,{nickname:'旧资料'}));await rejected;assert.equal(h.admin.hasSession(),true);assert.equal(h.admin.current().admin.nickname,'本校审核员');
});
test('运行中修改管理服务器地址后，不向新域名发送旧凭据',async()=>{
 const h=harness();await authenticate(h);const before=h.calls.length;config.adminBaseUrl='https://different.example/api/v1';
 await assert.rejects(h.admin.get('/me'),e=>e.code==='ADMIN_SERVER_CHANGED');assert.equal(h.calls.length,before);
});
