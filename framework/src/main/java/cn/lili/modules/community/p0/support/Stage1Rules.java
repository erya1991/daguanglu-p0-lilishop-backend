package cn.lili.modules.community.p0.support;

import java.time.*;
import java.util.*;

public final class Stage1Rules {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public static final Set<String> METHODS=Set.of("MERCHANT_DELIVERY","EXPRESS","COMMUNITY_PICKUP","STORE_REDEMPTION");
    private Stage1Rules(){}
    public record Window(Instant from,Instant to){
        public Window {if(from==null||to==null||!from.isBefore(to))throw Stage1Exception.field("effectiveTo","INVALID_WINDOW");}
        public boolean contains(Instant time){return !time.isBefore(from)&&time.isBefore(to);}
        public boolean overlaps(Window other){return from.isBefore(other.to)&&other.from.isBefore(to);}
        public boolean covers(Window other){return !from.isAfter(other.from)&&!to.isBefore(other.to);}
    }
    public static Window dates(LocalDate from,LocalDate through){return new Window(from.atStartOfDay(ZONE).toInstant(),through.plusDays(1).atStartOfDay(ZONE).toInstant());}
    public static String skuCode(String code){String value=code.trim().toUpperCase(Locale.ROOT);if(!value.matches("[A-Z0-9_-]{1,64}"))throw Stage1Exception.field("code","INVALID_SKU_CODE");return value;}
    public static String legalCode(String value){
        String code=value.trim().toUpperCase(Locale.ROOT), alphabet="0123456789ABCDEFGHJKLMNPQRTUWXY";
        if(code.length()!=18)throw Stage1Exception.field("legalCode","INVALID_CREDIT_CODE");
        int[] weights={1,3,9,27,19,26,16,17,20,29,25,13,8,24,10,30,28};int sum=0;
        for(int i=0;i<17;i++){int n=alphabet.indexOf(code.charAt(i));if(n<0)throw Stage1Exception.field("legalCode","INVALID_CREDIT_CODE");sum+=n*weights[i];}
        if(alphabet.charAt((31-sum%31)%31)!=code.charAt(17))throw Stage1Exception.field("legalCode","INVALID_CREDIT_CODE");return code;
    }
    public static void phone(String phone){if(phone==null||!phone.matches("(?:1[3-9][0-9]{9}|0[0-9]{2,3}-?[0-9]{7,8})"))throw Stage1Exception.field("phone","INVALID_PHONE");}
    public static <T> Set<T> intersection(List<Set<T>> sets){if(sets.isEmpty())return Set.of();Set<T> result=new HashSet<>(sets.getFirst());sets.forEach(result::retainAll);return Set.copyOf(result);}
}
