package com.corebank.service;

import com.corebank.dto.UserRegisterRequest;
import com.corebank.dto.UserResponse;
import com.corebank.entity.BankUser;
import com.corebank.exception.BusinessException;
import com.corebank.repository.AccountHistoryRepository;
import com.corebank.repository.AccountRepository;
import com.corebank.repository.BankUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final BankUserRepository bankUserRepository;
    private final AccountRepository accountRepository;
    private final AccountHistoryRepository accountHistoryRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse register(UserRegisterRequest request) {
        if (bankUserRepository.existsById(request.getUserId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 사용자 ID입니다.");
        }
        if (bankUserRepository.existsByUserName(request.getUserName())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 사용 중인 사용자명입니다.");
        }
        if (bankUserRepository.existsByRegistrationNumber(request.getRegistrationNumber())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 등록된 주민(식별)번호입니다.");
        }

        BankUser user = BankUser.builder()
                .userId(request.getUserId())
                .userName(request.getUserName())
                .userPassword(passwordEncoder.encode(request.getPassword()))
                .registrationNumber(request.getRegistrationNumber())
                .address(request.getAddress())
                .phoneNumber(request.getPhoneNumber())
                .build();
        bankUserRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public void deleteUser(String userId) {
        BankUser user = bankUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        accountHistoryRepository.deleteByUserId(userId);
        accountRepository.deleteByUserId(userId);
        bankUserRepository.delete(user);
    }

    private static UserResponse toResponse(BankUser user) {
        return UserResponse.builder()
                .userId(user.getUserId())
                .userName(user.getUserName())
                .registrationNumber(user.getRegistrationNumber())
                .address(user.getAddress())
                .phoneNumber(user.getPhoneNumber())
                .build();
    }
}
