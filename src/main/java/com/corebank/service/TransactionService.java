package com.corebank.service;

import com.corebank.dto.AccountResponse;
import com.corebank.dto.TransferResponse;
import com.corebank.entity.Account;
import com.corebank.entity.AccountHistory;
import com.corebank.entity.IdempotencyRecord;
import com.corebank.exception.BusinessException;
import com.corebank.model.IdempotencyOperation;
import com.corebank.repository.AccountHistoryRepository;
import com.corebank.repository.AccountRepository;
import com.corebank.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final String TARGET_DEPOSIT = "DEPOSIT";
    private static final String TARGET_WITHDRAW = "WITHDRAW";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final AccountRepository accountRepository;
    private final AccountHistoryRepository accountHistoryRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public AccountResponse deposit(String accountId, int amount) {
        return deposit(accountId, amount, null);
    }

    @Transactional
    public AccountResponse deposit(String accountId, int amount, String idempotencyKey) {
        if (isBlank(idempotencyKey)) {
            return executeDeposit(accountId, amount);
        }
        String key = normalizeIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprintDeposit(accountId, amount);
        return runWithIdempotency(
                IdempotencyOperation.DEPOSIT,
                key,
                fingerprint,
                () -> executeDeposit(accountId, amount),
                AccountResponse.class);
    }

    public AccountResponse withdraw(String accountId, int amount) {
        return withdraw(accountId, amount, null);
    }

    @Transactional
    public AccountResponse withdraw(String accountId, int amount, String idempotencyKey) {
        if (isBlank(idempotencyKey)) {
            return executeWithdraw(accountId, amount);
        }
        String key = normalizeIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprintWithdraw(accountId, amount);
        return runWithIdempotency(
                IdempotencyOperation.WITHDRAW,
                key,
                fingerprint,
                () -> executeWithdraw(accountId, amount),
                AccountResponse.class);
    }

    public TransferResponse transfer(String fromAccountId, String toAccountId, int amount) {
        return transfer(fromAccountId, toAccountId, amount, null);
    }

    @Transactional
    public TransferResponse transfer(String fromAccountId, String toAccountId, int amount, String idempotencyKey) {
        if (isBlank(idempotencyKey)) {
            return executeTransfer(fromAccountId, toAccountId, amount);
        }
        String key = normalizeIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprintTransfer(fromAccountId, toAccountId, amount);
        return runWithIdempotency(
                IdempotencyOperation.TRANSFER,
                key,
                fingerprint,
                () -> executeTransfer(fromAccountId, toAccountId, amount),
                TransferResponse.class);
    }

    /**
     * 동일 계좌에 대한 동시 입금·출금 시 잔액 정합성을 위해 단일 행에 대해 {@code SELECT ... FOR UPDATE}로 잠급니다.
     */
    private AccountResponse executeDeposit(String accountId, int amount) {
        Account account = lockAccount(accountId);

        int current = account.getBalance() == null ? 0 : account.getBalance();
        int next = current + amount;
        account.setBalance(next);
        accountRepository.save(account);

        accountHistoryRepository.save(AccountHistory.builder()
                .account(account)
                .transferDate(Instant.now())
                .transferTarget(TARGET_DEPOSIT)
                .transferAmount(amount)
                .remainingAmount(next)
                .build());

        return AccountService.toResponse(account);
    }

    private AccountResponse executeWithdraw(String accountId, int amount) {
        Account account = lockAccount(accountId);

        int current = account.getBalance() == null ? 0 : account.getBalance();
        if (current < amount) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "잔액이 부족합니다.");
        }
        int next = current - amount;
        account.setBalance(next);
        accountRepository.save(account);

        accountHistoryRepository.save(AccountHistory.builder()
                .account(account)
                .transferDate(Instant.now())
                .transferTarget(TARGET_WITHDRAW)
                .transferAmount(amount)
                .remainingAmount(next)
                .build());

        return AccountService.toResponse(account);
    }

    /**
     * A 계좌 출금과 B 계좌 입금을 하나의 DB 트랜잭션으로 처리합니다.
     * 두 계좌는 계좌 ID 사전순으로 잠가 동시 송금 시 데드락을 방지합니다.
     */
    private TransferResponse executeTransfer(String fromAccountId, String toAccountId, int amount) {
        if (amount <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "송금 금액은 1원 이상이어야 합니다.");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "동일 계좌로는 송금할 수 없습니다.");
        }

        String firstId = fromAccountId.compareTo(toAccountId) < 0 ? fromAccountId : toAccountId;
        String secondId = fromAccountId.compareTo(toAccountId) < 0 ? toAccountId : fromAccountId;

        Account firstLocked = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다."));
        Account secondLocked = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다."));

        Account from = fromAccountId.equals(firstLocked.getAccountId()) ? firstLocked : secondLocked;
        Account to = toAccountId.equals(firstLocked.getAccountId()) ? firstLocked : secondLocked;

        int fromBal = from.getBalance() == null ? 0 : from.getBalance();
        if (fromBal < amount) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "잔액이 부족합니다.");
        }
        int toBal = to.getBalance() == null ? 0 : to.getBalance();

        int fromNext = fromBal - amount;
        int toNext = toBal + amount;
        from.setBalance(fromNext);
        to.setBalance(toNext);
        accountRepository.save(from);
        accountRepository.save(to);

        Instant now = Instant.now();
        accountHistoryRepository.save(AccountHistory.builder()
                .account(from)
                .transferDate(now)
                .transferTarget(to.getAccountId())
                .transferAmount(amount)
                .remainingAmount(fromNext)
                .build());
        accountHistoryRepository.save(AccountHistory.builder()
                .account(to)
                .transferDate(now)
                .transferTarget(from.getAccountId())
                .transferAmount(amount)
                .remainingAmount(toNext)
                .build());

        return TransferResponse.builder()
                .fromAccount(AccountService.toResponse(from))
                .toAccount(AccountService.toResponse(to))
                .build();
    }

    private Account lockAccount(String accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다."));
    }

    private static boolean isBlank(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank();
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        String trimmed = idempotencyKey.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Idempotency-Key가 비어 있습니다.");
        }
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key는 " + MAX_IDEMPOTENCY_KEY_LENGTH + "자 이하여야 합니다.");
        }
        return trimmed;
    }

    private static String fingerprintDeposit(String accountId, int amount) {
        return "DEPOSIT|" + accountId + "|" + amount;
    }

    private static String fingerprintWithdraw(String accountId, int amount) {
        return "WITHDRAW|" + accountId + "|" + amount;
    }

    private static String fingerprintTransfer(String fromAccountId, String toAccountId, int amount) {
        return "TRANSFER|" + fromAccountId + "|" + toAccountId + "|" + amount;
    }

    private <T> T runWithIdempotency(
            IdempotencyOperation operation,
            String idempotencyKey,
            String fingerprint,
            java.util.function.Supplier<T> action,
            Class<T> responseType) {
        IdempotencyRecord record = idempotencyRecordRepository.findByIdForUpdate(idempotencyKey).orElse(null);
        if (record == null) {
            record = IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .requestFingerprint(fingerprint)
                    .operation(operation)
                    .build();
            idempotencyRecordRepository.save(record);
            idempotencyRecordRepository.flush();
        } else {
            if (!record.getRequestFingerprint().equals(fingerprint)) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "동일한 멱등성 키로 서로 다른 요청을 보낼 수 없습니다.");
            }
            if (record.getOperation() != operation) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "동일한 멱등성 키로 서로 다른 종류의 거래를 보낼 수 없습니다.");
            }
            if (record.getResponseJson() != null) {
                return readCachedResponse(record.getResponseJson(), responseType);
            }
        }

        T result = action.get();
        try {
            record.setResponseJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등성 응답 직렬화에 실패했습니다.", e);
        }
        idempotencyRecordRepository.save(record);
        return result;
    }

    private <T> T readCachedResponse(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 멱등성 응답을 읽는 데 실패했습니다.", e);
        }
    }
}
