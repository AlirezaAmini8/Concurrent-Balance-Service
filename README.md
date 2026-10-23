# Concurrent Balance Service

A Spring Boot service that manages account balances. It supports **credit**, **debit** and **atomic transfer**, and stays correct under heavy concurrency. Every operation is **idempotent** on its `transactionId`.

**Stack:** Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Spring JDBC (`JdbcClient`) · Flyway · JUnit 5 · Testcontainers

---

## TL;DR of the design

| Concern                                   | Mechanism                                                                                                                                                   |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Thread safety, no lost updates            | `SELECT … FOR UPDATE` row lock on each involved account, inside one DB transaction                                                                          |
| Independent accounts don't block          | Locks are per row, so operations on different accounts never wait for each other                                                                            |
| No negative balance                       | Funds are checked under the lock. A `CHECK (balance >= 0)` constraint backs it up                                                                           |
| Transfer atomicity                        | Debit, credit, ledger entries and outcome all commit in **one** DB transaction, or none of them do                                                          |
| No deadlocks                              | Accounts are always locked in the same global (sorted) order                                                                                                |
| Idempotency (incl. concurrent duplicates) | `INSERT … ON CONFLICT DO NOTHING` on the `transactions` primary key. Postgres makes a concurrent duplicate wait for the first one, which then gets replayed |
| Auditability                              | Append-only `ledger_entries`. For each account, balance = Σ ledger amounts                                                                                  |

---

## Running

**Requirements:** JDK 21 and Docker. Docker is used by Testcontainers for the tests and by docker-compose for running the app. Nothing else needs to be installed; the Maven wrapper downloads Maven.

```bash
./mvnw test                     # builds and runs all tests (starts a throw-away PostgreSQL container)

docker compose up --build       # PostgreSQL + the app on http://localhost:8080
# or: run only the DB and start the app from the IDE / CLI
docker compose up -d postgres && ./mvnw spring-boot:run
```

### REST API

| Method & path                       | Body                                                                                     | Success                       |
| ----------------------------------- | ---------------------------------------------------------------------------------------- | ----------------------------- |
| `POST /api/v1/accounts`             | `{"accountId":"A","initialBalance":1000}`                                                | `201` + `Location`            |
| `GET /api/v1/accounts/{id}`         |                                                                                          | `200 {"accountId","balance"}` |
| `GET /api/v1/accounts/{id}/ledger`  |                                                                                          | `200 [ledger entries]`        |
| `POST /api/v1/accounts/{id}/credit` | `{"amount":500,"transactionId":"TX-1"}`                                                  | `200` transaction             |
| `POST /api/v1/accounts/{id}/debit`  | `{"amount":500,"transactionId":"TX-2"}`                                                  | `200` transaction             |
| `POST /api/v1/transfers`            | `{"sourceAccountId":"A","destinationAccountId":"B","amount":300,"transactionId":"TX-3"}` | `200` transaction             |
| `GET /api/v1/transactions/{id}`     |                                                                                          | `200` transaction             |

Errors use RFC 9457 `application/problem+json`:

| Status                | Meaning                                                                         | Recorded?                                  |
| --------------------- | ------------------------------------------------------------------------------- | ------------------------------------------ |
| `400`                 | invalid input (amount ≤ 0, blank ids, same-account transfer, malformed JSON)    | no                                         |
| `404`                 | unknown account / transaction                                                   | no                                         |
| `409`                 | account already exists, or `transactionId` reused for a **different** operation | no                                         |
| `422`                 | insufficient funds                                                              | **yes**: final, replays get the same `422` |
| `503` + `Retry-After` | lock timeout / transient DB error                                               | no, so it's safe to retry with the same id |

```bash
curl -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"accountId":"A","initialBalance":1000}'
curl -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"accountId":"B","initialBalance":500}'
curl -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
     -d '{"sourceAccountId":"A","destinationAccountId":"B","amount":300,"transactionId":"TX-1"}'
```

---

## Architecture

```
api/           REST controllers, request/response DTOs, ApiExceptionHandler (problem details)
service/       BalanceService (the interface from the challenge) + LedgerBalanceService,
               AccountService, TransactionQueryService
domain/        Account, TransactionRequest, Transaction, LedgerEntry, enums, domain exceptions
persistence/   AccountRepository, TransactionRepository, LedgerRepository (JdbcClient + SQL)
resources/db/migration/   Flyway schema
```

The layering is deliberately simple: controllers → services → repositories. The domain objects are immutable records with no framework dependencies. `TransactionRequest` validates itself on construction, so a request that exists is always valid. `Account` holds the balance rules (`canDebit`, `debit`, overflow-safe `credit`).

