package com.corebank.repository;

import com.corebank.entity.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findByUser_UserId(String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountId = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") String accountId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Account a where a.user.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
