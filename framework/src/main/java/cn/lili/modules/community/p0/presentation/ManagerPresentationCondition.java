package cn.lili.modules.community.p0.presentation;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
/** The candidate's read extensions are absent from a Stage2 application assembly. */
public final class ManagerPresentationCondition implements Condition {
    @Override public boolean matches(ConditionContext context,AnnotatedTypeMetadata metadata){var env=context.getEnvironment();return env.getProperty("p0.stage1.enabled",Boolean.class,false)&&!env.getProperty("p0.stage2.enabled",Boolean.class,false);}
}
