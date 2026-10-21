package io.github.alirezaamini.balance.api;

import io.github.alirezaamini.balance.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through HTTP, JSON and the database: verifies wiring and the status code contract.
 */
@AutoConfigureMockMvc
class BalanceApiTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void openAccounts() throws Exception {
        postJson("/api/v1/accounts", """
                {"accountId": "A", "initialBalance": 1000}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/accounts/A"))
                .andExpect(jsonPath("$.balance").value(1000));
        postJson("/api/v1/accounts", """
                {"accountId": "B", "initialBalance": 500}""")
                .andExpect(status().isCreated());
    }

    @Test
    void creditReturnsTheAppliedTransactionAndReplayReturnsTheSame() throws Exception {
        for (int i = 0; i < 3; i++) {
            postJson("/api/v1/accounts/A/credit", """
                    {"amount": 500, "transactionId": "TX-1"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value("TX-1"))
                    .andExpect(jsonPath("$.type").value("CREDIT"))
                    .andExpect(jsonPath("$.status").value("APPLIED"));
        }

        mvc.perform(get("/api/v1/accounts/A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1500));
    }

    @Test
    void transferMovesMoneyAndShowsUpInTheLedger() throws Exception {
        postJson("/api/v1/transfers", """
                {"sourceAccountId": "A", "destinationAccountId": "B", "amount": 300, "transactionId": "TX-1"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        mvc.perform(get("/api/v1/accounts/B/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].type").value("CREDIT"))
                .andExpect(jsonPath("$[1].amount").value(300))
                .andExpect(jsonPath("$[1].balanceAfter").value(800));
    }

    @Test
    void insufficientFundsIs422AndStaysSoOnReplay() throws Exception {
        for (int i = 0; i < 2; i++) {
            postJson("/api/v1/accounts/A/debit", """
                    {"amount": 1200, "transactionId": "TX-1"}""")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("Insufficient funds"))
                    .andExpect(jsonPath("$.transactionId").value("TX-1"));
        }

        mvc.perform(get("/api/v1/transactions/TX-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void reusingTransactionIdForDifferentOperationIs409() throws Exception {
        postJson("/api/v1/accounts/A/credit", """
                {"amount": 500, "transactionId": "TX-1"}""")
                .andExpect(status().isOk());

        postJson("/api/v1/accounts/A/credit", """
                {"amount": 999, "transactionId": "TX-1"}""")
                .andExpect(status().isConflict());
    }

    @Test
    void unknownAccountIs404() throws Exception {
        postJson("/api/v1/accounts/NOPE/credit", """
                {"amount": 500, "transactionId": "TX-1"}""")
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/transactions/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void invalidInputIs400() throws Exception {
        postJson("/api/v1/accounts/A/credit", """
                {"amount": 0, "transactionId": "TX-1"}""")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/accounts/A/debit", """
                {"amount": 10}""")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/transfers", """
                {"sourceAccountId": "A", "destinationAccountId": "A", "amount": 100, "transactionId": "TX-100"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Source and destination account must differ, both were A"));
        postJson("/api/v1/accounts/A/credit", "{not json")
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateAccountIs409() throws Exception {
        postJson("/api/v1/accounts", """
                {"accountId": "A", "initialBalance": 1}""")
                .andExpect(status().isConflict());
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
