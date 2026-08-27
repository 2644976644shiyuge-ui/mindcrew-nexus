package com.simon.MindCrew.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.KnowledgeBaseService;
import com.simon.MindCrew.service.knowledge.MilvusService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 由本地请求文件触发的知识库串行索引重建协调器。
 *
 * <p>主应用只负责快速检测并原子 claim 请求文件，耗时任务在独立单线程执行器中运行，
 * 不占用 Spring 全局 scheduler。跨实例互斥由同一条独占 JDBC Connection 上持有的
 * MySQL advisory lock 保证；整个过程不暴露 HTTP 接口，也不需要管理员密码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRebuildRunner {

    static final String INDEX_VERSION = "context-v2";
    static final String LOCK_NAME = "mindcrew_kb_rebuild";
    static final String DEFAULT_PROGRESS_FILE = "/app/logs/kb-rebuild-context-v2.progress";

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|authorization|token|password|passwd|secret)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)Bearer\\s+[^\\s,;]+");
    private static final Pattern URL_USER_INFO = Pattern.compile("(?i)(://[^:/\\s]+:)[^@/\\s]+@");

    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper chunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final MilvusService milvusService;
    private final DataSource dataSource;

    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private final ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kb-rebuild-coordinator");
        thread.setDaemon(false);
        thread.setUncaughtExceptionHandler((ignored, error) ->
                log.error("[KbRebuild] worker terminated type={} err={}",
                        error.getClass().getSimpleName(), safeMessage(error)));
        return thread;
    });

    @Value("${mindcrew.maintenance.rebuild.request-file:/app/logs/kb-rebuild-context-v2.request}")
    private String requestFile;

    /**
     * 检测本地触发文件。这里只做原子 claim 和投递，绝不在 scheduler 线程内执行重建。
     */
    @Scheduled(fixedDelayString = "${mindcrew.maintenance.rebuild.scan-ms:5000}")
    public void detectRequest() {
        if (!requestInFlight.compareAndSet(false, true)) return;

        Path request = null;
        Path claimed = null;
        try {
            request = configuredRequestPath();
            if (!Files.isRegularFile(request)) {
                requestInFlight.set(false);
                return;
            }
            claimed = claim(request);
            Path originalRequest = request;
            Path claimedRequest = claimed;
            rebuildExecutor.execute(() -> runClaimedRequest(originalRequest, claimedRequest));
        } catch (NoSuchFileException ignored) {
            // 多实例或人工撤回请求时属于正常竞争结果。
            requestInFlight.set(false);
        } catch (RejectedExecutionException ex) {
            requestInFlight.set(false);
            moveToOutcome(request, claimed, "failed");
            log.error("[KbRebuild] request rejected because coordinator is shutting down");
        } catch (Throwable error) {
            requestInFlight.set(false);
            moveToOutcome(request, claimed, "failed");
            log.error("[KbRebuild] request claim failed type={} err={}",
                    error.getClass().getSimpleName(), safeMessage(error));
            rethrowFatal(error);
        }
    }

    private void runClaimedRequest(Path request, Path claimed) {
        boolean success = false;
        boolean interrupted = false;
        Throwable failure = null;
        Connection lockConnection = null;
        boolean lockHeld = false;
        try {
            RebuildRequest rebuildRequest = readRequest(claimed, request.getParent());
            lockConnection = dataSource.getConnection();
            lockHeld = tryAcquireLock(lockConnection);
            if (!lockHeld) {
                throw new IllegalStateException("another knowledge rebuild currently owns the database lock");
            }

            RunSummary summary = executeRebuild(rebuildRequest, lockConnection);
            success = summary.failed() == 0;
            log.info("[KbRebuild] COMPLETE success={} failed={} skipped={} orphanRows={}",
                    summary.succeeded(), summary.failed(), summary.skipped(), summary.orphanRowsDeleted());
        } catch (Throwable error) {
            failure = error;
            interrupted = error instanceof InterruptedException || Thread.currentThread().isInterrupted();
            log.error("[KbRebuild] ABORT type={} err={}",
                    error.getClass().getSimpleName(), safeMessage(error));
        } finally {
            if (lockConnection != null) {
                if (lockHeld) releaseLock(lockConnection);
                try {
                    lockConnection.close();
                } catch (SQLException closeError) {
                    log.warn("[KbRebuild] lock connection close failed type={} err={}",
                            closeError.getClass().getSimpleName(), safeMessage(closeError));
                }
            }
            moveToOutcome(request, claimed, success ? "done" : "failed");
            requestInFlight.set(false);
            if (interrupted) Thread.currentThread().interrupt();
        }

        rethrowFatal(failure);
    }

    private RunSummary executeRebuild(RebuildRequest request, Connection lockConnection) throws Exception {
        Path progress = request.progressFile();
        Path parent = progress.getParent();
        if (parent != null) Files.createDirectories(parent);

        ensureLockHeld(lockConnection);
        assertNoActiveRebuilds();
        int orphanRowsDeleted = request.cleanupOrphans() ? cleanupOrphans(lockConnection) : 0;
        // 协调器会在每个成功文档后追加 ID；始终复制为可变集合，避免首次运行时
        // 空 checkpoint 集合导致 add() 抛 UnsupportedOperationException。
        Set<Long> completedIds = new LinkedHashSet<>(loadCompletedIds(progress));
        List<MedKnowledgeBase> plan = buildPlan(request.ids());
        log.info("[KbRebuild] PLAN documents={} completedCheckpoint={} cleanupOrphans={} force={}",
                plan.size(), completedIds.size(), request.cleanupOrphans(), request.force());

        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        int attempted = 0;

        for (MedKnowledgeBase snapshot : plan) {
            if (attempted >= request.maxDocuments()) break;
            ensureLockHeld(lockConnection);

            Long id = snapshot.getId();
            MedKnowledgeBase current = knowledgeBaseMapper.selectById(id);
            if (!isRebuildCandidate(current)) {
                append(progress, "SKIP", id, current == null ? "missing" : current.getStatus(), 0L);
                skipped++;
                continue;
            }

            if (!request.force()
                    && "ready".equals(current.getStatus())
                    && completedIds.contains(id)
                    && allChunksAtCurrentVersion(lockConnection, id)) {
                skipped++;
                log.info("[KbRebuild] SKIP id={} checkpoint and {} metadata verified", id, INDEX_VERSION);
                continue;
            }

            attempted++;
            String metadataBefore = metadataFingerprint(current);
            long oldChunks = chunkCount(id);
            append(progress, "START", id, "oldChunks=" + oldChunks, oldChunks);
            log.info("[KbRebuild] START id={} oldChunks={} ({}/{})",
                    id, oldChunks, attempted, Math.min(plan.size(), request.maxDocuments()));

            try {
                knowledgeBaseService.reprocessForMaintenance(id);
                MedKnowledgeBase rebuilt = waitForTerminal(id, request, lockConnection);
                long actualChunks = chunkCount(id);
                if (!"ready".equals(rebuilt.getStatus()) || actualChunks <= 0
                        || rebuilt.getChunkCount() == null || rebuilt.getChunkCount() <= 0) {
                    throw new IllegalStateException("status=" + rebuilt.getStatus()
                            + " chunks=" + actualChunks + " error=" + safeText(rebuilt.getErrorMsg()));
                }
                if (rebuilt.getChunkCount().longValue() != actualChunks) {
                    throw new IllegalStateException("chunk count mismatch entity="
                            + rebuilt.getChunkCount() + " database=" + actualChunks);
                }
                long liveVectors = milvusService.countLiveByKnowledgeBaseId(id);
                if (liveVectors != actualChunks) {
                    throw new IllegalStateException("vector count mismatch milvus="
                            + liveVectors + " database=" + actualChunks);
                }
                if (!allChunksAtCurrentVersion(lockConnection, id)) {
                    throw new IllegalStateException("not all chunks carry indexVersion=" + INDEX_VERSION);
                }
                if (!Objects.equals(metadataBefore, metadataFingerprint(rebuilt))) {
                    throw new IllegalStateException("rebuild changed category, tags or summary");
                }

                append(progress, "READY", id, "newChunks=" + actualChunks, actualChunks);
                completedIds.add(id);
                succeeded++;
                log.info("[KbRebuild] READY id={} chunks={} success={}", id, actualChunks, succeeded);
            } catch (Throwable error) {
                failed++;
                append(progress, "FAILED", id, safeMessage(error), 0L);
                log.error("[KbRebuild] FAILED id={} type={} err={}",
                        id, error.getClass().getSimpleName(), safeMessage(error));
                if (error instanceof Error fatal) throw fatal;
                if (error instanceof InterruptedException interrupted) throw interrupted;
                if (request.stopOnFailure()) break;
            }
        }
        return new RunSummary(succeeded, failed, skipped, orphanRowsDeleted);
    }

    private List<MedKnowledgeBase> buildPlan(List<Long> requestedIds) {
        LambdaQueryWrapper<MedKnowledgeBase> query = new LambdaQueryWrapper<MedKnowledgeBase>()
                .eq(MedKnowledgeBase::getDeleted, 0)
                .in(MedKnowledgeBase::getStatus, List.of("ready", "rebuild_failed"))
                .isNotNull(MedKnowledgeBase::getOssObjectName);
        if (!requestedIds.isEmpty()) query.in(MedKnowledgeBase::getId, requestedIds);
        query.orderByAsc(MedKnowledgeBase::getFileSize).orderByAsc(MedKnowledgeBase::getId);
        List<MedKnowledgeBase> rows = knowledgeBaseMapper.selectList(query);
        if (requestedIds.isEmpty()) return rows;

        // 显式 canary ID 按请求顺序执行，而不是按文件大小重排。
        Map<Long, MedKnowledgeBase> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.getId(), row));
        List<MedKnowledgeBase> ordered = new ArrayList<>();
        requestedIds.forEach(id -> {
            MedKnowledgeBase row = byId.get(id);
            if (row != null) ordered.add(row);
        });
        List<Long> unavailable = requestedIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!unavailable.isEmpty()) {
            throw new IllegalStateException("requested documents are missing, non-terminal, or lack an OSS original: "
                    + unavailable);
        }
        return ordered;
    }

    private void assertNoActiveRebuilds() {
        Long count = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<MedKnowledgeBase>()
                        .eq(MedKnowledgeBase::getDeleted, 0)
                        .in(MedKnowledgeBase::getStatus, List.of("rebuild_queued", "rebuilding")));
        if (count != null && count > 0) {
            throw new IllegalStateException("an earlier knowledge rebuild is still active; wait for recovery first");
        }
    }

    private MedKnowledgeBase waitForTerminal(Long id, RebuildRequest request,
                                               Connection lockConnection) throws Exception {
        long started = System.nanoTime();
        long timeout = TimeUnit.MINUTES.toNanos(request.timeoutMinutes());
        while (System.nanoTime() - started < timeout) {
            ensureLockHeld(lockConnection);
            MedKnowledgeBase current = knowledgeBaseMapper.selectById(id);
            if (current == null) throw new IllegalStateException("document record disappeared");
            if ("ready".equals(current.getStatus())
                    || "failed".equals(current.getStatus())
                    || "rebuild_failed".equals(current.getStatus())) {
                return current;
            }
            TimeUnit.SECONDS.sleep(request.pollSeconds());
        }
        throw new IllegalStateException("document rebuild timed out; do not retry concurrently");
    }

    /**
     * 清理已没有 active knowledge-base 记录的关系数据。向量必须先严格删除并获得确认，
     * 然后才在单个数据库事务中删除该 kb_id 的 chunk/parent/graph 行。
     */
    private int cleanupOrphans(Connection connection) throws SQLException {
        List<Long> orphanIds = findOrphanIds(connection);
        int deletedRows = 0;
        for (Long orphanId : orphanIds) {
            ensureLockHeld(connection);
            milvusService.deleteByKnowledgeBaseIdStrict(orphanId);
            long remainingVectors = milvusService.countLiveByKnowledgeBaseId(orphanId);
            if (remainingVectors != 0L) {
                throw new IllegalStateException("orphan vector delete was not visible for knowledge id " + orphanId);
            }
            deletedRows += deleteOrphanRows(connection, orphanId);
            log.info("[KbRebuild] orphan cleanup id={} complete", orphanId);
        }
        log.info("[KbRebuild] orphan cleanup ids={} rows={}", orphanIds.size(), deletedRows);
        return deletedRows;
    }

    private List<Long> findOrphanIds(Connection connection) throws SQLException {
        String sql = """
                SELECT orphan.kb_id
                  FROM (
                        SELECT kb_id FROM kb_chunk
                        UNION SELECT kb_id FROM kb_parent_chunk
                        UNION SELECT kb_id FROM kb_graph_node
                        UNION SELECT kb_id FROM kb_graph_edge
                  ) orphan
                  LEFT JOIN kb_knowledge_base kb
                    ON kb.id = orphan.kb_id AND kb.deleted = ?
                 WHERE orphan.kb_id IS NOT NULL AND kb.id IS NULL
                 ORDER BY orphan.kb_id
                """;
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, 0);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
        }
        return ids;
    }

    private int deleteOrphanRows(Connection connection, Long kbId) throws SQLException {
        String[] statements = {
                "DELETE FROM kb_chunk WHERE kb_id = ?",
                "DELETE FROM kb_parent_chunk WHERE kb_id = ?",
                "DELETE FROM kb_graph_edge WHERE kb_id = ?",
                "DELETE FROM kb_graph_node WHERE kb_id = ?"
        };
        boolean originalAutoCommit = connection.getAutoCommit();
        boolean committed = false;
        int deleted = 0;
        try {
            connection.setAutoCommit(false);
            for (String sql : statements) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, kbId);
                    deleted += statement.executeUpdate();
                }
            }
            connection.commit();
            committed = true;
            return deleted;
        } finally {
            if (!committed) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    log.error("[KbRebuild] orphan rollback failed type={} err={}",
                            rollbackError.getClass().getSimpleName(), safeMessage(rollbackError));
                }
            }
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private boolean allChunksAtCurrentVersion(Connection connection, Long id) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE
                           WHEN JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.indexVersion')) = ? THEN 0
                           ELSE 1 END), 0) AS mismatched
                  FROM kb_chunk
                 WHERE kb_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, INDEX_VERSION);
            statement.setLong(2, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                long total = result.getLong("total");
                long mismatched = result.getLong("mismatched");
                return total > 0 && mismatched == 0;
            }
        }
    }

    private long chunkCount(Long id) {
        Long count = chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getKbId, id));
        return count == null ? 0L : count;
    }

    /**
     * 只把每个 ID 的最后一个 checkpoint 视为有效，避免历史 READY 后又 FAILED 时误跳过。
     */
    Set<Long> loadCompletedIds(Path progress) throws IOException {
        Map<Long, String> latestEvents = new HashMap<>();
        if (!Files.exists(progress)) return new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(progress, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\t", 5);
                if (fields.length < 3) continue;
                try {
                    latestEvents.put(Long.parseLong(fields[2]), fields[1]);
                } catch (NumberFormatException ignored) {
                    // 忽略不完整的最后一行或人工注释。
                }
            }
        }
        Set<Long> completed = new LinkedHashSet<>();
        latestEvents.forEach((id, event) -> {
            if ("READY".equals(event)) completed.add(id);
        });
        return completed;
    }

    private void append(Path progress, String event, Long id, String detail, long chunks) throws IOException {
        String line = Instant.now() + "\t" + event + "\t" + id + "\t" + chunks
                + "\t" + safeText(detail) + "\n";
        try (BufferedWriter writer = Files.newBufferedWriter(progress, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            writer.write(line);
        }
    }

    RebuildRequest readRequest(Path claimed, Path requestDirectory) throws IOException {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(claimed, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        List<Long> ids = parseIds(properties.getProperty("ids", ""));
        int maxDocuments = positiveOrZeroInt(properties, "max-documents", Integer.MAX_VALUE);
        int timeoutMinutes = positiveInt(properties, "timeout-minutes", 90);
        int pollSeconds = positiveInt(properties, "poll-seconds", 2);
        boolean stopOnFailure = booleanProperty(properties, "stop-on-failure", true);
        boolean cleanupOrphans = booleanProperty(properties, "cleanup-orphans", false);
        boolean force = booleanProperty(properties, "force", false);

        String progressValue = properties.getProperty("progress-file", DEFAULT_PROGRESS_FILE).trim();
        if (progressValue.isEmpty()) throw new IllegalArgumentException("progress-file must not be blank");
        Path progress = Path.of(progressValue);
        if (!progress.isAbsolute()) {
            Path base = requestDirectory == null ? Path.of(".").toAbsolutePath() : requestDirectory;
            progress = base.resolve(progress);
        }
        progress = progress.toAbsolutePath().normalize();

        return new RebuildRequest(ids, maxDocuments, timeoutMinutes, pollSeconds,
                stopOnFailure, progress, cleanupOrphans, force);
    }

    private List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Long::parseLong)
                    .filter(value -> value > 0)
                    .distinct()
                    .toList();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("ids must be a comma-separated list of positive integers");
        }
    }

    private int positiveInt(Properties properties, String name, int defaultValue) {
        int value = intProperty(properties, name, defaultValue);
        if (value <= 0) throw new IllegalArgumentException(name + " must be greater than zero");
        return value;
    }

    private int positiveOrZeroInt(Properties properties, String name, int defaultValue) {
        int value = intProperty(properties, name, defaultValue);
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private int intProperty(Properties properties, String name, int defaultValue) {
        String raw = properties.getProperty(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private boolean booleanProperty(Properties properties, String name, boolean defaultValue) {
        String raw = properties.getProperty(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        if ("true".equalsIgnoreCase(raw.trim())) return true;
        if ("false".equalsIgnoreCase(raw.trim())) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private boolean tryAcquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, 0);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1 && !result.wasNull();
            }
        }
    }

    private void ensureLockHeld(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT CASE WHEN IS_USED_LOCK(?) = CONNECTION_ID() THEN 1 ELSE 0 END")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("knowledge rebuild database lock was lost");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet ignored = statement.executeQuery()) {
                // 执行查询即可；连接随后关闭也是最终兜底。
            }
        } catch (Throwable error) {
            log.warn("[KbRebuild] database lock release failed type={} err={}",
                    error.getClass().getSimpleName(), safeMessage(error));
        }
    }

    private Path claim(Path request) throws IOException {
        Path claimed = request.resolveSibling(request.getFileName() + ".claimed-"
                + System.currentTimeMillis() + "-" + UUID.randomUUID());
        // 不降级为 copy/delete；只有文件系统确认支持原子 rename 才接管任务。
        return Files.move(request, claimed, StandardCopyOption.ATOMIC_MOVE);
    }

    private void moveToOutcome(Path request, Path claimed, String outcome) {
        try {
            if (request == null || claimed == null || !Files.exists(claimed)) return;
            Path target = request.resolveSibling(request.getFileName() + "." + outcome + "-"
                    + System.currentTimeMillis() + "-" + UUID.randomUUID());
            try {
                Files.move(claimed, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(claimed, target);
            }
        } catch (Throwable moveError) {
            log.error("[KbRebuild] outcome rename failed type={} err={}",
                    moveError.getClass().getSimpleName(), safeMessage(moveError));
        }
    }

    private Path configuredRequestPath() {
        return Path.of(requestFile).toAbsolutePath().normalize();
    }

    private boolean isRebuildCandidate(MedKnowledgeBase kb) {
        return kb != null && ("ready".equals(kb.getStatus()) || "rebuild_failed".equals(kb.getStatus()));
    }

    private String metadataFingerprint(MedKnowledgeBase kb) {
        return String.join("\u001f",
                Objects.toString(kb.getCategory(), ""),
                Objects.toString(kb.getTags(), ""),
                Objects.toString(kb.getSummary(), ""),
                Objects.toString(kb.getCategoryUserSet(), ""));
    }

    private static String safeMessage(Throwable error) {
        return error == null ? "" : safeText(error.getMessage());
    }

    private static String safeText(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[\\r\\n\\t]+", " ");
        safe = SECRET_ASSIGNMENT.matcher(safe).replaceAll("$1=[REDACTED]");
        safe = BEARER_VALUE.matcher(safe).replaceAll("Bearer [REDACTED]");
        safe = URL_USER_INFO.matcher(safe).replaceAll("$1[REDACTED]@");
        return safe.length() <= 300 ? safe : safe.substring(0, 300) + "…";
    }

    private static void rethrowFatal(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
    }

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdown();
        try {
            if (!rebuildExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                rebuildExecutor.shutdownNow();
                if (!rebuildExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("[KbRebuild] coordinator did not terminate cleanly");
                }
            }
        } catch (InterruptedException interrupted) {
            rebuildExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    record RebuildRequest(List<Long> ids,
                          int maxDocuments,
                          int timeoutMinutes,
                          int pollSeconds,
                          boolean stopOnFailure,
                          Path progressFile,
                          boolean cleanupOrphans,
                          boolean force) {
        RebuildRequest {
            ids = List.copyOf(ids);
        }
    }

    private record RunSummary(int succeeded, int failed, int skipped, int orphanRowsDeleted) { }
}
