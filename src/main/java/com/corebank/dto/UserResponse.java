package com.corebank.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserResponse {
    String userId;
    String userName;
    String registrationNumber;
    String address;
    String phoneNumber;
}
