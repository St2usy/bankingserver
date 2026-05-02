package com.corebank.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AccountResponse {
    String accountId;
    String userId;
    int balance;
    Instant createdAt;
}
