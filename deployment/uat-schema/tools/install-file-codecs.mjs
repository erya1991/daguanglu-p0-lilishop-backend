import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url));
const digest=(bytes,type)=>crypto.createHash(type).update(bytes).digest('hex');
async function main() {
  const args=process.argv.slice(2);
  if(args.length!==4||args[0]!=='--root'||args[2]!=='--run-id'||!/^UAT_[0-9]{17}$/.test(args[3]))
    throw new Error('Usage: node install-file-codecs.mjs --root <root> --run-id <run>');
  const root=fs.realpathSync(args[1]),run=fs.realpathSync(path.join(root,'.uat-runtime',args[3]));
  const owner=JSON.parse(fs.readFileSync(path.join(run,'owner.json'),'utf8'));
  if(owner.integration!==true||owner.purpose!=='WECHAT_TEST'||owner.runId!==args[3]||owner.mode!=='MOCK'||owner.environment!=='TEST'||fs.realpathSync(owner.workspace)!==root)
    throw new Error('Owned test run required');
  const dir=path.join(run,'runtime/file-codecs');fs.mkdirSync(dir,{recursive:true,mode:0o700});
  if(fs.lstatSync(dir).isSymbolicLink()||fs.realpathSync(dir)!==dir)throw new Error('Codec directory must not redirect');
  const specs=JSON.parse(fs.readFileSync(path.join(here,'codec-dependencies.json'),'utf8')).artifacts,artifacts=[];
  for(const item of specs) {
    const file=item.artifact+'-'+item.version+'.jar',target=path.join(dir,file);
    let bytes;
    if(fs.existsSync(target)) {
      if(fs.lstatSync(target).isSymbolicLink())throw new Error('Codec file must not redirect');
      bytes=fs.readFileSync(target);
    } else if(process.env.P0_CODEC_CACHE) bytes=fs.readFileSync(path.join(process.env.P0_CODEC_CACHE,file));
    else {
      const url='https://repo.maven.apache.org/maven2/'+item.group.replaceAll('.','/')+'/'+item.artifact+'/'+item.version+'/'+file;
      const response=await fetch(url,{signal:AbortSignal.timeout(60000)});
      if(!response.ok)throw new Error('Codec download failed: '+file);
      bytes=Buffer.from(await response.arrayBuffer());
    }
    if(bytes.length>12_000_000||digest(bytes,'sha512')!==item.sha512)throw new Error('Codec digest mismatch: '+file);
    if(!fs.existsSync(target))fs.writeFileSync(target,bytes,{flag:'wx',mode:0o600});
    artifacts.push({file,sha256:digest(bytes,'sha256')});
  }
  const manifest={runId:owner.runId,artifacts},file=path.join(dir,'manifest.json');
  if(fs.existsSync(file)) {
    const old=JSON.parse(fs.readFileSync(file));if(JSON.stringify(old)!==JSON.stringify(manifest))throw new Error('Existing codec manifest conflicts');
  } else fs.writeFileSync(file,JSON.stringify(manifest,null,2)+'\n',{flag:'wx',mode:0o600});
  console.log('VERIFIED_FILE_CODECS count='+artifacts.length);
}
try{await main();}catch(e){console.error('FILE_CODEC_INSTALLATION_FAILED: '+e.message);process.exitCode=1;}
