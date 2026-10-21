package io.github.alirezaamini.balance.api;

import io.github.alirezaamini.balance.service.BalanceService;
import io.github.alirezaamini.balance.service.TransactionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Failure modes that are impractical to provoke against a real database.
 */
@WebMvcTest(BalanceController.class)
class ApiExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BalanceService balanceService;

    @MockitoBean
    TransactionQueryService transactionQueryService;

    @Test
    void lockTimeoutIsReportedAsRetryable503() throws Exception {
        doThrow(new CannotAcquireLockException("lock timeout"))
                .when(balanceService).credit(anyString(), anyLong(), anyString());

        mvc.perform(post("/api/v1/accounts/A/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10, "transactionId": "TX-1"}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }
}
