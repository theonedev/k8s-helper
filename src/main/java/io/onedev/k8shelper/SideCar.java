package io.onedev.k8shelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.TaskLogger;

public class SideCar {

	private static final Logger logger = LoggerFactory.getLogger(SideCar.class);
	
	public static void main(String[] args) {
		try {
			String serverUrl = System.getenv(KubernetesHelper.ENV_SERVER_URL);
			if (serverUrl == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_SERVER_URL + "' is not defined");
			String jobToken = Preconditions.checkNotNull(System.getenv(KubernetesHelper.ENV_JOB_TOKEN));
			if (jobToken == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_JOB_TOKEN + "' is not defined");
			
			KubernetesHelper.sidecar(serverUrl, jobToken, args.length > 0);

			logger.info(KubernetesHelper.LOG_END_MESSAGE);
			System.exit(0);
		} catch (Exception e) {
			logger.error(TaskLogger.wrapWithAnsiError(TaskLogger.toString(null, e)));
			logger.info(KubernetesHelper.LOG_END_MESSAGE);
			System.exit(1);
		}
	}
	
}
