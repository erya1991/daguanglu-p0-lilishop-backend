package cn.lili.modules.live.util;

import cn.lili.cache.Cache;
import cn.lili.modules.live.mapper.LiveCouponMapper;
import cn.lili.modules.live.mapper.LiveGoodsMapper;
import cn.lili.modules.live.mapper.LiveRoomMapper;
import cn.lili.modules.live.service.LiveMessageService;
import cn.lili.modules.live.service.LiveUserService;
import cn.lili.modules.live.serviceimpl.LiveCouponServiceImpl;
import cn.lili.modules.live.serviceimpl.LiveGoodsServiceImpl;
import cn.lili.modules.live.serviceimpl.LiveOperateServiceImpl;
import cn.lili.modules.live.serviceimpl.LiveRoomServiceImpl;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.system.service.SettingService;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No real MqttClient is constructed, including in enabled/default-property tests. */
class MqttUtilIsolationTest {

    @Test
    void disabledInitCreatesNoClientsAndRegistersNoCallbacksOrReconnect() {
        FakeMqtt mqtt = disabled();
        mqtt.init();
        mqtt.disconnect();

        assertEquals(0, mqtt.creations);
        verifyNoInteractions(mqtt.publisher, mqtt.subscriber);
    }

    @Test
    void disabledPublishCannotReportSuccess() {
        FakeMqtt mqtt = disabled();
        mqtt.init();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> mqtt.publishMessage("unit/topic", "synthetic", 1, false));

