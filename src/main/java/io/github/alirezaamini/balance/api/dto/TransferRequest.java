package io.github.alirezaamini.balance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotBlank @Size(max = 64) String sourceAccountId,
        @NotBlank @Size(max = 64) String destinationAccountId,
        @NotNull @Positive Long amount,
        @NotBlank @Size(max = 128) String transactionId
) {
}
