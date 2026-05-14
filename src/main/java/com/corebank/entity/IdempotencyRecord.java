package com.corebank.entity;

import com.corebank.model.IdempotencyOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "IdempotencyRecord")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 512)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 32)
    private IdempotencyOperation operation;

    @Lob
    @Column(name = "response_json")
    private String responseJson;
}
