package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Db;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionApplications;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Immutable, hash-bound enrollment for this run's background work. Never defaults to all rows. */
final class Stage2RecoveryScope {
    private final Stage2Settings settings;
    private final Stage1Db db;
    private String loadedHash;
    private Enrollment loaded;
    private SessionApplications applications;

    Stage2RecoveryScope(Stage2Settings settings, Stage1Db db) { this.settings=settings; this.db=db; }
    void setApplications(SessionApplications applications) { this.applications=applications; }

    synchronized Enrollment current() {
        String expected=settings.getRecoveryScopeSha256();
        try {
            if(expected==null || !expected.matches("[a-f0-9]{64}") || settings.getRecoveryScopeFile()==null) throw new IllegalStateException();
            Path run=Path.of(settings.getFileRoot()).toRealPath().getParent();
            Path file=Path.of(settings.getRecoveryScopeFile()).toAbsolutePath().normalize();
            if(!file.startsWith(run) || !file.toRealPath().equals(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException();
            byte[] bytes=Files.readAllBytes(file);
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected)) throw new IllegalStateException();
            if(expected.equals(loadedHash) && !settings.isUatResourceMode()) return loaded;
            JsonObject value=object(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            if(!settings.getRunId().equals(value.get("runId").getAsString()) || value.get("evidence").getAsString().isBlank()) throw new IllegalStateException();
            Instant since=Instant.parse(value.get("admittedAt").getAsString());
            if(since.isAfter(Instant.now().plusSeconds(5))) throw new IllegalStateException();
            var members=ids(value,"members");var stores=ids(value,"stores");var skus=ids(value,"skus");
            boolean dynamic=value.has("dynamicOwnedUat") && value.get("dynamicOwnedUat").getAsBoolean();
            if(dynamic) {
                cn.lili.modules.community.p0.uat.UatResourceScope.registered(settings);
                if(settings.isServerUatMode()) {
                    if(applications==null)throw new IllegalStateException();
                    members=checkedRows(db.rows("SELECT member_id id FROM li_p0_s2_member_scope WHERE run_id=? AND permission='TRANSACT'",settings.getRunId()));
                    stores=checkedRows(db.rows("SELECT DISTINCT c.store_id id FROM li_p0_store_control c JOIN li_store s ON s.id=c.store_id AND s.member_id=c.owner_member_id JOIN li_p0_application a ON a.store_id=c.store_id AND a.applicant_id=c.owner_member_id WHERE c.admission_state='APPROVED' AND a.kind='ADMISSION' AND a.status='APPROVED' AND a.identity_application=?",applications.application(SessionDomain.APPLICANT)));
                } else {
                    var merchants=ids(value,"merchants");if(merchants.isEmpty())throw new IllegalStateException();
                    String merchantSql=String.join(",",merchants);
                    stores=db.rows("SELECT DISTINCT c.store_id id FROM li_p0_store_control c JOIN li_store s ON s.id=c.store_id AND s.member_id=c.owner_member_id JOIN li_p0_application a ON a.store_id=c.store_id AND a.applicant_id=c.owner_member_id WHERE c.admission_state='APPROVED' AND a.kind='ADMISSION' AND a.status='APPROVED' AND a.identity_application=? AND a.applicant_id IN ("+merchantSql+")",settings.getRunId()+"_merchant").stream().map(row->id(row,"id")).sorted().toList();
                }
                String storeSql=stores.isEmpty()?"NULL":String.join(",",stores);
                skus=db.rows("SELECT c.sku_id id FROM li_p0_sku_control c JOIN li_goods_sku s ON s.id=c.sku_id AND s.store_id=c.store_id WHERE c.source_run=? AND c.store_id IN ("+storeSql+")",settings.getRunId()).stream().map(row->id(row,"id")).sorted().toList();
                if(stores.size()>4096||skus.size()>4096||java.util.stream.Stream.concat(stores.stream(),skus.stream()).anyMatch(id->!id.matches("[1-9][0-9]{0,18}")))throw new IllegalStateException();
            }
            var enrollment=new Enrollment(members,stores,skus,since);
            if(settings.isWorkersEnabled() && ((!settings.isServerUatMode() && enrollment.members.isEmpty()) || !dynamic && (enrollment.stores.isEmpty() || enrollment.skus.isEmpty()))) throw new IllegalStateException();
            if(db.count("SELECT COUNT(*) FROM li_p0_s2_member_scope WHERE run_id=? AND permission='TRANSACT' AND member_id IN ("+enrollment.memberSql()+")",settings.getRunId())!=enrollment.members.size()) throw new IllegalStateException();
            if(db.count("SELECT COUNT(*) FROM li_p0_store_control WHERE store_id IN ("+enrollment.storeSql()+")")!=enrollment.stores.size()) throw new IllegalStateException();
            if(db.count("SELECT COUNT(*) FROM li_p0_sku_control c JOIN li_goods_sku s ON s.id=c.sku_id WHERE c.source_run=? AND c.sku_id IN ("+enrollment.skuSql()+") AND s.store_id IN ("+enrollment.storeSql()+")",settings.getRunId())!=enrollment.skus.size()) throw new IllegalStateException();
            // Existing unresolved/protected trades and their stock cannot be adopted by timestamp alone.
            if(db.count("SELECT COUNT(*) FROM li_p0_trade_state t WHERE t.created_at<? AND (t.status NOT IN ('PAID','CLOSED') OR t.protection IS NOT NULL OR EXISTS(SELECT 1 FROM li_p0_payment p WHERE p.trade_id=t.trade_id AND p.status='SUCCEEDED' AND JSON_EXTRACT(p.result_json,'$.paid') IS NULL)) AND (t.member_id IN ("+enrollment.memberSql()+") OR EXISTS(SELECT 1 FROM li_p0_order_snapshot s WHERE s.trade_id=t.trade_id AND s.store_id IN ("+enrollment.storeSql()+")) OR EXISTS(SELECT 1 FROM li_p0_inventory_hold h WHERE h.trade_id=t.trade_id AND h.sku_id IN ("+enrollment.skuSql()+")))",since)!=0) throw new IllegalStateException();
            loadedHash=expected; loaded=enrollment; return loaded;
        } catch(Exception rejected) { throw new IllegalStateException("STAGE2_RECOVERY_SCOPE_INVALID"); }
    }

    private static List<String> ids(JsonObject value,String key) {
        var result=new TreeSet<String>();
        for(var element:value.getAsJsonArray(key)) {
            String id=element.getAsString();
            if(!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id)<=0 || !result.add(id)) throw new IllegalStateException();
        }
        if(result.size()>4096) throw new IllegalStateException();
        return List.copyOf(result);
    }

