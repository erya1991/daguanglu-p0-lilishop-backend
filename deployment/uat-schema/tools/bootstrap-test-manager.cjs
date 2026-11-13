'use strict';
// Isolated TEST/MOCK bootstrap. Does not create WeChat bindings or transaction data.
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const {pathToFileURL}=require('node:url');
const menuPlan=require('./bootstrap-menu-plan.cjs');

function required(key) { const v=process.env[key]; if(!v)throw new Error('Missing '+key); return v; }
function run(command,args,input) {
  const r=spawnSync(command,args,{input,encoding:'utf8',maxBuffer:4*1024*1024});
  if(r.error || r.status!==0) {
    const code=String(r.stderr||'').match(/ERROR\s+(\d+)\s+\(([A-Z0-9]+)\)/);
    throw new Error('Bootstrap command failed: '+command+(code?' ('+code[1]+'/'+code[2]+')':''));
  }
  return r.stdout.trim();
}
function sqlValue(v) {
  if(v===null)return 'NULL';
  if(typeof v==='number') {
    if(!Number.isSafeInteger(v))throw new Error('Invalid numeric bootstrap value');
    return String(v);
  }
  if(String(v)==='')return "''";
  return "CONVERT(0x"+Buffer.from(String(v),'utf8').toString('hex')+" USING utf8mb4)";
}
function insert(table,values) {
  return 'INSERT INTO '+table+' ('+Object.keys(values).join(',')+') VALUES ('+
    Object.values(values).map(sqlValue).join(',')+');';
}
function main() {
  if(process.platform==='win32')throw new Error('Linux test-server bootstrap only');
  const root=fs.realpathSync(required('P0_TEST_ROOT'));
  const runId=required('P0_TEST_RUN_ID');
  if(!/^UAT_[0-9]{17}$/.test(runId))throw new Error('Invalid owned test run');
  const runDir=fs.realpathSync(path.join(root,'.uat-runtime',runId));
  if(path.dirname(runDir)!==fs.realpathSync(path.join(root,'.uat-runtime')))throw new Error('Invalid test run path');
  const owner=JSON.parse(fs.readFileSync(path.join(runDir,'owner.json'),'utf8'));
  const resources=JSON.parse(fs.readFileSync(path.join(runDir,'runtime/resources.json'),'utf8'));
  if(owner.integration!==true || owner.runId!==runId || owner.mode!=='MOCK' || owner.environment!=='TEST' ||
    owner.purpose!=='WECHAT_TEST' || fs.realpathSync(owner.workspace)!==root ||
    resources.runId!==runId || resources.mysqlPort!==(owner.mysqlPort||19706) || resources.redisPort!==(owner.redisPort||19779) ||
    !/^[0-9a-f-]{36}$/i.test(resources.mysqlUuid))throw new Error('Test resource registration mismatch');
  const privateDir=fs.realpathSync(path.join(runDir,'private'));
  if(path.dirname(privateDir)!==runDir || (fs.statSync(privateDir).mode&0o077)!==0)
    throw new Error('Private directory must be owned by this run and inaccessible to group/others');
  const config=fs.realpathSync(required('P0_MYSQL_DEFAULTS'));
  const uiRoot=fs.realpathSync(required('P0_UI_ROOT'));
  const classpath=required('P0_BCRYPT_CLASSPATH');
  if(classpath.split(path.delimiter).some(file=>!fs.existsSync(file)))throw new Error('BCrypt classpath missing');
  const mysqlArgs=['--defaults-extra-file='+config,'--skip-force','--batch','--raw','--skip-column-names','--database=dgl_p0_uat'];
  const query=statement=>run(process.env.P0_MYSQL_COMMAND||'mysql',mysqlArgs,statement+'\n');
  if(query('SELECT @@server_uuid;').toLowerCase()!==resources.mysqlUuid.toLowerCase())throw new Error('MySQL UUID mismatch');
  const migrate=path.join(__dirname,'migrate-mock-schema.sh');
  const migrationArgs=[migrate,'--defaults-file',config,'--expected-server-uuid',resources.mysqlUuid,
    '--business-db','dgl_p0_uat','--mock-db','dgl_p0_uat_mock','--confirm-test-mock'];
  if(!/pending=0(?:\s|;)/.test(run('sh',[...migrationArgs,'--check-only'])))throw new Error('Apply before-bootstrap migrations first');
  const state=query('SELECT source FROM li_p0_uat_bootstrap WHERE run_id='+sqlValue(runId)+';');
  const credentialsFile=path.join(privateDir,'manager-accounts.json');
  if(state==='WECHAT_TEST_COMPLETE') {
    console.log(fs.existsSync(credentialsFile)?'ALREADY_INITIALIZED credentials='+credentialsFile:'ALREADY_INITIALIZED credentials=NOT_PRESENT');
    return;
  }
  if(state && state!=='WECHAT_TEST_PENDING')throw new Error('Unexpected bootstrap receipt');
  let credentials;
  if(fs.existsSync(credentialsFile)) {
    if(fs.lstatSync(credentialsFile).isSymbolicLink() || (fs.statSync(credentialsFile).mode&0o077)!==0)
      throw new Error('Private credentials permissions invalid');
    credentials=JSON.parse(fs.readFileSync(credentialsFile,'utf8'));
    if(credentials.runId!==runId || !Array.isArray(credentials.accounts) || credentials.accounts.length!==2)
      throw new Error('Private credentials belong to a different run');
  } else {
    if(state)throw new Error('Pending bootstrap credentials are missing');
    const start=1000000000000000000n+(BigInt('0x'+crypto.randomBytes(6).toString('hex'))*1000n);
    credentials={runId,baseId:String(start),accounts:['uat_admin','uat_backup'].map(username=>
      ({username,password:crypto.randomBytes(24).toString('base64url')}))};
    fs.writeFileSync(credentialsFile,JSON.stringify(credentials,null,2)+'\n',{flag:'wx',mode:0o600});
  }
  if(!/^1[0-9]{18}$/.test(credentials.baseId) ||
     credentials.accounts.map(a=>a.username).join(',')!=='uat_admin,uat_backup' ||
     credentials.accounts.some(a=>typeof a.password!=='string'||!/^[A-Za-z0-9_-]{32}$/.test(a.password)))
    throw new Error('Invalid private bootstrap metadata');
  const base=BigInt(credentials.baseId), roleId=String(base+1n);
  if(!state) {
    const captchaDir=path.join(privateDir,'captcha');
    if(!fs.existsSync(captchaDir))fs.mkdirSync(captchaDir,{mode:0o700});
    if(fs.lstatSync(captchaDir).isSymbolicLink() || fs.realpathSync(captchaDir)!==captchaDir)
      throw new Error('Captcha resource directory must belong to this run');
    const assets=['base.png','mask-1.png','mask-2.png'].map(name=>{
      const source=path.join(__dirname,'../assets/captcha',name),target=path.join(captchaDir,'bootstrap-'+name);
      if(!fs.existsSync(target))fs.copyFileSync(source,target,fs.constants.COPYFILE_EXCL);
      if(fs.lstatSync(target).isSymbolicLink() || !fs.readFileSync(source).equals(fs.readFileSync(target)))
        throw new Error('Existing captcha resource conflicts with this bootstrap');
      return target;
    });
    const nativeInput=credentials.accounts.map(a=>crypto.createHash('md5').update(a.password).digest('hex')).join('\n')+'\n';
    const hashes=run('java',['-cp',classpath,path.join(__dirname,'UatPasswordHash.java')],nativeInput).split(/\r?\n/);
    if(hashes.length!==2 || hashes.some(h=>!/^\$2[aby]\$10\$/.test(h)))throw new Error('Native password hashing failed');
    const menus=menuPlan(uiRoot), ids=new Map();let next=base+10n;
    for(const row of menus)ids.set(row.name,String(next++));
    const statements=[];
    const baseFields={create_by:runId,delete_flag:0};
    for(let i=0;i<assets.length;i++)statements.push(insert('li_verification_source',{
      id:String(base+5n+BigInt(i)),...baseFields,name:'测试验证码素材 '+(i+1),
      resource:pathToFileURL(assets[i]).href,type:i===0?'RESOURCE':'SLIDER'}));
    for(const row of menus)statements.push(insert('li_menu',{id:ids.get(row.name),...baseFields,name:row.name,title:row.title,
      front_route:row.component,path:row.url,permission:row.permission,parent_id:row.parent?ids.get(row.parent):'0',level:row.level,sort_order:statements.length}));
    statements.push(insert('li_role',{id:roleId,...baseFields,name:'微信测试环境管理员',description:'可在原生角色页面维护'}));
    for(const row of menus)statements.push(insert('li_role_menu',{id:String(next++),...baseFields,role_id:roleId,menu_id:ids.get(row.name),is_super:1}));
    for(let i=0;i<2;i++) {
      const userId=String(base+2n+BigInt(i)), a=credentials.accounts[i];
      statements.push(insert('li_admin_user',{id:userId,...baseFields,username:a.username,nick_name:i?'备用管理员':'主管理员',
        password:hashes[i],is_super:0,status:1,role_ids:roleId,description:'独立微信测试环境账号'}));
      statements.push(insert('li_user_role',{id:String(next++),user_id:userId,role_id:roleId}));
    }
    const guard="CREATE TEMPORARY TABLE p0_bootstrap_guard(ok INT NOT NULL CHECK(ok=1));\n"+
      "INSERT INTO p0_bootstrap_guard VALUES(GET_LOCK('p0_wechat_bootstrap',0));\nSTART TRANSACTION;\n"+
      "INSERT INTO p0_bootstrap_guard SELECT IF((SELECT COUNT(*) FROM li_p0_uat_bootstrap)=0 AND "+
      "(SELECT COUNT(*) FROM li_admin_user)=0 AND (SELECT COUNT(*) FROM li_user_role)=0 AND "+
      "(SELECT COUNT(*) FROM li_verification_source)=0 AND "+
      "(SELECT COUNT(*) FROM li_menu WHERE name='uat-root')=0 AND "+
      "(SELECT COUNT(*) FROM li_role WHERE name="+sqlValue('微信测试环境管理员')+")=0,1,0);\n";
    query(guard+statements.join('\n')+'\n'+insert('li_p0_uat_bootstrap',{run_id:runId,completed_at:new Date().toISOString().slice(0,19).replace('T',' '),source:'WECHAT_TEST_PENDING'})+'\nCOMMIT;');
  }
  run('sh',[...migrationArgs,'--phase','complete']);
  const finish="CREATE TEMPORARY TABLE p0_bootstrap_guard(ok INT NOT NULL CHECK(ok=1));\n"+
    "INSERT INTO p0_bootstrap_guard VALUES(GET_LOCK('p0_wechat_bootstrap',0));\nSTART TRANSACTION;\n"+
    "INSERT INTO p0_bootstrap_guard SELECT IF((SELECT COUNT(*) FROM li_p0_uat_bootstrap WHERE run_id="+sqlValue(runId)+" AND source='WECHAT_TEST_PENDING')=1 "+
    "AND (SELECT COUNT(*) FROM li_role WHERE id="+sqlValue(roleId)+" AND delete_flag=0)=1 "+
    "AND (SELECT COUNT(*) FROM li_menu WHERE name IN ('uat-resident-group','s1-residents','action-s1-resident-manage') AND delete_flag=0)=3,1,0);\n"+
    "INSERT INTO li_role_menu(id,create_by,delete_flag,role_id,menu_id,is_super) "+
    "SELECT UUID_SHORT(),"+sqlValue(runId)+",0,"+sqlValue(roleId)+",m.id,1 FROM li_menu m "+
    "WHERE m.name IN ('uat-resident-group','s1-residents','action-s1-resident-manage') AND m.delete_flag=0 "+
    "AND NOT EXISTS(SELECT 1 FROM li_role_menu r WHERE r.role_id="+sqlValue(roleId)+" AND r.menu_id=m.id AND r.delete_flag=0);\n"+
    "UPDATE li_p0_uat_bootstrap SET source='WECHAT_TEST_COMPLETE',completed_at=UTC_TIMESTAMP(6) WHERE run_id="+sqlValue(runId)+" AND source='WECHAT_TEST_PENDING';\nCOMMIT;";
  query(finish);
  console.log('INITIALIZED credentials='+credentialsFile+'; later runs do not reset passwords or grants');
}
try { main(); } catch(error) { console.error('BOOTSTRAP_FAILED: '+error.message); process.exitCode=1; }
