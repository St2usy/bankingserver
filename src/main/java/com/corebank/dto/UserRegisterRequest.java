package com.corebank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserRegisterRequest {

    @NotBlank
    @Size(max = 20)
    private String userId;

    @NotBlank
    @Size(max = 10)
    private String userName;

    @NotBlank
    @Size(min = 4, max = 72)
    private String password;

    @NotBlank
    @Size(max = 20)
    private String registrationNumber;

    @Size(max = 100)
    private String address;

    @Size(max = 20)
    private String phoneNumber;
}
