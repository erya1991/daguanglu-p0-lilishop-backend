package cn.lili.common.security.session;

import cn.lili.common.security.session.http.*;
import cn.lili.modules.connect.wechat.ResidentWechatLoginService;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.stage3.*;
import cn.lili.modules.community.p0.stage45.*;
import cn.lili.modules.community.p0.channel.ChannelVault;
import cn.lili.modules.community.p0.catalog.NativeGoodsProjector;
import com.google.gson.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real owned MySQL + Redis + independent provider transactions, legal S1/S2/S3 flows.
 * Only the artifact launch gate is a test double; production admission is separately exercised by HTTP.
 * The explicit simulation clock tests schedules/capacity; it never supplies MAN or real-channel evidence.
 */
@EnabledIfEnvironmentVariable(named="S45_RUN",matches=".*S45_[0-9]{8}_[0-9]{6}.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value=15,unit=TimeUnit.MINUTES)
class Stage45BusinessIT {
 static Stage45Data data;static Stage45Funds funds;static Stage45MockFunds provider;static Stage45Inventory inverse;static Stage45StoreFlow flows;
 static Stage3Orders orders;static Stage45AfterSales afterSales;static MockMvc businessMvc;static FaultDb fault;static JdbcTemplate mockJdbc;
 static String config,receiver;static final List<Object> results=new ArrayList<>();static final MutableClock testClock=new MutableClock();
 static class FaultDb extends Stage1Db {
  volatile String failPrefix;FaultDb(JdbcTemplate jdbc){super(jdbc);}
  @Override public int update(String sql,Object...args){int n=super.update(sql,args);if(failPrefix!=null&&sql.startsWith(failPrefix)){failPrefix=null;throw new org.springframework.dao.DataAccessResourceFailureException("S45_TEST committed provider / rollback local consequence");}return n;}
 }
 @BeforeAll static void open()throws Exception{
  var runPath=Path.of(Objects.requireNonNull(System.getenv("S45_RUN"))).toRealPath();
  var registry=object(Files.readString(runPath.resolve("runtime/apis.json")));assertEquals("STOPPED",registry.get("state").getAsString(),"Stop only registered owned APIs before controllable-clock integration");
  initializeStage45Dependencies();var ctx=(Stage1Context)ReflectionTestUtils.getField(s2tx,"context");
  var a3=((org.springframework.beans.factory.ObjectProvider<Stage3Authorization>)ReflectionTestUtils.getField(stage1Access,"stage3")).getObject();
  var a45=((org.springframework.beans.factory.ObjectProvider<Stage45Authorization>)ReflectionTestUtils.getField(stage1Access,"stage45")).getObject();
  var gate3=mock(Stage3Gate.class);var gate45=mock(Stage45Gate.class);
  var paid=new Stage3Eligibility(db,paymentMock,s2Settings);orders=new Stage3Orders(db,ctx,a3,paid,gate3,s2tx,null,testClock);
  var jdbc=(JdbcTemplate)ReflectionTestUtils.getField(db,"jdbc");fault=new FaultDb(jdbc);
  data=new Stage45Data(fault,ctx,a45,orders,paid,gate45,testClock,new DataSourceTransactionManager(jdbc.getDataSource()));
  var files=new Stage45Files(data,s2Settings,null);afterSales=new Stage45AfterSales(data,files);provider=new Stage45MockFunds(s2Settings,testClock);
  funds=new Stage45Funds(data,provider,new ChannelVault(settings));
  inverse=new Stage45Inventory(data,s2inventory,(NativeGoodsProjector)ReflectionTestUtils.getField(s2inventory,"projector"),redis,s2Settings);
  flows=new Stage45StoreFlow(data,funds);
  var hook=new Stage45Fulfillment(db);ReflectionTestUtils.setField(orders,"stage45",new StaticListableBeanFactory(Map.of("s45",hook)).getBeanProvider(Stage45Fulfillment.class));
  var filters=new EnumMap<SessionDomain,SessionHttpFilter>(SessionDomain.class);for(var domain:SessionDomain.values())filters.put(domain,new SessionHttpFilter(sessions,authorization,apps,domain));
  Filter dispatch=(request,response,chain)->{String p=((jakarta.servlet.http.HttpServletRequest)request).getRequestURI();var domain=p.startsWith("/store/")?SessionDomain.STORE:p.startsWith("/manager/")?SessionDomain.MANAGER:SessionDomain.RESIDENT;try{filters.get(domain).doFilter(request,response,chain);}finally{SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}};
  businessMvc=MockMvcBuilders.standaloneSetup(new Stage45Controller(data,afterSales,new Stage45Evaluations(data,files,afterSales),files,funds,new Stage45FundViews(data,funds),new Stage45Messages(data,a3),null,new Stage45Reconciliation(data,funds)),new Stage3Controller(orders,null,null,null,ctx,a3)).setControllerAdvice(new Stage1Responses()).addFilters(dispatch).build();
  mockJdbc=new JdbcTemplate(new DriverManagerDataSource(s2Settings.getMockJdbcUrl(),s2Settings.getMockUsername(),s2Settings.getMockPassword()));
  mvc=supplyMvc;config=channelConfigFixture(10000);
  var made=call(post(Stage1Routes.MANAGER+"/receivers"),channel,map("requestId",req(),"configId",config,"displayName","S45_TEST IT佣金接收方","mockAccount","MOCK_RECEIVER_IT_"+testId,"reason","S45_TEST"),200);receiver=made.get("id").getAsString();
  var synced=call(post(Stage1Routes.MANAGER+"/receivers/"+receiver+"/sync"),channel,map("requestId",req(),"expectedVersion",0,"reason","S45_TEST"),200).getAsJsonObject("receiver");
  call(post(Stage1Routes.MANAGER+"/receivers/"+receiver+"/enable"),channel,map("requestId",req(),"expectedVersion",synced.get("version").getAsLong(),"reason","S45_TEST"),200);
 }
 @AfterAll static void close()throws Exception{if(run!=null)Files.writeString(run.resolve("evidence/stage45-business-"+(testId==null?"setup-failed":testId)+".json"),write(map("runId",settings.getRunId(),"mysqlUuid",settings.getMysqlUuid(),"source","OWNED_REAL_DATABASE_INDEPENDENT_MOCK_LEDGER_CONTROLLABLE_CLOCK","artifactGate","TEST_DOUBLE_SEPARATE_HTTP_ADMISSION_REQUIRED","clockScope","S3_S45_WORKER_CLOCK_CONTROLLED_S1_S2_AND_REDIS_SESSION_CLOCK_REAL","results",results,"realChannel",false,"manualAcceptance",false)));if(run!=null&&channel!=null){mvc=supplyMvc;channel=sessions.refresh(channel.refreshToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));String original=object(Files.readString(run.resolve("private/stage45-fixtures.json"))).get("configId").getAsString();for(var active:db.rows("SELECT id,config_version FROM li_p0_channel_config WHERE active_route=?",settings.getEnvironment()))if(!original.equals(id(active,"id")))call(post(Stage1Routes.MANAGER+"/channel-configs/"+id(active,"id")+"/disable"),channel,map("requestId",req(),"expectedVersion",number(active,"config_version"),"reason","S45_TEST恢复本run主夹具入口"),200);if(db.count("SELECT COUNT(*) FROM li_p0_channel_config WHERE id=? AND active_route=?",original,settings.getEnvironment())==0)call(post(Stage1Routes.MANAGER+"/channel-configs/"+original+"/enable"),channel,map("requestId",req(),"expectedVersion",db.count("SELECT config_version FROM li_p0_channel_config WHERE id=?",original),"reason","S45_TEST恢复本run主夹具入口"),200);}restorePrimarySupplyEvidence();closeS2();}
 @BeforeEach void refresh(){fault.failPrefix=null;for(int i=0;i<2000&&inverse.one();i++){}reviewer=sessions.refresh(reviewer.refreshToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));operator=sessions.refresh(operator.refreshToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));channel=sessions.refresh(channel.refreshToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));}
 record Supply(OwnerFixture owner,List<String> skus,int rate,int fee){}
 static class Paid {Supply supply;ResidentWechatLoginService.LoginResult identity;SessionTokens resident,store;String trade,order,transaction;List<String> items;}
 static Supply supply(int rate,int fee,int count,int stock,List<Integer> prices)throws Exception{
  mvc=supplyMvc;var owner=ownerFixture();var methods=deliveryMethods();methods.put("MERCHANT_DELIVERY",map("enabled",true,"feeCent",fee,"districtCodes",List.of("990101")));
  call(put(Stage1Routes.STORE+"/fulfillment"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"methods",methods,"reason","S45_TEST"),200);
  var agreement=agreementFixture(owner,rate,LocalDate.now(ZoneOffset.UTC),null);ruleFixture(owner,agreement,rate);
  var rel=channelRelationFixture(owner,config);var v=relationCommand(rel,1,"submit");for(int i=0;i<3;i++)v=relationCommand(rel,v.get("version").getAsLong(),"query");
  v=call(put(Stage1Routes.MANAGER+"/submerchants/"+rel+"/receiver"),channel,map("requestId",req(),"expectedVersion",v.get("version").getAsLong(),"receiverId",receiver,"reason","S45_TEST"),200);relationCommand(rel,v.get("version").getAsLong(),"enable");
  var skus=new ArrayList<String>();String unit=unitFixture();
  for(int start=0;start<count;start+=100){var payload=object(goodsPayload(unit,upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),stock));var list=new JsonArray();for(int i=start;i<Math.min(start+100,count);i++)list.add(object(map("code","S45_"+db.id(),"specifications",List.of(map("name","规格","value",String.valueOf(i))),"priceCent",prices.get(i%prices.size()),"costCent",0,"initialQuantity",stock,"weightGram",0)));payload.add("skus",list);
   var made=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),200);String goods=made.getAsJsonObject("goods").get("id").getAsString();for(var raw:made.getAsJsonArray("skus"))skus.add(raw.getAsJsonObject().get("id").getAsString());
   call(post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+goods+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);
  }return new Supply(owner,skus,rate,fee);
 }
 static Paid pay(Supply s,int quantity)throws Exception{
  mvc=transactionMvc;var p=new Paid();p.supply=s;p.identity=residentFixture();p.resident=p.identity.session();p.store=s.owner.login().session();var address=call(post(Stage2Routes.BASE+"/addresses"),p.resident,map("operationId",req(),"addressBookVersion",0,"name","S45_TEST","mobile","13800000000","provinceCode","990000","cityCode","990100","districtCode","990101","detail","S45_TEST新库一单元"),200).get("defaultAddressId").getAsString();
  var selection=map("items",s.skus.stream().map(sku->map("skuId",sku,"quantity",quantity)).toList(),"stores",List.of(map("storeId",s.owner.store(),"method","MERCHANT_DELIVERY","addressId",address,"addressVersion",0,"contact",map("name","S45_TEST","mobile","13800000000"))));
  var q=call(post(Stage2Routes.BASE+"/checkout/quotes"),p.resident,selection,200);p.trade=call(post(Stage2Routes.BASE+"/checkout/intents"),p.resident,map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()),200).get("tradeId").getAsString();
  call(post(Stage2Routes.BASE+"/trades/"+p.trade+"/payment/prepare"),p.resident,map("operationId",req()),200);call(post(Stage2Routes.BASE+"/trades/"+p.trade+"/payment/launch"),p.resident,map("operationId",req()),200);
  p.order=id(db.required("SELECT order_id FROM li_p0_order_snapshot WHERE trade_id=?",p.trade),"order_id");var payment=object(db.required("SELECT result_json FROM li_p0_payment WHERE trade_id=?",p.trade).get("result_json"));p.transaction=payment.getAsJsonObject("paid").getAsJsonArray("subOrders").get(0).getAsJsonObject().get("transactionId").getAsString();
  p.items=db.rows("SELECT id FROM li_order_item WHERE order_sn=(SELECT sn FROM li_order WHERE id=?) ORDER BY id",p.order).stream().map(r->id(r,"id")).toList();orders.locked(orders.row(p.order),()->orders.admitLocked(orders.row(p.order)));mvc=businessMvc;return p;
 }
 static JsonObject apply(Paid p,String item,int q,long amount)throws Exception{mvc=businessMvc;return call(post(Stage45Routes.BUYER+"/orders/"+p.order+"/aftersales"),p.resident,map("operationId",req(),"orderItemId",item,"quantity",q,"amountCent",amount,"type","REFUND","reason","商品问题","files",List.of()),200);}
 static JsonObject action(Paid p,JsonObject sale,String action,Map<String,Object> extra)throws Exception{mvc=businessMvc;var body=map("operationId",req(),"expectedVersion",sale.get("version").getAsLong());body.putAll(extra);boolean resident=Set.of("cancel","dispute","evidence","return").contains(action);return call(post((resident?Stage45Routes.BUYER:Stage45Routes.STORE)+"/aftersales/"+sale.get("id").getAsString()+"/"+action),resident?p.resident:p.store,body,200);}
 static void complete(Paid p)throws Exception{mvc=businessMvc;for(String action:List.of("prepare","dispatch","delivered","receive"))call(post((action.equals("receive")?"/buyer":"/store")+"/p0/s3/orders/"+p.order+"/"+action),action.equals("receive")?p.resident:p.store,map("operationId",req(),"expectedVersion",number(orders.state(orders.row(p.order)),"version")),200);}
 static void pump(Paid p,int rounds){for(int i=0;i<rounds;i++){funds.plan(orders.row(p.order));for(int t=0;t<15;t++)if(!funds.executeOne())break;}}
 static void control(Paid p,String kind,String scenario){mockJdbc.update("INSERT INTO li_p0_mock_funds_control(transaction_id,kind,scenario) VALUES(?,?,?) ON DUPLICATE KEY UPDATE scenario=VALUES(scenario)",p.transaction,kind,scenario);}
 static void external(Paid p,String kind,int used){mockJdbc.update("INSERT INTO li_p0_mock_funds_capacity VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE external_count=VALUES(external_count),complete=1",p.transaction,kind,used);}
 static long stateCount(Paid p,String kind,String state){return db.count("SELECT COUNT(*) FROM li_p0_s45_task WHERE order_id=? AND kind=? AND state=?",p.order,kind,state);}
 static long providerCount(Paid p,String kind){return mockJdbc.queryForObject("SELECT COUNT(*) FROM li_p0_mock_funds_request WHERE transaction_id=? AND kind=?",Long.class,p.transaction,kind);}
 static void result(String scenario,Object detail){results.add(map("scenario",scenario,"status","PASS","detail",detail));}
 @Test @Order(1) void penniesUseOrderResidualAndCompletedRefundKeepsEvaluationBeyondDay15()throws Exception{
  var p=pay(supply(300,0,3,5,List.of(33,33,34)),1);complete(p);pump(p,5);assertEquals(3,funds.actual(p.order,"SHARE"));assertEquals(1,stateCount(p,"FINISH","SUCCEEDED"));
  var returns=new ArrayList<Long>();for(String item:p.items){long amount=afterSales.line(orders.row(p.order),data.item(orders.row(p.order),item)).get("unitPriceCent").getAsLong();var a=apply(p,item,1,amount);action(p,a,"approve",map());testClock.advance(61);pump(p,6);returns.add(funds.actual(p.order,"RETURN"));}
  assertEquals(List.of(1L,2L,3L),returns);assertEquals(100,funds.refunded(p.order,"goods_cent"));assertEquals(3,providerCount(p,"RETURN"));assertEquals(3,db.count("SELECT COALESCE(SUM(num),0) FROM li_order_item WHERE order_sn=(SELECT sn FROM li_order WHERE id=?)",p.order));
  result("GOODS_33_33_34_RETURN_1_1_1",map("orderId",p.order,"returns",returns,"goodsRefundCent",100));
 }
 @Test @Order(2) void fiftyOneSerialApplicationsKeepFiftyFirstObligationAndOriginalIdentifiers()throws Exception{
  var p=pay(supply(0,0,1,60,List.of(1)),51);
  for(int i=0;i<51;i++){if(i%10==0){p.resident=sessions.refresh(p.resident.refreshToken(),SessionDomain.RESIDENT,apps.application(SessionDomain.RESIDENT));p.store=sessions.refresh(p.store.refreshToken(),SessionDomain.STORE,apps.application(SessionDomain.STORE));}var a=apply(p,p.items.getFirst(),1,1);action(p,a,"approve",map());testClock.advance(61);pump(p,5);}
  assertEquals(50,providerCount(p,"REFUND"));assertEquals(50,funds.refunded(p.order,"goods_cent"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=? AND state<>'SUCCEEDED'",p.order));assertEquals(51,db.count("SELECT COUNT(DISTINCT request_no) FROM li_p0_s45_task WHERE order_id=? AND kind='REFUND'",p.order));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_s45_order WHERE order_id=? AND fulfillment_ended=1",p.order));
  result("51_SERIAL_INDEPENDENT_APPLICATIONS_50_CAP",map("orderId",p.order,"providerRequests",50,"retainedObligations",1));
 }
 @Test @Order(3) void twoHundredSkuCompatibleMatureObligationsFormOneFrozenBatch()throws Exception{
  var p=pay(supply(0,0,200,2,List.of(1)),1);assertEquals(200,p.items.size());
  for(String item:p.items)action(p,apply(p,item,1,1),"approve",map());pump(p,8);
  assertEquals(1,providerCount(p,"REFUND"));assertEquals(200,funds.refunded(p.order,"goods_cent"));assertEquals(200,db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=? AND state='SUCCEEDED'",p.order));
  for(int i=0;i<2000&&inverse.one();i++){}assertEquals(200,db.count("SELECT COUNT(*) FROM li_p0_s45_inventory_return WHERE order_id=? AND status='APPLIED'",p.order));
  assertEquals(200,db.count("SELECT COUNT(*) FROM li_order_item WHERE order_sn=(SELECT sn FROM li_order WHERE id=?) AND num=1",p.order));assertEquals(0,db.count("SELECT COUNT(*) FROM li_order WHERE id=? AND order_status='COMPLETED'",p.order));
  result("200_SKU_COMPATIBLE_BATCH_AND_NATIVE_QUANTITY",map("orderId",p.order,"skuCount",200,"providerRefundRequests",1,"inventoryInverses",200));
 }
 @Test @Order(4) void lastGoodsRefundReservesFeeCapacityAt48_49_50()throws Exception{
  for(int used:List.of(48,49,50)){var p=pay(supply(0,2,1,3,List.of(1)),1);external(p,"REFUND",used);action(p,apply(p,p.items.getFirst(),1,1),"approve",map());pump(p,4);
   if(used==48){assertEquals(1,providerCount(p,"REFUND"));testClock.advance(61);pump(p,6);assertEquals(2,providerCount(p,"REFUND"));assertEquals(2,funds.refunded(p.order,"fee_cent"));assertEquals(1,db.count("SELECT actual_refund_price*100 FROM li_after_sale WHERE id=(SELECT after_sale_id FROM li_p0_s45_obligation WHERE order_id=? AND kind='GOODS')",p.order));}
   else{assertEquals(0,providerCount(p,"REFUND"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=? AND state='CURRENT'",p.order));assertEquals(0,funds.refunded(p.order,"goods_cent"));}
   result("FINAL_GOODS_WITH_FUTURE_FEE_EXTERNAL_"+used,map("orderId",p.order,"providerRequests",providerCount(p,"REFUND"),"futureFeeCapacity",true));
  }
 }
 @Test @Order(5) void detailFinishedWithClosedReceiverIsPartialMoneyAndReturnCapIsIndependent()throws Exception{
  var p=pay(supply(500,0,1,6,List.of(1000)),5);control(p,"SHARE","DETAIL_FINISHED");complete(p);pump(p,5);
  assertEquals(150,funds.actual(p.order,"SHARE"));assertEquals(0,stateCount(p,"SHARE","SUCCEEDED"));assertEquals(0,providerCount(p,"FINISH"));while(flows.one()){}
  assertEquals(150,db.count("SELECT COALESCE(SUM(commission_price*100),0) FROM li_store_flow WHERE order_sn=(SELECT sn FROM li_order WHERE id=?)",p.order));
  external(p,"RETURN",50);action(p,apply(p,p.items.getFirst(),5,5000),"approve",map());pump(p,6);assertEquals(0,providerCount(p,"RETURN"));assertEquals(0,providerCount(p,"REFUND"));external(p,"RETURN",49);testClock.advance(6);pump(p,8);assertEquals(150,funds.actual(p.order,"RETURN"));assertEquals(5000,funds.refunded(p.order,"goods_cent"));
  result("SHARE_FINISHED_CLOSED_DETAILS_AND_RETURN_50_CAP",map("orderId",p.order,"requestedShare",250,"actualShare",150,"actualReturn",150));
 }
 @Test @Order(6) void pendingShareDetailsBlockRefundUntilOriginalQueryConverges()throws Exception{
  var p=pay(supply(500,0,1,6,List.of(1000)),5);control(p,"SHARE","DETAIL_PARTIAL");complete(p);pump(p,4);assertEquals(150,funds.actual(p.order,"SHARE"));assertEquals(0,providerCount(p,"FINISH"));
  action(p,apply(p,p.items.getFirst(),1,1000),"approve",map());pump(p,4);assertEquals(0,providerCount(p,"REFUND"));control(p,"SHARE","SUCCESS");testClock.advance(6);pump(p,8);assertEquals(250,funds.actual(p.order,"SHARE"));assertEquals(50,funds.actual(p.order,"RETURN"));assertEquals(1,providerCount(p,"SHARE"));assertEquals(1000,funds.refunded(p.order,"goods_cent"));result("PENDING_RECEIVER_ORIGINAL_QUERY_BEFORE_REFUND",map("orderId",p.order,"shareRequests",1,"requiredReturn",50));
 }
 @Test @Order(7) void committedProviderSuccessSurvivesLocalFailureAndInventoryRetry()throws Exception{
  var p=pay(supply(0,0,1,4,List.of(100)),2);action(p,apply(p,p.items.getFirst(),1,100),"approve",map());fault.failPrefix="UPDATE li_after_sale SET service_status='COMPLETE'";pump(p,1);
  assertEquals(1,providerCount(p,"REFUND"));assertEquals(1,stateCount(p,"REFUND","SUCCEEDED"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_s45_aftersale WHERE order_id=? AND state='COMPLETE'",p.order));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_s45_task WHERE order_id=? AND kind='REFUND' AND applied=1",p.order));
  testClock.advance(6);pump(p,5);assertEquals(1,providerCount(p,"REFUND"));assertEquals(1,mockJdbc.queryForObject("SELECT SUM(send_count) FROM li_p0_mock_funds_request WHERE transaction_id=? AND kind='REFUND'",Long.class,p.transaction));
  fault.failPrefix="UPDATE li_goods_sku SET quantity=";assertFalse(inverse.one());assertEquals(2,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.supply.skus.getFirst()));assertTrue(inverse.one());assertEquals(3,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.supply.skus.getFirst()));assertFalse(inverse.one());
  fault.failPrefix="INSERT INTO li_store_flow";assertThrows(RuntimeException.class,()->{while(flows.one()){};});while(flows.one()){}var applied=db.required("SELECT a.refunded_at,x.succeeded_at FROM li_p0_s45_aftersale a JOIN li_p0_s45_obligation x ON x.after_sale_id=a.id AND x.kind='GOODS' WHERE a.order_id=?",p.order);assertTrue(instant(applied.get("refunded_at")).plusSeconds(5).isBefore(instant(applied.get("succeeded_at"))),"Local retry rewrote original provider success time");assertEquals(1,providerCount(p,"REFUND"));result("SUCCESS_THEN_LOCAL_ROLLBACK_RECOVERY",map("orderId",p.order,"providerSendCount",1,"inventoryReturnOnce",true));
 }


 static void restorePrimarySupplyEvidence()throws Exception{
  assertTrue(settings.getRunId().startsWith("S45_"));var fixture=object(Files.readString(run.resolve("private/stage45-fixtures.json")));String original=fixture.get("configId").getAsString();assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_channel_config WHERE id=? AND active_route=?",original,settings.getEnvironment()));mvc=supplyMvc;
  String receiverId=fixture.get("receiverId").getAsString();var receiverRow=db.required("SELECT * FROM li_p0_receiver_relation WHERE id=? AND config_id=?",receiverId,original);
  var receiverResult=call(post(Stage1Routes.MANAGER+"/receivers/"+receiverId+"/sync"),channel,map("requestId",req(),"expectedVersion",number(receiverRow,"version"),"reason","S45_TEST原主夹具配置恢复后重新取证"),200).getAsJsonObject("receiver");assertEquals("VERIFIED",receiverResult.get("state").getAsString());if(!flag(receiverRow,"enabled"))call(post(Stage1Routes.MANAGER+"/receivers/"+receiverId+"/enable"),channel,map("requestId",req(),"expectedVersion",receiverResult.get("version").getAsLong(),"reason","S45_TEST"),200);
  var proof=new ArrayList<Object>();for(var raw:fixture.getAsJsonArray("stores")){var s=raw.getAsJsonObject();String store=s.get("storeId").getAsString();var rel=db.required("SELECT * FROM li_p0_submerchant_relation WHERE store_id=? AND config_id=? AND active_store_key IS NOT NULL",store,original);var synced=relationCommand(id(rel,"id"),number(rel,"version"),"query");assertEquals("SUCCESS",synced.get("lastSyncState").getAsString());if(!flag(rel,"enabled"))relationCommand(id(rel,"id"),synced.get("version").getAsLong(),"enable");String originalCategory=id(db.required("SELECT category_id FROM li_p0_goods_control WHERE goods_id=?",s.get("goodsId").getAsString()),"category_id");var readiness=eligibility.evaluate(store,originalCategory,null);assertEquals(true,readiness.get("MOCK_READY"));proof.add(map("storeId",store,"relationId",id(rel,"id"),"mockReady",true));}
  Files.writeString(run.resolve("evidence/primary-supply-restored-"+testId+".json"),write(map("runId",settings.getRunId(),"mysqlUuid",settings.getMysqlUuid(),"configId",original,"source","NATIVE_MOCK_CONFIG_RECEIVER_AND_RELATION_REQUERY_NOT_REUSED_STALE_EVIDENCE","stores",proof)),StandardOpenOption.CREATE_NEW);
 }


 @Test @Order(15) void externallyUnfrozenCommissionGapIsObservedBeforeAnyNewShareSend()throws Exception{
  var p=pay(supply(500,0,1,3,List.of(1000)),1);complete(p);var external=funds.original(orders.row(p.order),true);external.addProperty("requestNo","S45_EXTERNAL_FINISH_"+req());external.addProperty("kind","FINISH");external.addProperty("amountCent",0);external.addProperty("finish",false);assertEquals("SUCCEEDED",provider.send(external).get("state").getAsString());
  pump(p,6);assertEquals(0,providerCount(p,"SHARE"));assertEquals(0,funds.actual(p.order,"SHARE"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_s45_exception WHERE order_id=? AND code='COMMISSION_GAP_AFTER_FINISH' AND state='OPEN'",p.order));
  pump(p,6);assertEquals(0,providerCount(p,"SHARE"));action(p,apply(p,p.items.getFirst(),1,1000),"approve",map());pump(p,6);assertEquals(1000,funds.refunded(p.order,"goods_cent"));assertEquals(0,providerCount(p,"RETURN"));pump(p,3);assertEquals(1,providerCount(p,"FINISH"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_s45_exception WHERE order_id=? AND code='COMMISSION_GAP_AFTER_FINISH' AND state='OPEN'",p.order));result("EXTERNAL_UNFREEZE_GAP_NO_BLIND_SHARE",map("orderId",p.order,"newShareRequests",0,"gapRetained",true,"refundCent",1000));
 }


 @Test @Order(16) void providerBillUsesImmutableAsOfObservationsAndExceptionHistoryIsGrouped()throws Exception{
  var p=pay(supply(0,0,1,4,List.of(100)),2);control(p,"REFUND","UNKNOWN");action(p,apply(p,p.items.getFirst(),1,100),"approve",map());pump(p,4);Instant cutoff=testClock.instant();String account=funds.original(orders.row(p.order),true).get("platformMerchantId").getAsString();LocalDate day=cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();var original=provider.bill(account,day,cutoff);assertTrue(original.get("complete").getAsBoolean());control(p,"REFUND","SUCCESS");testClock.advance(6);pump(p,6);assertEquals(hash(original),hash(provider.bill(account,day,cutoff)),"Later final result overwrote old bill evidence");
  data.issue(p.order,"TEST_OBSERVATION:"+p.order,"CHANNEL_RESULT_UNKNOWN",map("phase","A"));data.issue(p.order,"TEST_OBSERVATION:"+p.order,"CHANNEL_RESULT_UNKNOWN",map("phase","A"));data.issue(p.order,"TEST_OBSERVATION:"+p.order,"CHANNEL_RESULT_UNKNOWN",map("phase","B"));String exception=id(db.required("SELECT id FROM li_p0_s45_exception WHERE source_key=?","TEST_OBSERVATION:"+p.order),"id");assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_s45_exception_observation WHERE exception_id=?",exception));assertEquals(3,db.count("SELECT SUM(observations) FROM li_p0_s45_exception_observation WHERE exception_id=?",exception));
  result("IMMUTABLE_PROVIDER_CUTOFF_AND_GROUPED_OBSERVATIONS",map("orderId",p.order,"oldBillUnchanged",true,"distinctObservations",2,"observationCount",3));
 }
 @Test @Order(17) void staffFinanceAftersaleAndRevocationStayIndependent()throws Exception{
  var p=pay(supply(0,0,1,5,List.of(100)),2);mvc=supplyMvc;
  JsonObject reviewerStaff=object(Stage45FixtureIT.clerk(p.supply.owner,"S45_AFTERSALE_REVIEWER")),financeStaff=object(Stage45FixtureIT.clerk(p.supply.owner,"S45_FUNDS_READER"));SessionTokens reviewToken=new Gson().fromJson(reviewerStaff.get("session"),SessionTokens.class),financeToken=new Gson().fromJson(financeStaff.get("session"),SessionTokens.class);
  mvc=businessMvc;var sale=apply(p,p.items.getFirst(),1,100);call(get(Stage45Routes.STORE+"/aftersales/"+sale.get("id").getAsString()),reviewToken,null,200);call(get(Stage45Routes.STORE+"/orders/"+p.order+"/funds"),reviewToken,null,403);call(get(Stage45Routes.STORE+"/orders/"+p.order+"/funds"),financeToken,null,200);call(get(Stage45Routes.STORE+"/aftersales/"+sale.get("id").getAsString()),financeToken,null,403);
  mvc=supplyMvc;String clerk=reviewerStaff.get("clerkId").getAsString();call(post(Stage1Routes.STORE+"/staff/"+clerk+"/revoke"),p.store,map("requestId",req(),"expectedVersion",db.count("SELECT version FROM li_p0_clerk_control WHERE clerk_id=?",clerk),"reason","S45_TEST撤销旧会话售后权限"),200);mvc=businessMvc;call(get(Stage45Routes.STORE+"/aftersales/"+sale.get("id").getAsString()),reviewToken,null,403);action(p,sale,"cancel",map());result("STAFF_FINANCE_AFATER_SALE_AND_OLD_SESSION_REVOKED",map("orderId",p.order,"revokedClerk",clerk));
 }

 static SessionTokens auditor;static Paid deadlinePaid;static Stage3Messages delivery;
 @Test @Order(9) void accountReconciliationRecordsEmptyScopeAndReviewsExternalMissingLocalWithoutMoneyWrite()throws Exception{
  mvc=businessMvc;auditor=manager("744500000000004001");String no=req();var body=map("operationId",no,"configId",config,"businessDate",testClock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(1).toString());
  JsonObject report=call(post(Stage45Routes.MANAGER+"/reconciliations"),auditor,body,200),same=call(post(Stage45Routes.MANAGER+"/reconciliations"),auditor,body,200);assertEquals(report.get("id"),same.get("id"));assertEquals(0,report.getAsJsonArray("lines").size());
  assertNotNull(db.required("SELECT source_json FROM li_p0_s45_reconciliation WHERE id=?",report.get("id").getAsString()).get("source_json"));
  var p=pay(supply(0,0,1,3,List.of(100)),1);var external=funds.original(orders.row(p.order),true);external.addProperty("requestNo","S45_EXTERNAL_"+req());external.addProperty("kind","REFUND");external.addProperty("amountCent",1);external.addProperty("finish",false);assertEquals("SUCCEEDED",provider.send(external).get("state").getAsString());
  mvc=businessMvc;var next=call(post(Stage45Routes.MANAGER+"/reconciliations"),auditor,map("operationId",req(),"configId",config,"businessDate",testClock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()),200);
  var line=db.required("SELECT id,classification FROM li_p0_s45_reconciliation_line WHERE reconciliation_id=? AND request_no=?",next.get("id").getAsString(),external.get("requestNo").getAsString());assertEquals("MISSING_LOCAL",line.get("classification"));long before=providerCount(p,"REFUND");
  var reviewed=call(post(Stage45Routes.MANAGER+"/reconciliation-lines/"+id(line,"id")+"/review"),auditor,map("operationId",req(),"reason","S45_TEST渠道独立外部请求待调查，不改差异"),200);assertFalse(reviewed.get("fundsChanged").getAsBoolean());assertEquals("MISSING_LOCAL",reviewed.get("classification").getAsString());assertEquals(before,providerCount(p,"REFUND"));
  result("RECONCILIATION_ORIGINAL_SIGNED_SOURCE_AND_EXTERNAL_DIFFERENCE_REVIEW",map("reportId",next.get("id"),"classification","MISSING_LOCAL","providerWritesDuringReview",0));
 }
 @Test @Order(10) void approveCancelRaceUsesOneVersionAndOneMoneyObligation()throws Exception{
  var p=pay(supply(0,0,1,3,List.of(100)),1);var sale=apply(p,p.items.getFirst(),1,100);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try{var a=pool.submit(()->{start.await();return status(p,sale,"approve",p.store);});var b=pool.submit(()->{start.await();return status(p,sale,"cancel",p.resident);});start.countDown();var statuses=new ArrayList<>(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
   var state=id(db.required("SELECT state FROM li_p0_s45_aftersale WHERE id=?",sale.get("id").getAsString()),"state");assertEquals(state.equals("WAIT_FUNDS")?1:0,db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=?",p.order));result("REVIEW_CANCEL_REAL_TRANSACTION_RACE",map("orderId",p.order,"statuses",statuses,"state",state));
  }finally{pool.shutdownNow();}
 }
 static int status(Paid p,JsonObject sale,String action,SessionTokens actor)throws Exception{return businessMvc.perform(post((action.equals("cancel")?Stage45Routes.BUYER:Stage45Routes.STORE)+"/aftersales/"+sale.get("id").getAsString()+"/"+action).header("accessToken",actor.accessToken()).contentType("application/json").content(write(map("operationId",req(),"expectedVersion",sale.get("version").getAsLong())))).andReturn().getResponse().getStatus();}
 @Test @Order(11) void pendingShareAndFinishAreRecheckedAfterNewAftersale()throws Exception{
  var p=pay(supply(500,0,1,5,List.of(1000)),3);complete(p);funds.plan(orders.row(p.order));assertEquals(1,stateCount(p,"SHARE","PENDING"));
  var sale=apply(p,p.items.getFirst(),1,1000);pump(p,3);assertEquals(0,providerCount(p,"SHARE"));action(p,sale,"cancel",map());testClock.advance(6);funds.executeOne();assertEquals(150,funds.actual(p.order,"SHARE"));assertEquals(0,providerCount(p,"FINISH"));
  var next=apply(p,p.items.getFirst(),1,1000);action(p,next,"approve",map());pump(p,6);assertEquals(1000,funds.refunded(p.order,"goods_cent"));assertEquals(50,funds.actual(p.order,"RETURN"));pump(p,6);assertEquals(1,providerCount(p,"FINISH"));assertEquals(1,providerCount(p,"SHARE"));deadlinePaid=p;
  result("PENDING_SHARE_AND_POST_COMMISSION_PRE_FINISH_AFTERSALE",map("orderId",p.order,"shareRequests",1,"returnCent",50,"finishRequests",1));
 }
 @Test @Order(12) void returnUnknownRefundFailureAndOriginalExplicitRetryKeepCurrentBatch()throws Exception{
  var p=pay(supply(500,0,1,6,List.of(2000)),5);complete(p);pump(p,5);control(p,"RETURN","UNKNOWN");control(p,"REFUND","FAILURE");
  action(p,apply(p,p.items.getFirst(),1,2000),"approve",map());pump(p,4);assertEquals(1,stateCount(p,"RETURN","UNKNOWN"));action(p,apply(p,p.items.getFirst(),1,2000),"approve",map());pump(p,4);assertEquals(1,providerCount(p,"RETURN"));assertEquals(0,providerCount(p,"REFUND"));
  control(p,"RETURN","SUCCESS");testClock.advance(6);pump(p,6);assertEquals(100,funds.actual(p.order,"RETURN"));assertEquals(1,stateCount(p,"REFUND","FAILED"));assertEquals(0,funds.refunded(p.order,"goods_cent"));
  var task=db.required("SELECT id,request_no,version FROM li_p0_s45_task WHERE order_id=? AND kind='REFUND'",p.order);control(p,"REFUND","SUCCESS");mvc=businessMvc;call(post(Stage45Routes.MANAGER+"/tasks/"+id(task,"id")+"/recover"),auditor,map("operationId",req(),"expectedVersion",number(task,"version"),"retry",true,"reason","S45_TEST明确失败后按原请求恢复"),200);
  pump(p,7);testClock.advance(61);pump(p,7);assertEquals(4000,funds.refunded(p.order,"goods_cent"));assertEquals(200,funds.actual(p.order,"RETURN"));assertEquals(2,providerCount(p,"REFUND"));assertEquals(2,mockJdbc.queryForObject("SELECT send_count FROM li_p0_mock_funds_request WHERE request_no=?",Long.class,task.get("request_no")));
  result("RETURN_UNKNOWN_REFUND_FAILED_ORIGINAL_RETRY",map("orderId",p.order,"refundKeys",2,"firstRefundOriginalSendCount",2,"returnKeys",2));
 }
 @Test @Order(13) void lostProviderReceiptAndOutboxFailureRecoverWithoutAnotherMoneySend()throws Exception{
  var p=pay(supply(0,0,1,3,List.of(100)),1);control(p,"REFUND","LOST_RESPONSE");action(p,apply(p,p.items.getFirst(),1,100),"approve",map());pump(p,1);assertEquals(1,providerCount(p,"REFUND"));assertEquals(0,funds.refunded(p.order,"goods_cent"));testClock.advance(6);pump(p,6);assertEquals(100,funds.refunded(p.order,"goods_cent"));assertEquals(1,mockJdbc.queryForObject("SELECT send_count FROM li_p0_mock_funds_request WHERE transaction_id=? AND kind='REFUND'",Long.class,p.transaction));
  var ctx=(Stage1Context)ReflectionTestUtils.getField(s2tx,"context");var a3=((org.springframework.beans.factory.ObjectProvider<Stage3Authorization>)ReflectionTestUtils.getField(stage1Access,"stage3")).getObject();var jdbc=(JdbcTemplate)ReflectionTestUtils.getField(db,"jdbc");
  delivery=new Stage3Messages(fault,ctx,a3,orders,testClock,new DataSourceTransactionManager(jdbc.getDataSource()));var ext=new Stage45Messages(data,a3);ReflectionTestUtils.setField(delivery,"stage45",new StaticListableBeanFactory(Map.of("s45",ext)).getBeanProvider(Stage45Messages.class));
  fault.failPrefix="INSERT INTO li_member_message";assertFalse(delivery.deliverOne());testClock.advance(31);for(int i=0;i<3000&&delivery.deliverOne();i++){}long links=db.count("SELECT COUNT(*) FROM li_p0_s3_message_link WHERE order_id=? AND template='S45_REFUND_SUCCESS' AND recipient_domain='RESIDENT'",p.order);assertEquals(1,links);assertFalse(delivery.deliverOne());assertEquals(1,providerCount(p,"REFUND"));result("LOST_RECEIPT_AND_NATIVE_MESSAGE_FAILURE",map("orderId",p.order,"providerRequests",1,"residentSuccessReceipts",links));
 }
 @Test @Order(14) void remindersDoNotAdvancePhysicalReturnAndDeadlineDoesNotEraseEvaluation()throws Exception{
  var p=pay(supply(0,0,1,4,List.of(100)),2);complete(p);mvc=businessMvc;
  var sale=call(post(Stage45Routes.BUYER+"/orders/"+p.order+"/aftersales"),p.resident,map("operationId",req(),"orderItemId",p.items.getFirst(),"quantity",1,"amountCent",90,"type","RETURN_REFUND","reason","商品问题","files",List.of()),200);
  sale=action(p,sale,"approve",map("returnGuide",map("method","OFFLINE","contact","S45_TEST收货员","phone","13800000000","address","S45_TEST确认交回点","instructions","核对实物")));
  assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=?",p.order));testClock.advance(8*86400);var current=call(get(Stage45Routes.BUYER+"/aftersales/"+sale.get("id").getAsString()),p.resident,null,200);assertTrue(current.get("overdue").getAsBoolean());assertEquals("WAIT_RETURN",current.get("state").getAsString());
  sale=action(p,current,"return",map("returnInfo",map("method","OFFLINE","note","S45_TEST已交回，仍待明确实物收货")));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=?",p.order));sale=action(p,sale,"receive",map());pump(p,6);assertEquals(90,funds.refunded(p.order,"goods_cent"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_s45_inventory_return WHERE order_id=?",p.order));
  testClock.advance(8*86400);var qualified=call(get(Stage45Routes.BUYER+"/orders/"+deadlinePaid.order+"/eligibility"),deadlinePaid.resident,null,200);assertEquals("AFTERSALE_DEADLINE",qualified.get("blockReason").getAsString());assertTrue(qualified.getAsJsonArray("items").get(0).getAsJsonObject().get("canEvaluate").getAsBoolean());call(post(Stage45Routes.BUYER+"/orders/"+deadlinePaid.order+"/evaluations"),deadlinePaid.resident,map("operationId",req(),"orderItemId",deadlinePaid.items.getFirst(),"star",2,"content","","files",List.of()),200);
  result("PHYSICAL_RECEIPT_7_DAY_REMINDER_AND_EVALUATION_NO_DEADLINE",map("orderId",p.order,"refundCent",90,"inventoryReturned",0,"evaluationAfterDay15",true));
 }

 @Test @Order(8) void concurrentApplicationsCannotDoubleOccupyLastShare()throws Exception{
  var p=pay(supply(0,0,1,3,List.of(100)),1);var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
  try{var jobs=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return businessMvc.perform(post(Stage45Routes.BUYER+"/orders/"+p.order+"/aftersales").header("accessToken",p.resident.accessToken()).contentType("application/json").content(write(map("operationId",req(),"orderItemId",p.items.getFirst(),"quantity",1,"amountCent",100,"type","REFUND","reason","商品问题","files",List.of())))).andReturn().getResponse().getStatus();}));assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();var statuses=new ArrayList<Integer>();for(var job:jobs)statuses.add(job.get(30,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_s45_share WHERE item_id=? AND state='HELD'",p.items.getFirst()));result("REAL_DB_BARRIER_LAST_SHARE",map("orderId",p.order,"statuses",statuses));}finally{pool.shutdownNow();}
 }
}
