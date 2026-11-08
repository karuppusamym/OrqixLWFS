package com.orqix.operations;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.orqix.operations.retention.ArchiveStore;
import com.orqix.operations.retention.FileSystemArchiveStore;
import com.orqix.operations.retention.S3ArchiveStore;
import com.orqix.shared.json.Json;

/**
 * The two archive stores (CG-O07): the file system store writes atomically and refuses keys that leave its root; the
 * S3-compatible store sends SigV4-signed path-style PUT and GET requests (checked against a local WireMock, never a
 * live endpoint) and never sends the secret key.
 */
class ArchiveStoresTest {

    @TempDir
    Path root;

    @Test
    void fileSystemStoreRoundTripsAndRefusesEscapingKeys() {
        ArchiveStore store = new FileSystemArchiveStore(root);
        byte[] bytes = "archive".getBytes(StandardCharsets.UTF_8);
        store.put("purchasing/pa-1/2026/11/orq-1001-x.zip", bytes, Json.sha256Hex(bytes));
        assertThat(store.get("purchasing/pa-1/2026/11/orq-1001-x.zip")).hasValue(bytes);
        assertThat(store.get("purchasing/none.zip")).isEmpty();
        assertThat(Files.exists(root.resolve("purchasing/pa-1/2026/11/orq-1001-x.zip"))).isTrue();
        for (String bad : new String[] {"../x.zip", "/etc/x", "a\\b", "A/B.zip", "a/../../b"}) {
            assertThatThrownBy(() -> store.put(bad, bytes, "x")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void s3StoreSignsPathStyleRequests() {
        WireMockServer s3 = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        s3.start();
        try {
            byte[] bytes = "zip-bytes".getBytes(StandardCharsets.UTF_8);
            String sha = Json.sha256Hex(bytes);
            s3.stubFor(put(urlEqualTo("/orqix-archives/prod/purchasing/orq-1001.zip"))
                    .withHeader("Authorization", matching("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261108/eu-west-1/s3/"
                            + "aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=[0-9a-f]{64}"))
                    .withHeader("x-amz-content-sha256", equalTo(sha))
                    .withHeader("x-amz-date", equalTo("20261108T101500Z"))
                    .willReturn(aResponse().withStatus(200)));
            s3.stubFor(get(urlEqualTo("/orqix-archives/prod/purchasing/orq-1001.zip"))
                    .willReturn(aResponse().withStatus(200).withBody(bytes)));
            s3.stubFor(get(urlEqualTo("/orqix-archives/prod/purchasing/missing.zip"))
                    .willReturn(aResponse().withStatus(404)));
            Clock clock = Clock.fixed(Instant.parse("2026-11-08T10:15:00Z"), ZoneOffset.UTC);
            S3ArchiveStore store = new S3ArchiveStore(URI.create("http://localhost:" + s3.port()), "eu-west-1",
                    "orqix-archives", "prod", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                    Duration.ofSeconds(5), clock);
            store.put("purchasing/orq-1001.zip", bytes, sha);
            assertThat(store.get("purchasing/orq-1001.zip")).hasValue(bytes);
            assertThat(store.get("purchasing/missing.zip")).isEmpty();
            s3.verify(1, putRequestedFor(urlEqualTo("/orqix-archives/prod/purchasing/orq-1001.zip")));
            assertThat(s3.getAllServeEvents()).allSatisfy(e -> assertThat(e.getRequest().getHeaders().toString())
                    .doesNotContain("wJalrXUtnFEMI"));
            assertThatThrownBy(() -> store.put("purchasing/refused.zip", bytes, sha))
                    .isInstanceOf(ArchiveStore.ArchiveStoreException.class);
        } finally {
            s3.stop();
        }
    }
}
