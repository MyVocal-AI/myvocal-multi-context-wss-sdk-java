package ai.myvocal.multiwss;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class CallbackDispatcher {
    private final ThreadPoolExecutor executor;
    private final AtomicInteger pending = new AtomicInteger();
    private final Consumer<Throwable> failureHandler;

    CallbackDispatcher(String threadName, int capacity, Consumer<Throwable> failureHandler) {
        this.failureHandler = failureHandler;
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(Math.max(1, capacity - 1)),
                new NamedDaemonThreadFactory(threadName),
                new ThreadPoolExecutor.AbortPolicy());
    }

    boolean dispatch(final Runnable callback) {
        if (callback == null || executor.isShutdown()) {
            return false;
        }
        try {
            pending.incrementAndGet();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        callback.run();
                    } catch (Throwable failure) {
                        if (failureHandler != null) {
                            failureHandler.accept(failure);
                        }
                    } finally {
                        pending.decrementAndGet();
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            pending.decrementAndGet();
            return false;
        }
    }

    int pendingCount() {
        return pending.get();
    }

    void shutdown() {
        executor.shutdown();
    }

    void shutdownNow() {
        executor.shutdownNow();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String name;

        private NamedDaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