**Data model** (`V1__create_balance_schema.sql`):

- `accounts(id, balance CHECK >= 0)`: the current balance.
- `transactions(id = transactionId PK, type, source, destination, amount, status, rejection_reason)`: the idempotency registry. It stores each request's _fingerprint_ and its final outcome.
- `ledger_entries(account_id, transaction_id, entry_type, signed amount, balance_after)`: an append-only audit trail. A transfer writes two entries that sum to zero.

The balance is stored as a column **and** recorded in the ledger. The column makes `getBalance` and the funds check O(1). The ledger makes every balance explainable and reconcilable. The tests check that the two always agree.

### Why these technologies

- **PostgreSQL**: balances must survive restarts, and the service should be able to run as several instances behind a load balancer. The database is therefore the single source of truth and the single place where concurrency is coordinated. In-JVM locks (`synchronized`, `ReentrantLock`) only protect one process, so two instances would happily double-spend. _Trade-off:_ every operation pays a DB round trip, and a single very hot account is limited by how fast one row lock can be passed between transactions (see _Performance_).
- **Spring JDBC (`JdbcClient`) instead of JPA**: the correctness of this service depends on exactly which SQL runs and which locks it takes. Plain SQL keeps that explicit and reviewable (`FOR UPDATE`, `ON CONFLICT DO NOTHING`, `RETURNING`). JPA would add a persistence context, dirty checking and flush ordering, none of which help here. _Trade-off:_ hand-written row mappers.
- **Flyway**: versioned, reproducible schema, including the constraints that act as the last line of defence.
- **Testcontainers**: row locks, `ON CONFLICT` waiting behaviour and constraints are exactly what needs testing, and H2 does not reproduce them faithfully. _Trade-off:_ tests need Docker.
- **Not used: Redis or Kafka.** A distributed lock in Redis would be a second source of truth that can disagree with the database (lease expiry, GC pauses). Kafka would make the API asynchronous. Neither is needed to meet the requirements, so neither is used.

---

## Concurrency

Every mutating operation runs in **one database transaction** (`READ COMMITTED`) in `LedgerBalanceService#process`:

```
1. claim transactionId        INSERT INTO transactions ... ON CONFLICT (id) DO NOTHING
   └─ not inserted?           → replay the stored outcome (see Idempotency)
2. lock accounts, sorted      SELECT ... FROM accounts WHERE id = ? FOR UPDATE   (one per account, in sorted order)
   └─ missing?                → AccountNotFoundException → rollback (nothing recorded)
3. check funds                source.balance >= amount ?
   └─ no                      → mark transaction REJECTED, commit, then throw InsufficientFundsException
4. apply                      UPDATE balances, INSERT ledger entries
5. record outcome             UPDATE transactions SET status = 'APPLIED'
COMMIT
```

**What happens with concurrent operations on one account?** The first transaction to reach step 2 takes the row lock. The others block on the same `SELECT … FOR UPDATE` until it commits, and then read the **committed, up-to-date** balance. Operations on one account are therefore serialized, which rules out lost updates and stale funds checks. With 50 concurrent debits of 700 against a balance of 1,000, exactly one succeeds.

**Independent accounts** lock different rows and never wait for each other (`operationsOnIndependentAccountsDoNotBlockEachOther` proves it). Throughput across accounts is limited by the connection pool and the database, not by any global lock.

**Why pessimistic row locks?**

| Alternative                                                                | Why not                                                                                                                                                                                                                                 |
| -------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `synchronized` / `ReentrantLock` per account in the JVM                    | Not safe with more than one instance. Balances in memory are lost on restart                                                                                                                                                            |
| Optimistic locking (`@Version` + retry)                                    | Works, but this workload is contended _by design_ (hot accounts, concurrent retries). Optimistic locking then degrades into retry storms and needs retry or backoff code in every path. Pessimistic locks queue requests fairly instead |
| Single conditional `UPDATE … SET balance = balance - ? WHERE balance >= ?` | Atomic for one account and elegant for debit. For a transfer, though, the two `UPDATE`s lock rows in debit→credit order, which can deadlock (A→B vs B→A). It also loses the explicit read the ledger's `balance_after` needs            |
| `SERIALIZABLE` isolation                                                   | Correct, but it surfaces conflicts as serialization failures that must be retried. Explicit locks make the waiting deterministic                                                                                                        |

