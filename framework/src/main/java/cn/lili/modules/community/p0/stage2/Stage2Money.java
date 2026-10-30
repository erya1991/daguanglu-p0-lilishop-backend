package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Exception;
import java.math.BigInteger;

public final class Stage2Money {
    public static final long MAX_SAFE_CENT=9_007_199_254_740_991L;
    private Stage2Money() { }
    public static long add(long a,long b){try{return safe(Math.addExact(a,b));}catch(ArithmeticException e){throw new Stage1Exception(422,"AMOUNT_RANGE");}}
    public static long item(long price,long quantity){
        Stage1Exception.require(price>=1&&price<=99_999_999&&quantity>=1&&quantity<=99,422,"PRICE_OR_QUANTITY_INVALID");
        return safe(Math.multiplyExact(price,quantity));
    }
    public static long commission(long goods,long bps){Stage1Exception.require(goods>=0&&bps>=0&&bps<=10000,422,"COMMISSION_INVALID");return BigInteger.valueOf(goods).multiply(BigInteger.valueOf(bps)).divide(BigInteger.valueOf(10000)).longValueExact();}
    public static long safe(long value){Stage1Exception.require(value>=0&&value<=MAX_SAFE_CENT,422,"AMOUNT_RANGE");return value;}
}
