/*
 * Copyright (c) 2023. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.dist.client;

import com.baidu.bifromq.baserpc.IConnectable;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.Message;
import java.util.concurrent.CompletableFuture;

public interface IDistClient extends IConnectable {
    static DistClientBuilder newBuilder() {
        return new DistClientBuilder();
    }

    /**
     * publish a message at best effort, there are many edge cases that could lead to publish failure, so the normal
     * completion of the returned future doesn't mean the message has been delivered successfully. There are various
     * events reported during the process.
     *
     * @param reqId     the caller supplied request id for event tracing
     * @param topic     the message topic
     * @param publisher the publisher of the message which must have non-null tenantId and type field
     * @return a future for tracking the publishing process asynchronously
     */
    CompletableFuture<DistResult> pub(long reqId, String topic, Message message, ClientInfo publisher);

    /**
     * Add a topic match
     *
     * @param reqId        the caller supplied request id for event tracing
     * @param tenantId     the id of caller tenant
     * @param topicFilter  the topic filter to apply
     * @param receiverId   the id of the receiver hosted by the subbroker
     * @param delivererKey the key of the deliverer via which the message will be sent to the receiver
     * @param subBrokerId  the hosting subbroker of the receiver
     * @return correspond to Mqtt Sub QoS
     */
    CompletableFuture<MatchResult> match(long reqId,
                                         String tenantId,
                                         String topicFilter,
                                         String receiverId,
                                         String delivererKey,
                                         int subBrokerId);

    /**
     * Remove a topic match
     *
     * @param reqId        the caller supplied request id for event tracing
     * @param tenantId     the id of caller tenant
     * @param topicFilter  the topic filter to remove
     * @param receiverId   the id of the receiver hosted by the subbroker
     * @param delivererKey the key of the deliverer via which the message will be sent to the receiver
     * @param subBrokerId  the hosting subbroker of the receiver
     * @return true for remove successfully, false for match not found
     */
    CompletableFuture<UnmatchResult> unmatch(long reqId,
                                             String tenantId,
                                             String topicFilter,
                                             String receiverId,
                                             String delivererKey,
                                             int subBrokerId);

    void stop();
}
