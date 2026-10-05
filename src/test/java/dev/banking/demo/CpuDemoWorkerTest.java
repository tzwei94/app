package dev.banking.demo;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CpuDemoWorkerTest {
    @Test void iterationCeilingStopsWorkEvenWhenTheClockDoesNotAdvance() throws Exception {
        var result = CpuDemoWorker.hash(50, 0, new java.util.concurrent.atomic.AtomicBoolean(), () -> 0);
        assertThat(result.iterations()).isEqualTo(CpuDemoWorker.MAX_ITERATIONS);
        assertThat(result.stopReason()).isEqualTo("iteration_limit");
    }

    @Test void expiredAdmissionDeadlineDoesNotStartHashing() throws Exception {
        var result = CpuDemoWorker.hash(50, 0, new java.util.concurrent.atomic.AtomicBoolean(), () -> 1_000_000_000L);
        assertThat(result.iterations()).isZero();
        assertThat(result.stopReason()).isEqualTo("deadline");
    }
    @Test void concurrentWorkIsRejectedWithoutQueueingAndRecoveryGapIsEnforced() throws Exception {
        var metrics = new SimpleMeterRegistry();
        try (var worker = new CpuDemoWorker(metrics)) {
            var first = worker.submit(500);
            assertThatThrownBy(() -> worker.submit(50)).isInstanceOf(CpuDemoWorker.Unavailable.class);
            var result = first.result().get(3, TimeUnit.SECONDS);
            assertThat(result.iterations()).isPositive().isLessThanOrEqualTo(CpuDemoWorker.MAX_ITERATIONS);
            assertThat(result.checksum()).hasSize(64);
            assertThatThrownBy(() -> worker.submit(50)).isInstanceOf(CpuDemoWorker.Unavailable.class);
            assertThat(metrics.get("banking.demo.cpu.active").gauge().value()).isZero();
            assertThat(metrics.get("banking.demo.cpu.completed").counter().count()).isEqualTo(1);
            Thread.sleep(120);
            assertThat(worker.submit(50).result().get(3, TimeUnit.SECONDS).workMs()).isEqualTo(50);
        }
    }

    @Test void cancellationStopsWorkAndReleasesTheSlot() throws Exception {
        var metrics = new SimpleMeterRegistry();
        try (var worker = new CpuDemoWorker(metrics)) {
            var job = worker.submit(500);
            job.cancel();
            assertThatThrownBy(() -> job.result().get(3, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
            assertThat(metrics.get("banking.demo.cpu.cancelled").counter().count()).isEqualTo(1);
            assertThat(metrics.get("banking.demo.cpu.active").gauge().value()).isZero();
        }
    }

    @Test void deadlineStopsRealHashingAndReportsMeasuredCpuTime() throws Exception {
        try (var worker = new CpuDemoWorker(new SimpleMeterRegistry())) {
            var result = worker.submit(50).result().get(3, TimeUnit.SECONDS);
            assertThat(result.elapsedMs()).isBetween(0.0, 1000.0);
            assertThat(result.cpuMs()).isPositive();
            assertThat(result.stopReason()).isIn("deadline", "iteration_limit");
        }
    }

    @Test void shutdownCancelsWorkAndPreventsNewAdmission() throws Exception {
        var worker = new CpuDemoWorker(new SimpleMeterRegistry());
        var job = worker.submit(500);
        worker.close();
        assertThatThrownBy(() -> job.result().get(3, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> worker.submit(50)).isInstanceOf(CpuDemoWorker.Unavailable.class);
    }

    @Test void workerBoundsAlsoProtectNonHttpCallers() {
        try (var worker = new CpuDemoWorker(new SimpleMeterRegistry())) {
            for (int workMs : new int[]{0, 49, 501, Integer.MAX_VALUE}) {
                assertThatThrownBy(() -> worker.submit(workMs)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