`READ COMMITTED` is enough because no decision is ever made from an unlocked read. The funds check reads the balance under `FOR UPDATE`, which in PostgreSQL always returns the latest committed version of the row. `getBalance` is a plain read: a consistent snapshot, possibly outdated a millisecond later, which is the expected meaning of a balance query.

**Lock waits are bounded:** each connection runs `SET lock_timeout = '5s'`. If something holds a lock too long, waiting requests fail fast with `503 + Retry-After` instead of piling up and exhausting the pool. Their transactions roll back, so a retry with the same `transactionId` is safe.

---

## Idempotency

The `transactions` table has the `transactionId` as its primary key. The **first** step of every operation tries to insert it:

- **New id** → the insert succeeds and this request "owns" the id until it commits.
- **Already committed** → the insert does nothing. The stored row is loaded and its fingerprint (`type`, `source`, `destination`, `amount`) is compared with the request:
  - same fingerprint → **replay**: return the original outcome (success, or the same `InsufficientFundsException`) without touching any balance;
  - different fingerprint → `IdempotencyConflictException` (`409`). Silently accepting a reused id for a different operation would hide client bugs.

**What if the same transactionId arrives several times concurrently?** No application lock is needed, because PostgreSQL handles it. When transaction T2 inserts a key that an uncommitted transaction T1 has already inserted, T2 **blocks on the unique index** until T1 finishes:

- if T1 **commits** → T2's insert becomes a no-op and T2 replays T1's committed outcome;
- if T1 **rolls back** (crash, unknown account, …) → T2's insert succeeds and T2 processes the request itself.

Exactly one of the duplicates applies the operation, and all of them return the same answer. `concurrentDuplicatesOf…` tests fire 200 identical requests at once to check this.

Claiming the id **before** locking accounts also makes replays cheap: they never touch account rows. Since a transaction only ever holds one claim and takes it first, claiming cannot take part in a deadlock cycle.

### Which outcomes are remembered

| Outcome                                     | Recorded?            | Retry with same id                                              |
| ------------------------------------------- | -------------------- | --------------------------------------------------------------- |
| Applied                                     | yes (`APPLIED`)      | returns success, no effect                                      |
| Insufficient funds                          | **yes (`REJECTED`)** | returns the **same rejection**, even if money has arrived since |
| Validation error, unknown account, overflow | no (rolled back)     | is processed again                                              |
| Crash / DB error / lock timeout             | no (rolled back)     | is processed again                                              |

**Decision: a rejection is final.** "Same key → same response" is the standard contract for idempotency keys (as used by Stripe and most payment APIs). The alternative was to _not_ record rejections, so that a retry after a top-up could succeed. That would make the result of a `transactionId` depend on _when_ it was retried, which is exactly the ambiguity idempotency is meant to remove. A client that wants another attempt sends a new `transactionId`. Validation errors are _not_ recorded because those requests never became a real financial transaction, so the client may fix the input and reuse the id.

Implementation detail: a rejection has to be **committed** but reported as an **exception**. Throwing inside the transaction would roll back the `REJECTED` record. So `process()` returns the outcome, and the exception is thrown only after `TransactionTemplate` has committed. Programmatic `TransactionTemplate` is used instead of `@Transactional` for this reason; it also avoids proxy and self-invocation pitfalls.

---

## Transfer

**Atomicity:** the debit, the credit, both ledger entries and the outcome are written in a single PostgreSQL transaction. A partial state like `A = 700, B = 500` can never be committed. If anything fails between the debit and the credit (exception, connection loss, process crash), the whole transaction rolls back and both balances stay unchanged. Nothing is recorded under the `transactionId`, so the client can safely retry with the same id. `TransferAtomicityTest` injects a failure exactly between the two steps to check this.

The amount debited and the amount credited are the same `request.amount()`. The invariant test checks that the ledger entries of every applied transfer sum to exactly zero.

**Deadlocks: can they happen? No.** A deadlock needs a cycle: T1 holds A and waits for B, while T2 holds B and waits for A. Every operation locks its accounts **in the same global order** (sorted ids, see `TransactionRequest#accountIdsInLockOrder`), one by one, before writing anything. Both `transfer(A,B)` and `transfer(B,A)` lock A first, then B, so a cycle cannot form. The only other lock, the `transactionId` claim, is always taken first, and only one per transaction. The test `opposingTransfersBetweenTwoAccountsDoNotDeadlock` runs 1,000 opposing transfers concurrently. When the sorting was removed during development, PostgreSQL reported `deadlock detected` and the test failed.

