package dev.banking.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** CPU-only demo work: one worker, no backlog, bounded time, memory and iterations. */
@Component
public final class CpuDemoWorker implements AutoCloseable {
    static final long MAX_ITERATIONS = 5_000_000;
    private static final long RECOVERY_NS = TimeUnit.MILLISECONDS.toNanos(100);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> {
            var thread = new Thread(task, "cpu-demo");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final Counter completed;
    private final Counter cancelled;
    private final Counter failed;
    private final Counter rejected;
    private final Timer duration;
    private Job active;
    private long recoveryStarted;
    private boolean recovering;
    private boolean closed;

    public CpuDemoWorker(MeterRegistry metrics) {
        completed = metrics.counter("banking.demo.cpu.completed");
        cancelled = metrics.counter("banking.demo.cpu.cancelled");
        failed = metrics.counter("banking.demo.cpu.failed");
        rejected = metrics.counter("banking.demo.cpu.rejected");
        duration = metrics.timer("banking.demo.cpu.duration");
        Gauge.builder("banking.demo.cpu.active", this, worker -> worker.activeCount()).register(metrics);
    }

    public synchronized Job submit(int workMs) {
        if (workMs < 50 || workMs > 500) throw new IllegalArgumentException("workMs must be between 50 and 500");
        if (closed || active != null || (recovering && System.nanoTime() - recoveryStarted < RECOVERY_NS)) {
            rejected.increment();
            throw new Unavailable(closed);
        }
        var job = new Job(workMs, System.nanoTime());
        active = job;
        try { executor.execute(() -> run(job)); }
        catch (RejectedExecutionException e) {
            active = null;
            rejected.increment();
            throw new Unavailable(closed);
        }
        return job;
    }

    private synchronized int activeCount() { return active == null ? 0 : 1; }

    private void run(Job job) {
        synchronized (job) { job.runner = Thread.currentThread(); }
        Result result = null;
        Exception failure = null;
        try {
            result = burn(job);
            completed.increment();
        } catch (CancellationException e) {
            failure = e;
            cancelled.increment();
        } catch (Exception e) {
            failure = e;
            failed.increment();
        } finally {
            synchronized (job) {
                job.runner = null;
                Thread.interrupted();
            }
            synchronized (this) {
                active = null;
                recoveryStarted = System.nanoTime();
                recovering = true;
            }
            duration.record(System.nanoTime() - job.started, TimeUnit.NANOSECONDS);
        }
        if (failure == null) job.result.complete(result);
        else job.result.completeExceptionally(failure);
    }

    private static Result burn(Job job) throws Exception {
        return hash(job.workMs, job.started, job.cancelled, System::nanoTime);
    }

    static Result hash(int workMs, long started, AtomicBoolean cancelled, LongSupplier nanoTime) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var input = new byte[64];
        var output = new byte[32];
        long iterations = 0;
        long cpuStarted = cpuTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(workMs);
        // Deadline includes scheduling delay. Check every 256 fixed-size hashes.
        while (iterations < MAX_ITERATIONS) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (nanoTime.getAsLong() - started >= budget) break;
            for (int batch = 0; batch < 256 && iterations < MAX_ITERATIONS; batch++, iterations++) {
                digest.update(input);
                digest.digest(output, 0, output.length);
                System.arraycopy(output, 0, input, 0, output.length);
            }
        }
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        return new Result(workMs, (nanoTime.getAsLong() - started) / 1_000_000.0,
            Math.max(0, cpuTime() - cpuStarted) / 1_000_000.0, iterations, HexFormat.of().formatHex(output),
            iterations == MAX_ITERATIONS ? "iteration_limit" : "deadline");
    }

    private static long cpuTime() {
        var threads = ManagementFactory.getThreadMXBean();
        return threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
            ? Math.max(0, threads.getCurrentThreadCpuTime()) : 0;
    }

    @Override @PreDestroy public synchronized void close() {
        closed = true;
        if (active != null) active.cancel();
        executor.shutdown();
    }

    public record Result(int workMs, double elapsedMs, double cpuMs, long iterations, String checksum, String stopReason) {}

    public static final class Job {
        private final int workMs;
        private final long started;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private volatile Thread runner;
        private Job(int workMs, long started) { this.workMs = workMs; this.started = started; }
        public CompletableFuture<Result> result() { return result; }
        public synchronized void cancel() {
            cancelled.set(true);
            var thread = runner;
            if (thread != null) thread.interrupt();
        }
    }

    public static final class Unavailable extends RuntimeException {
        private final boolean closed;
        private Unavailable(boolean closed) { this.closed = closed; }
        public boolean closed() { return closed; }
    }
}
