package com.corebank.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.dto.AccountOpenRequest;
import com.corebank.dto.AccountResponse;
import com.corebank.dto.TransferResponse;
import com.corebank.entity.Account;
import com.corebank.entity.BankUser;
import com.corebank.exception.BusinessException;
import com.corebank.repository.AccountRepository;
import com.corebank.repository.BankUserRepository;
import com.corebank.service.AccountService;
import com.corebank.service.TransactionService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class BankingConcurrentAndTransferIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private BankUserRepository bankUserRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void manyThreadsDepositToSameAccount_balanceMatchesTotal() throws Exception {
        BankUser user = saveUser("dep-target");
        AccountResponse shared = accountService.openAccount(openRequest(user.getUserId()));
        String accountId = shared.getAccountId();

        int threads = 30;
        int each = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                transactionService.deposit(accountId, each);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(2, TimeUnit.MINUTES);
        }
        pool.shutdown();

        Account loaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(loaded.getBalance()).isEqualTo(threads * each);
    }

    @Test
    void manySendersTransferConcurrentlyToSameReceiver_receiverBalanceIsSum() throws Exception {
        BankUser receiverUser = saveUser("recv-one");
        AccountResponse receiverAcc = accountService.openAccount(openRequest(receiverUser.getUserId()));
        String receiverId = receiverAcc.getAccountId();

        int senders = 20;
        int eachTransfer = 400;
        List<String> senderAccountIds = new ArrayList<>();
        for (int i = 0; i < senders; i++) {
            BankUser s = saveUser("snd-" + i);
            AccountResponse a = accountService.openAccount(openRequest(s.getUserId()));
            transactionService.deposit(a.getAccountId(), 10_000);
            senderAccountIds.add(a.getAccountId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(senders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (String fromId : senderAccountIds) {
            futures.add(pool.submit(() -> {
                start.await();
                transactionService.transfer(fromId, receiverId, eachTransfer);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(2, TimeUnit.MINUTES);
        }
        pool.shutdown();

        Account receiver = accountRepository.findById(receiverId).orElseThrow();
        assertThat(receiver.getBalance()).isEqualTo(senders * eachTransfer);

        for (String fromId : senderAccountIds) {
            Account sender = accountRepository.findById(fromId).orElseThrow();
            assertThat(sender.getBalance()).isEqualTo(10_000 - eachTransfer);
        }
    }

    @Test
    void transferInsufficientFunds_rollsBackBothSides() {
        BankUser ua = saveUser("atom-a");
        BankUser ub = saveUser("atom-b");
        AccountResponse accA = accountService.openAccount(openRequest(ua.getUserId()));
        AccountResponse accB = accountService.openAccount(openRequest(ub.getUserId()));
        String idA = accA.getAccountId();
        String idB = accB.getAccountId();
        transactionService.deposit(idA, 500);

        assertThatThrownBy(() -> transactionService.transfer(idA, idB, 501))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("부족");

        Account a = accountRepository.findById(idA).orElseThrow();
        Account b = accountRepository.findById(idB).orElseThrow();
        assertThat(a.getBalance()).isEqualTo(500);
        assertThat(b.getBalance()).isEqualTo(0);
    }

    @Test
    void transferHappyPath_singleTransactionSemantics() {
        BankUser ua = saveUser("ok-a");
        BankUser ub = saveUser("ok-b");
        AccountResponse accA = accountService.openAccount(openRequest(ua.getUserId()));
        AccountResponse accB = accountService.openAccount(openRequest(ub.getUserId()));
        String idA = accA.getAccountId();
        String idB = accB.getAccountId();
        transactionService.deposit(idA, 2_000);

        TransferResponse res = transactionService.transfer(idA, idB, 750);
        assertThat(res.getFromAccount().getBalance()).isEqualTo(1250);
        assertThat(res.getToAccount().getBalance()).isEqualTo(750);
    }

    /**
     * A→B 송금과 B→A 송금이 동시에 실행될 때, 계좌 ID 사전순 잠금으로 데드락 없이 끝나고
     * 순환 송금이므로 양쪽 잔액은 초기와 같아야 한다.
     */
    @Test
    void simultaneousMutualTransfers_eachSends1000ToTheOther_netBalancesUnchanged() throws Exception {
        BankUser ua = saveUser("mut-a");
        BankUser ub = saveUser("mut-b");
        AccountResponse accA = accountService.openAccount(openRequest(ua.getUserId()));
        AccountResponse accB = accountService.openAccount(openRequest(ub.getUserId()));
        String idA = accA.getAccountId();
        String idB = accB.getAccountId();

        int initial = 50_000;
        transactionService.deposit(idA, initial);
        transactionService.deposit(idB, initial);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        int amount = 1_000;

        Future<TransferResponse> aToB = pool.submit(() -> {
            start.await();
            return transactionService.transfer(idA, idB, amount);
        });
        Future<TransferResponse> bToA = pool.submit(() -> {
            start.await();
            return transactionService.transfer(idB, idA, amount);
        });
        start.countDown();

        TransferResponse resAtoB = aToB.get(2, TimeUnit.MINUTES);
        TransferResponse resBtoA = bToA.get(2, TimeUnit.MINUTES);
        pool.shutdown();

        assertThat(resAtoB.getFromAccount().getAccountId()).isEqualTo(idA);
        assertThat(resAtoB.getToAccount().getAccountId()).isEqualTo(idB);
        assertThat(resBtoA.getFromAccount().getAccountId()).isEqualTo(idB);
        assertThat(resBtoA.getToAccount().getAccountId()).isEqualTo(idA);

        Account a = accountRepository.findById(idA).orElseThrow();
        Account b = accountRepository.findById(idB).orElseThrow();
        assertThat(a.getBalance()).isEqualTo(initial);
        assertThat(b.getBalance()).isEqualTo(initial);
    }

    private static AccountOpenRequest openRequest(String userId) {
        AccountOpenRequest r = new AccountOpenRequest();
        r.setUserId(userId);
        return r;
    }

    private BankUser saveUser(String suffix) {
        String uid = ("u-" + suffix).substring(0, Math.min(20, ("u-" + suffix).length()));
        String reg = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        BankUser u = BankUser.builder()
                .userId(uid)
                .userName(("n" + suffix).substring(0, Math.min(10, ("n" + suffix).length())))
                .userPassword(passwordEncoder.encode("pw"))
                .registrationNumber(reg)
                .build();
        return bankUserRepository.save(u);
    }
}
