import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const compose=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../compose.test.yml');
function bindSource(source) {
  const desktop=process.platform==='win32' && /^\/run\/desktop\/mnt\/host\/([a-z])\/(.+)$/i.exec(source);
  return desktop ? path.win32.join(desktop[1]+':/',desktop[2]) : source;
}
function sameFile(a,b) {
  const left=fs.realpathSync(a),right=fs.realpathSync(b);
  return process.platform==='win32' ? left.toLowerCase()===right.toLowerCase() : left===right;
}
function command(args,input,env=process.env) {
  const result=spawnSync('docker',args,{input,encoding:'utf8',env,timeout:30000,maxBuffer:4*1024*1024});
  if(result.error || result.status!==0) {
    const sqlCode=String(result.stderr||'').match(/ERROR\s+(\d+)\s+\(([A-Z0-9]+)\)/);
    throw new Error('Docker '+args[0]+' failed ('+(result.error?.code||sqlCode?.[1]||result.status)+')');
  }
  return result.stdout.trim();
}
function main() {
  const args=process.argv.slice(2);
  if(args.length!==6 || args[0]!=='--root' || args[2]!=='--run-id' || args[4]!=='--project' ||
     !/^UAT_[0-9]{17}$/.test(args[3]) || !/^dgl-[a-z0-9-]{1,50}$/.test(args[5]))
    throw new Error('Usage: node register-test-resources.mjs --root <root> --run-id UAT_<17 digits> --project dgl-<name>');
  const root=fs.realpathSync(args[1]),runId=args[3],project=args[5],parent=fs.realpathSync(path.join(root,'.uat-runtime'));
  const run=fs.realpathSync(path.join(parent,runId));
  if(path.dirname(run)!==parent)throw new Error('Invalid owned run');
  const owner=JSON.parse(fs.readFileSync(path.join(run,'owner.json'),'utf8'));
  if(owner.integration!==true || owner.purpose!=='WECHAT_TEST' || owner.runId!==runId ||
     owner.mode!=='MOCK' || owner.environment!=='TEST' || fs.realpathSync(owner.workspace)!==root)
    throw new Error('Owned test run required');
  const mysqlPort=owner.mysqlPort||19706,redisPort=owner.redisPort||19779;
  if(![mysqlPort,redisPort].every(p=>Number.isInteger(p)&&p>=1024&&p<=65535)||mysqlPort===redisPort)
    throw new Error('Invalid registered test resource ports');
  const env={...process.env,P0_RUN_DIR:run,P0_RUN_ID:runId,P0_MYSQL_PORT:String(mysqlPort),P0_REDIS_PORT:String(redisPort)};
  const composeArgs=['compose','-p',project,'-f',compose];
  const ids={};
  const imageIds={},volumeNames={};
  for(const [name,inside,outside] of [['mysql','3306/tcp',String(mysqlPort)],['redis','6379/tcp',String(redisPort)]]) {
    const id=command([...composeArgs,'ps','-q',name],undefined,env);
    if(!/^[a-f0-9]{64}$/.test(id))throw new Error('Expected one running owned '+name+' container');
    const info=JSON.parse(command(['inspect',id]))[0],labels=info.Config.Labels||{};
    const port=info.HostConfig.PortBindings?.[inside];
    if(!info.State.Running || labels['dgl.uat.run']!==runId || labels['dgl.uat.purpose']!=='wechat-test' ||
       labels['com.docker.compose.project']!==project || labels['com.docker.compose.service']!==name ||
       port?.length!==1 || port[0].HostIp!=='127.0.0.1' || port[0].HostPort!==outside)
      throw new Error('Container identity or port mismatch: '+name);
    const dest=name==='mysql'?'/var/lib/mysql':'/data';
    const mount=info.Mounts.find(m=>m.Destination===dest && m.Type==='volume');
    if(!mount)throw new Error('Owned data volume required: '+name);
    const volume=JSON.parse(command(['volume','inspect',mount.Name]))[0];
    if(volume.Labels?.['dgl.uat.run']!==runId || volume.Labels?.['dgl.uat.purpose']!=='wechat-test' ||
       volume.Labels?.['com.docker.compose.project']!==project)throw new Error('Data volume ownership mismatch');
    const secretDest=name==='mysql'?'/run/secrets/mysql-root.cnf':'/run/secrets/redis.conf';
    const secret=info.Mounts.find(m=>m.Destination===secretDest && m.Type==='bind');
    const secretName=name==='mysql'?'mysql-root-container.cnf':'redis.conf';
    if(!secret || !sameFile(bindSource(secret.Source),path.join(run,'private',secretName)) || secret.RW)
      throw new Error('Private resource configuration mismatch');
    ids[name]=id;
    imageIds[name]=info.Image;volumeNames[name]=mount.Name;
  }
  // MySQL ignores world-writable bind-mounted option files on Docker Desktop.
  // Copy to a private container-local file; no password enters arguments or output.
  const mysql=sql=>command(['exec','-i',ids.mysql,'sh','-c',
    'set -eu; umask 077; cfg=$(mktemp /tmp/dgl-uat-client.XXXXXX); trap \'rm -f "$cfg"\' EXIT; cat /run/secrets/mysql-root.cnf > "$cfg"; chmod 600 "$cfg"; mysql --defaults-extra-file="$cfg" --batch --raw --skip-column-names'],sql+'\n');
  const uuid=mysql('SELECT @@server_uuid;').toLowerCase();
  if(!/^[0-9a-f-]{36}$/.test(uuid) ||
     ['dc2fc878-dbf0-11f1-9c13-5ac1b87338ef','bffc3648-d139-11f1-afc9-9617b0124629','8d54bca3-ccfc-11f1-8a9b-1e861828a1bc'].includes(uuid))
    throw new Error('Invalid or protected database server UUID');
  const registry=path.join(run,'runtime/resources.json');
  if(fs.existsSync(registry)) {
    const old=JSON.parse(fs.readFileSync(registry,'utf8'));
    if(old.runId!==runId || old.mysql!==ids.mysql || old.redis!==ids.redis || old.mysqlUuid!==uuid ||
       old.state!=='INFRA_READY_SCHEMA_PENDING')throw new Error('Existing or incomplete resource registry requires inspection');
    console.log('ALREADY_REGISTERED run='+runId);return;
  }
  const empty=mysql("SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('dgl_p0_uat','dgl_p0_uat_mock')) + (SELECT COUNT(*) FROM mysql.user WHERE User IN ('uat_app','uat_mock'));");
  if(empty!=='0')throw new Error('Database or application account already exists; refusing overwrite');
  const db=JSON.parse(fs.readFileSync(path.join(run,'private/db.json'),'utf8'));
  if(db.runId!==runId || !/^[A-Za-z0-9_-]{43}$/.test(db.app) || !/^[A-Za-z0-9_-]{43}$/.test(db.mock))
    throw new Error('Private test database credentials invalid');
  const ping=command(['exec',ids.redis,'sh','-c',
    "REDISCLI_AUTH=$(awk '/^requirepass /{print $2}' /run/secrets/redis.conf) redis-cli ping"]);
  if(ping!=='PONG')throw new Error('Owned Redis authentication failed');
  const record={runId,project,mysql:ids.mysql,redis:ids.redis,mysqlUuid:uuid,mysqlPort,redisPort,imageIds,volumeNames,state:'PROVISIONING'};
  fs.writeFileSync(registry,JSON.stringify(record,null,2)+'\n',{flag:'wx',mode:0o600});
  // Values are generated base64url strings; they never enter process arguments or logs.
  mysql("CREATE DATABASE dgl_p0_uat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"+
    "CREATE DATABASE dgl_p0_uat_mock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"+
    "CREATE USER 'uat_app'@'%' IDENTIFIED BY '"+db.app+"';\n"+
    "CREATE USER 'uat_mock'@'%' IDENTIFIED BY '"+db.mock+"';\n"+
    "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE TEMPORARY TABLES,EXECUTE ON dgl_p0_uat.* TO 'uat_app'@'%';\n"+
    "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE TEMPORARY TABLES,EXECUTE ON dgl_p0_uat_mock.* TO 'uat_mock'@'%';");
  record.state='INFRA_READY_SCHEMA_PENDING';
  const temporary=registry+'.new';
  fs.writeFileSync(temporary,JSON.stringify(record,null,2)+'\n',{flag:'wx',mode:0o600});
  fs.renameSync(temporary,registry);
  console.log('REGISTERED run='+runId+' mysqlUuid='+uuid);
}
try {main();} catch(error) {console.error('TEST_RESOURCE_REGISTRATION_FAILED: '+error.message);process.exitCode=1;}
