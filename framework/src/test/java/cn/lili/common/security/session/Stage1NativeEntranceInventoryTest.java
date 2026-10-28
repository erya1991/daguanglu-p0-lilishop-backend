package cn.lili.common.security.session;

import cn.lili.common.security.session.http.SessionRoutes;
import cn.lili.modules.community.p0.support.Stage1EntranceGuard;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Parse-only javac inventory: no class initialization, annotation processors, or native business invocation. */
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
class Stage1NativeEntranceInventoryTest {
    record Mapping(List<String> paths,List<String> methods){}
    static final List<String> ALL=List.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE");
    @Test void everyNativeControllerMappingIsDeniedExceptTheExplicitStage0SessionContract()throws Exception{
        Path run=Path.of(System.getenv("S1_RUN")).toRealPath(),backend=run.getParent().getParent().resolve("lilishop-backend");
        var evidence=new ArrayList<Object>();int[] allowed={0},denied={0},controllers={0};
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler,"A JDK is required for parse-only source inventory");
        try(var files=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)){
            for(String module:List.of("manager-api","buyer-api")){
                List<Path> sources;try(var walk=Files.walk(backend.resolve(module+"/src/main/java"))){sources=walk.filter(p->p.getFileName().toString().endsWith("Controller.java")).sorted().toList();}
                var diagnostics=new DiagnosticCollector<JavaFileObject>();
                var task=(JavacTask)compiler.getTask(null,files,diagnostics,List.of("-proc:none"),null,files.getJavaFileObjectsFromPaths(sources));
                for(var unit:task.parse()){
                    var positions=Trees.instance(task).getSourcePositions();Path source=Path.of(unit.getSourceFile().toUri());String hash=digest(Files.readAllBytes(source));
                    for(var type:unit.getTypeDecls())if(type instanceof ClassTree clazz){
                        var root=mapping(clazz.getModifiers().getAnnotations());if(root==null)continue;controllers[0]++;
                        for(var member:clazz.getMembers())if(member instanceof MethodTree method){var leaf=mapping(method.getModifiers().getAnnotations());if(leaf==null)continue;
                            for(String base:root.paths())for(String suffix:leaf.paths())for(String verb:leaf.methods()){
                                String template=(base+"/"+suffix).replaceAll("/+","/").replaceAll("/$","");
                                String uri=template.replaceAll("\\{[^}]+}","123456789").replace("**","probe").replace("*","probe");
                                boolean expected=allowedSession(verb,uri);var request=new MockHttpServletRequest(verb,uri);var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
                                new Stage1EntranceGuard().doFilter(request,response,(r,s)->reached.set(true));assertEquals(expected,reached.get(),verb+" "+template);
                                if(expected)allowed[0]++;else{denied[0]++;assertEquals(403,response.getStatus(),template);assertTrue(response.getContentAsString().contains("STAGE1_ENTRANCE_DISABLED"));}
                                evidence.add(map("module",module,"source",source.toString(),"sha256",hash,"line",unit.getLineMap().getLineNumber(positions.getStartPosition(unit,method)),"controller",clazz.getSimpleName().toString(),"handler",method.getName().toString(),"method",verb,"template",template,"probe",uri,"decision",expected?"STAGE0_SESSION_CONTRACT":"DENIED_BY_STAGE1_RUNTIME"));
                            }
                        }
                    }
                }
                assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d->d.getKind()==Diagnostic.Kind.ERROR),"Controller source must parse without guessing mappings");
            }
        }
        assertTrue(controllers[0]>200);assertTrue(denied[0]>500);assertTrue(allowed[0]>5);
        Files.writeString(run.resolve("evidence/native-entry-inventory-"+System.currentTimeMillis()+".json"),write(map("source","Java compiler parse-only AST plus executed Stage1EntranceGuard for each native mapping","controllers",controllers[0],"denied",denied[0],"sessionPaths",allowed[0],"routes",evidence)),StandardOpenOption.CREATE_NEW);
    }
    static Mapping mapping(List<? extends AnnotationTree> annotations)throws Exception{
        for(var annotation:annotations){String name=annotation.getAnnotationType().toString();name=name.substring(name.lastIndexOf('.')+1);if(!Set.of("RequestMapping","GetMapping","PostMapping","PutMapping","DeleteMapping","PatchMapping").contains(name))continue;
            List<String> paths=List.of(""),methods=name.equals("RequestMapping")?ALL:List.of(name.substring(0,name.length()-7).toUpperCase(Locale.ROOT));
            for(var argument:annotation.getArguments()){String key="value";ExpressionTree value=argument;if(argument instanceof AssignmentTree assignment){key=assignment.getVariable().toString();value=assignment.getExpression();}
                if(key.equals("value")||key.equals("path"))paths=strings(value,false);if(key.equals("method"))methods=strings(value,true);
            }return new Mapping(paths,methods);
        }return null;
    }
    static List<String> strings(ExpressionTree expression,boolean enumValue)throws Exception{
        if(expression instanceof NewArrayTree array){var result=new ArrayList<String>();for(var item:array.getInitializers())result.addAll(strings(item,enumValue));return result;}
        if(expression instanceof LiteralTree literal&&literal.getValue() instanceof String value)return List.of(value);
        if(enumValue)return List.of(expression.toString().substring(expression.toString().lastIndexOf('.')+1));
        if(expression instanceof BinaryTree binary&&binary.getKind()==Tree.Kind.PLUS)return List.of(strings(binary.getLeftOperand(),false).getFirst()+strings(binary.getRightOperand(),false).getFirst());
        if(expression.toString().startsWith("SessionRoutes."))return List.of((String)SessionRoutes.class.getField(expression.toString().substring(14)).get(null));
        throw new IllegalArgumentException("Unresolved native route constant: "+expression);
    }
    static boolean allowedSession(String method,String uri){
        if(method.equals("POST")){if(Set.of(SessionRoutes.RESIDENT_LOGIN,SessionRoutes.MERCHANT_LOGIN,SessionRoutes.MANAGER_LOGIN,SessionRoutes.RESIDENT_PHONE,SessionRoutes.RESIDENT_AVATAR,SessionRoutes.RESIDENT_LINK).contains(uri))return true;for(var domain:SessionDomain.values())if(uri.equals(SessionRoutes.refresh(domain))||uri.equals(SessionRoutes.logout(domain)))return true;}
        if(method.equals("PUT")&&uri.equals(SessionRoutes.RESIDENT_EDIT))return true;
        return method.equals("GET")&&Set.of("/buyer/passport/member",SessionRoutes.APPLICANT+"/identity","/store/member/user","/store/member/user/info","/manager/passport/user/info","/manager/permission/menu/memberMenu").contains(uri);
    }
}
