import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

test('new run is private and a repeated run cannot reset secrets',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'p0-owned-'));
  try {
    const script=path.join(path.dirname(fileURLToPath(import.meta.url)),'prepare-test-run.mjs');
    const args=[script,'--root',root,'--run-id','UAT_20261113000000000'];
    const env={...process.env,P0_MYSQL_PORT:'29706',P0_REDIS_PORT:'29779'};
    const first=spawnSync(process.execPath,args,{encoding:'utf8',env});assert.equal(first.status,0,first.stderr);
    const run=path.join(root,'.uat-runtime','UAT_20261113000000000'),file=path.join(run,'private','db.json');
    const before=fs.readFileSync(file,'utf8');
    const owner=JSON.parse(fs.readFileSync(path.join(run,'owner.json')));
    assert.equal(owner.purpose,'WECHAT_TEST');assert.equal(owner.mysqlPort,29706);assert.equal(owner.redisPort,29779);
    if(process.platform!=='win32')assert.equal(fs.statSync(file).mode&0o077,0);
    const second=spawnSync(process.execPath,args,{encoding:'utf8',env});assert.notEqual(second.status,0);
    assert.equal(fs.readFileSync(file,'utf8'),before);
  } finally {
    const tempRoot=fs.realpathSync(os.tmpdir());
    if(path.dirname(fs.realpathSync(root))!==tempRoot)throw new Error('Unexpected temporary path');
    fs.rmSync(root,{recursive:true,force:true});
  }
});
