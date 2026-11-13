import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function main() {
  const args=process.argv.slice(2);
  if(args.length!==4||args[0]!=='--root'||args[2]!=='--run-id'||!/^UAT_[0-9]{17}$/.test(args[3]))
    throw new Error('Usage: node configure-test-server.mjs --root <root> --run-id <run>');
  const root=fs.realpathSync(args[1]),runId=args[3],run=fs.realpathSync(path.join(root,'.uat-runtime',runId));
  if(process.platform!=='win32')for(const name of ['private','private/wechat.json','private/db.json'])
    if((fs.statSync(path.join(run,name)).mode&0o077)!==0)throw new Error('Private files must not be readable by group or others');
  const read=relative=>JSON.parse(fs.readFileSync(path.join(run,relative),'utf8'));
  const owner=read('owner.json'),r=read('runtime/resources.json'),build=read('runtime/server-build.json'),db=read('private/db.json'),wx=read('private/wechat.json');
  if(owner.integration!==true||owner.purpose!=='WECHAT_TEST'||owner.mode!=='MOCK'||owner.environment!=='TEST'||owner.runId!==runId||
     fs.realpathSync(owner.workspace)!==root||path.dirname(run)!==fs.realpathSync(path.join(root,'.uat-runtime'))||
     [r,build,db].some(v=>v.runId!==runId))throw new Error('Owned server resources required');
  if(!/^wx[a-f0-9]{16}$/.test(wx.residentAppId)||!/^wx[a-f0-9]{16}$/.test(wx.merchantAppId)||wx.residentAppId===wx.merchantAppId||
     !/^[A-Za-z0-9]{32}$/.test(wx.residentAppSecret)||!/^[A-Za-z0-9]{32}$/.test(wx.merchantAppSecret))throw new Error('Two distinct real AppIDs and server-side AppSecrets are required');
  const origin=new URL(wx.publicOrigin);
  if(origin.protocol!=='https:'||origin.username||origin.password||origin.search||origin.hash||origin.pathname!=='/'||origin.origin!==wx.publicOrigin)
    throw new Error('publicOrigin must be an HTTPS origin without a path');
  const managerPort=wx.managerPort||19790,buyerPort=wx.buyerPort||19791;
  if(![managerPort,buyerPort,r.mysqlPort,r.redisPort].every(p=>Number.isInteger(p)&&p>=1024&&p<=65535)||
     new Set([managerPort,buyerPort,r.mysqlPort,r.redisPort]).size!==4)throw new Error('Distinct valid local service ports required');
  if(/[\r\n]/.test(run))throw new Error('Invalid runtime path');
  const settingsFile=path.join(run,'runtime/api-settings.json');
  if(fs.existsSync(path.join(run,'runtime/apis.json'))&&read('runtime/apis.json').state!=='STOPPED')
    throw new Error('Stop owned APIs before reconfiguring');
  const scope=path.join(run,'runtime/recovery-scope.json');
  if(!fs.existsSync(scope))fs.writeFileSync(scope,JSON.stringify({runId,evidence:'Registered company real-WeChat TEST/MOCK run; current persisted member scope and approved merchant application',dynamicOwnedUat:true,
    admittedAt:new Date().toISOString(),members:[],stores:[],skus:[]},null,2)+'\n',{flag:'wx',mode:0o600});
  const common={
    'server.address':'127.0.0.1','spring.datasource.url':'jdbc:mysql://127.0.0.1:'+r.mysqlPort+'/dgl_p0_uat?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true',
    'spring.datasource.username':'uat_app','spring.datasource.password':db.app,'spring.datasource.druid.initial-size':2,'spring.datasource.druid.max-active':32,
    'spring.data.redis.host':'127.0.0.1','spring.data.redis.port':r.redisPort,'spring.data.redis.password':db.redis,'spring.data.redis.database':0,
    'spring.data.redis.timeout':'3000ms','lili.jwt-setting.secret-key':fs.readFileSync(path.join(run,'private/session-signing-key'),'utf8').trim(),
    'lili.runtime.mode':'local','lili.system.isDemoSite':false,'lili.mqtt.enabled':false,'lili.message.consumer.enabled':false,'lili.promotion.delay-listener.enabled':false,
    'spring.quartz.auto-startup':false,'lili.schedule.every-minute-cron':'-','lili.schedule.every-hour-cron':'-','lili.schedule.every-day-cron':'-',
    'lili.stage0-session.resident-application':wx.residentAppId,'lili.stage0-session.merchant-application':wx.merchantAppId,'lili.stage0-session.manager-application':runId+'_manager',
    'lili.stage0-resident-wechat.schema-ready':true,'lili.stage0-merchant-wechat.schema-ready':true,
    'lili.stage0-resident-wechat.enabled':true,'lili.stage0-merchant-wechat.enabled':true,
    'lili.stage0-resident-wechat.app-secret':wx.residentAppSecret,'lili.stage0-merchant-wechat.app-secret':wx.merchantAppSecret,
    'lili.stage0-resident-profile.schema-ready':true,'lili.stage0-resident-profile.history-ready':true,
    'lili.stage0-resident-profile.upload-enabled':false,'lili.stage0-resident-profile.phone-enabled':false,'lili.verification-code.loginBypassEnabled':false,
    'lili.local-file.path':path.join(run,'files/public'),'springdoc.api-docs.enabled':false,'springdoc.swagger-ui.enabled':false,
    'p0.stage1.enabled':false,'p0.stage1.workers-enabled':false,'p0.stage2.enabled':true,'p0.stage2.stage3-resource-mode':false,'p0.stage2.stage45-resource-mode':false,
    'p0.stage2.uat-resource-mode':true,'p0.stage2.server-uat-mode':true,'p0.stage2.server-uat-root':root,
    'p0.stage2.server-mysql-port':r.mysqlPort,'p0.stage2.server-redis-port':r.redisPort,
    'p0.uat.enabled':true,'p0.uat.local-login-enabled':false,'p0.stage2.recovery-scope-file':scope,'p0.stage2.recovery-scope-sha256':sha(scope),
    'p0.stage2.web-ui-enabled':true,'p0.stage2.environment':'TEST','p0.stage2.mode':'MOCK','p0.stage2.run-id':runId,'p0.stage2.mysql-uuid':r.mysqlUuid,
    'p0.stage2.database':'dgl_p0_uat','p0.stage2.file-root':path.join(run,'files'),'p0.stage2.manager-role-ids':'',
    'p0.stage2.mock-jdbc-url':'jdbc:mysql://127.0.0.1:'+r.mysqlPort+'/dgl_p0_uat_mock?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
    'p0.stage2.mock-username':'uat_mock','p0.stage2.mock-password':db.mock,'p0.stage2.mock-control-key':db.mockControl,
    'p0.stage3.enabled':true,'p0.stage3.writes-enabled':true,'p0.stage3.credentials-enabled':true,
    'p0.stage3.key-file':path.join(run,'private/credential-root-key'),
    'p0.stage45.enabled':true,'p0.stage45.writes-enabled':true,'p0.stage45.channel-writes-enabled':true
  };
  const stamp=Date.now(),apis={runId,version:build.version,publicOrigin:wx.publicOrigin,sourceFingerprint:build.sourceFingerprint};
  for(const name of ['manager','buyer']) {
    const artifact=build.artifacts.find(a=>a.name===name);
    if(!artifact||sha(artifact.path)!==artifact.sha256||!fs.realpathSync(artifact.path).startsWith(path.join(run,'build')+path.sep))throw new Error('Installed artifact changed');
    const values={...common,'server.port':name==='manager'?managerPort:buyerPort,'logging.file.path':path.join(run,'logs',name),
      'p0.stage2.workers-enabled':name==='manager','p0.stage3.workers-enabled':name==='manager','p0.stage45.workers-enabled':name==='manager'};
    const config=path.join(run,'private',name+'-'+stamp+'.properties');
    const escape=v=>String(v).replaceAll('\\','\\\\').replaceAll('\n','\\n').replaceAll('\r','\\r');
    fs.writeFileSync(config,Object.entries(values).map(([k,v])=>k+'='+escape(v)).join('\n')+'\n',{flag:'wx',mode:0o600});
    apis[name]={jar:artifact.path,sha256:artifact.sha256,configuration:config,configurationSha256:sha(config),port:values['server.port']};
  }
  const temp=settingsFile+'.new';fs.writeFileSync(temp,JSON.stringify(apis,null,2)+'\n',{flag:'wx',mode:0o600});fs.renameSync(temp,settingsFile);
  console.log('CONFIGURED '+build.version+'; AppSecrets remain private');
}
try{main();}catch(e){console.error('SERVER_CONFIGURATION_FAILED: '+e.message);process.exitCode=1;}
