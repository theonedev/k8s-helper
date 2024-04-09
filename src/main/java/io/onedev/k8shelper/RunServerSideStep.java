package io.onedev.k8shelper;

import io.onedev.commons.utils.TaskLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.onedev.k8shelper.KubernetesHelper.LOG_END_MESSAGE;

public class RunServerSideStep {

	private static final Logger logger = LoggerFactory.getLogger(RunServerSideStep.class);
	
	public static void main(String[] args) {
		int exitCode = 0;
		try {
			String serverUrl = checkNotNull(System.getenv(KubernetesHelper.ENV_SERVER_URL));
			String jobToken = checkNotNull(System.getenv(KubernetesHelper.ENV_JOB_TOKEN));
			if (!KubernetesHelper.runServerStep(serverUrl, jobToken, args[0]))
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
