package io.onedev.k8shelper;

import io.onedev.commons.utils.TaskLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.onedev.k8shelper.KubernetesHelper.LOG_END_MESSAGE;

public class RunServerSideStep {

	private static final Logger logger = LoggerFactory.getLogger(RunServerSideStep.class);
	
	public static void main(String[] args) {
		int exitCode = 0;
		try {
			String serverUrl = System.getenv(KubernetesHelper.ENV_SERVER_URL);
			if (serverUrl == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_SERVER_URL + "' is not defined");
			String jobToken = System.getenv(KubernetesHelper.ENV_JOB_TOKEN);
			if (jobToken == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_JOB_TOKEN + "' is not defined");

			boolean successful;
			if (args.length >= 5)
				successful = KubernetesHelper.runServerStep(serverUrl, jobToken, args[0], args[1], args[2], args[3], args[4]);
			else
				successful = KubernetesHelper.runServerStep(serverUrl, jobToken, args[0], args[1], args[2], args[3], null);
			if (!successful)
				exitCode = 1;
		} catch (Exception e) {
			logger.error(TaskLogger.wrapWithAnsiError(TaskLogger.toString(null, e)));
			exitCode = 1;
		} finally {
			logger.info(LOG_END_MESSAGE);
			System.exit(exitCode);
		}
	}
	
}
