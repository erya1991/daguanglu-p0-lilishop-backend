package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.SessionException;
import com.google.gson.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@RestControllerAdvice(basePackages="cn.lili.modules.community.p0")
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class Stage1Responses {
    public static Object success(Object result){return map("success",true,"code","SUCCESS","message","成功","result",plain(result),"errors",List.of(),"retryable",false);}
    private static Object plain(Object value){return new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create().fromJson(write(value),Object.class);}
    @ExceptionHandler(Stage1Exception.class) public ResponseEntity<Object> business(Stage1Exception e){return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).body(map("success",false,"code",e.code(),"message",e.code(),"result",null,"errors",e.errors(),"details",plain(e.details()),"retryable",false));}
    @ExceptionHandler(SessionException.class) public ResponseEntity<Object> session(SessionException e){return failure(e.getCode()==SessionException.Code.AUTHORIZATION_REQUIRED||e.getCode()==SessionException.Code.STORE_UNAVAILABLE?503:403,"SESSION_REJECTED",List.of());}
    @ExceptionHandler(DuplicateKeyException.class) public ResponseEntity<Object> duplicate(){return failure(409,"UNIQUE_CONFLICT",List.of());}
    @ExceptionHandler(ConcurrencyFailureException.class) public ResponseEntity<Object> conflict(){return failure(409,"CONCURRENT_CONFLICT",List.of());}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Object> unavailable(){return failure(503,"STORAGE_UNAVAILABLE",List.of());}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<Object> malformed(){return failure(400,"FIELD_INVALID",List.of());}
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> malformedParameter(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e){return failure(400,"FIELD_INVALID",List.of(map("field",e.getName(),"code","TYPE_INVALID")));}
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<Object> internal(){return failure(500,"INTERNAL_ERROR",List.of());}
    private ResponseEntity<Object> failure(int status,String code,Object errors){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(map("success",false,"code",code,"message",code,"result",null,"errors",errors,"retryable",false));}
}
