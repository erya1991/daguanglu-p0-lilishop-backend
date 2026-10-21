package cn.lili.trigger;

import cn.lili.cache.Cache;
import cn.lili.trigger.interfaces.TimeTrigger;
import cn.lili.trigger.listen.PromotionDelayQueueListen;
import cn.lili.trigger.model.TimeTriggerMsg;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Captures enabled-loop submission; never initializes ThreadPoolUtil or executes its infinite loop. */
class PromotionDelayQueueListenIsolationTest {

    @Test
    void disabledRunnerCreatesNoDependenciesAndCannotPollOrTrigger() {
        Cache cache = mock(Cache.class);
        TimeTrigger trigger = mock(TimeTrigger.class);
        AtomicInteger dependenciesCreated = new AtomicInteger();
        new ApplicationContextRunner()
                .withUserConfiguration(PromotionDelayQueueListen.class)
                .withBean(Cache.class, () -> { dependenciesCreated.incrementAndGet(); return cache; },
                        definition -> definition.setLazyInit(true))
                .withBean(TimeTrigger.class, () -> { dependenciesCreated.incrementAndGet(); return trigger; },
                        definition -> definition.setLazyInit(true))
                .withPropertyValues("lili.promotion.delay-listener.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) {
                        runner.run(new DefaultApplicationArguments());
                    }
                    assertTrue(context.getBeansOfType(PromotionDelayQueueListen.class).isEmpty());
                    assertEquals(0, dependenciesCreated.get());
                    verifyNoInteractions(cache, trigger);
                });
    }

    @Test
    void disabledListenerDoesNotRequireCacheOrTimeTriggerBeans() {
        new ApplicationContextRunner().withUserConfiguration(PromotionDelayQueueListen.class)
                .withPropertyValues("lili.promotion.delay-listener.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(ApplicationRunner.class).isEmpty());
                });
    }

    @Test
    void omittedPropertyPreservesListenerAndSubmitsOneCapturedWorker() {
        assertEnabledSubmission();
    }

    @Test
    void explicitTruePreservesListenerAndSubmitsOneCapturedWorker() {
        assertEnabledSubmission("lili.promotion.delay-listener.enabled=true");
    }

    @Test
    void messageConsumerFalseDoesNotImplicitlyDisablePromotionListener() {
        assertEnabledSubmission("lili.message.consumer.enabled=false");
    }

    @Test
    void enabledInvokeStillDecodesAndDelegatesTheOriginalMessage() {
        TimeTrigger trigger = mock(TimeTrigger.class);
        PromotionDelayQueueListen listener = new PromotionDelayQueueListen();
        ReflectionTestUtils.setField(listener, "timeTrigger", trigger);

        listener.invoke("{\"triggerExecutor\":\"unit-only\",\"triggerTime\":1,\"param\":\"synthetic\","
                + "\"uniqueKey\":\"unit-key\",\"topic\":\"PROMOTION\"}");

        ArgumentCaptor<TimeTriggerMsg> msg = ArgumentCaptor.forClass(TimeTriggerMsg.class);
        verify(trigger).execute(msg.capture());
        assertEquals("unit-only", msg.getValue().getTriggerExecutor());
        assertEquals("unit-key", msg.getValue().getUniqueKey());
        assertEquals("synthetic", msg.getValue().getParam());
        assertEquals("PROMOTION", listener.setDelayQueueName());
    }

    private static void assertEnabledSubmission(String... properties) {
        Cache cache = mock(Cache.class);
        TimeTrigger trigger = mock(TimeTrigger.class);
        List<Runnable> capturedWorkers = new ArrayList<>();
        Executor executor = capturedWorkers::add;
        new ApplicationContextRunner()
                .withUserConfiguration(PromotionDelayQueueListen.class)
                .withBean(Cache.class, () -> cache)
                .withBean(TimeTrigger.class, () -> trigger)
                .withPropertyValues(properties)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    PromotionDelayQueueListen listener = spy(context.getBean(PromotionDelayQueueListen.class));
                    // Avoid even class-initializing the legacy prestarted ThreadPoolUtil pool.
                    doReturn(executor).when(listener).listenerExecutor();

                    listener.run(new DefaultApplicationArguments());

                    assertEquals(1, capturedWorkers.size());
                    assertNotNull(capturedWorkers.get(0));
                    verifyNoInteractions(cache, trigger);
                });
    }
}
