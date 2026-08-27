package com.simon.MindCrew.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    @Test
    void leadHuntExecutorRunsConfiguredTasksConcurrentlyAndBoundsTheQueue() throws Exception {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "leadHuntConcurrency", 4);
        ReflectionTestUtils.setField(config, "leadHuntQueueCapacity", 1);

        ThreadPoolTaskExecutor executor = config.leadHuntExecutor();
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        List<Runnable> blockers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            blockers.add(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        try {
            blockers.forEach(executor::execute);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(4, executor.getActiveCount());

            executor.execute(() -> { }); // 唯一一个等待位
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
