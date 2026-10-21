package io.github.alirezaamini.balance.api;

import io.github.alirezaamini.balance.api.dto.AmountRequest;
import io.github.alirezaamini.balance.api.dto.TransactionResponse;
import io.github.alirezaamini.balance.api.dto.TransferRequest;
import io.github.alirezaamini.balance.service.BalanceService;
import io.github.alirezaamini.balance.service.TransactionQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money movements. All of them are safe to retry with the same transactionId: a replay returns
 * the original outcome (200 with the transaction, or the same 422 rejection).
 */
@RestController
@RequestMapping("/api/v1")
public class BalanceController {

    private final BalanceService balanceService;
    private final TransactionQueryService transactionQueryService;

    public BalanceController(BalanceService balanceService, TransactionQueryService transactionQueryService) {
        this.balanceService = balanceService;
        this.transactionQueryService = transactionQueryService;
    }

    @PostMapping("/accounts/{accountId}/credit")
    public TransactionResponse credit(@PathVariable String accountId, @Valid @RequestBody AmountRequest request) {
        balanceService.credit(accountId, request.amount(), request.transactionId());
        return transaction(request.transactionId());
    }

    @PostMapping("/accounts/{accountId}/debit")
    public TransactionResponse debit(@PathVariable String accountId, @Valid @RequestBody AmountRequest request) {
        balanceService.debit(accountId, request.amount(), request.transactionId());
        return transaction(request.transactionId());
    }

    @PostMapping("/transfers")
    public TransactionResponse transfer(@Valid @RequestBody TransferRequest request) {
        balanceService.transfer(request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), request.transactionId());
        return transaction(request.transactionId());
    }

    @GetMapping("/transactions/{transactionId}")
    public TransactionResponse transaction(@PathVariable String transactionId) {
        return TransactionResponse.from(transactionQueryService.getTransaction(transactionId));
    }
}