    private static List<String> checkedRows(List<Map<String,Object>> rows) {
        var result=new TreeSet<String>();
        for(var row:rows) {
            String value=id(row,"id");
            if(value==null || !value.matches("[1-9][0-9]{0,18}") || !result.add(value))throw new IllegalStateException();
        }
        if(result.size()>4096)throw new IllegalStateException();
        return List.copyOf(result);
    }

    record Enrollment(List<String> members,List<String> stores,List<String> skus,Instant admittedAt) {
        private static String sql(List<String> values) { return values.isEmpty()?"NULL":String.join(",",values); }
        String memberSql(){return sql(members);} String storeSql(){return sql(stores);} String skuSql(){return sql(skus);}
        String sinceSql(){return "'"+admittedAt.toString().replace('T',' ').replace("Z","")+"'";}
        String trade(String alias) {
            return alias+".member_id IN ("+memberSql()+") AND "+alias+".created_at>="+sinceSql()+
                " AND EXISTS(SELECT 1 FROM li_p0_order_snapshot sc WHERE sc.trade_id="+alias+".trade_id)"+
                " AND NOT EXISTS(SELECT 1 FROM li_p0_order_snapshot sc WHERE sc.trade_id="+alias+".trade_id AND sc.store_id NOT IN ("+storeSql()+"))"+
                " AND NOT EXISTS(SELECT 1 FROM li_p0_inventory_hold sh WHERE sh.trade_id="+alias+".trade_id AND sh.sku_id NOT IN ("+skuSql()+"))";
        }
        String task() {
            return "(task_type IN ('PAYMENT','CART_CLEAN') AND CAST(target_key AS BINARY) IN (SELECT CAST(t.trade_id AS BINARY) FROM li_p0_trade_state t WHERE "+trade("t")+"))"+
                " OR (task_type='INTENT' AND CAST(target_key AS BINARY) IN (SELECT CAST(CONCAT(i.member_id,':',i.intent_id) AS BINARY) FROM li_p0_checkout_intent i WHERE i.member_id IN ("+memberSql()+") AND i.created_at>="+sinceSql()+"))"+
                " OR (task_type='INVENTORY_PROJECTION' AND target_key IN ("+(skus.isEmpty()?"NULL":skus.stream().map(s->"'"+s+"'").collect(java.util.stream.Collectors.joining(",")))+") AND created_at>="+sinceSql()+")";
        }
        String outbox() { return "store_id IN ("+storeSql()+") AND created_at>="+sinceSql()+" AND (event_type<>'SKU_STOCK' OR object_id IN ("+skuSql()+"))"; }
    }
}
