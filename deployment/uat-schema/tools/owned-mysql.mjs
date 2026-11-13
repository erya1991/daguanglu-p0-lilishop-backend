import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
function main() {
  const root=fs.realpathSync(process.env.P0_TEST_ROOT||''),id=process.env.P0_TEST_RUN_ID||'';
  if(!/^UAT_[0-9]{17}$/.test(id))throw new Error('Owned run ID required');
  const run=fs.realpathSync(path.join(root,'.uat-runtime',id)),owner=JSON.parse(fs.readFileSync(path.join(run,'owner.json')));
  const r=JSON.parse(fs.readFileSync(path.join(run,'runtime/resources.json')));
  if(owner.integration!==true||owner.purpose!=='WECHAT_TEST'||owner.environment!=='TEST'||owner.mode!=='MOCK'||owner.runId!==id||
     fs.realpathSync(owner.workspace)!==root||r.runId!==id||!r.mysql?.match(/^[a-f0-9]{64}$/))throw new Error('Owned MySQL registration required');
  const check=spawnSync('docker',['inspect',r.mysql],{encoding:'utf8',timeout:10000});
  if(check.status!==0)throw new Error('Owned MySQL unavailable');
  const info=JSON.parse(check.stdout)[0],binding=info.HostConfig.PortBindings?.['3306/tcp'];
  if(!info.State.Running||info.Config.Labels?.['dgl.uat.run']!==id||info.Config.Labels?.['dgl.uat.purpose']!=='wechat-test'||
     binding?.length!==1||binding[0].HostIp!=='127.0.0.1'||binding[0].HostPort!==String(r.mysqlPort))throw new Error('MySQL container ownership mismatch');
  const forwarded=[];
  for(const argument of process.argv.slice(2)) {
    if(argument.startsWith('--defaults-extra-file=')) {
      const selected=fs.realpathSync(argument.slice('--defaults-extra-file='.length));
      if(selected!==fs.realpathSync(path.join(run,'private/mysql-root.cnf')))throw new Error('Wrong client configuration');
    } else if(['--skip-force','--batch','--raw','--skip-column-names'].includes(argument)||
      /^--connect-timeout=[0-9]+$/.test(argument)||/^--database=dgl_p0_uat(?:_mock)?$/.test(argument)||argument.startsWith('--execute='))forwarded.push(argument);
    else throw new Error('Unsupported owned MySQL argument');
  }
  const script='set -eu; umask 077; cfg=$(mktemp /tmp/dgl-uat-client.XXXXXX); trap \'rm -f "$cfg"\' EXIT; cat /run/secrets/mysql-root.cnf > "$cfg"; chmod 600 "$cfg"; mysql --defaults-extra-file="$cfg" "$@"';
  const result=spawnSync('docker',['exec','-i',r.mysql,'sh','-c',script,'owned-mysql',...forwarded],
    {input:forwarded.some(a=>a.startsWith('--execute='))?undefined:fs.readFileSync(0),encoding:'utf8',maxBuffer:16*1024*1024,timeout:120000});
  if(result.stdout)process.stdout.write(result.stdout);
  if(result.stderr)process.stderr.write(result.stderr);
  if(result.error)throw new Error('Owned MySQL command did not complete');
  process.exitCode=result.status??1;
}
try{main();}catch(e){console.error('OWNED_MYSQL_FAILED: '+e.message);process.exitCode=1;}
