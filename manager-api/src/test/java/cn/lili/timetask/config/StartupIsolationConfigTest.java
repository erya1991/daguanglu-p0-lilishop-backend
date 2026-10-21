package cn.lili.timetask.config;

import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.sensitive.quartz.QuartzConfig;
import cn.lili.message.MessageDispatcher;
import cn.lili.message.RedisQueueConsumer;
import cn.lili.timetask.TimedTaskJobHandler;
import cn.lili.timetask.handler.EveryMinuteExecute;
import cn.lili.timetask.handler.EveryHourExecute;
import cn.lili.timetask.handler.EveryDayExecute;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration;
import org.springframework.boot.quartz.autoconfigure.QuartzProperties;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Restricted bean contexts only: no component scanning, data-source auto-config or actual Quartz scheduler. */
class StartupIsolationConfigTest {

    @Test
    void disabledRedisConsumerDoesNotInstantiateRedisOrDispatcher() {
        AtomicInteger constructed = new AtomicInteger();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        new ApplicationContextRunner().withUserConfiguration(RedisQueueConsumer.class)
                .withPropertyValues("lili.message.consumer.enabled=false")
                .withBean(StringRedisTemplate.class, () -> { constructed.incrementAndGet(); return redis; },
                        definition -> definition.setLazyInit(true))
                .withBean(MessageDispatcher.class, () -> { constructed.incrementAndGet(); return dispatcher; },
                        definition -> definition.setLazyInit(true))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(RedisQueueConsumer.class).isEmpty());
                    assertEquals(0, constructed.get());
                    verifyNoInteractions(redis, dispatcher);
                });
    }

    @Test
    void omittedRedisConsumerPropertyPreservesItsExistingDisabledDefault() {
        new ApplicationContextRunner().withUserConfiguration(RedisQueueConsumer.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(RedisQueueConsumer.class).isEmpty());
        });
    }

    @Test
    void enabledRedisConsumerStillSubmitsBothWorkersAndStopsUsingFakeExecutor() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        doReturn(CompletableFuture.completedFuture(null)).when(executor).submit(any(Runnable.class));
        RedisQueueConsumer consumer = new RedisQueueConsumer(redis, new RocketmqCustomProperties(), dispatcher);
        ExecutorService unused = (ExecutorService) ReflectionTestUtils.getField(consumer, "executor");
        assertNotNull(unused);
        unused.shutdownNow(); // A newFixedThreadPool creates no workers before submission.
        ReflectionTestUtils.setField(consumer, "executor", executor);

        consumer.start();
        consumer.start();
        consumer.stop();

        verify(executor, times(2)).submit(any(Runnable.class));
        verify(executor).shutdown();
        verifyNoInteractions(redis, dispatcher);
    }

    @Test
    void threeDisabledCronValuesRegisterNoScheduledTasks() {
        TimedTaskJobHandler handler = mock(TimedTaskJobHandler.class);
        new ApplicationContextRunner().withUserConfiguration(TimedTaskScheduler.class)
                .withBean(TimedTaskJobHandler.class, () -> handler)
                .withBean(EveryMinuteExecute.class, () -> mock(EveryMinuteExecute.class))
                .withBean(EveryHourExecute.class, () -> mock(EveryHourExecute.class))
                .withBean(EveryDayExecute.class, () -> mock(EveryDayExecute.class))
                .withPropertyValues("lili.schedule.every-minute-cron=-",
                        "lili.schedule.every-hour-cron=-", "lili.schedule.every-day-cron=-")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    ScheduledAnnotationBeanPostProcessor processor =
                            context.getBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertTrue(processor.getScheduledTasks().isEmpty());
                    verifyNoInteractions(handler);
                });
    }

    @Test
    void omittedCronPropertiesStillRegisterThreeScheduledTasks() {
        new ApplicationContextRunner().withUserConfiguration(TimedTaskScheduler.class)
                .withBean(TimedTaskJobHandler.class, () -> mock(TimedTaskJobHandler.class))
                .withBean(EveryMinuteExecute.class, () -> mock(EveryMinuteExecute.class))
                .withBean(EveryHourExecute.class, () -> mock(EveryHourExecute.class))
                .withBean(EveryDayExecute.class, () -> mock(EveryDayExecute.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(3, context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                            .getScheduledTasks().size());
                });
    }

    @Test
    void quartzAutoStartupFalseDoesNotStartFakeScheduler() throws Exception {
        assertQuartzLifecycle(false);
    }

    @Test
    void quartzEnabledPathStillStartsOnlyFakeScheduler() throws Exception {
        assertQuartzLifecycle(true);
    }

    private static void assertQuartzLifecycle(boolean autoStartup) throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        SchedulerFactory factory = mock(SchedulerFactory.class);
        when(factory.getScheduler()).thenReturn(scheduler);
        when(scheduler.getSchedulerName()).thenReturn("unit-only-quartz");
        when(scheduler.getContext()).thenReturn(new SchedulerContext());
        when(scheduler.isInStandbyMode()).thenReturn(true);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(QuartzAutoConfiguration.class))
                .withUserConfiguration(QuartzConfig.class)
                .withBean(SchedulerFactoryBeanCustomizer.class,
                        () -> bean -> bean.setSchedulerFactory(factory))
                .withPropertyValues("spring.quartz.auto-startup=" + autoStartup,
                        "spring.quartz.job-store-type=memory")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(autoStartup, context.getBean(QuartzProperties.class).isAutoStartup());
                    assertEquals(autoStartup,
                            context.getBean("&quartzScheduler", SchedulerFactoryBean.class).isAutoStartup());
                    verify(scheduler, times(autoStartup ? 1 : 0)).start();
                });
        verify(scheduler).shutdown(false);
    }
}
