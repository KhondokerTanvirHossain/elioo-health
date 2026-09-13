package com.elioo.baymax.healthrecord.adapter.out.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PatientProfileRepository extends ReactiveCrudRepository<PatientProfileEntity, UUID> {
    Flux<PatientProfileEntity> findByFamilyIdOrderByCreatedAt(UUID familyId);

    Mono<Long> countByFamilyId(UUID familyId);
}
