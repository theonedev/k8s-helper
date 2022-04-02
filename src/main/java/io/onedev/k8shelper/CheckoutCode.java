package io.onedev.k8shelper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

public class CheckoutCode {

	private static final Logger logger = LoggerFactory.getLogger(CheckoutCode.class);
	
	public static void main(String[] args) {
		int exitCode = 0;
		try {
			String serverUrl = System.getenv(KubernetesHelper.ENV_SERVER_URL);
			if (serverUrl == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_SERVER_URL + "' is not defined");
			String jobToken = Preconditions.checkNotNull(System.getenv(KubernetesHelper.ENV_JOB_TOKEN));
			if (jobToken == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_JOB_TOKEN + "' is not defined");

			String checkoutPath = null;
			if (args.length >= 6) 
				checkoutPath = new String(Base64.getDecoder().decode(args[5]), StandardCharsets.UTF_8);
			KubernetesHelper.checkoutCode(serverUrl, jobToken, args[0], Boolean.parseBoolean(args[1]), 
					Boolean.parseBoolean(args[2]), Integer.parseInt(args[3]), CloneInfo.fromString(args[4]), 
					checkoutPath);
		} catch (Exception e) {
			logger.error("Error executing step", e);
			exitCode = 1;
		} finally {
			logger.info(KubernetesHelper.LOG_END_MESSAGE);
			System.exit(exitCode);
		}
	}
	
}
