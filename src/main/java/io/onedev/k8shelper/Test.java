package io.onedev.k8shelper;

import io.onedev.commons.utils.FileUtils;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import nl.altindag.ssl.SSLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.onedev.k8shelper.KubernetesHelper.*;

public class Test {

	public static final String TEST_PATH = "/onedev-test";

	public static final String ENV_TEST_TOKEN = "ONEDEV_TEST_TOKEN";

	private static final Logger logger = LoggerFactory.getLogger(Test.class);

	private static File getTrustCertsDir() {
		return new File(TEST_PATH, "trust-certs");
	}

	public static void main(String[] args) {
		try {
			run();
			System.exit(0);
		} catch (Throwable e) {
			KubernetesHelper.logFailure(logger, e);
			System.exit(1);
		}
	}

	private static void run() throws IOException {
		String serverUrl = KubernetesHelper.requireServerUrl();
		String token = checkNotNull(System.getenv(ENV_TEST_TOKEN),
				"Environment variable %s is not defined", ENV_TEST_TOKEN);

		logger.info("Testing PVC write...");
		FileUtils.touch(new File(TEST_PATH, ".pvc-test"));
		logger.info("PVC write test successful");

		logger.info("Connecting to server '{}' from Kubernetes pod (helper container)...", serverUrl);
		SSLFactory sslFactory = buildSSLFactory(getTrustCertsDir());
        try (var client = buildRestClient(sslFactory)) {
            WebTarget target = client.target(serverUrl)
                    .path("~api/worker/test")
                    .queryParam("token", token);
            Invocation.Builder builder = target.request();
            try (Response response = builder.get()) {
                checkStatus(response);
            }
            logger.info("Successfully connected to server from Kubernetes pod (helper init container)");
        }
	}

}
