package com.corebank.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TransferRequest {

    @NotBlank
    @Size(max = 25)
    private String toAccountId;

    @NotNull
    @Min(1)
    private Integer amount;
}
