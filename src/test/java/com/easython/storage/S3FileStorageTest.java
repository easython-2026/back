package com.easython.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3FileStorageTest {
	@Test
	void signsPrivateObjectDownloadWithoutNetwork() {
		try (var presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
				.build()) {
			var storage = new S3FileStorage(null, presigner, "easython-test-evidence");
			Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
			var signed = storage.signedUrl("contract_123.pdf", Duration.ofMinutes(30));
			URI uri = URI.create(signed.url());

			assertEquals("https", uri.getScheme());
			assertEquals("easython-test-evidence.s3.ap-northeast-2.amazonaws.com", uri.getHost());
			assertEquals("/contract_123.pdf", uri.getPath());
			assertTrue(uri.getQuery().contains("X-Amz-SignedHeaders=host"));
			assertTrue(uri.getQuery().contains("X-Amz-Expires=1800"));
			assertTrue(uri.getQuery().contains("response-cache-control=private, no-store"));
			assertFalse(signed.expiresAt().isBefore(before.plusSeconds(1800)));
		}
	}
}
