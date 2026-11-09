package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.springframework.data.redis.core.StringRedisTemplate;import org.springframework.data.redis.core.script.DefaultRedisScript;import java.util.*;
/** Atomically reserve failure budget including in-flight attempts; successful requests release only their own window generation. */
public final class Stage3AttemptLimiter {
 private final StringRedisTemplate redis;
 private static final DefaultRedisScript<List> RESERVE=new DefaultRedisScript<>("for i,k in ipairs(KEYS) do if tonumber(redis.call('HGET',k,'count') or '0')>=tonumber(ARGV[i+1]) then return {} end end local result={} for i,k in ipairs(KEYS) do local g=redis.call('HGET',k,'generation'); if not g then g=ARGV[1];redis.call('HSET',k,'generation',g,'count',0);redis.call('PEXPIRE',k,60000) end redis.call('HINCRBY',k,'count',1);table.insert(result,g) end return result",List.class);
 private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("for i,k in ipairs(KEYS) do if redis.call('HGET',k,'generation')==ARGV[i] and tonumber(redis.call('HGET',k,'count') or '0')>0 then redis.call('HINCRBY',k,'count',-1) end end return 1",Long.class);
 public Stage3AttemptLimiter(StringRedisTemplate redis){this.redis=redis;}
 public Ticket begin(List<String> keys){try{List<?> generations=redis.execute(RESERVE,keys,UUID.randomUUID().toString(),"5","60","120");if(generations==null)throw new IllegalStateException();if(generations.isEmpty())throw new Stage1Exception(429,"VERIFICATION_RATE_LIMITED");if(generations.size()!=keys.size())throw new IllegalStateException();return new Ticket(List.copyOf(keys),generations.stream().map(Object::toString).toList());}catch(Stage1Exception e){throw e;}catch(RuntimeException e){throw new Stage1Exception(503,"VERIFICATION_LIMIT_UNAVAILABLE");}}
 public final class Ticket {private final List<String> keys,generations;private boolean released;private Ticket(List<String> keys,List<String> generations){this.keys=keys;this.generations=generations;}public void succeeded(){if(released)return;try{if(redis.execute(RELEASE,keys,generations.toArray())==null)throw new IllegalStateException();released=true;}catch(RuntimeException e){throw new Stage1Exception(503,"VERIFICATION_LIMIT_UNAVAILABLE");}}}
}
