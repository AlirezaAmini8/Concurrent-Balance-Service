package io.github.alirezaamini.balance.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Runs tasks in parallel and releases them all at once through a start gate, so that as many of them
 * as possible hit the same rows at the same moment. That is what gives race conditions a chance to show.
 */
public final class ConcurrentRunner {

    private static final long TIMEOUT_SECONDS = 60;

    private ConcurrentRunner() {
    }

    public static Result run(int threads, List<Runnable> tasks) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (Runnable task : tasks) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    task.run();
                    return null;
                }));
            }
            startGate.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                } catch (TimeoutException e) {
                    throw new AssertionError("Tasks did not finish within " + TIMEOUT_SECONDS
                            + "s - possible deadlock or starvation", e);
                }
            }
            return new Result(tasks.size() - failures.size(), failures);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for tasks", e);
        } finally {
            executor.shutdownNow();
        }
    }

    public record Result(int succeeded, List<Throwable> failures) {

        public long failuresOf(Class<? extends Throwable> type) {
            return failures.stream().filter(type::isInstance).count();
        }

        public Map<String, Long> failureCountsByType() {
            return failures.stream().collect(Collectors.groupingBy(
                    f -> f.getClass().getSimpleName(), Collectors.counting()));
        }
    }
}
