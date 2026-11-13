const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const plan = require('./bootstrap-menu-plan.cjs');

test('initial catalog has a single root and lets UAT_008 create residents', () => {
  const ui = process.env.P0_UI_ROOT;
  assert.ok(ui && fs.existsSync(path.join(ui,'manager/src/libs/native-access-core.cjs')));
  const rows = plan(ui);
  assert.equal(rows.filter(row=>row.name==='uat-root').length,1);
  assert.equal(rows.filter(row=>row.name==='s1-residents').length,0);
  assert.ok(rows.some(row=>row.permission==='/manager/passport/user/*'));
  assert.ok(rows.some(row=>row.permission==='s1.goods.review'));
  assert.ok(rows.some(row=>row.permission==='s45.funds.recover'));
  assert.ok(rows.every(row=>row.parent===null || rows.some(parent=>parent.name===row.parent)));
});
