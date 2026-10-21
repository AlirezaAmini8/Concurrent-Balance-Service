package io.github.alirezaamini.balance.api;

import io.github.alirezaamini.balance.api.dto.AccountResponse;
import io.github.alirezaamini.balance.api.dto.CreateAccountRequest;
import io.github.alirezaamini.balance.api.dto.LedgerEntryResponse;
import io.github.alirezaamini.balance.domain.Account;
import io.github.alirezaamini.balance.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(request.accountId(), request.initialBalance());
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}").buildAndExpand(account.id()).toUri())
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable String accountId) {
        return AccountResponse.from(accountService.getAccount(accountId));
    }

    @GetMapping("/{accountId}/ledger")
    public List<LedgerEntryResponse> ledger(@PathVariable String accountId) {
        return accountService.getLedger(accountId).stream().map(LedgerEntryResponse::from).toList();
    }
}
