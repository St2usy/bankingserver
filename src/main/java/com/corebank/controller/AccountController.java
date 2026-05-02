package com.corebank.controller;

import com.corebank.dto.AccountOpenRequest;
import com.corebank.dto.AccountResponse;
import com.corebank.dto.AmountRequest;
import com.corebank.dto.TransferRequest;
import com.corebank.dto.TransferResponse;
import com.corebank.service.AccountService;
import com.corebank.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final TransactionService transactionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@Valid @RequestBody AccountOpenRequest request) {
        return accountService.openAccount(request);
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable String accountId) {
        accountService.closeAccount(accountId);
    }

    @PostMapping("/{accountId}/deposit")
    public AccountResponse deposit(
            @PathVariable String accountId,
            @Valid @RequestBody AmountRequest request) {
        return transactionService.deposit(accountId, request.getAmount());
    }

    @PostMapping("/{accountId}/withdraw")
    public AccountResponse withdraw(
            @PathVariable String accountId,
            @Valid @RequestBody AmountRequest request) {
        return transactionService.withdraw(accountId, request.getAmount());
    }

    @PostMapping("/{fromAccountId}/transfer")
    public TransferResponse transfer(
            @PathVariable String fromAccountId,
            @Valid @RequestBody TransferRequest request) {
        return transactionService.transfer(fromAccountId, request.getToAccountId(), request.getAmount());
    }
}
