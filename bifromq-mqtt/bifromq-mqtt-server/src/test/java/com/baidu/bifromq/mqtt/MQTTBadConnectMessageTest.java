/*
 * Copyright (c) 2023. Baidu, Inc. All Rights Reserved.
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

package com.baidu.bifromq.mqtt;

import static org.eclipse.paho.client.mqttv3.MqttException.REASON_CODE_INVALID_CLIENT_ID;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.baidu.bifromq.mqtt.client.MqttTestClient;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.channelclosed.IdentifierRejected;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class MQTTBadConnectMessageTest extends MQTTTest {
    @Test
    public void testCleanSessionFalseAndEmptyClientIdentifier() {
        MqttTestClient mqttClient = new MqttTestClient(brokerURI, "");

        MqttConnectOptions connOpts = new MqttConnectOptions();
        connOpts.setMqttVersion(4);
        connOpts.setCleanSession(false);
        connOpts.setWill("/abc", new byte[] {}, 0, false);
        MqttException e = TestUtils.expectThrow(() -> mqttClient.connect(connOpts));
        assertEquals(REASON_CODE_INVALID_CLIENT_ID, e.getReasonCode());

        verify(eventCollector).report(argThat(event -> event instanceof IdentifierRejected));
        mqttClient.close();
    }

    @Test
    public void testInvalidClientId() {
        MqttTestClient mqttClient = new MqttTestClient(brokerURI, ",.+!@");

        MqttConnectOptions connOpts = new MqttConnectOptions();
        connOpts.setMqttVersion(4);
        connOpts.setCleanSession(false);
        connOpts.setWill("/abc", new byte[] {}, 0, false);
        MqttException e = TestUtils.expectThrow(() -> mqttClient.connect(connOpts));
        assertEquals(REASON_CODE_INVALID_CLIENT_ID, e.getReasonCode());

        verify(eventCollector).report(argThat(event -> event instanceof IdentifierRejected));
    }
}
