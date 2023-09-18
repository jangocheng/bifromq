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

package com.baidu.bifromq.basekv.localengine;

import static com.baidu.bifromq.basekv.localengine.RangeUtil.compare;
import static com.baidu.bifromq.basekv.localengine.RangeUtil.upperBound;
import static com.google.protobuf.ByteString.EMPTY;
import static com.google.protobuf.ByteString.copyFrom;
import static com.google.protobuf.UnsafeByteOperations.unsafeWrap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.rocksdb.SizeApproximationFlag.INCLUDE_FILES;
import static org.rocksdb.SizeApproximationFlag.INCLUDE_MEMTABLES;

import com.baidu.bifromq.baseenv.EnvProvider;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Checkpoint;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.CompactRangeOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.Range;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

@Slf4j
public class RocksDBKVEngine extends AbstractKVEngine<RocksDBKVEngine.KeyRange, RocksDBKVEngine.WBatch> {
    static {
        RocksDB.loadLibrary();
    }

    private final Map<String, ColumnFamilyHandle> cfHandles = Maps.newHashMap();
    private final Map<String, ColumnFamilyDescriptor> cfDescs = Maps.newLinkedHashMap();
    private final LoadingCache<String, OpenedCheckpoint> openedCheckpoints;
    private final RocksDBKVEngineConfigurator configurator;
    private final DBOptions dbOptions;
    private final WriteOptions writeOptions;
    private final ConcurrentHashMap<RangeCompactionTask, CompletableFuture<Void>> compactionTasks =
        new ConcurrentHashMap<>();
    // key: namespace, subKey: rangeStart, value: rangeEnd
    private final Map<String, NavigableMap<ByteString, ByteString>> rangeCompactionQueue = new HashMap<>();
    private final AtomicBoolean compacting = new AtomicBoolean();
    private MetricManager metricMgr;
    private OptimisticTransactionDB instance;
    private String identity;
    private ExecutorService compactionExecutor;
    private final File dbRootDir;
    private final File dbCheckPointRootDir;
    private final Duration checkpointAge;
    private Checkpoint checkpoint;

    RocksDBKVEngine(String overrideIdentity,
                    List<String> namespaces,
                    Predicate<String> checkpointCheck,
                    RocksDBKVEngineConfigurator c) {
        super(overrideIdentity, namespaces, checkpointCheck, Duration.ofSeconds(c.getGcIntervalInSec()));
        configurator = c;
        checkpointAge = Duration.ofSeconds(c.getGcIntervalInSec() / 2);
        // default cf must appear as the first one
        cfDescs.put(DEFAULT_NS,
            new ColumnFamilyDescriptor(DEFAULT_NS.getBytes(UTF_8), configurator.config(DEFAULT_NS)));
        rangeCompactionQueue.put(DEFAULT_NS,
            new ConcurrentSkipListMap<>(ByteString.unsignedLexicographicalComparator()));
        namespaces.forEach(ns -> {
            if (!ns.equals(DEFAULT_NS)) {
                cfDescs.put(ns, new ColumnFamilyDescriptor(ns.getBytes(UTF_8), configurator.config(ns)));
                rangeCompactionQueue.put(ns,
                    new ConcurrentSkipListMap<>(ByteString.unsignedLexicographicalComparator()));
            }
        });
        openedCheckpoints = Caffeine.newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .executor(MoreExecutors.directExecutor()) // ensure Removal Listener called synchronously
            .evictionListener((RemovalListener<String, OpenedCheckpoint>) (key, value, removalCause) -> {
                log.debug("Close checkpoint[{}]", key);
                if (value != null) {
                    value.close();
                }
            })
            .build(cpPath -> new OpenedCheckpoint(cpPath, configurator));
        dbOptions = configurator.config();
        writeOptions = new WriteOptions().setDisableWAL(configurator.isDisableWAL());
        dbRootDir = new File(configurator.getDbRootDir());
        dbCheckPointRootDir = new File(configurator.getDbCheckpointRootDir());
        try {
            Files.createDirectories(dbRootDir.getAbsoluteFile().toPath());
            Files.createDirectories(dbCheckPointRootDir.getAbsoluteFile().toPath());
            boolean isCreation = isEmpty(dbRootDir.toPath());
            openRocksDB(isCreation);
            log.info("RocksDBKVEngine[{}] {} at path[{}]", identity, isCreation ? "initialized" : "loaded",
                instance.getName());
        } catch (Throwable e) {
            throw new KVEngineException("Failed to initialize RocksDB", e);
        }
    }

