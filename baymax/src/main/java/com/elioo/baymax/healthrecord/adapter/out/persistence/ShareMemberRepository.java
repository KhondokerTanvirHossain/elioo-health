package com.elioo.baymax.healthrecord.adapter.out.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ShareMemberRepository extends ReactiveCrudRepository<ShareMemberEntity, UUID> {
    Mono<Long> countByPatientId(UUID patientId);
}
