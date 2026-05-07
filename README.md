# BankingService
직접 BankingService의 API 서버와 데이터베이스를 구축해보며 금융 IT의 비즈니스 로직을 연구해보고자 함

참여자 : St2ussy (신철언)

최종 수정일 : 2026/05/06

최종 수정 내용 : Readme 수정

## 개요
- **Spring Boot 3.2.5 / Java 17**
- **JPA(MySQL)** 기반 Core Banking API
- 기본 포트: **8080** (`application.yml`)

<details>
<summary><strong>1) API 서버 기능 소개 (디렉토리 구조, 핵심 비즈니스 로직)</strong></summary>

### 디렉토리 구조

```text
src/main/java/com/corebank
├── CorebankApplication.java
├── config
│   └── AppConfig.java
├── controller
│   ├── AccountController.java
│   └── UserController.java
├── dto
│   ├── AccountOpenRequest.java
│   ├── AccountResponse.java
│   ├── AmountRequest.java
│   ├── TransferRequest.java
│   ├── TransferResponse.java
│   ├── UserRegisterRequest.java
│   └── UserResponse.java
├── entity
│   ├── Account.java
│   ├── AccountHistory.java
│   └── BankUser.java
├── exception
│   ├── ApiError.java
│   ├── BusinessException.java
│   └── GlobalExceptionHandler.java
├── repository
│   ├── AccountHistoryRepository.java
│   ├── AccountRepository.java
│   └── BankUserRepository.java
└── service
    ├── AccountService.java
    ├── TransactionService.java
    └── UserService.java
```

### 핵심 비즈니스 로직
- **사용자 등록/삭제 (`UserService`)**
  - 사용자 ID 중복, 사용자명 중복, 주민(식별)번호 중복을 사전에 검증
  - 비밀번호는 `BCryptPasswordEncoder`로 해시 저장 (`AppConfig`)
  - 사용자 삭제 시 사용자 소유 **계좌/거래내역을 먼저 정리** 후 사용자 삭제
- **계좌 개설/해지 (`AccountService`)**
  - 계좌 개설 시 `A` + UUID 기반으로 **25자리 `accountId` 생성**, 중복 시 재시도
  - 계좌 해지는 **잔액이 0원일 때만 가능**
  - 해지 시 계좌의 거래내역 삭제 후 계좌 삭제
- **입금/출금/송금 (`TransactionService`)**
  - **동시성 제어**: 동일 계좌 입출금 시 잔액 정합성을 위해 `PESSIMISTIC_WRITE`(사실상 `SELECT ... FOR UPDATE`)로 단일 행 잠금
  - **송금(계좌↔계좌)**: 출금+입금을 **단일 트랜잭션**으로 처리하고, 두 계좌를 **계좌 ID 사전순으로 잠금**하여 데드락을 예방
  - 모든 입금/출금/송금은 `AccountHistory`로 거래 이력 저장

### API 엔드포인트(컨트롤러 기준)
- **사용자**
  - `POST /api/users` 사용자 등록 (`UserRegisterRequest` → `UserResponse`)
  - `DELETE /api/users/{userId}` 사용자 삭제
- **계좌**
  - `POST /api/accounts` 계좌 개설 (`AccountOpenRequest` → `AccountResponse`)
  - `DELETE /api/accounts/{accountId}` 계좌 해지
  - `POST /api/accounts/{accountId}/deposit` 입금 (`AmountRequest` → `AccountResponse`)
  - `POST /api/accounts/{accountId}/withdraw` 출금 (`AmountRequest` → `AccountResponse`)
  - `POST /api/accounts/{fromAccountId}/transfer` 송금 (`TransferRequest` → `TransferResponse`)

### 입력값 검증/에러 응답
- DTO에 Bean Validation 적용 (`@NotBlank`, `@Size`, `@Min`, `@NotNull`)
- `BusinessException(HttpStatus, message)`를 `GlobalExceptionHandler`가 받아 아래 형태로 응답

```json
{
  "timestamp": "2026-05-06T00:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "에러 메시지"
}
```

</details>

<details>
<summary><strong>2) 데이터베이스 엔티티 구조 소개</strong></summary>

### 엔티티 목록
- **`BankUser`** (`@Table(name="User")`)
  - PK: `user_id`(String, 20)
  - 주요 컬럼: `user_name`(unique), `user_password`, `registration_number`(unique), `address`, `phone_number`
- **`Account`** (`@Table(name="Account")`)
  - PK: `account_id`(String, 25)
  - FK: `user_id` → `User.user_id` (N:1, `ManyToOne`)
  - 주요 컬럼: `balance`(Integer), `created_at`(Instant)
- **`AccountHistory`** (`@Table(name="AccountHistory")`)
  - PK: `account_history_id`(Integer, auto-increment)
  - FK: `account_id` → `Account.account_id` (N:1, `ManyToOne`)
  - 주요 컬럼: `transfer_date`, `transfer_target`, `transfer_amount`, `remaining_amount`

### 관계(요약)
- **User(1) : Account(N)**  
  한 사용자는 여러 계좌를 가질 수 있고, 계좌는 한 사용자에 속합니다.
- **Account(1) : AccountHistory(N)**  
  한 계좌는 여러 거래 이력을 가지며, 거래 이력은 반드시 특정 계좌에 귀속됩니다.

### 거래 이력(`AccountHistory.transfer_target`) 의미
- **입금**: `"DEPOSIT"`
- **출금**: `"WITHDRAW"`
- **송금**: 상대 계좌의 `accountId` (A→B 송금이면 A 이력의 target은 B의 계좌ID, B 이력의 target은 A의 계좌ID)

### 동시성(락) 관련 리포지토리 메서드
- `AccountRepository.findByIdForUpdate(accountId)`에 `PESSIMISTIC_WRITE` 적용  
  입출금/송금 시 잔액 변경 전 계좌 행을 잠가 정합성을 보장합니다.

</details>

## 트랜잭션과 격리성 수준
트랜잭션이란, 데이터베이스에서 작업단위로, 트랜잭션의 작업은 ACID 특성을 가진다.
- Atomicity
- Consistency
- Isolation
- Durability
금융권의 데이터는 절대 오류가 나선 안되므로, 트랜잭션의 충돌을 가정하는 'Pessimistic LOCK'을 사용한다.

격리성 수준은 아래의 4가지와 같다
- Read uncommited : 트랜잭션에서 처리중인, 아직 커밋되지 않은 데이터를 읽기를 허용
- Read commited : 트랜잭션에서 커밋 완료된 데이터를 읽을 수 있음
- Repetable Read : 트랜잭션에서 수정, 삭제한 데이터는 Undo 로그에 저장되고, 트랜잭션 중 데이터 읽기 요청이 들어오면, 트랜잭션 시작 전 데이터를 읽게 함.MySQL에서 기본으로 설정한 격리성 수준
- Serializable Read : 트랜잭션이 순서대로 처리되게끔 보이게 함. 정합성이 가장 높지만 성능이 가장 낮음

** 격리성 수준은 정합성과 동시성의 Trade-Off 이다. 

