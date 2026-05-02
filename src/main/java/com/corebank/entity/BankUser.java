package com.corebank.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "User")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankUser {

    @Id
    @Column(name = "user_id", length = 20)
    private String userId;

    @Column(name = "user_name", length = 10, nullable = false, unique = true)
    private String userName;

    @Column(name = "user_password", length = 100, nullable = false)
    private String userPassword;

    @Column(name = "registration_number", length = 20, nullable = false, unique = true)
    private String registrationNumber;

    @Column(name = "address", length = 100)
    private String address;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;
}
