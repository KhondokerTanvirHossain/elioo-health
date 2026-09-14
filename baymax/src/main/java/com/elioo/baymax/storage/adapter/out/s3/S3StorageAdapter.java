package com.elioo.baymax.storage.adapter.out.s3;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.Closeable;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link StoragePort} on the AWS S3 API, which AWS S3, Supabase Storage and MinIO all speak. The bucket is
 * private; nothing here ever sets an ACL. Every PUT carries {@code x-amz-server-side-encryption} when
 * {@code baymax.storage.server-side-encryption} is set (AWS: AES256; Supabase and MinIO encrypt at rest
 * themselves and may reject the header, so leave it blank there).
 *
 * <p>Uses the synchronous SDK client on {@link Schedulers#boundedElastic()} to keep a single netty in the
 * application; images are a few MB and calls are few, so this is not the bottleneck.</p>
 */
@Slf4j
public class S3StorageAdapter implements StoragePort, Closeable {

    private static final int DELETE_BATCH = 1000;

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String serverSideEncryption;

    S3StorageAdapter(S3Client s3, S3Presigner presigner, String bucket, String serverSideEncryption) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.serverSideEncryption = serverSideEncryption;
    }

    public static S3StorageAdapter create(BaymaxProperties.Storage cfg) {
        if (!StringUtils.hasText(cfg.getBucket())) {
            throw new IllegalArgumentException("baymax.storage.bucket is required");
        }
        Region region = Region.of(cfg.getRegion());
        AwsCredentialsProvider credentials = credentialsProvider(cfg);
        S3Configuration serviceConfig = S3Configuration.builder().pathStyleAccessEnabled(cfg.isPathStyle()).build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig)
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        if (StringUtils.hasText(cfg.getEndpoint())) {
            URI endpoint = URI.create(cfg.getEndpoint());
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }
        log.info("[baymax] object storage: bucket={} region={} endpoint={} pathStyle={} sse={} credentials={}",
                cfg.getBucket(), cfg.getRegion(), StringUtils.hasText(cfg.getEndpoint()) ? cfg.getEndpoint() : "aws",
                cfg.isPathStyle(), StringUtils.hasText(cfg.getServerSideEncryption()) ? cfg.getServerSideEncryption() : "none",
                cfg.getCredentials());
        return new S3StorageAdapter(clientBuilder.build(), presignerBuilder.build(), cfg.getBucket(),
                cfg.getServerSideEncryption());
    }

    /** Static keys when given; otherwise the instance profile (DR-5) or the SDK default chain, as configured. */
    static AwsCredentialsProvider credentialsProvider(BaymaxProperties.Storage cfg) {
        boolean hasKeys = StringUtils.hasText(cfg.getAccessKey()) && StringUtils.hasText(cfg.getSecretKey());
        return switch (cfg.getCredentials()) {
            case STATIC -> {
                if (!hasKeys) {
                    throw new IllegalArgumentException("baymax.storage.credentials=static needs access-key and secret-key");
                }
                yield StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey()));
            }
            case INSTANCE_ROLE -> InstanceProfileCredentialsProvider.create();
            case DEFAULT_CHAIN -> hasKeys
                    ? StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey()))
                    : DefaultCredentialsProvider.create();
        };
    }

    @Override
    public Mono<Long> put(String key, byte[] bytes, String contentType) {
        return Mono.fromCallable(() -> {
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .contentLength((long) bytes.length);
            if (StringUtils.hasText(serverSideEncryption)) {
                request.serverSideEncryption(ServerSideEncryption.fromValue(serverSideEncryption));
            }
            s3.putObject(request.build(), RequestBody.fromBytes(bytes));
            return (long) bytes.length;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> get(String key) {
        return Mono.fromCallable(() -> s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<String> listKeys(String prefix) {
        return Mono.fromCallable(() -> allKeys(prefix))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<URI> signedGetUrl(String key, Duration ttl) {
        return Mono.fromCallable(() -> {
            GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build();
            try {
                return presigner.presignGetObject(request).url().toURI();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("presigned URL is not a valid URI", e);
            }
        });
    }

    @Override
    public Mono<Long> deleteByPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return Mono.error(new IllegalArgumentException("refusing to delete with a blank prefix"));
        }
        return Mono.fromCallable(() -> {
            List<String> keys = allKeys(prefix);
            for (int i = 0; i < keys.size(); i += DELETE_BATCH) {
                List<ObjectIdentifier> batch = keys.subList(i, Math.min(i + DELETE_BATCH, keys.size())).stream()
                        .map(k -> ObjectIdentifier.builder().key(k).build())
                        .toList();
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(batch).quiet(true).build())
                        .build());
            }
            return (long) keys.size();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private List<String> allKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .contents().forEach(object -> keys.add(object.key()));
        keys.sort(String::compareTo);
        return keys;
    }

    @Override
    public void close() {
        presigner.close();
        s3.close();
    }
}
