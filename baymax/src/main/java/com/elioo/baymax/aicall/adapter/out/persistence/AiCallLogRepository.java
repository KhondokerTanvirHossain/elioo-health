package com.elioo.baymax.aicall.adapter.out.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

public interface AiCallLogRepository extends ReactiveCrudRepository<AiCallLogEntity, UUID> {
}
