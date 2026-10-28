package cn.lili.modules.community.p0.channel;
public final class ChannelStatus {
    private ChannelStatus(){}
    public static String normalize(String raw){return switch(raw==null?"":raw){
        case "CHECKING","AUDITING"->"PROCESSING";
        case "ACCOUNT_NEED_VERIFY","NEED_SIGN"->"NEED_USER_ACTION";
        case "REJECTED"->"NEED_CORRECTION";
        case "FINISH"->"COMPLETED";
        case "FROZEN","CANCELED"->"REJECTED";
        default->"UNKNOWN";
    };}
}
