package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Field matrices exercise the production services, real persistence and real authorization.
 * Clock changes are restricted to this test's StaffService instance. */
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1BusinessBoundaryTest {
    static final Gson GSON=new Gson();
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    static JsonObject changed(JsonObject original,String field,Object value){var copy=original.deepCopy();copy.add(field,GSON.toJsonTree(value));return copy;}
    static JsonObject draft(SessionTokens token,String id,long version,Object payload,int status)throws Exception{return call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),token,map("requestId",req(),"expectedVersion",version,"payload",payload),status);}
    @Test void bnd001AdmissionFieldsRejectInvalidDraftsWithoutSubmittedRevisions()throws Exception{
        var applicant=login("S1_TEST_boundary_"+req());var token=applicant.session();String file=upload(token,Stage1Routes.APPLICANT);var base=object(payload(file));
        var first=call(post(Stage1Routes.APPLICANT+"/application"),token,map("requestId",req(),"payload",Map.of()),200);String id=first.get("id").getAsString();
        call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),token,map("requestId",req(),"expectedVersion",0),422);
        var invalid=new LinkedHashMap<String,List<Object>>();invalid.put("legalName",List.of("字","字".repeat(101)));invalid.put("legalPerson",List.of("字","字".repeat(51)));invalid.put("storeName",List.of("字","字".repeat(31)));invalid.put("contactName",List.of("字","字".repeat(31)));invalid.put("address",List.of("字".repeat(4),"字".repeat(201)));invalid.put("description",List.of("字".repeat(501)));invalid.put("subjectType",List.of("PERSON"));String code=base.get("legalCode").getAsString();invalid.put("legalCode",List.of(code.substring(0,17),code+"0",code.substring(0,17)+(code.endsWith("0")?"1":"0")));invalid.put("phone",List.of("abc12345678","123",""));
        for(var field:invalid.entrySet())for(var value:field.getValue()){draft(token,id,0,changed(base,field.getKey(),value),422);assertEquals(0,db.count("SELECT version FROM li_p0_application WHERE id=?",id));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",id));}
        long version=0;
        for(int edge:new int[]{0,1}){var valid=base.deepCopy();valid.addProperty("legalName","字".repeat(edge==0?2:100));valid.getAsJsonArray("qualifications").get(0).getAsJsonObject().addProperty("holder",valid.get("legalName").getAsString());valid.addProperty("legalPerson","字".repeat(edge==0?2:50));valid.addProperty("storeName","字".repeat(edge==0?2:30));valid.addProperty("contactName","字".repeat(edge==0?2:30));valid.addProperty("address","字".repeat(edge==0?5:200));valid.addProperty("description","字".repeat(edge==0?0:500));draft(token,id,version++,valid,200);}
        var submitted=call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),token,map("requestId",req(),"expectedVersion",version),200);String store=submitted.get("storeId").getAsString();assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",id));
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/reject"),reviewer,map("requestId",req(),"expectedVersion",3,"revision",1,"reason","S1_TEST补充资料"),200);
        var individual=base.deepCopy();individual.addProperty("subjectType","INDIVIDUAL_BUSINESS");draft(token,id,4,individual,200);var resubmitted=call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),token,map("requestId",req(),"expectedVersion",5),200);assertEquals(store,resubmitted.get("storeId").getAsString());
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",3,"revision",1),409);
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",6,"revision",2),200);
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE store_id=? AND shopkeeper=1",store));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",id));
        // Same display name is neither an ownership proof nor a reason to reject another legal entity.
        var second=login("S1_TEST_samename_"+req());var secondPayload=object(payload(upload(second.session(),Stage1Routes.APPLICANT)));secondPayload.addProperty("storeName",individual.get("storeName").getAsString());var other=call(post(Stage1Routes.APPLICANT+"/application"),second.session(),map("requestId",req(),"payload",secondPayload),200);var otherSubmitted=call(post(Stage1Routes.APPLICANT+"/application/"+other.get("id").getAsString()+"/submit"),second.session(),map("requestId",req(),"expectedVersion",0),200);assertNotEquals(store,otherSubmitted.get("storeId").getAsString());
        observations.add(map("case","BND-001","invalidDraftRollback",true,"bothSubjectTypes",true,"resubmissionSameStore",true,"oldReviewRejected",true));
    }
    static JsonObject category(String parent,String name,Object sort,int status)throws Exception{return call(post(Stage1Routes.MANAGER+"/categories"),operator,map("requestId",req(),"parentId",parent,"name",name,"sort",sort,"reason","S1_TEST类目矩阵"),status);}
    @Test void bnd005CatalogTreeNormalizationAndReferencedUnitProtection()throws Exception{
        for(String name:List.of("","字".repeat(31)))category("0",name,0,422);for(Object sort:List.of(-1,10000,0.5))category("0","S1_TEST"+db.id(),sort,422);
        String root=category("0","S1_TEST"+db.id(),9999,200).get("id").getAsString();String child=category(root,"Ａ",0,200).get("id").getAsString();category(root,"a",0,409);String leaf=category(child,"字".repeat(30),0,200).get("id").getAsString();category(leaf,"第四级",0,422);category("999999999999999999","不存在",0,404);category(category("0","S1_TEST"+db.id(),0,200).get("id").getAsString(),"a",0,200);
        call(put(Stage1Routes.MANAGER+"/categories/"+root),operator,map("requestId",req(),"expectedVersion",0,"parentId",leaf,"name","S1_TEST环","sort",0,"reason","S1_TEST不能成环"),422);
        call(post(Stage1Routes.MANAGER+"/categories/"+child+"/enable"),operator,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST父未启用"),422);
        for(String c:List.of(root,child,leaf))call(post(Stage1Routes.MANAGER+"/categories/"+c+"/enable"),operator,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST逐级启用"),200);
        for(String name:List.of("","字".repeat(11)))call(post(Stage1Routes.MANAGER+"/units"),operator,map("requestId",req(),"name",name,"reason","S1_TEST单位矩阵"),422);
        String unit=unitFixture();String unitName=String.valueOf(db.required("SELECT name FROM li_goods_unit WHERE id=?",unit).get("name"));call(post(Stage1Routes.MANAGER+"/units"),operator,map("requestId",req(),"name",unitName,"reason","S1_TEST重复单位"),409);
        var owner=ownerFixture();var saved=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",goodsPayload(unit,upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),1)),200);String goods=saved.getAsJsonObject("goods").get("id").getAsString();
        call(put(Stage1Routes.MANAGER+"/units/"+unit),operator,map("requestId",req(),"expectedVersion",1,"name","变义单位","reason","S1_TEST不可改义"),409);
        call(post(Stage1Routes.MANAGER+"/units/"+unit+"/disable"),operator,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST禁止新选"),200);
        assertEquals(unitName,db.required("SELECT name FROM li_goods_unit WHERE id=?",unit).get("name"));assertEquals(unit,String.valueOf(db.required("SELECT unit_id FROM li_p0_goods_control WHERE goods_id=?",goods).get("unit_id")));
        call(delete(Stage1Routes.MANAGER+"/units/"+unit),operator,null,403);call(delete("/manager/goods/goodsUnit/"+unit),operator,null,403);
        observations.add(map("case","BND-005","threeLevels",true,"normalizedNames",true,"referencedUnitPreserved",true));
    }
    static JsonObject pointBody(){return object(map("requestId",req(),"type","COMMUNITY_PICKUP","name","S1_TEST点位","provinceCode","990000","cityCode","990100","districtCode","990101","address","S1_TEST结构化地址","contactName","合成联系人","phone","13800000000","opening",map("weekdays",List.of(1,7),"allDay",false,"start","22:00","end","06:00","crossDay",true),"instructions","","reason","S1_TEST点位边界"));}
    @Test void bnd011And012PointHoursCoordinatesAndFourMethodFees()throws Exception{
        var owner=ownerFixture();var token=owner.login().session();var base=pointBody();
        for(var entry:Map.<String,List<Object>>of("name",List.of("字","字".repeat(41)),"address",List.of("字".repeat(4),"字".repeat(201)),"contactName",List.of("字","字".repeat(31)),"instructions",List.of("字".repeat(501)),"phone",List.of("abcdefgh"),"coordinates",List.of(map("longitudeMillionths",180000001,"latitudeMillionths",0),map("longitudeMillionths",0,"latitudeMillionths",-90000001),map("longitudeMillionths",1.1,"latitudeMillionths",0))).entrySet())for(Object value:entry.getValue())call(post(Stage1Routes.STORE+"/points"),token,changed(base,entry.getKey(),value),422);
        for(Object opening:List.of(map("weekdays",List.of(0),"allDay",true),map("weekdays",List.of(1,1),"allDay",true),map("weekdays",List.of(1),"allDay",true,"start","09:00"),map("weekdays",List.of(1),"allDay",false,"start","22:00","end","06:00","crossDay",false),map("weekdays",List.of(1),"allDay",false,"start","24:00","end","06:00","crossDay",true)))call(post(Stage1Routes.STORE+"/points"),token,changed(base,"opening",opening),422);
        var legal=changed(changed(changed(changed(base,"name","字".repeat(40)),"address","字".repeat(200)),"contactName","字".repeat(30)),"instructions","字".repeat(500));legal.add("coordinates",GSON.toJsonTree(map("longitudeMillionths",180000000,"latitudeMillionths",-90000000)));
        var point=call(post(Stage1Routes.STORE+"/points"),token,legal,200);String id=point.get("id").getAsString();assertFalse(point.get("enabled").getAsBoolean());
        legal.addProperty("requestId",req());legal.addProperty("expectedVersion",0);legal.addProperty("type","STORE_REDEMPTION");call(put(Stage1Routes.STORE+"/points/"+id),token,legal,422);
        call(post(Stage1Routes.STORE+"/points/"+id+"/enable"),token,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST启用"),200);
        String redemption=createPointFixture(owner,"STORE_REDEMPTION");call(post(Stage1Routes.STORE+"/points/"+redemption+"/enable"),token,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST启用"),200);
        var methods=object(deliveryMethods());methods.getAsJsonObject("COMMUNITY_PICKUP").addProperty("enabled",true);methods.getAsJsonObject("STORE_REDEMPTION").addProperty("enabled",true);
        for(Object fee:List.of(-1,1000000,0.5)){var invalid=methods.deepCopy();invalid.getAsJsonObject("MERCHANT_DELIVERY").add("feeCent",GSON.toJsonTree(fee));call(put(Stage1Routes.STORE+"/fulfillment"),token,map("requestId",req(),"expectedVersion",0,"methods",invalid,"reason","S1_TEST非法费用"),422);}
        for(String method:List.of("COMMUNITY_PICKUP","STORE_REDEMPTION")){var invalid=methods.deepCopy();invalid.getAsJsonObject(method).addProperty("feeCent",1);call(put(Stage1Routes.STORE+"/fulfillment"),token,map("requestId",req(),"expectedVersion",0,"methods",invalid,"reason","S1_TEST点位免费"),422);}
        var noFee=methods.deepCopy();noFee.getAsJsonObject("MERCHANT_DELIVERY").remove("feeCent");call(put(Stage1Routes.STORE+"/fulfillment"),token,map("requestId",req(),"expectedVersion",0,"methods",noFee,"reason","S1_TEST未填不等于零"),422);
        methods.getAsJsonObject("MERCHANT_DELIVERY").addProperty("feeCent",999999);methods.add("EXPRESS",GSON.toJsonTree(map("enabled",true,"feeCent",0,"nationwide",true,"excludedDistrictCodes",List.of("990101"))));
        call(put(Stage1Routes.STORE+"/fulfillment"),token,map("requestId",req(),"expectedVersion",0,"methods",methods,"reason","S1_TEST四方式"),200);
        call(post(Stage1Routes.STORE+"/points/"+id+"/disable"),token,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST最后自提点"),200);
        var config=call(get(Stage1Routes.STORE+"/fulfillment"),token,null,200);var effective=config.getAsJsonArray("effectiveMethods").toString();assertFalse(effective.contains("COMMUNITY_PICKUP"));assertTrue(effective.contains("STORE_REDEMPTION"));assertTrue(effective.contains("MERCHANT_DELIVERY"));assertTrue(effective.contains("EXPRESS"));
        var detail=call(get(Stage1Routes.STORE+"/points/"+id),token,null,200);assertEquals(3,detail.getAsJsonArray("history").size());assertEquals(1,db.count("SELECT COUNT(*) FROM li_store_address WHERE id=? AND delete_flag=0",id));
        var other=ownerFixture();call(get(Stage1Routes.STORE+"/points/"+id),other.login().session(),null,404);call(delete("/store/member/storeAddress/"+id),token,null,403);
        observations.add(map("case","BND-011-012","nativePointHistory",3,"lastPointOnlyRemovesOwnMethod",true,"integerFees",true));
    }
    @Test void bnd010InvitationAndOwnerConfirmationHaveIndependentExact24HourDeadlines()throws Exception{
        var owner=ownerFixture();String role=String.valueOf(db.required("SELECT id FROM li_store_role WHERE store_id=? AND name='READ_ONLY'",owner.store()).get("id"));
        Instant t=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        try{
            ReflectionTestUtils.setField(staff,"clock",Clock.fixed(t,ZoneOffset.UTC));
            var first=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role),"reason","S1_TEST邀请边界"),200);var person=login("S1_TEST_invite_"+req());
            ReflectionTestUtils.setField(staff,"clock",Clock.fixed(t.plusSeconds(86400),ZoneOffset.UTC));
            call(post(Stage1Routes.APPLICANT+"/invitations/accept"),person.session(),map("requestId",req(),"expectedVersion",0,"token",first.get("invitationToken").getAsString()),409);assertEquals(0,db.count("SELECT version FROM li_p0_staff_invitation WHERE id=?",first.get("id").getAsString()));
            ReflectionTestUtils.setField(staff,"clock",Clock.fixed(t,ZoneOffset.UTC));var second=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role),"reason","S1_TEST二段期限"),200);
            Instant accepted=t.plusSeconds(86400).minusMillis(1);ReflectionTestUtils.setField(staff,"clock",Clock.fixed(accepted,ZoneOffset.UTC));var acceptedRow=call(post(Stage1Routes.APPLICANT+"/invitations/accept"),person.session(),map("requestId",req(),"expectedVersion",0,"token",second.get("invitationToken").getAsString()),200);assertEquals(accepted.plusSeconds(86400),Instant.parse(acceptedRow.get("confirmDeadline").getAsString()));
            ReflectionTestUtils.setField(staff,"clock",Clock.fixed(accepted.plusSeconds(86400),ZoneOffset.UTC));call(post(Stage1Routes.STORE+"/staff-invitations/"+second.get("id").getAsString()+"/confirm"),owner.login().session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST过期复核"),409);
            assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE store_id=?",owner.store()));
            ReflectionTestUtils.setField(staff,"clock",Clock.fixed(t,ZoneOffset.UTC));var third=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role),"reason","S1_TEST合法边界"),200);call(post(Stage1Routes.APPLICANT+"/invitations/accept"),person.session(),map("requestId",req(),"expectedVersion",0,"token",third.get("invitationToken").getAsString()),200);
            ReflectionTestUtils.setField(staff,"clock",Clock.fixed(t.plusSeconds(86400).minusMillis(1),ZoneOffset.UTC));call(post(Stage1Routes.STORE+"/staff-invitations/"+third.get("id").getAsString()+"/confirm"),owner.login().session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST有效复核"),200);assertEquals(2,db.count("SELECT COUNT(*) FROM li_clerk WHERE store_id=?",owner.store()));
        }finally{ReflectionTestUtils.setField(staff,"clock",clock);}
        observations.add(map("case","BND-010","invite24hExclusive",true,"confirm24hFromAcceptance",true,"noExpiredGrant",true));
    }
}
