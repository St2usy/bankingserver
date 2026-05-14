# BankingService
직접 BankingService의 API 서버와 데이터베이스를 구축해보며 금융 IT의 비즈니스 로직을 연구해보고자 함

참여자 : St2ussy (신철언)

최종 수정일 : 2026/05/14

최종 수정 내용 : pom.xml 수정 (23번째 줄 testContianer버전 수정, testContainer와 Docker 충돌)

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

격리성 수준은 아래의 4가지와 같다
- Read uncommited : 트랜잭션에서 처리중인, 아직 커밋되지 않은 데이터를 읽기를 허용
- Read commited : 트랜잭션에서 커밋 완료된 데이터를 읽을 수 있음
- Repetable Read : 트랜잭션에서 수정, 삭제한 데이터는 Undo 로그에 저장되고, 트랜잭션 중 데이터 읽기 요청이 들어오면, 트랜잭션 시작 전 데이터를 읽게 함.MySQL에서 기본으로 설정한 격리성 수준
- Serializable Read : 트랜잭션이 순서대로 처리되게끔 보이게 함. 정합성이 가장 높지만 성능이 가장 낮음

**격리성 수준은 정합성과 동시성의 Trade-Off 이다.**

## Lock의 종류와 정책
만약 A의 계좌의 잔액이 0원이고, 누군가가 10,000원을 입금 했는데, 동시에 10,000원을 출금하려 하면 어떤 일이 벌어질까? 동시성 문제가 발생 할 수있다.

이를 해결하고자 DB의 lock을 걸어 동시성을 희생하고 데이터의 무결성과 정합성을 보장한다. <Trade-off>

- Shared Lock
읽기 전용 lock, 누군가가 읽고 싶으면 S-lock를 얻고 들어와야한다. S-lock는 다중 사용자가 얻을 수 있다. 단, S-lock가 걸려있는 자원은 수정 불허

- Exclusive Lock
수정 전용 lock, 수정이 필요할 때 X-lock를 얻고 들어와야한다. X-lock는 수정에 참여하는 사용자 한명만 얻을 수 있다.

- 비관적 LOCK
데이터베이스 수정 작업시, 동시성 충돌이 일어날 것을 '가정'하고 lock를 미리 획득 하는 정책. 데이터베이스 단계에서 lock를 얻는다., cons로 성능 저하와, DeadLock 발생 가능성이 있다.

- 낙관적 LOCK
동시성 충돌이 거의 일어나지 않을 것을 '가정'하고, Version, TimeStamp등 애플리케이션 수준의 동시성 제어 정책. 비관적 LOCK보다 데이터베이스 작업 성능이 뛰어나지만, 충돌 발생시 복구 비용이 크다.