    @Override
    public String id() {
        return identity;
    }

    @Override
    protected KeyRange newKeyRange(int id, String namespace, ByteString start, ByteString end) {
        return new KeyRange(id, namespace, start, end);
    }

    protected ByteString doSkip(KeyRange range, long count) {
        try (final ReadOptions readOptions = new ReadOptions()) {
            if (range.start != null) {
                readOptions.setIterateLowerBound(new Slice(range.start.toByteArray()));
            }
            if (range.end != null) {
                readOptions.setIterateUpperBound(new Slice(range.end.toByteArray()));
            }
            try (final RocksIterator it = instance.newIterator(cfHandles.get(range.ns), readOptions)) {
                it.seekToFirst();
                long approximateKeys = 0;
                while (true) {
                    byte[] lastKey = new byte[] {};
                    if (it.isValid()) {
                        lastKey = it.key();
                    }
                    // The accuracy is 100, don't ask more
                    for (int i = 0; i < 100; i++) {
                        if (!it.isValid()) {
                            return copyFrom(lastKey);
                        }
                        it.next();
                        if (++approximateKeys >= count) {
                            return copyFrom(it.key());
                        }
                    }
                }
            }
        }
    }

    protected long size(ByteString start, ByteString end, KeyRange range) {
        start = start == null ? EMPTY : start;
        end = end == null ? leastUpperBound(instance, range) : end;
        if (compare(start, end) < 0) {
            try (Slice startSlice = new Slice(start.toByteArray()); Slice endSlice = new Slice(end.toByteArray());) {
                Range rocksDBRange = new Range(startSlice, endSlice);
                return instance.getApproximateSizes(cfHandles.get(range.ns),
                    singletonList(rocksDBRange),
                    INCLUDE_MEMTABLES, INCLUDE_FILES)[0];
            }
        }
        return 0;
    }

    protected long size(String checkpointId, KeyRange range, ByteString start, ByteString end) {
        File cpPath = toCheckpointPath(checkpointId);
        if (cpPath.exists()) {
            OpenedCheckpoint openedCheckpoint = openedCheckpoints.get(checkpointId);
            ByteString lowerBound = start == null ? EMPTY : start;
            ByteString upperBound = end == null ?
                leastUpperBound(openedCheckpoint.instance, range.ns, start, null) : end;
            if (compare(lowerBound, upperBound) <= 0) {
                Slice startSlice = new Slice(lowerBound.toByteArray());
                Slice endSlice = new Slice(upperBound.toByteArray());
                Range rocksDBRange = new Range(startSlice, endSlice);
                long approximateSize = openedCheckpoint.instance().getApproximateSizes(cfHandles.get(range.ns),
                    singletonList(rocksDBRange),
                    INCLUDE_MEMTABLES, INCLUDE_FILES)[0];
                startSlice.close();
                endSlice.close();
                return approximateSize;
            } else {
                return 0;
            }
        } else {
            throw new KVEngineException("Checkpoint[" + checkpointId + "] not found");
        }
    }

