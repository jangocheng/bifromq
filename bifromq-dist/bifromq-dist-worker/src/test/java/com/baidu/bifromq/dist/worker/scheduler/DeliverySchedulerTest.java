package com.baidu.bifromq.dist.worker.scheduler;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import com.baidu.bifromq.plugin.subbroker.DeliveryResult;
import com.baidu.bifromq.plugin.subbroker.IDeliverer;
import com.baidu.bifromq.plugin.subbroker.ISubBroker;
import com.baidu.bifromq.plugin.subbroker.ISubBrokerManager;
import com.baidu.bifromq.type.SubInfo;
import com.baidu.bifromq.type.TopicMessagePack;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import lombok.SneakyThrows;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class DeliverySchedulerTest {
    @Mock
    private ISubBrokerManager subBrokerManager;
    @Mock
    private ISubBroker subBroker;
    @Mock
    private IDeliverer groupWriter;
    private DeliveryScheduler testScheduler;
    private AutoCloseable closeable;

    @BeforeMethod
    public void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        when(subBrokerManager.get(0)).thenReturn(subBroker);
        when(subBroker.open(anyString())).thenReturn(groupWriter);
        when(subBroker.id()).thenReturn(0);
        testScheduler = new DeliveryScheduler(subBrokerManager);
    }

    @SneakyThrows
    @AfterMethod
    public void teardown() {
        closeable.close();
    }

    @Test
    public void writeSucceed() {
        SubInfo subInfo = SubInfo.newBuilder().build();
        MessagePackWrapper msgPack = MessagePackWrapper.wrap(TopicMessagePack.newBuilder().build());
        DeliveryRequest request = new DeliveryRequest(subInfo, 0, "group1", msgPack);
        when(groupWriter.deliver(anyList())).thenReturn(
            CompletableFuture.completedFuture(Collections.singletonMap(subInfo, DeliveryResult.OK)));
        DeliveryResult result = testScheduler.schedule(request).join();
        assertEquals(result, DeliveryResult.OK);
    }

    @Test
    public void writeIncompleteResult() {
        SubInfo subInfo = SubInfo.newBuilder().build();
        MessagePackWrapper msgPack = MessagePackWrapper.wrap(TopicMessagePack.newBuilder().build());
        DeliveryRequest request = new DeliveryRequest(subInfo, 0, "group1", msgPack);
        when(groupWriter.deliver(anyList())).thenReturn(CompletableFuture.completedFuture(Collections.emptyMap()));
        DeliveryResult result = testScheduler.schedule(request).join();
        assertEquals(result, DeliveryResult.OK);
    }

    @Test
    public void writeNoInbox() {
        SubInfo subInfo = SubInfo.newBuilder().build();
        MessagePackWrapper msgPack = MessagePackWrapper.wrap(TopicMessagePack.newBuilder().build());
        DeliveryRequest request = new DeliveryRequest(subInfo, 0, "group1", msgPack);
        when(groupWriter.deliver(anyList())).thenReturn(
            CompletableFuture.completedFuture(Collections.singletonMap(subInfo, DeliveryResult.NO_INBOX)));
        DeliveryResult result = testScheduler.schedule(request).join();
        assertEquals(result, DeliveryResult.NO_INBOX);
    }

    @Test(expectedExceptions = RuntimeException.class)
    public void writeFail() {
        SubInfo subInfo = SubInfo.newBuilder().build();
        MessagePackWrapper msgPack = MessagePackWrapper.wrap(TopicMessagePack.newBuilder().build());
        DeliveryRequest request = new DeliveryRequest(subInfo, 0, "group1", msgPack);
        when(groupWriter.deliver(anyList())).thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("Mock Exception")));
        testScheduler.schedule(request).join();
        fail();
    }
}
