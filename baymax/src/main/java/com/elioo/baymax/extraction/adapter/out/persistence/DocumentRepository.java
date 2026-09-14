package com.elioo.baymax.extraction.adapter.out.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

public interface DocumentRepository extends ReactiveCrudRepository<DocumentEntity, UUID> {
}