    protected void checkpoint(String checkpointId) {
        checkState();
        File cpPath = toCheckpointPath(checkpointId);
        log.debug("Generating checkpoint[{}] in path[{}]", checkpointId, cpPath);
        try (FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true)) {
            // flush before checkpointing
            instance.flush(flushOptions);
            checkpoint.createCheckpoint(cpPath.toString());
        } catch (RocksDBException e) {
            log.error("Failed to generate checkpoint[{}] in path[{}]", checkpointId, cpPath, e);
            throw new KVEngineException("Failed to generate checkpoint", e);
        }
    }

    @Override
    public boolean hasCheckpoint(String checkpointId) {
        checkState();
        File cpPath = toCheckpointPath(checkpointId);
        return cpPath.exists();
    }

    protected boolean exist(ByteString key, KeyRange range) {
        return instance.keyMayExist(cfHandles.get(range.ns), key.toByteArray(), null);
    }

    protected boolean exist(String checkpointId, KeyRange range, ByteString key) {
        File cpPath = toCheckpointPath(checkpointId);
        if (cpPath.exists()) {
            OpenedCheckpoint openedCheckpoint = openedCheckpoints.get(checkpointId);
            return openedCheckpoint.instance().keyMayExist(cfHandles.get(range.ns), key.toByteArray(), null);
        } else {
            throw new KVEngineException("Checkpoint[" + checkpointId + "] not found");
        }
    }

    protected Optional<ByteString> get(ByteString key, KeyRange range) {
        try {
            byte[] data = instance.get(cfHandles.get(range.ns), key.toByteArray());
            return Optional.ofNullable(data == null ? null : unsafeWrap(data));
        } catch (RocksDBException rocksDBException) {
            throw new KVEngineException("Get failed", rocksDBException);
        }
    }

    protected Optional<ByteString> get(String checkpointId, KeyRange range, ByteString key) {
        File cpPath = toCheckpointPath(checkpointId);
        if (cpPath.exists()) {
            try {
                OpenedCheckpoint openedCheckpoint = openedCheckpoints.get(checkpointId);
                byte[] data = openedCheckpoint.instance().get(cfHandles.get(range.ns), key.toByteArray());
                return Optional.ofNullable(data == null ? null : unsafeWrap(data));
            } catch (RocksDBException rocksDBException) {
                throw new KVEngineException("Get failed", rocksDBException);
            }
        } else {
            throw new KVEngineException("Checkpoint[" + checkpointId + "] not found");
        }
    }

    protected IKVEngineIterator newIterator(ByteString start, ByteString end, KeyRange range) {
        return new LatencyAwareIterator(start, end, range);
    }

    protected IKVEngineIterator newIterator(String checkpointId, String namespace, ByteString start, ByteString end) {
        File cpPath = toCheckpointPath(checkpointId);
        if (cpPath.exists()) {
            OpenedCheckpoint openedCheckpoint = openedCheckpoints.get(checkpointId);
            return new RocksDBKVEngineIterator(openedCheckpoint.instance(),
                openedCheckpoint.cfHandle(namespace), start, end);
        } else {
            throw new KVEngineException("Checkpoint[" + checkpointId + "] not found");
        }
    }

    protected WBatch newWriteBatch(int batchId) {
        return new WBatch(batchId);
    }

    protected void delete(int batchId, KeyRange range, ByteString key) {
        getBatch(batchId).delete(range, key);
    }

    protected void doDelete(KeyRange range, ByteString key) {
        int batchId = startBatch();
        delete(batchId, range, key);
        endBatch(batchId);
    }

    protected void clearSubRange(int batchId, KeyRange range, ByteString start, ByteString end) {
        getBatch(batchId).deleteRange(range, start, end);
    }

    protected void doClearSubRange(KeyRange range, ByteString start, ByteString end) {
        int batchId = startBatch();
        clearSubRange(batchId, range, start, end);
        endBatch(batchId);
    }

    protected void insert(int batchId, KeyRange range, ByteString key, ByteString value) {
        assert !exist(key, range);
        getBatch(batchId).insert(range, key, value);
    }

    protected void doInsert(KeyRange range, ByteString key, ByteString value) {
        assert !exist(key, range);
        int batchId = startBatch();
        insert(batchId, range, key, value);
        endBatch(batchId);
    }

    protected void put(int batchId, KeyRange range, ByteString key, ByteString value) {
        getBatch(batchId).put(range, key, value);
    }

    protected void doPut(KeyRange range, ByteString key, ByteString value) {
        int batchId = startBatch();
        put(batchId, range, key, value);
        endBatch(batchId);
    }

    protected void doFlush() {
        checkState();
        try {
            if (!writeOptions.disableWAL()) {
                instance.flushWal(true);
            } else {
                try (FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true)) {
                    instance.flush(flushOptions);
                }
            }
        } catch (Throwable e) {
            log.error("Flush error", e);
            throw new KVEngineException("Flush error", e);
        }
    }

    @Override
    protected Iterable<String> checkpoints() {
        return Arrays.stream(dbCheckPointRootDir.listFiles())
            .filter(d -> d.isDirectory() &&
                checkpointAge.compareTo(Duration.ofMillis(System.currentTimeMillis() - d.lastModified())) < 0)
            .map(File::getName)
            .collect(Collectors.toList());
    }

    @Override
    protected void cleanCheckpoint(String checkpointId) {
        log.debug("Deleting checkpoint[{}]", checkpointId);
        openedCheckpoints.invalidate(checkpointId);
        try {
            Files.walkFileTree(toCheckpointPath(checkpointId).toPath(), EnumSet.noneOf(FileVisitOption.class),
                Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException e) {
            log.error("Failed to clean checkpoint:{} at path:{}", checkpointId, toCheckpointPath(checkpointId));
        }
    }

    @Override
    protected void doStart(String... metricTags) {
        compactionExecutor = ExecutorServiceMetrics.monitor(Metrics.globalRegistry,
            new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                EnvProvider.INSTANCE.newThreadFactory("compaction-executor")),
            "compaction-executor-" + Tags.of(metricTags));
        metricMgr = new MetricManager(metricTags);
    }

    @Override
    protected void doStop() {
        log.info("Stopping RocksDBKVEngine[{}]", identity);
        metricMgr.close();
        openedCheckpoints.invalidateAll();
        log.debug("Waiting for compaction task[{}] finish", compactionTasks.size());
        CompletableFuture.allOf(compactionTasks.values().toArray(new CompletableFuture[0]))
            .exceptionally(e -> null)
            .join();
        log.debug("Closing rocksdb instance");
        checkpoint.close();
        instance.close();
        cfHandles.values().forEach(ColumnFamilyHandle::close);
        cfDescs.values().forEach(cfDesc -> cfDesc.getOptions().close());
        dbOptions.close();
        writeOptions.close();
        compactionExecutor.shutdown();
    }

    private void submitRangeCompactionTask(String namespace, ByteString start, ByteString end) {
        if (start == null) {
            start = ByteString.empty();
        }
        rangeCompactionQueue.get(namespace)
            .compute(start, (k, v) -> {
                if (v == null) {
                    return end == null ? ByteString.empty() : end;
                }
                if (v.isEmpty()) {
                    return v;
                }
                if (end.isEmpty()) {
                    return end;
                }
                return ByteString.unsignedLexicographicalComparator().compare(v, end) < 0 ? v : end;
            });
        compact();
    }

    private void compact() {
        if (state() != State.STARTED) {
            return;
        }
        if (compacting.compareAndSet(false, true)) {
            List<CompletableFuture<Void>> compactionFutures = new ArrayList<>();
            for (String namespace : rangeCompactionQueue.keySet()) {
                NavigableMap<ByteString, ByteString> ranges = rangeCompactionQueue.get(namespace);
                Map.Entry<ByteString, ByteString> entry = ranges.pollFirstEntry();
                if (entry != null) {
                    ByteString startKey = entry.getKey();
                    ByteString endKey = entry.getValue();
                    Map.Entry<ByteString, ByteString> nextEntry;
                    while ((nextEntry = ranges.ceilingEntry(startKey)) != null) {
                        if (endKey.isEmpty() || compare(nextEntry.getKey(), endKey) <= 0) {
                            if (!endKey.isEmpty()) {
                                // coalesces adjacent ranges
                                if (nextEntry.getValue().isEmpty()) {
                                    endKey = nextEntry.getValue();
                                } else {
                                    endKey = compare(endKey, nextEntry.getValue()) < 0 ? nextEntry.getValue() : endKey;
                                }
                            }
                            ranges.remove(nextEntry.getKey());
                        } else {
                            // coalesces the range based on boundary 'similarity'
                            break;
                        }
                    }
                    compactionFutures.add(compactRange(namespace,
                        startKey.isEmpty() ? null : startKey,
                        endKey.isEmpty() ? null : endKey));
                }
            }
            CompletableFuture.allOf(compactionFutures.toArray(CompletableFuture[]::new))
                .whenComplete((v, e) -> {
                    compacting.set(false);
                    if (rangeCompactionQueue.values().stream().anyMatch(m -> !m.isEmpty())) {
                        compact();
                    }
                });
        }
    }

    @VisibleForTesting
    CompletableFuture<Void> compactRange(String namespace, ByteString start, ByteString end) {
        RangeCompactionTask key = new RangeCompactionTask(namespace, start, end);
        CompletableFuture<Void> onDone = compactionTasks.computeIfAbsent(key, k -> {
            CompletableFuture<Void> whenFinish = new CompletableFuture<>();
            Runnable compact = metricMgr.compactionTimer.wrap(() -> {
                if (state() != State.STARTED) {
                    whenFinish.complete(null);
                    return;
                }
                try (CompactRangeOptions options = new CompactRangeOptions()) {
                    options.setBottommostLevelCompaction(CompactRangeOptions.BottommostLevelCompaction.kSkip);
                    instance.compactRange(cfHandles.get(namespace),
                        start != null ? start.toByteArray() : null,
                        end != null ? end.toByteArray() : null, options);
                    log.debug("Compaction end[id={}, namespace={}, start={}, end={}]",
                        identity, DEFAULT_NS, start, end);
                    whenFinish.complete(null);
                } catch (Throwable e) {
                    whenFinish.completeExceptionally(new KVEngineException("Compaction failed", e));
                }
            });
            log.debug("Compaction start[id={}, namespace={}, start={}, end={}]", identity, DEFAULT_NS, start, end);
            compactionExecutor.execute(compact);
            return whenFinish;
        });
        onDone.whenComplete((v, e) -> compactionTasks.remove(key, onDone));
        return onDone.thenApply(v -> null);
    }

    private void openRocksDB(boolean isCreation) throws RocksDBException {
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        instance = OptimisticTransactionDB.open(dbOptions, dbRootDir.getAbsolutePath(),
            Lists.newArrayList(cfDescs.values()), handles);
        List<String> cfNames = new ArrayList<>(cfDescs.keySet());
        for (int i = 0; i < cfNames.size(); i++) {
            cfHandles.put(cfNames.get(i), handles.get(i));
        }
        identity = loadIdentity(isCreation);
        checkpoint = Checkpoint.create(instance);
    }

    private String loadIdentity(boolean isCreation) {
        try {
            Path overrideIdentityFilePath = Paths.get(dbRootDir.getAbsolutePath(), "OVERRIDEIDENTITY");
            if (isCreation && (overrideIdentity != null && !overrideIdentity.trim().isEmpty())) {
                Files.writeString(overrideIdentityFilePath, overrideIdentity, StandardOpenOption.CREATE);
            }
            if (overrideIdentityFilePath.toFile().exists()) {
                List<String> lines = Files.readAllLines(overrideIdentityFilePath);
                if (!lines.isEmpty()) {
                    return lines.get(0);
                }
            }
            List<String> lines = Files.readAllLines(Paths.get(dbRootDir.getAbsolutePath(), "IDENTITY"));
            return lines.get(0);
        } catch (IndexOutOfBoundsException | IOException e) {
            throw new KVEngineException("Failed to read IDENTITY file", e);
        }
    }

    private ByteString leastUpperBound(RocksDB instance, KeyRange range) {
        return leastUpperBound(instance, range.ns, range.start, range.end);
    }

    private ByteString leastUpperBound(RocksDB instance, String namespace, ByteString start, ByteString end) {
        try (IKVEngineIterator itr =
                 new RocksDBKVEngineIterator(instance, cfHandles.get(namespace), start, end)) {
            itr.seekToLast();
            if (itr.isValid()) {
                return upperBound(itr.key());
            }
        }
        return EMPTY;
    }

    private boolean isEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                return entries.findFirst().isEmpty();
            }
        }

        return false;
    }

    private File toCheckpointPath(String checkpointId) {
        return Paths.get(dbCheckPointRootDir.getAbsolutePath(), checkpointId).toFile();
    }

    private static Range toRange(ByteString start, ByteString end) {
        if (start == null && end == null) {
            return new org.rocksdb.Range(null, null);
        }
        if (start == null) {
            return new org.rocksdb.Range(null, new Slice(end.toByteArray()));
        }
        if (end == null) {
            return new org.rocksdb.Range(new Slice(start.toByteArray()), null);
        }
        return new org.rocksdb.Range(new Slice(start.toByteArray()), new Slice(end.toByteArray()));
    }

    private record RangeCompactionTask(String namespace, ByteString startKey, ByteString endKey) {
    }

    @AllArgsConstructor
    private class OpenedCheckpoint {
        private final Map<String, ColumnFamilyHandle> cfHandles = Maps.newHashMap();
        private final RocksDB instance;
        private final DBOptions dbOptions;

        OpenedCheckpoint(String checkpointId, RocksDBKVEngineConfigurator configurator) {
            File cpPath = toCheckpointPath(checkpointId);
            try {
                dbOptions = configurator.config();
                List<ColumnFamilyHandle> handles = new ArrayList<>();
                instance = RocksDB.openReadOnly(dbOptions,
                    cpPath.getAbsolutePath(),
                    Lists.newArrayList(cfDescs.values()),
                    handles);
                List<String> cfNames = new ArrayList<>(cfDescs.keySet());
                for (int i = 0; i < cfNames.size(); i++) {
                    cfHandles.put(cfNames.get(i), handles.get(i));
                }
            } catch (RocksDBException e) {
                throw new KVEngineException("Failed to open checkpoint", e);
            }
        }

        public ColumnFamilyHandle cfHandle(String namespace) {
            return cfHandles.get(namespace);
        }

        public RocksDB instance() {
            return instance;
        }

        public void close() {
            instance.close();
            dbOptions.close();
        }
    }

    public class KeyRange extends AbstractKeyRange {
        private final AtomicInteger keyCount = new AtomicInteger();
        private final AtomicInteger tombstoneCount = new AtomicInteger();
        private final AtomicInteger deleteRangeCount = new AtomicInteger();

        private final ConcurrentHashMap<Integer, AtomicInteger[]> batch = new ConcurrentHashMap<>();

        KeyRange(int id, String ns, ByteString start, ByteString end) {
            super(id, ns, start, end);
        }

        void recordPut(int batchId) {
            AtomicInteger[] counters =
                batch.computeIfAbsent(batchId,
                    k -> new AtomicInteger[] {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()});
            counters[0].incrementAndGet();
            counters[1].incrementAndGet();
        }

        void recordInsert(int batchId) {
            AtomicInteger[] counters =
                batch.computeIfAbsent(batchId,
                    k -> new AtomicInteger[] {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()});
            counters[0].incrementAndGet();
        }

        void recordDelete(int batchId) {
            AtomicInteger[] counters =
                batch.computeIfAbsent(batchId,
                    k -> new AtomicInteger[] {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()});
            counters[1].incrementAndGet();
        }

        void recordDeleteRange(int batchId) {
            AtomicInteger[] counters =
                batch.computeIfAbsent(batchId,
                    k -> new AtomicInteger[] {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()});
            counters[2].incrementAndGet();
        }

        void endBatch(int batchId) {
            assert batch.containsKey(batchId);
            AtomicInteger[] counters = batch.remove(batchId);
            keyCount.addAndGet(counters[0].get());
            tombstoneCount.addAndGet(counters[1].get());
            deleteRangeCount.addAndGet(counters[2].get());
            compactIfNeeded();
        }

        void abortBatch(int batchId) {
            batch.remove(batchId);
        }

        private void compactIfNeeded() {
            int totalDeleteKeys = tombstoneCount.get();
            int totalKeys = keyCount.get();
            int totalDeleteRanges = deleteRangeCount.get();
            if (totalDeleteRanges > 0 ||
                (totalDeleteKeys > configurator.getCompactMinTombstoneKeys() &&
                    (double) totalDeleteKeys / (totalKeys + totalDeleteKeys) >=
                        configurator.getCompactTombstonePercent())) {
                tombstoneCount.set(0);
                keyCount.set(0);
                deleteRangeCount.set(0);
                submitRangeCompactionTask(DEFAULT_NS, start, end);
            }
        }
    }

    public class WBatch extends AbstractWriteBatch<KeyRange> {
        private final WriteBatch batch = new WriteBatch();
        private final Set<KeyRange> ranges = ConcurrentHashMap.newKeySet();

        private WBatch(int batchId) {
            super(batchId);
        }

        public int count() {
            return batch.count();
        }

        public void insert(KeyRange range, ByteString key, ByteString value) {
            try {
                ranges.add(range);
                batch.put(cfHandles.get(range.ns), key.toByteArray(), value.toByteArray());
                range.recordInsert(batchId);
            } catch (RocksDBException e) {
                throw new KVEngineException("Insert in batch failed", e);
            }
        }

        public void put(KeyRange range, ByteString key, ByteString value) {
            try {
                ranges.add(range);
                batch.singleDelete(cfHandles.get(range.ns), key.toByteArray());
                batch.put(cfHandles.get(range.ns), key.toByteArray(), value.toByteArray());
                range.recordPut(batchId);
            } catch (RocksDBException e) {
                throw new KVEngineException("Put in batch failed", e);
            }
        }

        public void delete(KeyRange range, ByteString key) {
            try {
                ranges.add(range);
                batch.singleDelete(cfHandles.get(range.ns), key.toByteArray());
                range.recordDelete(batchId);
            } catch (RocksDBException e) {
                throw new KVEngineException("Single delete in batch failed", e);
            }
        }

        public void deleteRange(KeyRange range, ByteString startKey, ByteString endKey) {
            try {
                ranges.add(range);
                if (startKey == null || endKey == null) {
                    try (IKVEngineIterator itr = newIterator(startKey, endKey, range)) {
                        if (startKey == null) {
                            itr.seekToFirst();
                            if (itr.isValid()) {
                                startKey = itr.key();
                            }
                        }
                        if (endKey == null) {
                            itr.seekToLast();
                            if (itr.isValid()) {
                                endKey = upperBound(itr.key());
                            }
                        }
                    }
                }
                if (startKey != null && endKey != null) {
                    batch.deleteRange(cfHandles.get(range.ns), startKey.toByteArray(), endKey.toByteArray());
                    range.recordDeleteRange(batchId);
                }
            } catch (Throwable e) {
                throw new KVEngineException("Delete range in batch failed", e);
            }
        }

        public void end() {
            try {
                if (count() > 0) {
                    // write batch always executed in underlying baseDB, since deleteRange is a
                    // disabled feature in
                    // TransactionDB and OptimisticTransactionDB
                    instance.getBaseDB().write(writeOptions, batch);
                    ranges.forEach(r -> r.endBatch(batchId));
                } else {
                    ranges.forEach(r -> r.abortBatch(batchId));
                }
            } catch (RocksDBException e) {
                log.error("Batch[{}] commit failed", batchId, e);
                throw new KVEngineException("Batch commit failed", e);
            } finally {
                batch.close();
            }
        }

        public void abort() {
            ranges.forEach(r -> r.abortBatch(batchId));
            batch.close();
        }
    }

    private class LatencyAwareIterator implements IKVEngineIterator {
        private final RocksDBKVEngineIterator delegate;
        private final long[] window;
        private final KeyRange range;
        private final ByteString start;
        private final ByteString end;
        private int total;
        private int count;
        private int estimate;

        private LatencyAwareIterator(ByteString start, ByteString end, KeyRange range) {
            delegate = new RocksDBKVEngineIterator(instance, cfHandles.get(range.ns), start, end);
            window = new long[10]; // record recent 10 latency values
            this.range = range;
            this.start = start;
            this.end = end;
        }

        @Override
        public ByteString key() {
            return delegate.key();
        }

        @Override
        public ByteString value() {
            return delegate.value();
        }

        @Override
        public boolean isValid() {
            return delegate.isValid();
        }

        @Override
        public void next() {
            delegate.next();
        }

        @Override
        public void prev() {
            delegate.prev();
        }

        @Override
        public void seekToFirst() {
            measure(delegate::seekToFirst);
        }

        @Override
        public void seekToLast() {
            measure(delegate::seekToLast);
        }

        @Override
        public void seek(ByteString target) {
            measure(() -> delegate.seek(target));
        }

        @Override
        public void seekForPrev(ByteString target) {
            measure(() -> delegate.seekForPrev(target));
        }

        @Override
        public void refresh() {
            delegate.refresh();
        }

        @Override
        public void close() {
            delegate.close();
        }

        private void measure(Runnable runnable) {
            long startNs = System.nanoTime();
            runnable.run();
            long latency = System.nanoTime() - startNs;
            int idx = count++ % window.length;
            long dropped = window[idx];
            window[idx] = latency;
            total += (int) (latency - dropped);
            estimate = total / Math.min(count, window.length);
            if (estimate > 10_000_000) { // 10ms seems a reasonable guess from my observation so far
                submitRangeCompactionTask(range.ns, start, end);
            }
            metricMgr.iterLatencySummary.record(estimate);
        }
    }

    private class MetricManager {
        private final DistributionSummary iterLatencySummary;
        private final Gauge dataTotalSpaceGauge;
        private final Gauge checkpointTotalSpaceGauge;
        private final Gauge dataUsableSpaceGauge;
        private final Gauge checkpointsUsableSpaceGauge;
        private final Gauge checkpointGauge;
        private final Gauge compactionTaskGauge;
        private final Timer compactionTimer;
        private final Gauge blockCacheMemSizeGauge;
        private final Gauge indexAndFilterSizeGauge;
        private final Gauge memtableSizeGauges;
        private final Gauge pinedMemorySizeGauges;

        MetricManager(String... metricTags) {
            Tags tags = Tags.of(metricTags);
            iterLatencySummary = DistributionSummary.builder("basekv.le.rocksdb.iter.latency")
                .tags(tags)
                .baseUnit("ns")
                .register(Metrics.globalRegistry);
            dataTotalSpaceGauge = Gauge.builder("basekv.le.rocksdb.total.data", dbRootDir::getTotalSpace)
                .tags(tags)
                .register(Metrics.globalRegistry);
            checkpointTotalSpaceGauge =
                Gauge.builder("basekv.le.rocksdb.total.checkpoints", dbCheckPointRootDir::getTotalSpace)
                    .tags(tags)
                    .register(Metrics.globalRegistry);
            dataUsableSpaceGauge = Gauge.builder("basekv.le.rocksdb.usable.data", dbRootDir::getUsableSpace)
                .tags(tags)
                .register(Metrics.globalRegistry);
            checkpointsUsableSpaceGauge = Gauge.builder("basekv.le.rocksdb.usable.checkpoints",
                    dbCheckPointRootDir::getUsableSpace)
                .tags(tags)
                .register(Metrics.globalRegistry);
            checkpointGauge = Gauge.builder("basekv.le.active.checkpoints", openedCheckpoints::estimatedSize)
                .tags(tags)
                .register(Metrics.globalRegistry);
            compactionTaskGauge = Gauge.builder("basekv.le.rocksdb.compaction", compactionTasks::size)
                .tags(tags)
                .baseUnit("tasks")
                .register(Metrics.globalRegistry);
            compactionTimer = Timer.builder("basekv.le.rocksdb.compaction.time")
                .tags(tags)
                .register(Metrics.globalRegistry);
            blockCacheMemSizeGauge = Gauge.builder("basekv.le.rocksdb.memusage",
                    () -> {
                        try {
                            return instance.getLongProperty("rocksdb.block-cache-usage");
                        } catch (RocksDBException e) {
                            log.warn("Unable to get long property {}", "rocksdb.block-cache-usage");
                            return 0;
                        }
                    })
                .tags(tags.and("kind", "blockcache"))
                .baseUnit("bytes")
                .register(Metrics.globalRegistry);

            indexAndFilterSizeGauge = Gauge.builder("basekv.le.rocksdb.memusage", () -> {
                    try {
                        return instance.getLongProperty("rocksdb.estimate-table-readers-mem");
                    } catch (RocksDBException e) {
                        log.warn("Unable to get long property {}", "rocksdb.block-cache-usage");
                        return 0;
                    }
                })
                .tags(tags.and("kind", "indexfilters"))
                .baseUnit("bytes")
                .register(Metrics.globalRegistry);

            memtableSizeGauges = Gauge.builder("basekv.le.rocksdb.memusage", () -> {
                    try {
                        return instance.getLongProperty("rocksdb.block-cache-usage");
                    } catch (RocksDBException e) {
                        log.warn("Unable to get long property {}", "rocksdb.cur-size-all-mem-tables");
                        return 0;
                    }
                })
                .tags(tags.and("kind", "memtable"))
                .baseUnit("bytes")
                .register(Metrics.globalRegistry);

            pinedMemorySizeGauges = Gauge.builder("basekv.le.rocksdb.memusage", () -> {
                    try {
                        return instance.getLongProperty("rocksdb.cur-size-all-mem-tables");
                    } catch (RocksDBException e) {
                        log.warn("Unable to get long property {}", "rocksdb.block-cache-pinned-usage");
                        return 0;
                    }
                })
                .tags(tags.and("kind", "pinedblocks"))
                .baseUnit("bytes")
                .register(Metrics.globalRegistry);

        }

        void close() {
            Metrics.globalRegistry.remove(iterLatencySummary);
            Metrics.globalRegistry.remove(dataTotalSpaceGauge);
            Metrics.globalRegistry.remove(checkpointTotalSpaceGauge);
            Metrics.globalRegistry.remove(dataUsableSpaceGauge);
            Metrics.globalRegistry.remove(checkpointsUsableSpaceGauge);
            Metrics.globalRegistry.remove(checkpointGauge);
            Metrics.globalRegistry.remove(compactionTaskGauge);
            Metrics.globalRegistry.remove(compactionTimer);
            Metrics.globalRegistry.remove(blockCacheMemSizeGauge);
            Metrics.globalRegistry.remove(indexAndFilterSizeGauge);
            Metrics.globalRegistry.remove(memtableSizeGauges);
            Metrics.globalRegistry.remove(pinedMemorySizeGauges);
        }
    }
}
