import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
function main() {
  const args=process.argv.slice(2);
  if(args.length!==4||args[0]!=='--root'||args[2]!=='--run-id'||!/^UAT_[0-9]{17}$/.test(args[3]))throw new Error('Usage: node backup-test-server.mjs --root <root> --run-id <run>');
  const root=fs.realpathSync(args[1]),run=fs.realpathSync(path.join(root,'.uat-runtime',args[3]));
  const read=file=>JSON.parse(fs.readFileSync(path.join(run,file),'utf8')),owner=read('owner.json'),r=read('runtime/resources.json');
  if(owner.integration!==true||owner.purpose!=='WECHAT_TEST'||owner.mode!=='MOCK'||owner.environment!=='TEST'||owner.runId!==args[3]||
     fs.realpathSync(owner.workspace)!==root||r.runId!==owner.runId||!r.mysql?.match(/^[a-f0-9]{64}$/))throw new Error('Owned TEST/MOCK resource registry required');
  if(fs.existsSync(path.join(run,'runtime/apis.json'))&&read('runtime/apis.json').state!=='STOPPED')throw new Error('Stop owned APIs and workers before the consistent backup');
  const inspected=spawnSync('docker',['inspect',r.mysql],{encoding:'utf8',timeout:10000});
  if(inspected.status!==0)throw new Error('Owned MySQL unavailable');
  const container=JSON.parse(inspected.stdout)[0],port=container.HostConfig.PortBindings?.['3306/tcp'];
  if(container.Config.Labels?.['dgl.uat.run']!==owner.runId||container.Config.Labels?.['dgl.uat.purpose']!=='wechat-test'||
     port?.length!==1||port[0].HostIp!=='127.0.0.1'||port[0].HostPort!==String(r.mysqlPort))throw new Error('MySQL ownership mismatch');
  const parent=path.join(run,'backups');fs.mkdirSync(parent,{recursive:true,mode:0o700});
  const backup=path.join(parent,new Date().toISOString().replaceAll(/[:.]/g,'-'));fs.mkdirSync(backup,{mode:0o700});
  const sql=path.join(backup,'business-and-mock.sql'),fd=fs.openSync(sql,'wx',0o600);
  const command='set -eu; umask 077; cfg=$(mktemp /tmp/dgl-uat-dump.XXXXXX); trap \'rm -f "$cfg"\' EXIT; cat /run/secrets/mysql-root.cnf > "$cfg"; chmod 600 "$cfg"; mysqldump --defaults-extra-file="$cfg" --single-transaction --routines --events --triggers --set-gtid-purged=OFF --databases dgl_p0_uat dgl_p0_uat_mock';
  const result=spawnSync('docker',['exec',r.mysql,'sh','-c',command],{stdio:['ignore',fd,'pipe'],encoding:'utf8',timeout:300000,maxBuffer:4*1024*1024});
  fs.closeSync(fd);
  if(result.error||result.status!==0)throw new Error('Database dump failed; incomplete backup directory preserved');
  function rejectLinks(dir) {
    for(const entry of fs.readdirSync(dir,{withFileTypes:true})) {
      const file=path.join(dir,entry.name);if(entry.isSymbolicLink())throw new Error('Backup refuses redirected files');
      if(entry.isDirectory())rejectLinks(file);
    }
  }
  for(const dir of ['files','private']) {
    rejectLinks(path.join(run,dir));fs.cpSync(path.join(run,dir),path.join(backup,dir),{recursive:true,errorOnExist:true,force:false,preserveTimestamps:true});
  }
  fs.mkdirSync(path.join(backup,'runtime'),{mode:0o700});
  for(const name of ['resources.json','server-build.json','api-settings.json'])
    if(fs.existsSync(path.join(run,'runtime',name)))fs.copyFileSync(path.join(run,'runtime',name),path.join(backup,'runtime',name),fs.constants.COPYFILE_EXCL);
  fs.copyFileSync(path.join(run,'owner.json'),path.join(backup,'owner.json'),fs.constants.COPYFILE_EXCL);
  const files=[];
  function manifest(dir) {
    for(const entry of fs.readdirSync(dir,{withFileTypes:true})) {
      const file=path.join(dir,entry.name);if(entry.isDirectory())manifest(file);
      else files.push({path:path.relative(backup,file).split(path.sep).join('/'),sha256:crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')});
    }
  }
  manifest(backup);
  fs.writeFileSync(path.join(backup,'backup.json'),JSON.stringify({format:1,runId:owner.runId,mysqlUuid:r.mysqlUuid,mode:'MOCK',createdAt:new Date().toISOString(),files,
    restorePolicy:'Independent target only; register its new UUID, preserve funds facts, do not restore live sessions or old PID metadata'},null,2)+'\n',{flag:'wx',mode:0o600});
  console.log('BACKUP_COMPLETE '+backup);
}
try{main();}catch(e){console.error('BACKUP_FAILED: '+e.message);process.exitCode=1;}
