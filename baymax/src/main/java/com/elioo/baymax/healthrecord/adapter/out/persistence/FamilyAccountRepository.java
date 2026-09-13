package com.elioo.baymax.healthrecord.adapter.out.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface FamilyAccountRepository extends ReactiveCrudRepository<FamilyAccountEntity, UUID> {
    Mono<FamilyAccountEntity> findByWhatsappNumber(String whatsappNumber);
}
