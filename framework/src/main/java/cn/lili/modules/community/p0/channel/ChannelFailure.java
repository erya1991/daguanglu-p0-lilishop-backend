package cn.lili.modules.community.p0.channel;
/** Never retains an SDK request, original exception, response body or private material. */
public final class ChannelFailure extends RuntimeException {
    private final String code;private final boolean uncertain,retryable;
    public ChannelFailure(String code,boolean uncertain,boolean retryable){super(code);this.code=code;this.uncertain=uncertain;this.retryable=retryable;}
    public String code(){return code;}public boolean uncertain(){return uncertain;}public boolean retryable(){return retryable;}
}
