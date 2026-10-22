package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;

@EnabledOnOs({OS.LINUX, OS.MAC})
@Timeout(30)
class CacheSymlinkTest {

    @TempDir
    Path temp;

    private HttpServer server;

    private Path outside;

    private byte[] archive;

    private final AtomicReference<byte[]> uploaded = new AtomicReference<>();

    private final AtomicInteger downloads = new AtomicInteger();

    private final TaskLogger logger = new TaskLogger() {
        @Override
        public void log(String message, String sessionId) {
        }
    };

    @BeforeEach
    void startWorker() throws Exception {
        outside = Files.writeString(temp.resolve("outside"), "must not be copied");
        var source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "cached");
        Files.createSymbolicLink(source.resolve("absolute"), outside);
        Files.createSymbolicLink(source.resolve("relative"), Path.of("../outside"));
        var output = new ByteArrayOutputStream();
        TarUtils.tar(source.toFile(), output, false);
        archive = output.toByteArray();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/~api/worker/job-cache", exchange -> {
            try {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        downloads.incrementAndGet();
                        exchange.sendResponseHeaders(200, archive.length + 1);
                        exchange.getResponseBody().write(CacheAvailability.PARTIAL_MATCH.ordinal());
                        exchange.getResponseBody().write(archive);
                    }
                    case "HEAD" -> exchange.sendResponseHeaders(200, -1);
                    case "POST" -> {
                        uploaded.set(exchange.getRequestBody().readAllBytes());
                        exchange.sendResponseHeaders(200, -1);
                    }
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopWorker() {
        if (server != null)
            server.stop(0);
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private CacheConfigFacade config() {
        return new CacheConfigFacade("key", null, List.of(new CacheEntryFacade("cache", null)),
                UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH, null, null);
    }

    @Test
    void containerCachePreservesExternalLinksWithoutUploadingTheirTargets() throws Exception {
        var build = Files.createDirectory(temp.resolve("build"));
        var provisioner = KubernetesHelper.newCacheProvisioner(serverUrl(), "~api/worker/job-cache",
                "token", config(), temp.resolve("trust-certs").toFile(), 1, true);
        // Kubernetes persists provisioners between setup and finalization.
        provisioner = SerializationUtils.clone(provisioner);
        provisioner.download(build.toFile(), logger);
        var cache = build.resolve("work/cache");
        assertEquals(outside, Files.readSymbolicLink(cache.resolve("absolute")));
        assertEquals(Path.of("../outside"), Files.readSymbolicLink(cache.resolve("relative")));
        assertEquals("cached", Files.readString(cache.resolve("file")));

        provisioner.upload(build.toFile(), logger);
        assertNotNull(uploaded.get());
        var names = new HashSet<String>();
        try (var tar = new TarArchiveInputStream(new ByteArrayInputStream(uploaded.get()))) {
            for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                names.add(entry.getName());
                if (entry.getName().equals("file"))
                    assertTrue(entry.isFile());
                else
                    assertTrue(entry.isSymbolicLink());
            }
        }
        assertEquals(Set.of("file", "absolute", "relative"), names);
        assertEquals("must not be copied", Files.readString(outside));
    }

    @Test
    void hostCacheEntryPointsKeepStrictDefaults() throws Exception {
        var build = Files.createDirectory(temp.resolve("build"));
        var provisioner = KubernetesHelper.newCacheProvisioner(serverUrl(), "~api/worker/job-cache",
                "token", config(), temp.resolve("trust-certs").toFile(), 1);
        assertThrows(ExplicitException.class, () -> provisioner.download(build.toFile(), logger));
        assertThrows(ExplicitException.class, () -> KubernetesHelper.downloadCache(
                serverUrl(), "~api/worker/job-cache", "token", "key", null, "cache",
                temp.resolve("strict").toFile(), null));
        assertEquals("must not be copied", Files.readString(outside));
    }

    @Test
    void containerCacheStillRejectsSymlinkedCacheRoots() throws Exception {
        var build = Files.createDirectory(temp.resolve("build"));
        var work = Files.createDirectory(build.resolve("work"));
        Files.createSymbolicLink(work.resolve("cache"), temp);
        var provisioner = KubernetesHelper.newCacheProvisioner(serverUrl(), "~api/worker/job-cache",
                "token", config(), temp.resolve("trust-certs").toFile(), 1, true);
        assertThrows(ExplicitException.class, () -> provisioner.download(build.toFile(), logger));
        assertEquals(0, downloads.get());
        assertEquals("must not be copied", Files.readString(outside));
    }
}
