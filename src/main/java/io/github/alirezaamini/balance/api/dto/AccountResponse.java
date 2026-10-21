package io.github.alirezaamini.balance.api.dto;

import io.github.alirezaamini.balance.domain.Account;

public record AccountResponse(String accountId, long balance) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.balance());
    }
}
