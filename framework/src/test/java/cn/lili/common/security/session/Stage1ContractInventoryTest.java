package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.http.server.PathContainer;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
class Stage1ContractInventoryTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @Test void finiteAuthorizationCatalogueResolvesEveryControllerAndRecordsSourceDependencies()throws Exception{
        var handlers=mvc.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class).getHandlerMethods();
        Path main=run.getParent().getParent().resolve("lilishop-backend/framework/src/main/java");var inventory=new ArrayList<Object>();
        for(var route:Stage1Routes.all()){
            String path=route.template().replace("{id}","123456789").replace("{fileId}","123456788").replace("{requestId}","s1_inventory_request");
            var matches=handlers.entrySet().stream().filter(entry->entry.getKey().getMethodsCondition().getMethods().stream().anyMatch(method->method.name().equals(route.method()))&&entry.getKey().getPatternValues().stream().anyMatch(pattern->new PathPatternParser().parse(pattern).matches(PathContainer.parsePath(path)))).toList();
            assertFalse(matches.isEmpty(),route.method()+" "+route.template());var ordered=matches.stream().sorted((a,b)->org.springframework.web.util.pattern.PathPattern.SPECIFICITY_COMPARATOR.compare(bestPattern(a.getKey(),path),bestPattern(b.getKey(),path))).toList();if(ordered.size()>1)assertNotEquals(0,org.springframework.web.util.pattern.PathPattern.SPECIFICITY_COMPARATOR.compare(bestPattern(ordered.get(0).getKey(),path),bestPattern(ordered.get(1).getKey(),path)),"Ambiguous route "+path);var handler=ordered.getFirst().getValue();Class<?> type=handler.getBeanType();Path file=main.resolve(type.getName().replace('.','/')+".java");String text=Files.readString(file);String method=handler.getMethod().getName();int offset=text.indexOf(" "+method+"(");assertTrue(offset>=0);int line=text.substring(0,offset).split("\n").length;
            var dependencies=new ArrayList<Object>();for(var field:type.getDeclaredFields())if(field.getType().getName().startsWith("cn.lili.modules.community.p0.")){
                Path service=main.resolve(field.getType().getName().replace('.','/')+".java");String source=Files.readString(service);var tables=new TreeSet<String>();var matcher=Pattern.compile("(?i)(?:FROM|JOIN|UPDATE|INTO)\\s+(li_[a-z0-9_]+)|nativeInsert\\(\\\"(li_[a-z0-9_]+)").matcher(source);while(matcher.find())tables.add(matcher.group(1)!=null?matcher.group(1):matcher.group(2));
                dependencies.add(map("field",field.getName(),"type",field.getType().getName(),"source",service.toString(),"sha256",digest(Files.readAllBytes(service)),"possibleTablesInDependency",tables));
            }
            inventory.add(map("method",route.method(),"template",route.template(),"identityDomain",route.domain().name(),"permission",route.action(),"controller",type.getName(),"handler",method,"source",file.toString(),"line",line,"sha256",digest(Files.readAllBytes(file)),"directDependencies",dependencies,"tableEvidenceKind","STATIC_SERVICE_REFERENCES; action-specific runtime assertions are separate"));
        }
        // Shared controller shapes not in the finite domain catalogue must remain unreachable.
        for(String path:List.of(Stage1Routes.BUYER+"/files",Stage1Routes.BUYER+"/files/123/preview",Stage1Routes.BUYER+"/operations/s1_inventory_request/abandon",Stage1Routes.APPLICANT+"/goods/123/images/456"))for(String method:List.of("GET","POST","PUT","DELETE"))call(request(org.springframework.http.HttpMethod.valueOf(method),path),null,null,403);
        Files.writeString(run.resolve("evidence/controller-contract-"+testId+".json"),write(map("source","Actual Spring RequestMappingHandlerMapping paired with finite authorization routes","routes",inventory,"nativePersistence","GoodsServiceImpl/GoodsSkuServiceImpl with native MyBatis mappers; Stage1Db guarded JdbcTemplate for auxiliary tables","count",inventory.size())),StandardOpenOption.CREATE_NEW);
        assertEquals(Stage1Routes.all().size(),inventory.size());
    }
    static org.springframework.web.util.pattern.PathPattern bestPattern(org.springframework.web.servlet.mvc.method.RequestMappingInfo mapping,String path){return mapping.getPatternValues().stream().map(pattern->new PathPatternParser().parse(pattern)).filter(pattern->pattern.matches(PathContainer.parsePath(path))).min(org.springframework.web.util.pattern.PathPattern.SPECIFICITY_COMPARATOR).orElseThrow();}
}
