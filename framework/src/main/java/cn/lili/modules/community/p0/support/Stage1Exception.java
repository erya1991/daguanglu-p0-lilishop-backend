package cn.lili.modules.community.p0.support;

import java.util.List;
import java.util.Map;

/** Safe public codes only; SQL, input values and credential material never become messages. */
public final class Stage1Exception extends RuntimeException {
    private final int status;
    private final String code;
    private final List<Map<String,String>> errors;
    private final Map<String,Object> details;
    public Stage1Exception(int status, String code) { this(status,code,List.of()); }
    public Stage1Exception(int status, String code, List<Map<String,String>> errors) {
        super(code); this.status=status; this.code=code; this.errors=List.copyOf(errors);this.details=Map.of();
    }
    public Stage1Exception(int status,String code,Map<String,Object> details){super(code);this.status=status;this.code=code;this.errors=List.of();this.details=Map.copyOf(details);}
    public int status(){return status;}
    public String code(){return code;}
    public List<Map<String,String>> errors(){return errors;}
    public Map<String,Object> details(){return details;}
    public static void require(boolean test, int status, String code) { if(!test)throw new Stage1Exception(status,code); }
    public static Stage1Exception field(String field, String reason) {
        return new Stage1Exception(422,"FIELD_INVALID",List.of(Map.of("field",field,"reason",reason)));
    }
}