        assertTrue(error.getMessage().contains("MQTT_DISABLED"));
        assertEquals(0, mqtt.creations);
        verifyNoInteractions(mqtt.publisher, mqtt.subscriber);
    }

    @Test
    void disabledSensorSendCannotReportSuccess() {
        FakeMqtt mqtt = disabled();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> mqtt.sendSensorData("unit-sensor", "synthetic"));

        assertTrue(error.getMessage().contains("MQTT_DISABLED"));
        verifyNoInteractions(mqtt.publisher, mqtt.subscriber);
    }

    @Test
    void disabledBeanStillInjectsIntoAllFourExistingLiveServices() {
        FakeMqtt mqtt = new FakeMqtt();
        new ApplicationContextRunner()
                .withPropertyValues("lili.mqtt.enabled=false")
                .withBean(MqttUtil.class, () -> mqtt)
                .withUserConfiguration(LiveRoomServiceImpl.class, LiveGoodsServiceImpl.class,
                        LiveCouponServiceImpl.class, LiveOperateServiceImpl.class)
                .withBean(Cache.class, () -> mock(Cache.class))
                .withBean(TencentLiveUtil.class, () -> mock(TencentLiveUtil.class))
                .withBean(LiveUserService.class, () -> mock(LiveUserService.class))
                .withBean(OrderService.class, () -> mock(OrderService.class))
                .withBean(OrderItemService.class, () -> mock(OrderItemService.class))
                .withBean(SettingService.class, () -> mock(SettingService.class))
                .withBean(LiveMessageService.class, () -> mock(LiveMessageService.class))
                .withBean(LiveRoomMapper.class, () -> mock(LiveRoomMapper.class))
                .withBean(LiveGoodsMapper.class, () -> mock(LiveGoodsMapper.class))
                .withBean(LiveCouponMapper.class, () -> mock(LiveCouponMapper.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    for (Class<?> service : new Class<?>[]{LiveRoomServiceImpl.class, LiveGoodsServiceImpl.class,
                            LiveCouponServiceImpl.class, LiveOperateServiceImpl.class}) {
                        assertSame(mqtt, ReflectionTestUtils.getField(context.getBean(service), "mqttUtil"));
                    }
                    assertEquals(0, mqtt.creations);
                    assertThrows(IllegalStateException.class, () -> context.getBean(MqttUtil.class)
                            .publishMessage("unit/topic", "synthetic", 1, false));
                    verifyNoInteractions(mqtt.publisher, mqtt.subscriber);
                });
    }

    @Test
    void omittedPropertyPreservesEnabledInitializationWithFakeClients() {
        FakeMqtt mqtt = new FakeMqtt();
        new ApplicationContextRunner().withBean(MqttUtil.class, () -> mqtt).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(2, mqtt.creations);
            ArgumentCaptor<MqttConnectOptions> options = ArgumentCaptor.forClass(MqttConnectOptions.class);
            verify(mqtt.publisher).connect(options.capture());
            verify(mqtt.subscriber).connect(options.capture());
            for (MqttConnectOptions value : options.getAllValues()) {
                assertTrue(value.isAutomaticReconnect());
                assertFalse(value.isCleanSession());
                assertEquals(10, value.getConnectionTimeout());
                assertEquals(60, value.getKeepAliveInterval());
            }
        });
    }

    @Test
    void explicitEnabledPropertyConnectsOnlyFakes() {
        FakeMqtt mqtt = new FakeMqtt();
        new ApplicationContextRunner().withPropertyValues("lili.mqtt.enabled=true")
                .withBean(MqttUtil.class, () -> mqtt).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(2, mqtt.creations);
                    verify(mqtt.publisher).connect(any(MqttConnectOptions.class));
                    verify(mqtt.subscriber).connect(any(MqttConnectOptions.class));
                });
    }

    @Test
    void enabledReconnectCallbackPreservesSubscription() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        mqtt.init();
        ArgumentCaptor<MqttCallback> callback = ArgumentCaptor.forClass(MqttCallback.class);
        verify(mqtt.subscriber).setCallback(callback.capture());

        ((MqttCallbackExtended) callback.getValue()).connectComplete(true, "unit-fake-broker");

        verify(mqtt.subscriber).subscribe(cn.lili.modules.live.config.MqttConfig.DEFAULT_TOPIC_PREFIX + "#", 1);
        mqtt.disconnect();
    }

    @Test
    void enabledPublishPreservesPayloadQosAndRetained() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        mqtt.init();
        when(mqtt.publisher.isConnected()).thenReturn(true);

        mqtt.publishMessage("unit/topic", "synthetic", 2, true);

        ArgumentCaptor<MqttMessage> message = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqtt.publisher).publish(eq("unit/topic"), message.capture());
        assertEquals("synthetic", new String(message.getValue().getPayload(), StandardCharsets.UTF_8));
        assertEquals(2, message.getValue().getQos());
        assertTrue(message.getValue().isRetained());
        mqtt.disconnect();
    }

    @Test
    void enabledSensorSendPreservesLiveTopicAndQos() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        mqtt.init();
        when(mqtt.publisher.isConnected()).thenReturn(true);

        mqtt.sendSensorData("unit-sensor", "synthetic");

        ArgumentCaptor<MqttMessage> message = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqtt.publisher).publish(eq(cn.lili.modules.live.config.MqttConfig.LIVE + "unit-sensor"),
                message.capture());
        assertEquals(1, message.getValue().getQos());
        assertFalse(message.getValue().isRetained());
        mqtt.disconnect();
    }

    @Test
    void disconnectBeforeInitializationIsSafeAndIdempotent() {
        FakeMqtt mqtt = new FakeMqtt();
        assertDoesNotThrow(mqtt::disconnect);
        assertDoesNotThrow(mqtt::disconnect);
        assertEquals(0, mqtt.creations);
        verifyNoInteractions(mqtt.publisher, mqtt.subscriber);
    }

    @Test
    void failedInitializationStillClosesCreatedDisconnectedClient() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        doThrow(new MqttException(32103)).when(mqtt.publisher).connect(any(MqttConnectOptions.class));

        assertDoesNotThrow(mqtt::init);
        assertDoesNotThrow(mqtt::disconnect);

        assertEquals(1, mqtt.creations);
        verify(mqtt.publisher).close();
        verify(mqtt.publisher, never()).disconnect();
        verifyNoInteractions(mqtt.subscriber);
    }

    @Test
    void disconnectAlsoClosesClientsThatAreNotConnected() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        mqtt.init();
        mqtt.disconnect();
        mqtt.disconnect();

        verify(mqtt.publisher, times(1)).close();
        verify(mqtt.subscriber, times(1)).close();
        verify(mqtt.publisher, never()).disconnect();
        verify(mqtt.subscriber, never()).disconnect();
    }

    @Test
    void oneDisconnectFailureDoesNotPreventBothCloseAttempts() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        mqtt.init();
        when(mqtt.publisher.isConnected()).thenReturn(true);
        when(mqtt.subscriber.isConnected()).thenReturn(true);
        doThrow(new MqttException(32103)).when(mqtt.publisher).disconnect();

        assertDoesNotThrow(mqtt::disconnect);

        verify(mqtt.publisher).close();
        verify(mqtt.subscriber).disconnect();
        verify(mqtt.subscriber).close();
    }

    @Test
    void oneCloseFailureDoesNotPreventOtherClientCleanup() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        mqtt.init();
        doThrow(new MqttException(32103)).when(mqtt.publisher).close();

        assertDoesNotThrow(mqtt::disconnect);

        verify(mqtt.subscriber).close();
    }

    private static FakeMqtt disabled() {
        FakeMqtt mqtt = new FakeMqtt();
        ReflectionTestUtils.setField(mqtt, "enabled", false);
        return mqtt;
    }

    private static final class FakeMqtt extends MqttUtil {
        private final IMqttClient publisher = mock(IMqttClient.class);
        private final IMqttClient subscriber = mock(IMqttClient.class);
        private int creations;

        @Override
        protected IMqttClient createClient(String clientId) {
            return ++creations == 1 ? publisher : subscriber;
        }
    }
}
