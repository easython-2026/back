package com.easython.storage;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

final class S3FileStorage implements FileStorage {
	private static final String NO_CACHE = "private, no-store";

	private final S3Client client;
	private final S3Presigner presigner;
	private final String bucket;

	S3FileStorage(S3Client client, S3Presigner presigner, String bucket) {
		if (bucket.isBlank()) throw new IllegalArgumentException("S3_BUCKET is required for S3 storage");
		this.client = client;
		this.presigner = presigner;
		this.bucket = bucket;
	}

	@Override
	public void save(String key, byte[] bytes) {
		client.putObject(request -> request.bucket(bucket).key(key)
				.ifNoneMatch("*").cacheControl(NO_CACHE)
				.contentType(FileStorage.contentType(key).orElse("application/octet-stream")),
				RequestBody.fromBytes(bytes));
	}

	@Override
	public byte[] load(String key) {
		return client.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
	}

	@Override
	public void delete(String key) {
		client.deleteObject(request -> request.bucket(bucket).key(key));
	}

	@Override
	public SignedUrl signedUrl(String key, Duration ttl) {
		var signed = presigner.presignGetObject(request -> request.signatureDuration(ttl)
				.getObjectRequest(get -> get.bucket(bucket).key(key).responseCacheControl(NO_CACHE)));
		return new SignedUrl(signed.url().toExternalForm(), signed.expiration());
	}
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.storage.backend", havingValue = "s3")
class S3StorageConfiguration {
	@Bean
	S3Client s3Client(@Value("${app.storage.s3.region}") String region) {
		return S3Client.builder().region(Region.of(region)).build();
	}

	@Bean
	S3Presigner s3Presigner(@Value("${app.storage.s3.region}") String region) {
		return S3Presigner.builder().region(Region.of(region)).build();
	}

	@Bean
	FileStorage s3Storage(S3Client client, S3Presigner presigner, @Value("${app.storage.s3.bucket}") String bucket) {
		return new S3FileStorage(client, presigner, bucket);
	}
}
