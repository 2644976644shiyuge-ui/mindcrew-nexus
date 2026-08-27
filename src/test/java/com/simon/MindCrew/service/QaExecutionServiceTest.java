package com.simon.MindCrew.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaExecutionServiceTest {

    private ThreadPoolTaskExecutor executor;
    private QaExecutionService service;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        service = new QaExecutionService(executor);
    }

    @AfterEach
    void tearDown() {
        executor.getThreadPoolExecutor().shutdownNow();
    }

    @Test
    void waitingTurnsFromOneConversationDoNotOccupyGlobalWorkerQueue() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> completed = new CopyOnWriteArrayList<>();

        Future<?> first = service.submit("conversation-a", () -> {
            firstStarted.countDown();
            await(releaseFirst);
            completed.add("a1");
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        // a2 留在会话自己的短队列中，不应占用全局 executor 的两个等待位。
        Future<?> secondSameConversation = service.submit("conversation-a", () -> completed.add("a2"));
        Future<?> otherUserOne = service.submit("conversation-b", () -> completed.add("b1"));
        Future<?> otherUserTwo = service.submit("conversation-c", () -> completed.add("c1"));

        releaseFirst.countDown();
        first.get(3, TimeUnit.SECONDS);
        secondSameConversation.get(3, TimeUnit.SECONDS);
        otherUserOne.get(3, TimeUnit.SECONDS);
        otherUserTwo.get(3, TimeUnit.SECONDS);

        assertTrue(completed.indexOf("a1") < completed.indexOf("a2"));
        assertTrue(completed.containsAll(List.of("b1", "c1")));
    }

    @Test
    void perConversationQueueIsBounded() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<?> first = service.submit("conversation-a", () -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        Future<?> second = service.submit("conversation-a", () -> { });
        Future<?> third = service.submit("conversation-a", () -> { });
        Future<?> fourth = service.submit("conversation-a", () -> { });
        assertThrows(RejectedExecutionException.class,
                () -> service.submit("conversation-a", () -> { }));

        releaseFirst.countDown();
        first.get(3, TimeUnit.SECONDS);
        second.get(3, TimeUnit.SECONDS);
        third.get(3, TimeUnit.SECONDS);
        fourth.get(3, TimeUnit.SECONDS);
        assertEquals(0, executor.getThreadPoolExecutor().getQueue().size());
    }

    @Test
    void oneUserCannotFillTheQueueAcrossManyConversations() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<?> first = service.submit("user-a", "conversation-a1", () -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        Future<?> secondSameUser = service.submit("user-a", "conversation-a2", () -> { });
        assertThrows(RejectedExecutionException.class,
                () -> service.submit("user-a", "conversation-a3", () -> { }));

        // 用户 A 已达到自己的上限时，用户 B 仍可进入全局队列。
        Future<?> otherUser = service.submit("user-b", "conversation-b1", () -> { });
        releaseFirst.countDown();

        first.get(3, TimeUnit.SECONDS);
        secondSameUser.get(3, TimeUnit.SECONDS);
        otherUser.get(3, TimeUnit.SECONDS);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
