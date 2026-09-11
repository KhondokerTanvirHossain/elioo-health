//package com.elioo.healthcare.core.config;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
//import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
//import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
//import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
//import software.amazon.awssdk.regions.Region;
//import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
//import software.amazon.awssdk.services.comprehendmedical.ComprehendMedicalAsyncClient;
//import software.amazon.awssdk.services.textract.TextractAsyncClient;
//
///**
// * AWS Services Configuration.
// *
// * This configuration class sets up AWS SDK clients for:
// * - Amazon Textract (OCR)
// * - Amazon Comprehend Medical (Medical entity classification)
// * - Amazon Bedrock Runtime (AI/LLM for clinical insights)
// * - Amazon S3 (Document storage)
// *
// * Architecture Note:
// * These beans are infrastructure concerns and belong in the configuration layer.
// * They are injected into adapter classes that implement outbound ports.
// *
// * Credentials Strategy:
// * 1. If access key/secret are configured → Use StaticCredentialsProvider
// * 2. Otherwise → Use DefaultCredentialsProvider (IAM roles, env vars, ~/.aws/credentials)
// */
//@Slf4j
//@Configuration
//public class AwsConfig {
//
//    @Value("${aws.region:us-east-1}")
//    private String awsRegion;
//
//    @Value("${aws.access-key-id:}")
//    private String accessKeyId;
//
//    @Value("${aws.secret-access-key:}")
//    private String secretAccessKey;
//
//    /**
//     * Configure AWS credentials provider.
//     * Uses StaticCredentialsProvider if keys are provided, otherwise defaults to IAM roles.
//     */
//    @Bean
//    public AwsCredentialsProvider awsCredentialsProvider() {
//        if (accessKeyId != null && !accessKeyId.isBlank()
//                && secretAccessKey != null && !secretAccessKey.isBlank()) {
//            log.info("Using static AWS credentials");
//            return StaticCredentialsProvider.create(
//                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
//            );
//        }
//
//        log.info("Using default AWS credentials provider chain");
//        return DefaultCredentialsProvider.create();
//    }
//
//    /**
//     * Amazon Textract Async Client for OCR operations.
//     *
//     * Textract provides:
//     * - Text detection (DetectDocumentText)
//     * - Document analysis (AnalyzeDocument) with tables, forms, and layout
//     * - Expense analysis
//     * - Async job processing for large documents
//     */
//    @Bean
//    public TextractAsyncClient textractAsyncClient(AwsCredentialsProvider credentialsProvider) {
//        log.info("Initializing Amazon Textract client in region: {}", awsRegion);
//
//        return TextractAsyncClient.builder()
//                .region(Region.of(awsRegion))
//                .credentialsProvider(credentialsProvider)
//                .build();
//    }
//
//    /**
//     * Amazon Comprehend Medical Async Client for medical NLP.
//     *
//     * Comprehend Medical provides:
//     * - DetectEntitiesV2: Extract medical entities (conditions, medications, tests, anatomy)
//     * - InferICD10CM: Map to ICD-10-CM diagnosis codes
//     * - InferRxNorm: Map medications to RxNorm codes
//     * - InferSNOMEDCT: Map to SNOMED CT codes
//     * - DetectPHI: Identify protected health information
//     */
//    @Bean
//    public ComprehendMedicalAsyncClient comprehendMedicalAsyncClient(
//            AwsCredentialsProvider credentialsProvider
//    ) {
//        log.info("Initializing Amazon Comprehend Medical client in region: {}", awsRegion);
//
//        return ComprehendMedicalAsyncClient.builder()
//                .region(Region.of(awsRegion))
//                .credentialsProvider(credentialsProvider)
//                .build();
//    }
//
//    /**
//     * Amazon Bedrock Runtime Async Client for AI/LLM operations.
//     *
//     * Bedrock provides access to foundation models:
//     * - Anthropic Claude (recommended for medical use cases)
//     * - Amazon Titan
//     * - AI21 Labs Jurassic
//     * - Meta Llama
//     * - Cohere Command
//     *
//     * Supports:
//     * - InvokeModel: Synchronous inference
//     * - InvokeModelWithResponseStream: Streaming responses
//     */
//    @Bean
//    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(
//            AwsCredentialsProvider credentialsProvider
//    ) {
//        log.info("Initializing Amazon Bedrock Runtime client in region: {}", awsRegion);
//
//        return BedrockRuntimeAsyncClient.builder()
//                .region(Region.of(awsRegion))
//                .credentialsProvider(credentialsProvider)
//                .build();
//    }
//
//    /**
//     * Amazon S3 Async Client for document storage.
//     *
//     * S3 provides:
//     * - Document upload/download
//     * - Presigned URLs for secure access
//     * - Lifecycle policies for data retention
//     * - Encryption at rest
//     * - Versioning for document history
//     */
//    @Bean
//    public S3AsyncClient s3AsyncClient(AwsCredentialsProvider credentialsProvider) {
//        log.info("Initializing Amazon S3 client in region: {}", awsRegion);
//
//        return S3AsyncClient.builder()
//                .region(Region.of(awsRegion))
//                .credentialsProvider(credentialsProvider)
//                .build();
//    }
//
//    /**
//     * ObjectMapper for JSON processing.
//     * Used by Bedrock adapter for request/response serialization.
//     */
//    @Bean
//    public ObjectMapper objectMapper() {
//        return new ObjectMapper();
//    }
//}
