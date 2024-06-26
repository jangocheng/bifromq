/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.mqtt.handler;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.plugin.eventcollector.EventType;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.google.common.util.concurrent.RateLimiter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.TimeUnit;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ConnectionRateLimitHandlerTest {
    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private IEventCollector eventCollector;

    @Mock
    private ConnectionRateLimitHandler.ChannelPipelineInitializer initializer;

    private ConnectionRateLimitHandler handler;

    @BeforeMethod
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ConnectionRateLimitHandler(rateLimiter, eventCollector, initializer);
    }

    @Test
    public void testChannelActiveWhenRateLimiterAllows() {
        when(rateLimiter.tryAcquire()).thenReturn(true);

        EmbeddedChannel channel = new EmbeddedChannel(handler);

        verify(initializer).initialize(channel.pipeline());
        assertTrue(channel.isActive());
    }

    @Test
    public void testChannelActiveWhenRateLimiterDenies() {
        when(rateLimiter.tryAcquire()).thenReturn(false);

        EmbeddedChannel channel = new EmbeddedChannel(handler);

        verify(initializer, never()).initialize(any(ChannelPipeline.class));
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());
        verify(eventCollector).report(argThat(e -> e.type() == EventType.CHANNEL_ERROR));
    }
}
