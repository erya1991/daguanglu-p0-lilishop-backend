package cn.lili.modules.community.p0.channel;

import com.google.gson.JsonObject;
import cn.lili.modules.community.p0.support.Fields;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Only a server/channel directory may decide whether a branch may be omitted. */
final class ChannelBankRules {
    static void apply(JsonObject account,JsonObject directoryEntry){
        try{
            String requested=new Fields(account).text("account_bank",1,128,true);
            String code=directoryEntry.get("account_bank_code").getAsString(),name=directoryEntry.get("account_bank").getAsString();
            if(!requested.equals(code)&&!requested.equals(name))throw new IllegalArgumentException();
            if(!directoryEntry.has("need_bank_branch")||!directoryEntry.get("need_bank_branch").isJsonPrimitive()||!directoryEntry.get("need_bank_branch").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException();
            var f=new Fields(account);String branch=f.text("bank_branch_id",1,64,false),full=f.text("bank_name",1,128,false);
            if(directoryEntry.get("need_bank_branch").getAsBoolean()&&branch==null&&full==null)throw new ChannelFailure("CHANNEL_BANK_BRANCH_REQUIRED",false,false);
            account.addProperty("account_bank",name);
        }catch(ChannelFailure safe){throw safe;}catch(RuntimeException invalid){throw new ChannelFailure("CHANNEL_BANK_DIRECTORY_INVALID",false,false);}
    }
    private ChannelBankRules(){}
}
