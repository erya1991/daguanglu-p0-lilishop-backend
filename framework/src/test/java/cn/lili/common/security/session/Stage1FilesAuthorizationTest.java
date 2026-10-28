package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
class Stage1FilesAuthorizationTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @AfterEach void clear(){sqlObserver.remove();}
    @Test void interruptedUploadAndFailedDraftSaveKeepOriginalReferenceAndUnboundReplacement()throws Exception{
        var applicant=login("S1_TEST_file_failure_"+req());String f1=upload(applicant.session(),Stage1Routes.APPLICANT);var draft=call(post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",payload(f1)),200);String id=draft.get("id").getAsString();var before=db.required("SELECT draft_payload,version FROM li_p0_application WHERE id=?",id);long filesBefore=db.count("SELECT COUNT(*) FROM li_file");
        var broken=new org.springframework.mock.web.MockMultipartFile("file","test.png","image/png",new byte[]{1,2,3}){@Override public java.io.InputStream getInputStream()throws java.io.IOException{throw new java.io.IOException("S1_TEST upload interrupted");}};assertEquals(503,mvc.perform(multipart(Stage1Routes.APPLICANT+"/files").file(broken).param("metadata",write(map("requestId",req(),"purpose","QUALIFICATION"))).header("accessToken",applicant.session().accessToken())).andReturn().getResponse().getStatus());assertEquals(filesBefore,db.count("SELECT COUNT(*) FROM li_file"));
        String f2=upload(applicant.session(),Stage1Routes.APPLICANT);var body=map("requestId",req(),"expectedVersion",0,"payload",payload(f2));sqlObserver.set(sql->{if(sql.startsWith("UPDATE li_p0_application SET draft_payload"))throw new java.sql.SQLTransientConnectionException("S1_TEST draft interrupted","08006");});call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),applicant.session(),body,503);sqlObserver.remove();assertEquals(before,db.required("SELECT draft_payload,version FROM li_p0_application WHERE id=?",id));assertEquals("UPLOADED",db.required("SELECT status FROM li_p0_file_control WHERE file_id=?",f2).get("status"));assertEquals(200,mvc.perform(get(Stage1Routes.APPLICANT+"/files/"+f1+"/preview").header("accessToken",applicant.session().accessToken())).andReturn().getResponse().getStatus());
        call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),applicant.session(),body,200);assertTrue(String.valueOf(db.required("SELECT draft_payload FROM li_p0_application WHERE id=?",id).get("draft_payload")).contains(f2));assertEquals("UPLOADED",db.required("SELECT status FROM li_p0_file_control WHERE file_id=?",f2).get("status"));
        var source=object(payload(f2));var q=source.getAsJsonArray("qualifications").get(0).getAsJsonObject();q.addProperty("longTerm",true);q.remove("validThrough");q.add("fileIds",new com.google.gson.JsonArray());call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),applicant.session(),map("requestId",req(),"expectedVersion",1,"payload",source),200);call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),applicant.session(),map("requestId",req(),"expectedVersion",2),422);q.addProperty("longTerm",false);q.add("fileIds",new com.google.gson.Gson().toJsonTree(List.of(f2)));call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),applicant.session(),map("requestId",req(),"expectedVersion",2,"payload",source),200);call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),applicant.session(),map("requestId",req(),"expectedVersion",3),422);assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",id));
    }
    @Test void agreementPreviewRechecksPermissionAndCannotBecomePublicProductMediaOrForeignEvidence()throws Exception{
        var owner=ownerFixture();String agreement=agreementFixture(owner,0,java.time.LocalDate.now(Stage1Rules.ZONE),null);var terms=object(db.required("SELECT terms FROM li_p0_agreement WHERE id=?",agreement).get("terms"));String file=terms.getAsJsonArray("fileIds").get(0).getAsString(),url=Stage1Routes.STORE+"/files/"+file+"/preview";assertEquals(200,mvc.perform(get(url).header("accessToken",owner.login().session().accessToken())).andReturn().getResponse().getStatus());
        var other=ownerFixture();call(get(url),other.login().session(),null,404);var employee=Stage1AuthorizationMatrixTest.employee(owner,List.of(Stage1AuthorizationMatrixTest.roles(owner).get("COOPERATION_VIEWER")));assertEquals(200,mvc.perform(get(url).header("accessToken",employee.login().session().accessToken())).andReturn().getResponse().getStatus());call(post(Stage1Routes.STORE+"/staff/"+employee.clerk()+"/revoke"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"reason","S1_TEST撤销附件访问"),200);call(get(url),employee.login().session(),null,403);
        call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),file,1)),404);var goods=Stage1SupplyMatrixTest.createGoods(owner,1,List.of("MERCHANT_DELIVERY"),false);call(get(Stage1Routes.STORE+"/goods/"+goods.getAsJsonObject("goods").get("id").getAsString()+"/images/"+file),owner.login().session(),null,404);
        var invalid=map("requestId",req(),"storeId",owner.store(),"businessType","GROUP_BUY","payload",terms);call(post(Stage1Routes.MANAGER+"/agreements"),operator,invalid,422);String foreign=agreementFixture(other,0,java.time.LocalDate.now(Stage1Rules.ZONE),null);terms.add("fileIds",object(db.required("SELECT terms FROM li_p0_agreement WHERE id=?",foreign).get("terms")).get("fileIds"));call(post(Stage1Routes.MANAGER+"/agreements"),operator,map("requestId",req(),"storeId",owner.store(),"businessType","MALL","payload",terms),404);
    }
    @Test void managerCannotGuessUnboundPrivateFilesAndOnlyReviewerCanReadPendingMaterial()throws Exception{
        var applicant=login("file_scope_"+req());String file=upload(applicant.session(),Stage1Routes.APPLICANT,"QUALIFICATION"),url=Stage1Routes.MANAGER+"/files/"+file+"/preview";
        for(var actor:List.of(reviewer,operator,channel))call(get(url),actor,null,404);
        var draft=call(post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",payload(file)),200);String application=draft.get("id").getAsString();call(post(Stage1Routes.APPLICANT+"/application/"+application+"/submit"),applicant.session(),map("requestId",req(),"expectedVersion",0),200);
        assertEquals(200,mvc.perform(get(url).header("accessToken",reviewer.accessToken())).andReturn().getResponse().getStatus());call(get(url),operator,null,404);call(get(url),channel,null,404);
        call(post(Stage1Routes.MANAGER+"/applications/"+application+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1),200);
        for(var actor:List.of(reviewer,operator,channel))assertEquals(200,mvc.perform(get(url).header("accessToken",actor.accessToken())).andReturn().getResponse().getStatus());
        var stranger=login("file_stranger_"+req());call(get(Stage1Routes.APPLICANT+"/files/"+file+"/preview"),stranger.session(),null,404);
    }
}
