package com.herabarman.meshnet.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Real-time {@link Clock} that also serialises everything onto one thread. The engine logic is
 * single-threaded by design: all calls into it (timers, radio callbacks, user calls) run here.
 */
public final class SystemClock implements Clock {
    private final ScheduledThreadPoolExecutor executor;
    private volatile Thread thread;

    public SystemClock() {
        executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mesh-engine");
                t.setDaemon(true);
                thread = t;
                return t;
            }
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public long nowMs() {
        return System.currentTimeMillis();
    }

    @Override
    public void schedule(long delayMs, Runnable task) {
        executor.schedule(guard(task), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    /** Run on the engine thread as soon as possible (immediately if already on it). */
    public void post(Runnable task) {
        if (onEngineThread()) guard(task).run();
        else executor.execute(guard(task));
    }

    /** Run on the engine thread and wait for the result. Safe to call from the engine thread itself. */
    public <T> T call(Callable<T> task) {
        try {
            if (onEngineThread()) return task.call();
            Future<T> f = executor.submit(task);
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean onEngineThread() {
        return Thread.currentThread() == thread;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static Runnable guard(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    e.printStackTrace(); // one bad callback must not kill the engine thread
                }
            }
        };
    }
}
