package cn.lili.modules.community.p0.presentation;

import cn.lili.modules.community.p0.support.Stage1Exception;
import com.google.gson.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Explicit read projections. Never serialize a native entity or arbitrary internal row. */
public final class ManagerPresentationViews {
    private ManagerPresentationViews() {}
    public static JsonObject pick(Object value,String... fields){var source=object(value);var result=new JsonObject();for(String name:fields)if(source.has(name))result.add(name,source.get(name).deepCopy());return result;}
    public static String identifier(String value){Stage1Exception.require(value!=null&&value.matches("[1-9][0-9]{0,18}"),422,"ID_REQUIRED");return value;}
    public static String search(String value){if(value==null||value.isBlank())return null;Stage1Exception.require(value.codePointCount(0,value.length())<=100,422,"SEARCH_TOO_LONG");return value.trim();}
    public static String like(String value){return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    public static JsonObject profile(Object value){var result=pick(value,"id","version","state","admissionState","closedReason","storeName","description","legalName","legalPerson","legalCode","contactName","phone","address","categoryIds","qualifications");var source=object(value);result.add("extra",source.has("extra")?pick(source.get("extra"),"subjectType","provinceCode","cityCode","districtCode","businessHours"):new JsonObject());return result;}
    public static Map<String,Object> page(long total,int page,int size,List<?> records){return map("total",total,"pageNumber",page,"pageSize",size,"records",records);}
    public static String operator(Map<String,Object> row){String name=id(row,"operator_name");return name!=null&&!name.isBlank()?name:row.get("actor_id")==null?null:"管理员 · "+tail(id(row,"actor_id"));}
    private static String tail(String value){return value.substring(Math.max(0,value.length()-4));}
}
