package io.onedev.k8shelper;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.onedev.k8shelper.KubernetesHelper.buildRestClient;
import static io.onedev.k8shelper.KubernetesHelper.buildSSLFactory;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;

import java.io.File;
import java.io.IOException;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import nl.altindag.ssl.SSLFactory;

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
			logger.info("This is a information message");
			logger.error("This is a error message");
			System.out.println(TaskLogger.wrapWithAnsiSuccess("This is a system out message"));
			System.out.println(TaskLogger.wrapWithAnsiError("This is a system out message"));
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

		logger.info("Connecting to server '{}'...", serverUrl);
		SSLFactory sslFactory = buildSSLFactory(getTrustCertsDir());
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/test")
					.queryParam("token", token);
			Invocation.Builder builder = target.request();
			try (Response response = builder.get()) {
				checkStatus(response);
			}
			logger.info("Successfully connected to OneDev server");
		} finally {
			client.close();
		}
	}

}
