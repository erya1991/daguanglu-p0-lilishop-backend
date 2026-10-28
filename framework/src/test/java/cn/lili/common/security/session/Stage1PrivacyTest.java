package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
class Stage1PrivacyTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @Test void q015PrivateSentinelsNeverEnterOrdinaryListsAuditDiagnosticsOrOtherStoreResponses()throws Exception{
        var root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();root.addAppender(events);
        var publicViews=new ArrayList<String>();var sentinels=new ArrayList<String>();
        try{
            String open="S1_TEST_private_"+req();var person=login(open);sentinels.add(person.session().accessToken());sentinels.add(person.session().refreshToken());
            String document="S1_TEST_PRIVATE_DOCUMENT_"+req();sentinels.add(document);var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);bytes.write(document.getBytes(StandardCharsets.UTF_8));
            var uploaded=mvc.perform(multipart(Stage1Routes.APPLICANT+"/files").file(new MockMultipartFile("file","synthetic-private.png","image/png",bytes.toByteArray())).param("metadata",write(map("requestId",req(),"purpose","QUALIFICATION"))).header("accessToken",person.session().accessToken())).andReturn();String file=checked(uploaded,200).get("id").getAsString();
            var input=object(payload(file));sentinels.add(input.get("legalCode").getAsString());
            String id=call(post(Stage1Routes.APPLICANT+"/application"),person.session(),map("requestId",req(),"payload",input),200).get("id").getAsString();call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),person.session(),map("requestId",req(),"expectedVersion",0),200);
            var download=mvc.perform(get(Stage1Routes.MANAGER+"/files/"+file+"/preview").header("accessToken",reviewer.accessToken())).andReturn().getResponse();assertEquals(200,download.getStatus());assertTrue(new String(download.getContentAsByteArray(),StandardCharsets.ISO_8859_1).contains(document));
            publicViews.add(call(get(Stage1Routes.MANAGER+"/applications"),reviewer,null,200).toString());
            var other=ownerFixture();publicViews.add(call(get(Stage1Routes.STORE+"/files/"+file+"/preview"),other.login().session(),null,404).toString());
            publicViews.add(call(get(Stage1Routes.MANAGER+"/applications/"+id),other.login().session(),null,403).toString());
            call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1),200);
            publicViews.add(call(get(Stage1Routes.MANAGER+"/stores"),operator,null,200).toString());
            var owner=login(open);String store=owner.identity().equals("STORE")?call(get(Stage1Routes.STORE+"/profile"),owner.session(),null,200).get("id").getAsString():null;assertNotNull(store);
            String role=id(db.required("SELECT id FROM li_store_role WHERE store_id=? AND name='READ_ONLY'",store),"id");var invite=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.session(),map("requestId",req(),"roleIds",List.of(role),"reason","S1_TEST私密传递"),200);sentinels.add(invite.get("invitationToken").getAsString());
            publicViews.add(call(get(Stage1Routes.STORE+"/staff-invitations"),owner.session(),null,200).toString());
            publicViews.add(write(db.rows("SELECT * FROM li_p0_audit WHERE object_id IN (?,?,?)",id,file,invite.get("id").getAsString())));
            assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_audit WHERE action='file.read' AND object_id=?",file));
            var approval=db.required("SELECT actor_id,identity_domain,before_version,after_version,created_at FROM li_p0_audit WHERE object_id=? AND action='application.approve'",id);for(String field:List.of("actor_id","identity_domain","before_version","after_version","created_at"))assertNotNull(approval.get(field));
            for(var event:events.list)publicViews.add(event.getFormattedMessage()+ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));
            for(String value:publicViews)for(String sentinel:sentinels)assertFalse(value.contains(sentinel),"Private sentinel leaked into ordinary evidence");
            observations.add(map("case","Q015/W02-006/M02-007","sentinelKinds",List.of("access","refresh","credit-code","private-document","invitation-secret"),"checkedRecords",publicViews.size(),"authorizedPrivateReadAudited",true,"plaintextFindings",0));
        }finally{root.detachAppender(events);events.stop();}
    }
}
