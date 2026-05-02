package com.corebank.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TransferResponse {
    AccountResponse fromAccount;
    AccountResponse toAccount;
}
