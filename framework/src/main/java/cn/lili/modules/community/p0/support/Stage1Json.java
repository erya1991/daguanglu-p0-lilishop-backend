package cn.lili.modules.community.p0.support;

import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

public final class Stage1Json {
    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL)
        .registerTypeAdapter(Instant.class,(JsonSerializer<Instant>)(v,t,c)->new JsonPrimitive(v.toString()))
        .registerTypeAdapter(LocalDate.class,(JsonSerializer<LocalDate>)(v,t,c)->new JsonPrimitive(v.toString()))
        .registerTypeAdapter(LocalDateTime.class,(JsonSerializer<LocalDateTime>)(v,t,c)->new JsonPrimitive(v.toString()))
        .create();
    private Stage1Json(){}
    public static String write(Object value){return GSON.toJson(value);}
    public static JsonObject object(Object value){
        try {
            JsonElement json=value instanceof String s ? JsonParser.parseString(s) : GSON.toJsonTree(value);
            if(!json.isJsonObject())throw Stage1Exception.field("body","OBJECT_REQUIRED");
            return json.getAsJsonObject();
        } catch(JsonParseException ex){throw Stage1Exception.field("body","INVALID_JSON");}
    }
    public static JsonArray array(Object value){return JsonParser.parseString(String.valueOf(value)).getAsJsonArray();}
    public static Map<String,Object> map(Object... pairs){
        if(pairs.length%2!=0)throw new IllegalArgumentException();
        Map<String,Object> map=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;
    }
    public static String hash(Object value){return digest(canonical(GSON.toJsonTree(value)).getBytes(StandardCharsets.UTF_8));}
    public static String digest(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static String canonical(JsonElement value){
        if(value.isJsonObject()){
            var parts=new ArrayList<String>();new TreeSet<>(value.getAsJsonObject().keySet()).forEach(k->parts.add(GSON.toJson(k)+":"+canonical(value.getAsJsonObject().get(k))));
            return "{"+String.join(",",parts)+"}";
        }
        if(value.isJsonArray()){var parts=new ArrayList<String>();value.getAsJsonArray().forEach(v->parts.add(canonical(v)));return "["+String.join(",",parts)+"]";}
        return value.toString();
    }
    public static String id(Map<String,Object> row,String key){return row.get(key)==null?null:String.valueOf(row.get(key));}
    public static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    public static boolean flag(Map<String,Object> row,String key){Object v=row.get(key);return Boolean.TRUE.equals(v)||(v instanceof Number n && n.intValue()==1)||(v instanceof byte[] b&&b.length==1&&b[0]==1);}
    public static Instant instant(Object value){
        if(value==null)return null;
        if(value instanceof java.sql.Timestamp t)return t.toInstant();
        if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);
        if(value instanceof Instant i)return i;
        return Instant.parse(value.toString());
    }
    public static long cents(Object value){return new BigDecimal(String.valueOf(value)).movePointRight(2).longValueExact();}
    public static BigDecimal money(long cents){return BigDecimal.valueOf(cents,2);}
}