**Transfer to the same account** (`transfer("A","A",100,"TX-100")`) is **rejected as invalid** (`400`, nothing recorded). A self-transfer has no meaningful financial effect. Treating it as a successful no-op would create a record of a transaction that did nothing and could hide a client bug (e.g. a wrongly copied field), so it fails fast before touching the database.

---

## Testing

`./mvnw test` runs everything: 79 tests, about 20 s including the container start.

| Test class                                  | What it checks                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `AccountTest`, `TransactionRequestTest`     | Unit tests for domain rules: validation, lock ordering, fingerprint comparison, overflow                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `BalanceServiceTest`                        | Functional behaviour of credit/debit/transfer, validation, unknown accounts, same-account transfer                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `IdempotencyTest`                           | Sequential replays of credit/debit/transfer (`1,000 + 3×100 with TX-1 = 1,100`), rejection is final, id reused for another operation → conflict, unrecorded failures can be retried                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `ConcurrencyTest`                           | All tasks released at once through a start gate (64 threads) to maximise races. Covers: the spec's exact scenario (2 threads × `debit(A, 700)`, repeated 20× because the race window is narrow); competing debits (exactly 1 of 50 wins); 1,000 mixed credits/debits on one account with an exact expected balance; 1,000 oversubscribed debits (exactly 666 succeed, balance 100, never negative); 200 concurrent duplicates of credit, debit, transfer and a rejected debit; 100 concurrent requests reusing one id with different amounts (exactly 1 applied, 99 conflicts); 1,000 opposing A↔B transfers (no deadlock); 2,000 random transfers across 10 accounts (total money conserved); 2,000 random mixed credits/debits/transfers across 5 accounts (total = initial + successful credits − successful debits); independent accounts don't block each other |
| `TransferAtomicityTest`                     | A failure injected between debit and credit leaves no trace; a retry with the same id then succeeds; when the first of 50 concurrent duplicates fails mid-transfer, a waiting duplicate takes over the id and applies it exactly once                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `BalanceApiTest`, `ApiExceptionHandlerTest` | HTTP contract: status codes, problem details, JSON shape                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |

Integration tests also assert the **system invariants straight from SQL** after each test (`LedgerInvariants`): no negative balance, every balance equals the sum of its ledger, every applied transaction has exactly the ledger effect its type implies, rejected or failed transactions have none, and no `PENDING` row remains.

**Do the tests catch real bugs?** During development, two mutations were applied to check this. Removing `FOR UPDATE` made 5 of the 9 concurrency tests fail (lost updates, wrong balances). Removing the lock ordering made the deadlock and random-transfer tests fail with `deadlock detected`.

---

## Performance characteristics

- **Per operation:** one DB transaction of 4–6 short statements on primary-key lookups, all O(1) / O(log n).
- **Same account:** operations are serialized by the row lock. Throughput on a single hot account is about 1 / (lock hold time). Locally that is roughly a thousand operations per second (1,000 concurrent operations on one account finish in about 0.8 s, including Testcontainers overhead).
- **Different accounts:** fully parallel, limited by the connection pool (`DB_POOL_SIZE`, default 20) and the database.
- **Replays** never lock account rows. Concurrent duplicates wait only on the primary-key index entry of their own id.

## Trade-offs and known limitations

- **Hot accounts** are the main scalability limit, since one row is one serialization point. Typical remedies, not implemented here: split a hot account into N sub-accounts (sharded balances), or batch many small credits into one update.
- **Unbounded growth** of the `transactions` and `ledger_entries` tables. Production would add time-based partitioning and a retention window for idempotency keys (and document that window to clients).
- **Idempotency keys are global**, not scoped per client or tenant. With several API clients, the key would become `(clientId, transactionId)`.
- **Single database.** Horizontal scaling of the app is free, but the DB is the bottleneck and the single point of failure (mitigated by standard HA replication).
- **Money is a `long` in minor units, single currency.** Multi-currency would add a currency column and forbid cross-currency transfers (or add FX).
- **No authentication or authorization.** Out of scope for the challenge.

## What I would do next with more time

1. Load test (Gatling or k6) to measure throughput and latency for a hot account vs. many accounts, and tune pool size and `lock_timeout`.
2. Observability: Micrometer metrics for lock wait time, rejections, replays and conflicts, plus tracing by `transactionId`.
3. A transactional outbox that publishes `TransactionApplied` events (e.g. to Kafka) for downstream consumers without dual-write problems.
4. A periodic reconciliation job comparing `accounts.balance` with the ledger in production (the same query the tests use).
5. Pagination for the ledger endpoint, and an OpenAPI description.
