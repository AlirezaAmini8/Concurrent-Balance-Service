package io.github.alirezaamini.balance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of a credit or debit. The account comes from the path.
 */
public record AmountRequest(
        @NotNull @Positive Long amount,
        @NotBlank @Size(max = 128) String transactionId
) {
}
