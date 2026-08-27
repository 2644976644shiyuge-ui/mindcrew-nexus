package com.simon.MindCrew.maintenance;

import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.KnowledgeBaseService;
import com.simon.MindCrew.service.knowledge.MilvusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class KnowledgeRebuildRunnerTest {

    @TempDir
    Path tempDirectory;

    private final KnowledgeRebuildRunner runner = new KnowledgeRebuildRunner(
            mock(MedKnowledgeBaseMapper.class),
            mock(KbChunkMapper.class),
            mock(KnowledgeBaseService.class),
            mock(MilvusService.class),
            mock(DataSource.class)
    );

    @AfterEach
    void closeExecutor() {
        runner.shutdown();
    }

    @Test
    void parsesSupportedPropertiesAndResolvesRelativeProgressPath() throws Exception {
        Path request = tempDirectory.resolve("rebuild.request");
        Files.writeString(request, """
                ids=44, 12,44
                max-documents=7
                timeout-minutes=25
                poll-seconds=3
                stop-on-failure=false
                progress-file=checkpoints/rebuild.progress
                cleanup-orphans=true
                force=true
                """, StandardCharsets.UTF_8);

        KnowledgeRebuildRunner.RebuildRequest parsed = runner.readRequest(request, tempDirectory);

        assertEquals(java.util.List.of(44L, 12L), parsed.ids());
        assertEquals(7, parsed.maxDocuments());
        assertEquals(25, parsed.timeoutMinutes());
        assertEquals(3, parsed.pollSeconds());
        assertFalse(parsed.stopOnFailure());
        assertEquals(tempDirectory.resolve("checkpoints/rebuild.progress").toAbsolutePath().normalize(),
                parsed.progressFile());
        assertTrue(parsed.cleanupOrphans());
        assertTrue(parsed.force());
    }

    @Test
    void appliesSafeDefaults() throws Exception {
        Path request = tempDirectory.resolve("rebuild.request");
        Files.writeString(request, "", StandardCharsets.UTF_8);

        KnowledgeRebuildRunner.RebuildRequest parsed = runner.readRequest(request, tempDirectory);

        assertTrue(parsed.ids().isEmpty());
        assertEquals(Integer.MAX_VALUE, parsed.maxDocuments());
        assertEquals(90, parsed.timeoutMinutes());
        assertEquals(2, parsed.pollSeconds());
        assertTrue(parsed.stopOnFailure());
        assertFalse(parsed.cleanupOrphans());
        assertFalse(parsed.force());
        assertEquals(Path.of(KnowledgeRebuildRunner.DEFAULT_PROGRESS_FILE), parsed.progressFile());
    }

    @Test
    void rejectsInvalidPropertiesWithoutEchoingTheirValues() throws Exception {
        Path invalidBoolean = tempDirectory.resolve("invalid-boolean.request");
        Files.writeString(invalidBoolean, "force=definitely-not-a-secret\n", StandardCharsets.UTF_8);
        IllegalArgumentException booleanError = assertThrows(IllegalArgumentException.class,
                () -> runner.readRequest(invalidBoolean, tempDirectory));
        assertEquals("force must be true or false", booleanError.getMessage());

        Path invalidTimeout = tempDirectory.resolve("invalid-timeout.request");
        Files.writeString(invalidTimeout, "timeout-minutes=0\n", StandardCharsets.UTF_8);
        IllegalArgumentException timeoutError = assertThrows(IllegalArgumentException.class,
                () -> runner.readRequest(invalidTimeout, tempDirectory));
        assertEquals("timeout-minutes must be greater than zero", timeoutError.getMessage());
    }

    @Test
    void checkpointSetIsMutableAndOnlyLatestEventWins() throws Exception {
        Path missing = tempDirectory.resolve("missing.progress");
        var initiallyEmpty = runner.loadCompletedIds(missing);
        initiallyEmpty.add(7L);
        assertEquals(java.util.Set.of(7L), initiallyEmpty);

        Path progress = tempDirectory.resolve("rebuild.progress");
        Files.writeString(progress, """
                2026-08-23T00:00:00Z\tREADY\t10\t3\tok
                2026-08-23T00:00:01Z\tFAILED\t10\t0\tretry
                2026-08-23T00:00:02Z\tREADY\t11\t4\tok
                """, StandardCharsets.UTF_8);

        var completed = runner.loadCompletedIds(progress);
        assertFalse(completed.contains(10L));
        assertTrue(completed.contains(11L));
        completed.add(12L);
        assertTrue(completed.contains(12L));
    }
}
