package cn.lili.modules.community.p0.presentation;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
/** Preserve historical Stage isolation; the explicit integrated UAT also uses these finite read projections. */
public final class ManagerPresentationCondition implements Condition {
    @Override public boolean matches(ConditionContext context,AnnotatedTypeMetadata metadata){var env=context.getEnvironment();return env.getProperty("p0.stage1.enabled",Boolean.class,false)&&!env.getProperty("p0.stage2.enabled",Boolean.class,false)||env.getProperty("p0.uat.enabled",Boolean.class,false)&&env.getProperty("p0.stage2.enabled",Boolean.class,false);}
}
