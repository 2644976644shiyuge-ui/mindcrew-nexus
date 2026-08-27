package com.simon.MindCrew.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能问答/数字员工共享的有界后台执行入口。
 * 提交时固定当前 SecurityContext；调用方可在 SSE 断开后取消 Future。
 */
@Service
public class QaExecutionService {

    private final ThreadPoolTaskExecutor executor;
    private final ConcurrentHashMap<String, SerialQueue> conversationQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> userOutstanding = new ConcurrentHashMap<>();

    /** 包含正在执行的一条；限制同一会话连点造成的内存排队和跨用户饥饿。 */
    @Value("${mindcrew.qa.per-conversation-queue-capacity:4}")
    private int perConversationQueueCapacity = 4;

    /** 每个用户最多同时占用的运行中+等待中请求数，防止单用户多开会话挤满全局队列。 */
    @Value("${mindcrew.qa.per-user-capacity:2}")
    private int perUserCapacity = 2;

    public QaExecutionService(@Qualifier("qaExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    public Future<?> submit(Runnable task) {
        return submit(null, null, task);
    }

    /**
     * 同一会话严格串行，避免并发落库后历史互相串线；等待中的同会话任务不占 QA worker，
     * 因而一个用户连续点击不会把其他用户的工作线程全部锁住。
     */
    public Future<?> submit(String conversationKey, Runnable task) {
        return submit(null, conversationKey, task);
    }

    /** userKey 用于跨会话公平限流；conversationKey 用于同会话 FIFO。 */
    public Future<?> submit(String userKey, String conversationKey, Runnable task) {
        acquireUserPermit(userKey);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        FutureTask<Void> future = new FutureTask<>(
                new DelegatingSecurityContextRunnable(task, context), null);
        QueuedTask queuedTask = new QueuedTask(future, userKey);

        try {
            if (conversationKey == null || conversationKey.isBlank()) {
                executor.execute(() -> runStandalone(queuedTask));
                return future;
            }

            int capacity = Math.max(1, perConversationQueueCapacity);
            while (true) {
                SerialQueue queue = conversationQueues.computeIfAbsent(conversationKey, ignored -> new SerialQueue());
                synchronized (queue) {
                    // 完成线程先标记 closed 再移除 map；并发提交拿到旧引用时重试即可。
                    if (queue.closed) continue;
                    if (queue.outstanding >= capacity) {
                        throw new RejectedExecutionException("同一会话等待请求过多，请等待当前回答完成");
                    }

                    queue.outstanding++;
                    if (queue.running) {
                        queue.waiting.addLast(queuedTask);
                        return future;
                    }

                    queue.running = true;
                    try {
                        executor.execute(() -> runTask(conversationKey, queue, queuedTask));
                        return future;
                    } catch (RejectedExecutionException ex) {
                        queue.outstanding--;
                        queue.running = false;
                        if (queue.outstanding == 0) {
                            queue.closed = true;
                            conversationQueues.remove(conversationKey, queue);
                        }
                        throw ex;
                    }
                }
            }
        } catch (RuntimeException ex) {
            releaseUserPermit(userKey);
            throw ex;
        }
    }

    private void runStandalone(QueuedTask task) {
        try {
            task.future().run();
        } finally {
            releaseUserPermit(task.userKey());
        }
    }

    /**
     * 每次只向全局池投递同一会话的一条任务。若全局队列瞬时已满，最多在当前 worker
     * 内继续处理该会话的少量有界任务，保证任务不丢失且不会形成无限递归。
     */
    private void runTask(String key, SerialQueue queue, QueuedTask first) {
        QueuedTask current = first;
        while (current != null) {
            try {
                current.future().run();
            } finally {
                releaseUserPermit(current.userKey());
            }
            current = scheduleNextOrRunInline(key, queue);
        }
    }

    private QueuedTask scheduleNextOrRunInline(String key, SerialQueue queue) {
        synchronized (queue) {
            queue.outstanding--;

            QueuedTask next = null;
            while (!queue.waiting.isEmpty()) {
                QueuedTask candidate = queue.waiting.removeFirst();
                if (candidate.future().isCancelled()) {
                    queue.outstanding--;
                    releaseUserPermit(candidate.userKey());
                } else {
                    next = candidate;
                    break;
                }
            }

            if (next == null) {
                queue.running = false;
                queue.closed = true;
                conversationQueues.remove(key, queue);
                return null;
            }

            QueuedTask taskToSchedule = next;
            try {
                executor.execute(() -> runTask(key, queue, taskToSchedule));
                return null;
            } catch (RejectedExecutionException ignored) {
                // 当前任务刚释放一个 worker；在此 worker 内继续，避免丢掉已接受的会话任务。
                return taskToSchedule;
            }
        }
    }

    private void acquireUserPermit(String userKey) {
        if (userKey == null || userKey.isBlank()) return;
        int capacity = Math.max(1, perUserCapacity);
        AtomicBoolean accepted = new AtomicBoolean();
        userOutstanding.compute(userKey, (ignored, current) -> {
            int count = current == null ? 0 : current;
            if (count >= capacity) return current;
            accepted.set(true);
            return count + 1;
        });
        if (!accepted.get()) {
            throw new RejectedExecutionException("当前用户已有较多问答正在生成，请等待后重试");
        }
    }

    private void releaseUserPermit(String userKey) {
        if (userKey == null || userKey.isBlank()) return;
        userOutstanding.computeIfPresent(userKey,
                (ignored, current) -> current <= 1 ? null : current - 1);
    }

    private static final class SerialQueue {
        private final ArrayDeque<QueuedTask> waiting = new ArrayDeque<>();
        private int outstanding;
        private boolean running;
        private boolean closed;
    }

    private record QueuedTask(FutureTask<Void> future, String userKey) {}
}
