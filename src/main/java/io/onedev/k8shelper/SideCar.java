package io.onedev.k8shelper;

import io.onedev.commons.utils.TaskLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

public class SideCar {

	private static final Logger logger = LoggerFactory.getLogger(SideCar.class);
	
	public static void main(String[] args) {
		try {
			String serverUrl = checkNotNull(System.getenv(KubernetesHelper.ENV_SERVER_URL));
			String jobToken = checkNotNull(System.getenv(KubernetesHelper.ENV_JOB_TOKEN));

			logger.info(KubernetesHelper.LOG_END_MESSAGE);
			if (KubernetesHelper.sidecar(serverUrl, jobToken, args.length > 0))
				System.exit(0);
			else
				System.exit(1);
		} catch (Exception e) {
			logger.error(TaskLogger.wrapWithAnsiError(TaskLogger.toString(null, e)));
			logger.info(KubernetesHelper.LOG_END_MESSAGE);
			System.exit(1);
		}
	}
	
}
