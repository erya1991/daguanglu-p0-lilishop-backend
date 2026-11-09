package cn.lili.modules.permission;
import cn.lili.modules.permission.entity.vo.SystemLogVO;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SystemLogRedactionTest {
 @Test void credentialsAreRemovedButOperationContextRemains() {
  var params=new LinkedHashMap<String,String[]>();
  for(String key:List.of("password","newPassword","access_token","refreshToken","clientSecret","private-key","apiKey","Authorization","loginCode","phoneCode","code","key"))params.put(key,new String[]{"secret-"+key});
  params.put("username",new String[]{"operator"}); params.put("roleId",new String[]{"123"});
  var log=new SystemLogVO();log.setMapToParams(params);
  assertFalse(log.getRequestParam().contains("secret-"));assertTrue(log.getRequestParam().contains("operator"));assertTrue(log.getRequestParam().contains("123"));
  assertEquals("secret-password",params.get("password")[0]);
 }
 @Test void structuredSettingsCannotExposeNestedCredentials(){var log=new SystemLogVO();log.setMapToParams(java.util.Map.of("settingValue",new String[]{"{\"nested\":{\"password\":\"not-for-log\"}}"}));assertFalse(log.getRequestParam().contains("not-for-log"));}
 @Test void missingParametersDoNotBreakLogging(){var log=new SystemLogVO();assertDoesNotThrow(()->log.setMapToParams(null));}
}
