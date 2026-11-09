package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.SessionDomain;
import com.google.gson.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Receipts retain historical facts, but never retain historical authorization. */
final class Stage1ReceiptPolicy {
    private Stage1ReceiptPolicy() { }
    static JsonObject view(SessionDomain domain,Set<String> grants,String action,Object value){
        JsonObject result=object(value).deepCopy();
        String command=action.split(":",2)[0];
        if(command.equals("OPERATION_ABANDONED"))return result;
        boolean allowed;
        if(command.equals("file.upload"))allowed=file(domain,grants,result);
        else if(domain==SessionDomain.APPLICANT)allowed=Set.of("application.create","application.draft","application.submit","invitation.accept","invitation.decline").contains(command);
        else if(domain==SessionDomain.STORE)allowed=switch(command){
            case "profile.update","profile-change.submit","qualification.draft","qualification.submit"->grants.contains("profile.read");
            case "goods.save","goods.submit","goods.list","goods.unlist"->grants.contains("goods.read");
            case "stock.set","stock.batch"->grants.contains("stock.read");
            case "cost.set"->grants.contains("cost.read");
            case "fulfillment.save"->grants.contains("fulfillment.read");
            case "point.save","point.enable","point.disable"->grants.contains("point.read");
            case "invitation.accept","invitation.decline"->grants.contains("invitation.accept");
            case "invitation.create","invitation.confirm","invitation.reject","invitation.revoke","staff.roles","staff.enable","staff.disable","staff.revoke"->grants.contains("staff.manage");
            default->false;
        };
        else if(domain==SessionDomain.MANAGER)allowed=switch(command){
            case "application.approve","application.reject","qualification.revoke"->grants.contains("admission.review");
            case "store.close","store.reopen"->grants.contains("store.read");
            case "agreement.create","agreement.draft","agreement.confirm","agreement.cancel","agreement.terminate"->grants.contains("agreement.read");
            case "rule.create","rule.draft","rule.publish","rule.disable"->grants.contains("rule.read");
            case "channel-config.save","channel-config.enable","channel-config.disable","channel-config.check"->grants.contains("channel.configure");
            case "submerchant.create","submerchant.materials","submerchant.submit","submerchant.query","submerchant.enable","submerchant.disable"->grants.contains("channel.onboard");
            case "submerchant.receiver","receiver.create","receiver.sync","receiver.enable","receiver.disable"->grants.contains("channel.receiver");
            case "catalog.save","catalog.enable","catalog.disable"->grants.contains("catalog.configure");
            case "goods.approve","goods.reject","goods.force-off","goods.release"->grants.contains("goods.read");
            default->false;
        };
        else allowed=false;
        Stage1Exception.require(allowed,403,"RECEIPT_PERMISSION_CHANGED");
        if(!(domain==SessionDomain.STORE&&grants.contains("cost.read")||domain==SessionDomain.MANAGER&&grants.contains("goods.review")))redactCost(result);
        return result;
    }
    private static boolean file(SessionDomain domain,Set<String> grants,JsonObject result){
        String purpose=result.has("purpose")?result.get("purpose").getAsString():"";
        return switch(purpose){
            case "QUALIFICATION"->domain==SessionDomain.APPLICANT||domain==SessionDomain.STORE&&grants.contains("profile.read")||domain==SessionDomain.MANAGER&&(grants.contains("admission.review")||grants.contains("store.read"));
            case "GOODS"->domain==SessionDomain.STORE&&grants.contains("goods.read");
            case "AGREEMENT"->domain==SessionDomain.MANAGER&&grants.contains("agreement.read");
            case "CHANNEL"->domain==SessionDomain.MANAGER&&grants.contains("channel.onboard");
            default->false;
        };
    }
    private static void redactCost(JsonElement value){
        if(value.isJsonObject()){
            var object=value.getAsJsonObject();
            for(String key:List.of("costCent","costVersion","costProvided","cost"))object.remove(key);
            object.entrySet().forEach(entry->redactCost(entry.getValue()));
        }else if(value.isJsonArray())value.getAsJsonArray().forEach(Stage1ReceiptPolicy::redactCost);
    }
}
