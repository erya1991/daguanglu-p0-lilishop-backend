'use strict';
// Build the initial menu tree from the same component and permission catalog as Manager.
// This module does not connect to a database or create credentials.
module.exports = function menuPlan(uiRoot) {
  const path = require('node:path');
  const base = path.join(uiRoot, 'manager/src/libs');
  const native = require(path.join(base, 'native-access-core.cjs')).pages;
  const s1 = require(path.join(base, 'stage1-access-core.cjs')).pages;
  const s3 = require(path.join(base, 'stage3-access-core.cjs')).definitions;
  const s45 = require(path.join(base, 'stage45-access-core.cjs')).definitions;
  const rows = [];
  const add = (name, title, component, url, permission, parent, level) =>
    rows.push({name,title,component,url,permission,parent,level});
  add('uat-root','智慧社区商城','Main','/uat','',null,0);
  add('uat-settings-group','原生设置','Main','/settings','','uat-root',1);
  add('s1-management','商户与商品','Main','/s1','','uat-root',1);
  add('s3-management','订单与履约','Main','/s3','','uat-root',1);
  add('s45-management','售后与资金','Main','/s45','','uat-root',1);
  for (const [key,title,component,permission] of native)
    add('uat-'+key,title,component,'/settings/'+key,permission,'uat-settings-group',2);
  const titles={applications:'入驻审核',stores:'商户档案',agreements:'合作协议',eligibility:'经营资格','channel-configs':'渠道配置',submerchants:'二级商户',receivers:'平台接收关系','commission-rules':'佣金规则',categories:'商品分类',units:'商品单位',reviews:'商品审核',goods:'商品监管',fulfillment:'履约配置'};
  // UAT_008 creates the user-management group and resident page under uat-root.
  for (const [key,[action,component]] of Object.entries(s1)) {
    if (key === 'residents') continue;
    if (!titles[key]) throw new Error('Missing title for Manager menu: '+key);
    add('s1-'+key,titles[key],component,'/s1/'+key,'s1.'+action,'s1-management',2);
  }
  for (const [prefix,defs,group] of [['s3',s3,'s3-management'],['s45',s45,'s45-management']])
    for (const [key,[action,title,component]] of Object.entries(defs))
      add(prefix+'-'+key,title,'p0-stage'+prefix.slice(1)+'/'+component,'/'+prefix+'/'+key,prefix+'.'+action,group,2);
  const writes={'s1.store.supervise':'商户关闭与恢复','s1.agreement.write':'维护合作协议','s1.rule.write':'维护佣金规则','s1.goods.supervise':'商品监管操作','s45.channel.sync':'同步渠道状态','s45.aftersale.resolve':'售后争议处理','s45.evaluation.moderate':'评价监管操作','s45.funds.recover':'资金异常恢复','s45.reconciliation.create':'创建对账','s45.reconciliation.review':'处理对账差异'};
  for (const [permission,title] of Object.entries(writes))
    add('action-'+permission.replaceAll('.','-'),title,'','',permission,permission.startsWith('s1.')?'s1-management':'s45-management',2);
  for (const [key,title,permission] of [['user-roles','账号角色授权','/manager/permission/userRole/*'],['role-menus','角色菜单授权','/manager/permission/roleMenu/*'],['store-menus','店铺菜单管理','/manager/permission/storeMenu*'],['department-roles','部门继承授权','/manager/permission/departmentRole/*'],['notice-edit','通知模板维护','/manager/setting/noticeMessage*'],['payment-support','支付支持配置','/manager/setting/settingx/paymentSupport'],['tasks','查看任务状态','/manager/setting/timed-task'],['inbox','站内信发送与记录','/manager/other/message*'],['recipients','站内信收件记录','/manager/other/memberMessage,/manager/other/storeMessage'],['wechat-message','微信消息模板配置','/manager/wechat/wechatMessage*,/manager/wechat/wechatMPMessage*']])
    add('action-'+key,title,'','',permission,'uat-settings-group',2);
  const names=new Set();
  for(const row of rows) {
    if(names.has(row.name)) throw new Error('Duplicate Manager menu name: '+row.name);
    names.add(row.name);
  }
  return rows;
};
