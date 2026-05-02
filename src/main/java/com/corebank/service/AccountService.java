package com.corebank.service;

import com.corebank.dto.AccountOpenRequest;
import com.corebank.dto.AccountResponse;
import com.corebank.entity.Account;
import com.corebank.entity.BankUser;
import com.corebank.exception.BusinessException;
import com.corebank.repository.AccountHistoryRepository;
import com.corebank.repository.AccountRepository;
import com.corebank.repository.BankUserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final BankUserRepository bankUserRepository;
    private final AccountHistoryRepository accountHistoryRepository;

    @Transactional
    public AccountResponse openAccount(AccountOpenRequest request) {
        BankUser user = bankUserRepository.findById(request.getUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        String accountId = generateAccountId();
        while (accountRepository.existsById(accountId)) {
            accountId = generateAccountId();
        }

        Account account = Account.builder()
                .accountId(accountId)
                .user(user)
                .balance(0)
                .createdAt(Instant.now())
                .build();
        accountRepository.save(account);
        return toResponse(account);
    }

    @Transactional
    public void closeAccount(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다."));

        int balance = account.getBalance() == null ? 0 : account.getBalance();
        if (balance != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "잔액이 0원일 때만 계좌를 해지할 수 있습니다.");
        }

        accountHistoryRepository.deleteByAccountId(accountId);
        accountRepository.delete(account);
    }

    private static String generateAccountId() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return ("A" + hex).substring(0, 25);
    }

    static AccountResponse toResponse(Account account) {
        return AccountResponse.builder()
                .accountId(account.getAccountId())
                .userId(account.getUser().getUserId())
                .balance(account.getBalance() == null ? 0 : account.getBalance())
                .createdAt(account.getCreatedAt())
                .build();
    }
}
