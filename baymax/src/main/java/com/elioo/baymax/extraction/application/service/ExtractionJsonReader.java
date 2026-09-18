package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.elioo.healthcare.llm.json.LlmJsonExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the model's reply into an {@link ExtractionResult}, refusing anything that does not match
 * {@code extraction-schema.json}.
 *
 * <p>JSON mode guarantees the reply parses, not that it has the right shape, so the schema is checked
 * separately. A failure returns the validation messages, which the caller feeds back to the model once as a
 * repair attempt. Validation messages describe structure only and never quote patient values, so they are
 * safe to log and to send back.</p>
 */
@Slf4j
@Component
public class ExtractionJsonReader {

    private final ObjectMapper mapper;
    private final JsonSchema schema;

    public ExtractionJsonReader(ObjectMapper mapper) {
        this.mapper = mapper;
        this.schema = loadSchema(mapper);
    }

    /** Thrown when the reply is not valid against the schema; the message is the repair instruction. */
    public static class InvalidExtractionException extends RuntimeException {
        public InvalidExtractionException(String message) {
            super(message);
        }
    }

    public ExtractionResult read(String rawReply) {
        JsonNode node;
        try {
            // LlmJsonExtractor unwraps a markdown fence or surrounding prose, and throws when the reply
            // contains no JSON at all; either way the caller gets one repairable failure type.
            node = mapper.readTree(LlmJsonExtractor.extract(rawReply));
        } catch (IOException | RuntimeException e) {
            throw new InvalidExtractionException("The reply was not valid JSON: " + e.getMessage());
        }
        Set<ValidationMessage> problems = schema.validate(node);
        if (!problems.isEmpty()) {
            String detail = problems.stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .limit(12)
                    .collect(Collectors.joining("; "));
            log.debug("[baymax] extraction schema rejected: {}", detail);
            throw new InvalidExtractionException("The JSON did not match the required shape: " + detail);
        }
        try {
            return mapper.treeToValue(node, ExtractionResult.class);
        } catch (IOException e) {
            throw new InvalidExtractionException("The JSON matched the shape but could not be read: "
                    + e.getMessage());
        }
    }

    private static JsonSchema loadSchema(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("extraction-schema.json").getInputStream()) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(mapper.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("extraction-schema.json is missing from the Baymax module", e);
        }
    }

    /**
     * Reads the pipeline's own stored extraction_json, which is the validated result re-serialised (and so
     * carries derived fields such as source_span.usable that the input schema forbids). Not for model output.
     */
    public ExtractionResult readStored(String storedJson) {
        try {
            return mapper.copy()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(storedJson, ExtractionResult.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new InvalidExtractionException("stored extraction_json is unreadable: " + e.getOriginalMessage());
        }
    }
}
