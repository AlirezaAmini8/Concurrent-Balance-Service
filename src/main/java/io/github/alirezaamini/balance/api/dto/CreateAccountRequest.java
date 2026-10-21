package io.github.alirezaamini.balance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 64) String accountId,
        @NotNull @PositiveOrZero Long initialBalance
) {
}
