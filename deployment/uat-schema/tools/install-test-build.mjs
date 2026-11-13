import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url));
const required=JSON.parse(fs.readFileSync(path.join(here,'server-suites.json'),'utf8'));
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function main() {
  const args=process.argv.slice(2);
  if(args.length!==6||args[0]!=='--root'||args[2]!=='--run-id'||args[4]!=='--bundle'||!/^UAT_[0-9]{17}$/.test(args[3]))
    throw new Error('Usage: node install-test-build.mjs --root <root> --run-id <run> --bundle <verified-build>');
  const root=fs.realpathSync(args[1]),run=fs.realpathSync(path.join(root,'.uat-runtime',args[3])),bundle=fs.realpathSync(args[5]);
  const owner=JSON.parse(fs.readFileSync(path.join(run,'owner.json'))),resources=JSON.parse(fs.readFileSync(path.join(run,'runtime/resources.json')));
  const apiRegistry=path.join(run,'runtime/apis.json');
  if(fs.existsSync(apiRegistry)&&JSON.parse(fs.readFileSync(apiRegistry)).state!=='STOPPED')
    throw new Error('Stop and reconcile owned APIs before installing another build');
  if(owner.purpose!=='WECHAT_TEST'||owner.integration!==true||owner.runId!==args[3]||owner.mode!=='MOCK'||owner.environment!=='TEST'||
     fs.realpathSync(owner.workspace)!==root||path.dirname(run)!==fs.realpathSync(path.join(root,'.uat-runtime'))||resources.runId!==args[3])throw new Error('Owned test run required');
  const build=JSON.parse(fs.readFileSync(path.join(bundle,'build.json')));
  if(build.format!==1||build.purpose!=='WECHAT_TEST_MOCK'||!/^[a-f0-9]{64}$/.test(build.sourceFingerprint)||
     !Array.isArray(build.artifacts)||build.artifacts.length!==2||new Set(build.artifacts.map(a=>a.name)).size!==2||
     build.artifacts.some(a=>!['manager','buyer'].includes(a.name))||
     build.suites.length!==required.length||new Set(build.suites.map(s=>s.name)).size!==required.length||
     required.some(name=>!build.suites.some(s=>s.name===name&&s.tests>0)))throw new Error('Verified build manifest required');
  const safe=entry=>{
    const file=fs.realpathSync(path.resolve(bundle,entry.path));
    if(!file.startsWith(bundle+path.sep)||sha(fs.readFileSync(file))!==entry.sha256)throw new Error('Build evidence or artifact changed');
    return file;
  };
  build.artifacts.forEach(safe);build.suites.forEach(safe);
  const version='server-'+build.sourceFingerprint.slice(0,12)+'-'+sha(Buffer.from(JSON.stringify(build.artifacts))).slice(0,8);
  const artifactDir=path.join(run,'build',version),reportDir=path.join(run,'evidence',version);
  if(fs.existsSync(artifactDir)||fs.existsSync(reportDir))throw new Error('This version is already installed; existing artifacts are preserved');
  fs.mkdirSync(artifactDir,{mode:0o700});fs.mkdirSync(reportDir,{mode:0o700});
  for(const artifact of build.artifacts)fs.copyFileSync(safe(artifact),path.join(artifactDir,artifact.name+'.jar'),fs.constants.COPYFILE_EXCL);
  const suites=build.suites.map(s=>{
    const target=path.join(reportDir,'TEST-'+s.name+'.xml');fs.copyFileSync(safe(s),target,fs.constants.COPYFILE_EXCL);
    return {...s,path:path.relative(run,target).split(path.sep).join('/')};
  });
  const proof={format:1,purpose:'WECHAT_TEST_MOCK',runId:owner.runId,mysqlUuid:resources.mysqlUuid,
    sourceFingerprint:build.sourceFingerprint,credentialPolicy:'S3_FIXED_128_AESGCM_HMAC_120_V1',
    artifactHashes:build.artifacts.map(a=>a.sha256),suites,validation:build.validation};
  const record={version,runId:owner.runId,gitCommit:build.gitCommit,sourceFingerprint:build.sourceFingerprint,
    artifacts:build.artifacts.map(a=>({...a,path:path.join(artifactDir,a.name+'.jar')})),suites,installedAt:new Date().toISOString()};
  const proofFile=path.join(run,'private/server-admission.json'),versionFile=path.join(run,'runtime/server-build.json');
  // Versions have unique directories; replacement only changes the selected manifest.
  for(const [file,value] of [[proofFile,proof],[versionFile,record]]) {
    const temp=file+'.new';fs.writeFileSync(temp,JSON.stringify(value,null,2)+'\n',{flag:'wx',mode:0o600});fs.renameSync(temp,file);
  }
  console.log('INSTALLED '+version);
}
try{main();}catch(e){console.error('BUILD_INSTALLATION_FAILED: '+e.message);process.exitCode=1;}
