package io.github.alirezaamini.balance.service;

import io.github.alirezaamini.balance.domain.Transaction;
import io.github.alirezaamini.balance.domain.TransactionStatus;
import io.github.alirezaamini.balance.domain.exception.IdempotencyConflictException;
import io.github.alirezaamini.balance.domain.exception.InsufficientFundsException;
import io.github.alirezaamini.balance.support.ConcurrentRunner;
import io.github.alirezaamini.balance.support.DatabaseInvariants;
import io.github.alirezaamini.balance.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest extends PostgresIntegrationTest {

    private static final int THREADS = 64;

    @Autowired
    BalanceService balanceService;

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionQueryService transactionQueryService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @AfterEach
    void invariantsHold() {
        DatabaseInvariants.assertHold(jdbc);
    }

    // ---------------------------------------------------------------- single account

    @Test
    void onlyOneOfTwoCompetingDebitsSucceeds() {
        accountService.createAccount("A", 1_000);

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, IntStream.range(0, 50)
                .<Runnable>mapToObj(i -> () -> balanceService.debit("A", 700, "TX-" + i))
                .toList());

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failuresOf(InsufficientFundsException.class)).isEqualTo(49);
        assertThat(balanceService.getBalance("A")).isEqualTo(300);
    }

    // The exact scenario from the challenge. Only two threads, so the race window is narrow: repeat it.
    @RepeatedTest(20)
    void specScenarioTwoConcurrentDebitsOf700OnlyOneSucceeds() {
        accountService.createAccount("A", 1_000);

        ConcurrentRunner.Result result = ConcurrentRunner.run(2, List.of(
                () -> balanceService.debit("A", 700, "TX-1"),
                () -> balanceService.debit("A", 700, "TX-2")));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failuresOf(InsufficientFundsException.class)).isEqualTo(1);
        assertThat(balanceService.getBalance("A")).isEqualTo(300);
    }

    @Test
    void thousandConcurrentCreditsAndDebitsProduceTheExactBalance() {
        accountService.createAccount("A", 100_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String id = String.valueOf(i);
            tasks.add(() -> balanceService.credit("A", 30, "CREDIT-" + id));
            tasks.add(() -> balanceService.debit("A", 50, "DEBIT-" + id));
        }

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, tasks);

        assertThat(result.failureCountsByType()).isEmpty();
        // 100,000 + 500 * 30 - 500 * 50; any lost update would show up as a different number.
        assertThat(balanceService.getBalance("A")).isEqualTo(90_000);
    }

    @Test
    void oversubscribedConcurrentDebitsDrainTheAccountWithoutGoingNegative() {
        accountService.createAccount("A", 100_000);

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, IntStream.range(0, 1_000)
                .<Runnable>mapToObj(i -> () -> balanceService.debit("A", 150, "TX-" + i))
                .toList());

        // 100,000 / 150 = 666 debits fit, whatever order they arrive in.
        assertThat(result.succeeded()).isEqualTo(666);
        assertThat(result.failuresOf(InsufficientFundsException.class)).isEqualTo(334);
        assertThat(balanceService.getBalance("A")).isEqualTo(100);
    }

    // ---------------------------------------------------------------- concurrent duplicates

    @Test
    void concurrentDuplicatesOfACreditAreAppliedExactlyOnce() {
        accountService.createAccount("A", 1_000);

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, IntStream.range(0, 200)
                .<Runnable>mapToObj(i -> () -> balanceService.credit("A", 500, "TX-100"))
                .toList());

        assertThat(result.failureCountsByType()).isEmpty();
        assertThat(balanceService.getBalance("A")).isEqualTo(1_500);
    }

    @Test
    void concurrentDuplicatesOfADebitAreAppliedExactlyOnce() {
        accountService.createAccount("A", 1_000);

        // 700 fits only once: a duplicate that slipped through would surface as InsufficientFundsException.
        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, IntStream.range(0, 200)
                .<Runnable>mapToObj(i -> () -> balanceService.debit("A", 700, "TX-1"))
                .toList());

        assertThat(result.failureCountsByType()).isEmpty();
        assertThat(balanceService.getBalance("A")).isEqualTo(300);
    }

    @Test
    void concurrentReuseOfATransactionIdForDifferentOperationsAppliesExactlyOne() {
        accountService.createAccount("A", 1_000);

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, IntStream.rangeClosed(1, 100)
                .<Runnable>mapToObj(amount -> () -> balanceService.credit("A", amount, "TX-1"))
                .toList());

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failuresOf(IdempotencyConflictException.class)).isEqualTo(99);
        Transaction winner = transactionQueryService.getTransaction("TX-1");
        assertThat(balanceService.getBalance("A")).isEqualTo(1_000 + winner.amount());
    }

    @Test
    void concurrentDuplicatesOfATransferAreAppliedExactlyOnce() {
        accountService.createAccount("A", 1_000);
        accountService.createAccount("B", 500);

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, IntStream.range(0, 200)
                .<Runnable>mapToObj(i -> () -> balanceService.transfer("A", "B", 300, "TX-1"))
                .toList());

        assertThat(result.failureCountsByType()).isEmpty();
        assertThat(balanceService.getBalance("A")).isEqualTo(700);
        assertThat(balanceService.getBalance("B")).isEqualTo(800);
    }

    @Test
    void concurrentDuplicatesOfARejectedDebitAllSeeTheSameRejection() {
        accountService.createAccount("A", 1_000);

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, IntStream.range(0, 200)
                .<Runnable>mapToObj(i -> () -> balanceService.debit("A", 1_200, "TX-1"))
                .toList());

        assertThat(result.failuresOf(InsufficientFundsException.class)).isEqualTo(200);
        assertThat(transactionQueryService.getTransaction("TX-1").status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
    }

    // ---------------------------------------------------------------- multiple accounts

    @Test
    void opposingTransfersBetweenTwoAccountsDoNotDeadlock() {
        accountService.createAccount("A", 10_000);
        accountService.createAccount("B", 10_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String id = String.valueOf(i);
            tasks.add(() -> balanceService.transfer("A", "B", 7, "AB-" + id));
            tasks.add(() -> balanceService.transfer("B", "A", 7, "BA-" + id));
        }

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, tasks);

        // Without ordered locking PostgreSQL would detect deadlocks and abort some of these.
        assertThat(result.failureCountsByType()).isEmpty();
        assertThat(balanceService.getBalance("A")).isEqualTo(10_000);
        assertThat(balanceService.getBalance("B")).isEqualTo(10_000);
    }

    @Test
    void randomConcurrentTransfersAcrossManyAccountsConserveTotalMoney() {
        int accountCount = 10;
        long initialBalance = 10_000;
        for (int i = 0; i < accountCount; i++) {
            accountService.createAccount("ACC-" + i, initialBalance);
        }
        Random random = new Random(42);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            int source = random.nextInt(accountCount);
            int destination = (source + 1 + random.nextInt(accountCount - 1)) % accountCount;
            long amount = 1 + random.nextInt(2_000);
            String transactionId = "TX-" + i;
            tasks.add(() -> balanceService.transfer("ACC-" + source, "ACC-" + destination, amount, transactionId));
        }

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, tasks);

        // Insufficient funds is a legitimate outcome here; anything else (deadlock, lock timeout) is not.
        assertThat(result.failures()).allMatch(InsufficientFundsException.class::isInstance);
        long total = IntStream.range(0, accountCount).mapToLong(i -> balanceService.getBalance("ACC-" + i)).sum();
        assertThat(total).isEqualTo(accountCount * initialBalance);
    }

    @Test
    void mixedConcurrentOperationsAcrossManyAccountsAccountForEveryUnitOfMoney() {
        int accountCount = 5;
        long initialBalance = 1_000;
        for (int i = 0; i < accountCount; i++) {
            accountService.createAccount("ACC-" + i, initialBalance);
        }
        // Money entering minus money leaving the system, counted only for operations that succeeded.
        AtomicLong netInflow = new AtomicLong();
        Random random = new Random(7);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            String account = "ACC-" + random.nextInt(accountCount);
            String other = "ACC-" + random.nextInt(accountCount);
            long amount = 1 + random.nextInt(500);
            String transactionId = "TX-" + i;
            switch (random.nextInt(3)) {
                case 0 -> tasks.add(() -> {
                    balanceService.credit(account, amount, transactionId);
                    netInflow.addAndGet(amount);
                });
                case 1 -> tasks.add(() -> {
                    balanceService.debit(account, amount, transactionId);
                    netInflow.addAndGet(-amount);
                });
                default -> {
                    if (!account.equals(other)) {
                        tasks.add(() -> balanceService.transfer(account, other, amount, transactionId));
                    }
                }
            }
        }

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREADS, tasks);

        assertThat(result.failures()).allMatch(InsufficientFundsException.class::isInstance);
        long total = IntStream.range(0, accountCount).mapToLong(i -> balanceService.getBalance("ACC-" + i)).sum();
        assertThat(total).isEqualTo(accountCount * initialBalance + netInflow.get());
    }

    @Test
    void operationsOnIndependentAccountsDoNotBlockEachOther() throws Exception {
        accountService.createAccount("A", 1_000);
        accountService.createAccount("B", 1_000);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // Simulates a slow operation that holds the row lock on A.
        CompletableFuture<Void> lockHolder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(s -> {
            jdbc.sql("SELECT id FROM accounts WHERE id = 'A' FOR UPDATE").query(String.class).single();
            lockHeld.countDown();
            await(release);
        }));
        assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> onA = CompletableFuture.runAsync(() -> balanceService.credit("A", 100, "TX-A"));
        CompletableFuture<Void> onB = CompletableFuture.runAsync(() -> balanceService.credit("B", 100, "TX-B"));

        assertThat(onB).succeedsWithin(Duration.ofSeconds(2));
        assertThat(onA).as("operation on the locked account waits").isNotDone();

        release.countDown();
        assertThat(lockHolder).succeedsWithin(Duration.ofSeconds(5));
        assertThat(onA).succeedsWithin(Duration.ofSeconds(5));
        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
        assertThat(balanceService.getBalance("B")).isEqualTo(1_100);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
