package com.corebank.repository;

import com.corebank.entity.BankUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankUserRepository extends JpaRepository<BankUser, String> {

    boolean existsByUserName(String userName);

    boolean existsByRegistrationNumber(String registrationNumber);
}
