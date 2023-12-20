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

package com.baidu.bifromq.retain.store;

import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.store.api.IKVRangeCoProc;
import com.baidu.bifromq.basekv.store.api.IKVRangeCoProcFactory;
import com.baidu.bifromq.basekv.store.api.IKVReader;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import java.time.Clock;
import java.util.function.Supplier;

public class RetainStoreCoProcFactory implements IKVRangeCoProcFactory {
    private final ISettingProvider settingProvider;
    private final Clock clock;

    public RetainStoreCoProcFactory(ISettingProvider settingProvider, Clock clock) {
        this.settingProvider = settingProvider;
        this.clock = clock;
    }

    @Override
    public IKVRangeCoProc createCoProc(String clusterId, String storeId, KVRangeId id,
                                       Supplier<IKVReader> rangeReaderProvider) {
        return new RetainStoreCoProc(id, rangeReaderProvider, settingProvider, clock);
    }
}
