package com.corebank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AccountOpenRequest {

    @NotBlank
    @Size(max = 20)
    private String userId;
}
