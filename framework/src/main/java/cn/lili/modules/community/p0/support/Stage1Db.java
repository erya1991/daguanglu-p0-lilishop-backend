package cn.lili.modules.community.p0.support;

import cn.hutool.core.util.IdUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class Stage1Db {
    private final JdbcTemplate jdbc;
    private static final Set<String> NATIVE_INSERT = Set.of("li_store","li_store_detail","li_clerk","li_clerk_role","li_store_role","li_store_menu_role","li_store_menu","li_goods","li_goods_sku","li_goods_gallery","li_store_address","li_category","li_goods_unit","li_file");
    public Stage1Db(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public String id(){return IdUtil.getSnowflakeNextIdStr();}
    public List<Map<String,Object>> rows(String sql,Object...args){return jdbc.queryForList(sql,convert(args));}
    public Map<String,Object> one(String sql,Object...args){var rows=rows(sql,args);if(rows.size()>1)throw new Stage1Exception(409,"RELATION_CONFLICT");return rows.isEmpty()?null:rows.getFirst();}
    public Map<String,Object> required(String sql,Object...args){var row=one(sql,args);if(row==null)throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");return row;}
    public long count(String sql,Object...args){Long n=jdbc.queryForObject(sql,Long.class,convert(args));return n==null?0:n;}
    public int update(String sql,Object...args){return jdbc.update(sql,convert(args));}
    public void nativeInsert(String table,Map<String,Object> values){
        if(!NATIVE_INSERT.contains(table)||values.isEmpty()||values.keySet().stream().anyMatch(k->!k.matches("[a-z_]+")))throw new IllegalArgumentException("Invalid native projection");
        update("INSERT INTO "+table+" ("+String.join(",",values.keySet())+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.values().toArray());
    }
    public void cas(int updated){if(updated!=1)throw new Stage1Exception(409,"VERSION_CONFLICT");}
    private static Object[] convert(Object[] args){return Arrays.stream(args).map(v->v instanceof Instant i?Timestamp.from(i):v).toArray();}
}
