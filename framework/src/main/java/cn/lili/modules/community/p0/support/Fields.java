package cn.lili.modules.community.p0.support;

import com.google.gson.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Strict command reader. Unknown fields, identity selectors and numeric coercions are rejected. */
public final class Fields {
    private final JsonObject body;
    public Fields(Object value){body=Stage1Json.object(value);}
    public JsonObject json(){return body.deepCopy();}
    public Fields only(String... keys){
        Set<String> allowed=new HashSet<>(Arrays.asList(keys));
        for(String key:body.keySet())if(!allowed.contains(key))throw Stage1Exception.field(key,"UNKNOWN_FIELD");
        return this;
    }
    public boolean has(String key){return body.has(key)&&!body.get(key).isJsonNull();}
    public JsonObject child(String key,boolean required){
        if(!has(key)){if(required)throw Stage1Exception.field(key,"REQUIRED");return new JsonObject();}
        if(!body.get(key).isJsonObject())throw Stage1Exception.field(key,"OBJECT_REQUIRED");return body.getAsJsonObject(key).deepCopy();
    }
    public String text(String key,int min,int max,boolean required){
        if(!has(key)){if(required)throw Stage1Exception.field(key,"REQUIRED");return null;}
        JsonElement v=body.get(key);
        if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw Stage1Exception.field(key,"STRING_REQUIRED");
        String s=v.getAsString().trim();int length=s.codePointCount(0,s.length());
        if(length<min||length>max||s.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw Stage1Exception.field(key,"LENGTH_OR_FORMAT");
        return s;
    }
    public String id(String key,boolean required){
        String id=text(key,1,19,required);if(id!=null&&!id.matches("[1-9][0-9]{0,18}"))throw Stage1Exception.field(key,"ID_REQUIRED");
        if(id!=null)try{Long.parseLong(id);}catch(NumberFormatException ex){throw Stage1Exception.field(key,"ID_RANGE");}return id;
    }
    public Long integer(String key,long min,long max,boolean required){
        if(!has(key)){if(required)throw Stage1Exception.field(key,"REQUIRED");return null;}
        JsonElement value=body.get(key);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw Stage1Exception.field(key,"INTEGER_REQUIRED");
        try{long n=value.getAsBigDecimal().longValueExact();if(n<min||n>max)throw Stage1Exception.field(key,"RANGE");return n;}
        catch(ArithmeticException|NumberFormatException ex){throw Stage1Exception.field(key,"INTEGER_REQUIRED");}
    }
    public boolean bool(String key,boolean fallback){
        if(!has(key))return fallback;
        JsonElement v=body.get(key);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw Stage1Exception.field(key,"BOOLEAN_REQUIRED");return v.getAsBoolean();
    }
    public JsonArray list(String key,int min,int max,boolean required){
        if(!has(key)){if(required)throw Stage1Exception.field(key,"REQUIRED");return new JsonArray();}
        JsonElement v=body.get(key);if(!v.isJsonArray()||v.getAsJsonArray().size()<min||v.getAsJsonArray().size()>max)throw Stage1Exception.field(key,"ARRAY_SIZE");return v.getAsJsonArray().deepCopy();
    }
    public List<String> ids(String key,int min,int max,boolean required){
        var result=new ArrayList<String>();for(JsonElement v:list(key,min,max,required)){
            var item=new JsonObject();item.add("id",v);String id=new Fields(item).id("id",true);
            if(result.contains(id))throw Stage1Exception.field(key,"DUPLICATE");result.add(id);
        }return List.copyOf(result);
    }
    public String choice(String key,Set<String> values,boolean required){String v=text(key,1,64,required);if(v!=null&&!values.contains(v))throw Stage1Exception.field(key,"INVALID_CHOICE");return v;}
    public LocalDate date(String key,boolean required){String s=text(key,10,10,required);if(s==null)return null;try{return LocalDate.parse(s);}catch(DateTimeException ex){throw Stage1Exception.field(key,"DATE_REQUIRED");}}
    public String requestId(){String id=text("requestId",8,64,true);if(!id.matches("[A-Za-z0-9_-]+"))throw Stage1Exception.field("requestId","INVALID_FORMAT");return id;}
    public long version(){return integer("expectedVersion",0,Long.MAX_VALUE,true);}
}
