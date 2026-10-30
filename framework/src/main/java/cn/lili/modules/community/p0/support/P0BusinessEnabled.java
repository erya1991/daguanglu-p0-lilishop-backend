package cn.lili.modules.community.p0.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import java.lang.annotation.*;

/** Shared business assembly; each runtime still has its own entrance/resource guards. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnExpression("${p0.stage1.enabled:false} or ${p0.stage2.enabled:false}")
public @interface P0BusinessEnabled { }
