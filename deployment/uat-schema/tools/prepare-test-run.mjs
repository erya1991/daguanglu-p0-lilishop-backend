import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

function main() {
  const args=process.argv.slice(2);
  if(args.length!==4 || args[0]!=='--root' || args[2]!=='--run-id' || !/^UAT_[0-9]{17}$/.test(args[3]))
    throw new Error('Usage: node prepare-test-run.mjs --root <absolute-existing-directory> --run-id UAT_<17 digits>');
  if(!path.isAbsolute(args[1]))throw new Error('Test root must be absolute');
  const mysqlPort=Number(process.env.P0_MYSQL_PORT||19706),redisPort=Number(process.env.P0_REDIS_PORT||19779);
  if(![mysqlPort,redisPort].every(p=>Number.isInteger(p)&&p>=1024&&p<=65535)||mysqlPort===redisPort)
    throw new Error('Distinct valid test resource ports required');
  const root=fs.realpathSync(args[1]), parent=path.join(root,'.uat-runtime'), runId=args[3];
  if(!fs.existsSync(parent))fs.mkdirSync(parent,{mode:0o700});
  if(fs.lstatSync(parent).isSymbolicLink() || fs.realpathSync(parent)!==parent)throw new Error('Runtime root must not redirect');
  const run=path.join(parent,runId);
  fs.mkdirSync(run,{mode:0o700}); // Existing runs and their secrets are never replaced.
  for(const dir of ['private','runtime','files','files/private','files/public','logs','build','tmp','evidence'])
    fs.mkdirSync(path.join(run,dir),{mode:0o700});
  const secret=()=>crypto.randomBytes(32).toString('base64url');
  const rootPassword=secret(),redis=secret();
  const data={runId,app:secret(),mock:secret(),redis,mockControl:secret()};
  const put=(name,value)=>fs.writeFileSync(path.join(run,'private',name),value,{flag:'wx',mode:0o600});
  put('mysql-root-password',rootPassword+'\n');
  put('mysql-root.cnf','[client]\nhost=127.0.0.1\nport='+mysqlPort+'\nuser=root\npassword='+rootPassword+'\n');
  put('mysql-root-container.cnf','[client]\nhost=127.0.0.1\nport=3306\nuser=root\npassword='+rootPassword+'\n');
  put('db.json',JSON.stringify(data,null,2)+'\n');
  put('redis.conf','bind 0.0.0.0\nprotected-mode yes\nport 6379\nappendonly yes\nrequirepass '+redis+'\n');
  put('session-signing-key',crypto.randomBytes(32).toString('base64')+'\n');
  put('credential-root-key',crypto.randomBytes(32).toString('base64')+'\n');
  fs.writeFileSync(path.join(run,'owner.json'),JSON.stringify({integration:true,runId,mode:'MOCK',environment:'TEST',purpose:'WECHAT_TEST',workspace:root,mysqlPort,redisPort},null,2)+'\n',{flag:'wx',mode:0o600});
  console.log('Prepared owned test run: '+run);
}
try { main(); } catch(error) { console.error('TEST_RUN_PREPARATION_FAILED: '+error.message); process.exitCode=1; }
