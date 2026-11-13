import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import {spawn,spawnSync} from 'node:child_process';
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function running(item) {
  if(!item||item.pid===undefined)return false;
  if(!Number.isSafeInteger(item.pid))throw new Error('Invalid owned PID record');
  if(item.bootId&&item.bootId!==fs.readFileSync('/proc/sys/kernel/random/boot_id','utf8').trim())return false;
  const proc='/proc/'+item.pid;
  if(!fs.existsSync(proc))return false;
  if(/^State:\s+Z/m.test(fs.readFileSync(proc+'/status','utf8')))return false;
  const argv=fs.readFileSync(proc+'/cmdline','utf8').split('\0');
  if(!argv.includes(item.jar)||!argv.includes('--spring.config.additional-location=file:'+item.configuration))return false;
  if(sha(item.jar)!==item.sha256||sha(item.configuration)!==item.configurationSha256)
    throw new Error('Owned artifact or configuration changed');
  return true;
}
async function health(item) {
  try {
    const response=await fetch('http://127.0.0.1:'+item.port+'/actuator/health',{signal:AbortSignal.timeout(3000)});
    return response.status===200&&(await response.json()).status==='UP';
  }catch{return false;}
}
async function main() {
  if(process.platform!=='linux')throw new Error('This application lifecycle uses Linux /proc');
  const args=process.argv.slice(2);
  if(args.length!==5||!['start','stop','status'].includes(args[0])||args[1]!=='--root'||args[3]!=='--run-id'||!/^UAT_[0-9]{17}$/.test(args[4]))
    throw new Error('Usage: node api-runtime.mjs start|stop|status --root <root> --run-id <run>');
  const action=args[0],root=fs.realpathSync(args[2]),runId=args[4],run=fs.realpathSync(path.join(root,'.uat-runtime',runId));
  const owner=JSON.parse(fs.readFileSync(path.join(run,'owner.json'),'utf8'));
  if(owner.purpose!=='WECHAT_TEST'||owner.integration!==true||owner.runId!==runId||owner.mode!=='MOCK'||
     owner.environment!=='TEST'||fs.realpathSync(owner.workspace)!==root||path.dirname(run)!==fs.realpathSync(path.join(root,'.uat-runtime')))
    throw new Error('Owned TEST/MOCK run required');
  const file=path.join(run,'runtime/apis.json'),old=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):null;
  if(old&&old.runId!==runId)throw new Error('Wrong application registry');
  if(action!=='start') {
    if(!old){console.log('NOT_STARTED');return;}
    const items=['manager','buyer'].map(name=>({name,...old[name]}));
    const active=items.filter(item=>running(item)); // Validate both before any mutation.
    if(action==='stop') {
      for(const item of active)process.kill(item.pid,'SIGTERM');
      const deadline=Date.now()+30000;
      while(Date.now()<deadline&&active.some(running))await sleep(500);
      if(active.some(running))throw new Error('Owned APIs are still stopping; no force kill was used');
      old.state='STOPPED';fs.writeFileSync(file,JSON.stringify(old,null,2)+'\n',{mode:0o600});
    }
    const services=[];
    for(const item of items){const live=running(item);services.push({name:item.name,pid:item.pid,port:item.port,running:live,healthy:live&&await health(item)});}
    console.log(JSON.stringify({runId,state:old.state,services}));return;
  }
  if(old&&['manager','buyer'].some(name=>running(old[name])))throw new Error('Owned APIs already run; inspect status first');
  const settings=JSON.parse(fs.readFileSync(path.join(run,'runtime/api-settings.json'),'utf8'));
  if(settings.runId!==runId)throw new Error('Wrong API settings');
  for(const name of ['manager','buyer']) {
    const item=settings[name];
    if(!fs.realpathSync(item.jar).startsWith(path.join(run,'build')+path.sep)||
       !fs.realpathSync(item.configuration).startsWith(path.join(run,'private')+path.sep)||
       sha(item.jar)!==item.sha256||sha(item.configuration)!==item.configurationSha256)throw new Error('Configured build changed');
    await new Promise((resolve,reject)=>{const probe=net.createServer();probe.once('error',reject);probe.listen(item.port,'127.0.0.1',()=>probe.close(resolve));});
  }
  const java=process.env.P0_JAVA_COMMAND||'java',version=spawnSync(java,['-version'],{encoding:'utf8'});
  if(version.status!==0||!/version "21[."]/.test(String(version.stderr)+String(version.stdout)))throw new Error('JDK/JRE 21 is required');
  const record={runId,version:settings.version,state:'STARTING',startedAt:new Date().toISOString()};
  for(const name of ['manager','buyer']) {
    const item=settings[name],stamp=Date.now(),stdout=path.join(run,'logs',name+'-'+stamp+'.out.log'),stderr=path.join(run,'logs',name+'-'+stamp+'.err.log');
    const out=fs.openSync(stdout,'wx',0o600),err=fs.openSync(stderr,'wx',0o600);
    const argv=['-Xms128m','-Xmx768m','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.io.tmpdir='+path.join(run,'tmp'),
      '-Dp0.s1.codecRoot='+path.join(run,'runtime/file-codecs'),'-jar',item.jar,'--spring.config.additional-location=file:'+item.configuration];
    const child=spawn(java,argv,{cwd:run,detached:true,stdio:['ignore',out,err]});
    await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});
    fs.closeSync(out);fs.closeSync(err);child.unref();
    record[name]={...item,pid:child.pid,bootId:fs.readFileSync('/proc/sys/kernel/random/boot_id','utf8').trim(),stdout,stderr};
    fs.writeFileSync(file,JSON.stringify(record,null,2)+'\n',{mode:0o600});
  }
  console.log('STARTING run='+runId);
  const deadline=Date.now()+180000;
  while(Date.now()<deadline) {
    if(['manager','buyer'].some(name=>!running(record[name])))throw new Error('An owned API exited; inspect its private logs');
    if((await Promise.all(['manager','buyer'].map(name=>health(record[name])))).every(Boolean)) {
      record.state='RUNNING';fs.writeFileSync(file,JSON.stringify(record,null,2)+'\n',{mode:0o600});
      console.log(JSON.stringify({runId,state:'RUNNING',ports:[record.manager.port,record.buyer.port],version:record.version}));return;
    }
    await sleep(1000);
  }
  throw new Error('APIs not healthy; registered processes and logs were preserved');
}
try{await main();}catch(e){console.error('API_RUNTIME_FAILED: '+e.message);process.exitCode=1;}
