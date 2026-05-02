package com.corebank.repository;

import com.corebank.entity.AccountHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountHistoryRepository extends JpaRepository<AccountHistory, Integer> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AccountHistory h where h.account.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") String accountId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AccountHistory h where h.account.user.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
