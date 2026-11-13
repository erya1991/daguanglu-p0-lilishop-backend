import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url)),repo=path.resolve(here,'../../..');
const suites=JSON.parse(fs.readFileSync(path.join(here,'server-suites.json'),'utf8'));
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function call(name,args,options={}) {
  const result=spawnSync(name,args,{cwd:repo,encoding:'utf8',maxBuffer:8*1024*1024,...options});
  if(result.error||result.status!==0)throw new Error(name+' failed; build not admitted');
  return result.stdout?.trim();
}
function fingerprint() {
  const files=call('git',['ls-files','--cached','--others','--exclude-standard','-z']).split('\0').filter(Boolean).sort();
  const digest=crypto.createHash('sha256');
  for(const file of files) {
    const full=path.join(repo,file);
    if(!fs.statSync(full).isFile()||!fs.realpathSync(full).startsWith(repo+path.sep))throw new Error('Source path redirects: '+file);
    digest.update(file+'\0'+sha(fs.readFileSync(full))+'\n');
  }
  return digest.digest('hex');
}
function main() {
  if(process.platform==='win32')throw new Error('Run this build in Linux with JDK 21, Maven and Node');
  const args=process.argv.slice(2);
  if(args.length!==2||args[0]!=='--output'||!path.isAbsolute(args[1]))throw new Error('Usage: node build-test-server.mjs --output <new-absolute-directory-outside-repository>');
  const output=path.resolve(args[1]);
  if(output===repo||output.startsWith(repo+path.sep))throw new Error('Build output must be outside the source repository');
  fs.mkdirSync(output,{recursive:false,mode:0o700});
  const before=fingerprint(),startedAt=new Date().toISOString();
  const mvnArgs=['-pl','manager-api,buyer-api','-am','-DskipTests=false','-Dsurefire.failIfNoSpecifiedTests=false',
    '-Dtest='+suites.map(n=>n.split('.').at(-1)).join(','),'-Dp0.s1.outputRoot='+path.join(output,'work')];
  if(process.env.P0_MAVEN_SETTINGS)mvnArgs.push('-s',process.env.P0_MAVEN_SETTINGS);
  if(process.env.P0_MAVEN_CACHE)mvnArgs.push('-Dmaven.repo.local='+process.env.P0_MAVEN_CACHE);
  if(process.env.P0_MAVEN_OFFLINE==='true')mvnArgs.push('-o');
  // Explicit instrumentation avoids dynamic self-attach restrictions in build containers.
  if(process.env.P0_TEST_JAVA_AGENT)mvnArgs.push('-DargLine=-javaagent:'+process.env.P0_TEST_JAVA_AGENT);
  call(process.env.P0_MAVEN_COMMAND||'mvn',[...mvnArgs,'package'],{stdio:'inherit'});
  if(before!==fingerprint())throw new Error('Source changed during build');
  fs.mkdirSync(path.join(output,'artifacts'));fs.mkdirSync(path.join(output,'reports'));
  const reports=suites.map(name=>{
    const source=path.join(output,'work/framework/target/surefire-reports/TEST-'+name+'.xml'),bytes=fs.readFileSync(source);
    const xml=bytes.toString('utf8'),header=xml.match(/<testsuite\s+[^>]*>/)?.[0];
    const attr=key=>header?.match(new RegExp('\\b'+key+'="([^"]*)"'))?.[1];
    if(attr('name')!==name||Number(attr('tests'))<=0||['failures','errors','skipped'].some(k=>attr(k)!=='0')||
       /<(failure|error|skipped)(\s|>)/.test(xml))throw new Error('Required suite is incomplete: '+name);
    const target='reports/TEST-'+name+'.xml';fs.copyFileSync(source,path.join(output,target));
    return {name,path:target,sha256:sha(bytes),tests:Number(attr('tests'))};
  });
  const artifacts=['manager','buyer'].map(name=>{
    const dir=path.join(output,'work',name+'-api/target');
    const candidates=fs.readdirSync(dir).filter(file=>file.endsWith('.jar')&&!file.includes('sources')&&!file.includes('javadoc'));
    if(candidates.length!==1)throw new Error('Expected one packaged API artifact: '+name);
    const source=path.join(dir,candidates[0]),target='artifacts/'+name+'.jar';
    fs.copyFileSync(source,path.join(output,target));
    return {name,path:target,sha256:sha(fs.readFileSync(source))};
  });
  fs.writeFileSync(path.join(output,'build.json'),JSON.stringify({format:1,purpose:'WECHAT_TEST_MOCK',startedAt,finishedAt:new Date().toISOString(),
    gitCommit:call('git',['rev-parse','HEAD']),sourceFingerprint:before,artifacts,suites:reports,
    validation:'AUTOMATED_ONLY_NOT_REAL_WECHAT_DEVICE_OR_OWNER_ACCEPTANCE'},null,2)+'\n',{flag:'wx',mode:0o600});
  console.log('SERVER_BUILD_VERIFIED '+output);
}
try {main();}catch(e){console.error('SERVER_BUILD_FAILED: '+e.message);process.exitCode=1;}
