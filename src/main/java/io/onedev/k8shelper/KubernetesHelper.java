package io.onedev.k8shelper;

import static io.onedev.k8shelper.UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.Base64.getEncoder;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;
import static javax.ws.rs.core.Response.Status.OK;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;
import static org.apache.commons.io.FileUtils.readFileToByteArray;
import static org.apache.commons.io.FileUtils.readFileToString;
import static org.apache.commons.io.FileUtils.writeByteArrayToFile;
import static org.apache.commons.lang3.SerializationUtils.deserialize;
import static org.apache.commons.lang3.SerializationUtils.serialize;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.glassfish.jersey.client.ClientProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.util.CertificateUtils;
import nl.altindag.ssl.util.HostnameVerifierUtils;

public class KubernetesHelper {

	public static final String IMAGE_REPO = "1dev/k8s-helper";
	
	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";
	
	public static final String ENV_JOB_TOKEN = "ONEDEV_JOB_TOKEN";

	public static final String AUTHORIZATION = "OneDevAuthorization";

	public static final String BEARER = "Bearer";
	
	public static final String LOG_END_MESSAGE = "===== End of OneDev K8s Helper Log =====";

	public static final String BUILD_VERSION = "buildVersion";
	
	public static final String PAUSE = "pause";
	
	public static final String WORKDIR = "work";
	
	public static final String ATTRIBUTES = "attributes";

	public static final String PLACEHOLDER_PREFIX = "<&onedev#";
	
	public static final String PLACEHOLDER_SUFFIX = "#onedev&>";

	public static final String GIT_TRUST_ALL_DIRS = "(touch \"$HOME/.gitconfig\" "
				+ "&& (grep -q 'directory=\\*' \"$HOME/.gitconfig\" "
				+ "|| printf '[safe]\\n\\tdirectory=*\\n' >> \"$HOME/.gitconfig\"))";
	
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(PLACEHOLDER_PREFIX + "(.*?)" + PLACEHOLDER_SUFFIX);

	private static final Logger logger = LoggerFactory.getLogger(KubernetesHelper.class);
	
	private static File getBuildDir() {
		return new File("/onedev-build");
	}
	
	private static File getJobDataFile() {
		return new File(getBuildDir(), "job-data");
	}
	
	private static File getTrustCertsDir() {
		return new File(getBuildDir(), "trust-certs");
	}
	
	private static File getWorkDir() {
		return new File(getBuildDir(), WORKDIR);
	}
	
	private static File getCommandDir() {
		return new File(getBuildDir(), "command");
	}
	
	private static File getMarkDir() {
		return new File(getBuildDir(), "mark");
	}
	
	@SuppressWarnings("deprecation")
	public static String getVersion() {
		try (InputStream is = KubernetesHelper.class.getClassLoader().getResourceAsStream("k8s-helper-version.properties")) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			IOUtils.copy(is, baos);
			return baos.toString();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void generateCommandScript(List<Integer> position, String stepPath,
			CommandFacade commandFacade, File workingDir) {
		try {
			String positionStr = stringifyStepPosition(position);
			File commandDir = getCommandDir();
			File stepScriptFile = new File(commandDir, "step-" + positionStr + commandFacade.getScriptExtension());
			FileUtils.writeStringToFile(stepScriptFile, commandFacade.normalizeCommands(commandFacade.getCommands()), UTF_8);

			String escapedStepPath = stepPath.replace("'", "'\\''");

			File scriptFile = new File(commandDir, positionStr + ".sh");
			String markPrefix = getMarkDir().getAbsolutePath() + "/" + positionStr;
			List<String> wrapperScriptContent = Lists.newArrayList(
					"while [ ! -f " + markPrefix + ".start ] && [ ! -f " + markPrefix + ".skip ] && [ ! -f " + markPrefix + ".error ]",
					"do",
					"  sleep 0.1",
					"done",
					"if [ -f " + markPrefix + ".skip ]",
					"then",
					"  echo '" + TaskLogger.wrapWithAnsiNotice("Step \"" + escapedStepPath + "\" is skipped") + "'",
					"  echo " + LOG_END_MESSAGE,
					"  exit 0",
					"fi",
					"if [ -f " + markPrefix + ".error ]",
					"then",
					"  echo '" + TaskLogger.wrapWithAnsiNotice("Running step \"" + escapedStepPath + "\"...") + "'",
					"  cat " + markPrefix + ".error",
					"  touch " + markPrefix + ".failed",
					"  echo " + LOG_END_MESSAGE,
					"  exit 0",
					"fi",
					"cd " + "'" + workingDir.getAbsolutePath() + "'",
					"echo '" + TaskLogger.wrapWithAnsiNotice("Running step \"" + escapedStepPath + "\"...") + "'",
					GIT_TRUST_ALL_DIRS,
					commandFacade.getExecutable() + " " + stream(commandFacade.getScriptOptions()).map(it -> it + " ").collect(joining()) + stepScriptFile.getAbsolutePath(),

					"exitCode=\"$?\"",
					"if [ $exitCode -eq 0 ]",
					"then",
					"  echo '" + TaskLogger.wrapWithAnsiSuccess("Step \"" + escapedStepPath + "\" is successful") + "'",
					"  touch " + markPrefix + ".successful",
					"else",
					"  echo \"" + TaskLogger.wrapWithAnsiError("Command exited with code $exitCode") + "\"",
					"  echo '" + TaskLogger.wrapWithAnsiError("Step \"" + escapedStepPath + "\" is failed") + "'",
					"  touch " + markPrefix + ".failed",
					"fi",
					"echo " + LOG_END_MESSAGE,
					"exit 0");
			FileUtils.writeLines(scriptFile, wrapperScriptContent, "\n");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static LineConsumer newInfoLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.info(line);
			}
			
		};
	}
	
	private static LineConsumer newErrorLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.error(TaskLogger.wrapWithAnsiError(line));
			}
			
		};
	}

	/**
	 * This method does two things:
	 * 1. Set up git config file to trust the certificates. This way git operations inside 
	 * command build step or workspace can trust certificates without using extra options
	 * 2. Set up git command line to add arguments to trust certificates for git operations 
	 * preparing git repository to be used by command build step or workspace
	 */
	public static void setupGitCerts(Commandline git, File trustCertsDir, File trustCertsFile,
									  String runtimeTrustCertsFilePath, LineConsumer stdoutLogger, 
									  LineConsumer stderrLogger) {
		var presetArgs = new ArrayList<String>(git.args());
		if (trustCertsDir.exists()) {
			List<String> certLines = new ArrayList<>();
			for (var file: trustCertsDir.listFiles()) {
				if (file.isFile() && !file.isHidden()) {
					try {
						certLines.addAll(FileUtils.readLines(file, UTF_8));
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}

			if (!certLines.isEmpty()) {
				try {
					FileUtils.writeLines(trustCertsFile, certLines, "\n");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				runtimeTrustCertsFilePath = runtimeTrustCertsFilePath.replace("\\", "/");
				var trustCertsFilePath = trustCertsFile.getAbsolutePath().replace("\\", "/");
				git.args("-c", "safe.directory=*", "config", "http.sslCAInfo", runtimeTrustCertsFilePath);
				git.execute(stdoutLogger, stderrLogger).checkReturnCode();
				git.args(presetArgs);
				git.addArgs("-c", "http.sslCAInfo=\"" + trustCertsFilePath + "\"");
			}
		}
	}
	
	public static void init(String serverUrl, String jobToken, boolean test) {
		SSLFactory sslFactory = buildSSLFactory(getTrustCertsDir());
		try {
			FileUtils.createDir(getCommandDir());
			FileUtils.createDir(getMarkDir());
			if (test) {
				logger.info("Connecting to server '{}'...", serverUrl);
				Client client = buildRestClient(sslFactory);
				try {
					WebTarget target = client.target(serverUrl)
							.path("~api/k8s/test")
							.queryParam("jobToken", jobToken);
					Invocation.Builder builder =  target.request();
					try (Response response = builder.get()) {
						checkStatus(response);
					} 
				} finally {
					client.close();
				}
				FileUtils.createDir(getWorkDir());
				var commandsBuilder = new StringBuilder("echo hello from container\n");
				generateCommandScript(Lists.newArrayList(0), "test",
						new CommandFacade("any", "0:0", null, new HashMap<>(), true, commandsBuilder.toString()), getWorkDir());
			} else {
				K8sJobData jobData;
				Client client = buildRestClient(sslFactory);
				try {
					WebTarget target = client.target(serverUrl)
							.path("~api/k8s/job-data")
							.queryParam("jobToken", jobToken)
							.queryParam("jobWorkDir", getWorkDir().getAbsolutePath());
					Invocation.Builder builder =  target.request();

					logger.info("Retrieving job data from {}...", serverUrl);
					
					byte[] jobDataBytes;
					try (Response response = builder.get()) {
						checkStatus(response);
						jobDataBytes = response.readEntity(byte[].class);
					}
					
					FileUtils.writeByteArrayToFile(getJobDataFile(), jobDataBytes);
					jobData = deserialize(jobDataBytes);
				} finally {
					client.close();
				}
				
				File workDir = getWorkDir();
				FileUtils.createDir(workDir);
				
				logger.info("Generating command scripts...");
				
				CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
				entryFacade.traverse((LeafVisitor<Void>) (facade, position) -> {
					String stepPath = entryFacade.getPathAsString(position);

					String positionStr = stringifyStepPosition(position);

					File workingDir = getWorkDir();
					CommandFacade commandFacade;
					if (facade instanceof CommandFacade) {
						commandFacade = (CommandFacade) facade;
					} else {
						String command;
						String classPath = "/k8s-helper/*";
						if (facade instanceof CheckoutFacade) {
							CheckoutFacade checkoutFacade = (CheckoutFacade) facade;
							command = String.format("java -classpath \"%s\" io.onedev.k8shelper.CheckoutCode %s %b %b %d %s",
									classPath, positionStr, checkoutFacade.isWithLfs(), checkoutFacade.isWithSubmodules(),
									checkoutFacade.getCloneDepth(), checkoutFacade.getCloneInfo().toString());
							if (checkoutFacade.getCheckoutPath() != null) {
								byte[] bytes = checkoutFacade.getCheckoutPath().getBytes(UTF_8);
								command += " " + getEncoder().encodeToString(bytes);
							}
						} else if (facade instanceof SetupCacheFacade) {
							command = String.format("java -classpath \"%s\" io.onedev.k8shelper.SetupCache %s",
									classPath, positionStr);
						} else {
							command = String.format("java -classpath \"%s\" io.onedev.k8shelper.RunServerSideStep %s",
									classPath, positionStr);
						}

						var commandsBuilder = new StringBuilder();
						commandsBuilder.append(command).append("\n");

						commandFacade = new CommandFacade("any", "0:0", null, new HashMap<>(), true, commandsBuilder.toString());
					}

					generateCommandScript(position, stepPath, commandFacade, workingDir);

					return null;
				}, new ArrayList<>());
				
				logger.info("Downloading job dependencies from {}...", serverUrl);
				
				downloadDependencies(serverUrl, jobToken, workDir, sslFactory);
				logger.info("Job working directory initialized");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static String stringifyStepPosition(List<Integer> stepPosition) {
		return StringUtils.join(stepPosition, "-");
	}
	
	public static List<Integer> parseStepPosition(String stepPosition) {
		return Splitter.on('-').splitToList(stepPosition)
				.stream()
				.map(it->Integer.parseInt(it))
				.collect(toList());
	}
	
	public static void checkStatus(Response response) {
		int status = response.getStatus();
		if (status != OK.getStatusCode() && status != NO_CONTENT.getStatusCode()) {
			String errorMessage = response.readEntity(String.class);
			if (StringUtils.isNotBlank(errorMessage)) {
				throw new RuntimeException(String.format("Http request failed (status code: %d, error message: %s)", 
						status, errorMessage));
			} else if (status >= 500){
				throw new RuntimeException("Http request failed with status " + status 
						+ ", check server log for details");
			} else {
				throw new RuntimeException("Http request failed with status " + status);
			}
		} 
	}

	public static void addMacUsrLocalBinToPath(Commandline cmdline) {
		if (SystemUtils.IS_OS_MAC_OSX) {
			String path = System.getenv("PATH") + ":/usr/local/bin";
			cmdline.envs().put("PATH", path);
		}
	}

	public static void installGitLfs(Commandline git, LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		git.args("-c", "safe.directory=*", "lfs", "install", "--force");
		git.execute(stdoutLogger, stderrLogger).checkReturnCode();
	}

	public static void initRepository(Commandline git, LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		if (!new File(git.workingDir(), ".git").exists()) {
			git.args("-c", "safe.directory=*", "init", "-b", "main", ".");
			git.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.startsWith("Initialized empty Git repository"))
						stdoutLogger.consume(line);
				}

			}, stderrLogger).checkReturnCode();
		}
	}

	public static void setupOriginUrl(Commandline git, String remoteUrl, LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		var originExists = new AtomicBoolean(false);
		git.args("-c", "safe.directory=*", "remote", "add", "origin", remoteUrl);
		var result = git.execute(stdoutLogger, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.contains("remote origin already exists"))
					originExists.set(true);
				else
					stderrLogger.consume(line);
			}

		});

		if (originExists.get()) {
			git.args("-c", "safe.directory=*", "remote", "set-url", "origin", remoteUrl);
			result = git.execute(stdoutLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					stderrLogger.consume(line);
				}

			});
		}
		result.checkReturnCode();
		git.clearArgs();
	}

	/**
	 * The git arguments will be initialized with remote access arguments before calling this method. 
	 * Also .git/config inside the git working directory should also be set up for runtime (while job 
	 * or workspace runs) remote access
	 */
	public static void cloneRepository(Commandline git, String cloneUrl, String remoteUrl, 
				String refName, @Nullable String commitHash, boolean withLfs, boolean withSubmodules, 
				int cloneDepth, LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		var presetArgs = new ArrayList<>(git.args());

		String configContent;
		try {
			var configFile = new File(git.workingDir(), ".git/config");
			configContent = FileUtils.readFileToString(configFile, UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		git.addArgs("-c", "safe.directory=*", "fetch", cloneUrl, "--force", "--quiet"); 
		if (cloneDepth != 0)
			git.addArgs("--depth=" + cloneDepth);
		git.addArgs(commitHash != null ? commitHash : refName);
		git.execute(stdoutLogger, stderrLogger).checkReturnCode();

		setupOriginUrl(git, remoteUrl, stdoutLogger, stderrLogger);

		if (withLfs) 
			installGitLfs(git, stdoutLogger, stderrLogger);

		var fetched = commitHash != null ? commitHash : "FETCH_HEAD";

		git.args(presetArgs);
		git.addArgs("-c", "safe.directory=*", "checkout", "--quiet", fetched);
		git.execute(stdoutLogger, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("Filtering content:"))
					stdoutLogger.consume(line);
				else
					stderrLogger.consume(line);
			}

		}).checkReturnCode();

		if (withSubmodules && new File(git.workingDir(), ".gitmodules").exists()) {
			// deinit submodules in case submodule url is changed
			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "submodule", "deinit", "--all", "--force", "--quiet");
			git.execute(stdoutLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.contains("error: could not lock config file") &&
							!line.contains("warning: Could not unset core.worktree setting in submodule")) {
						stderrLogger.consume(line);
					}
				}

			}).checkReturnCode();

			stdoutLogger.consume("Retrieving submodules...");

			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "submodule", "update", "--init", "--recursive", "--force", "--quiet");
			if (cloneDepth != 0)
				git.addArgs("--depth=" + cloneDepth);
			git.execute(stdoutLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Submodule") && line.contains("registered for path")
							|| line.startsWith("From ") || line.startsWith(" * branch")
							|| line.startsWith(" +") && line.contains("->")) {
						stdoutLogger.consume(line);
					} else {
						stderrLogger.consume(line);
					}
				}

			}).checkReturnCode();

			if (configContent != null) {
				var modulesDir = new File(git.workingDir(), ".git/modules");
				if (modulesDir.isDirectory())
					writeConfigToSubmodules(modulesDir, configContent);
			}
		}

		if (refName.startsWith("refs/heads/")) {
			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "update-ref", refName, fetched);
			git.execute(stdoutLogger, stderrLogger).checkReturnCode();

			String branch = refName.substring("refs/heads/".length());
			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "checkout", branch);
			git.execute(stdoutLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Switched to branch"))
						stdoutLogger.consume(line);
					else
						stderrLogger.consume(line);
				}

			}).checkReturnCode();

			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "update-ref", "refs/remotes/origin/" + branch, fetched);
			git.execute(stdoutLogger, stderrLogger).checkReturnCode();

			git.args(presetArgs);
			git.addArgs("-c", "safe.directory=*", "branch", "--set-upstream-to=origin/" + branch, branch);
			git.execute(stdoutLogger, stderrLogger).checkReturnCode();
		}
		git.args(presetArgs);
	}

	private static void writeConfigToSubmodules(File modulesDir, String configContent) {
		for (File child : modulesDir.listFiles()) {
			if (child.isDirectory()) {
				try {
					FileUtils.writeStringToFile(new File(child, "config"), configContent, UTF_8);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				var nestedModulesDir = new File(child, "modules");
				if (nestedModulesDir.isDirectory())
					writeConfigToSubmodules(nestedModulesDir, configContent);
			}
		}
	}

	private static K8sJobData readJobData() {
		byte[] jobDataBytes;
		try {
			jobDataBytes = readFileToByteArray(getJobDataFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return deserialize(jobDataBytes);
	}
	
	public static void testGitLfsAvailability(Commandline git, TaskLogger jobLogger) {
		jobLogger.log("Checking if git-lfs exists...");
		
		git.args("lfs", "version");

		AtomicBoolean lfsExists = new AtomicBoolean(true);
		var result = git.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("git: 'lfs' is not a git command"))
					lfsExists.set(false);
				if (lfsExists.get())
					jobLogger.error(line);
			}
			
		});
		if (lfsExists.get()) {
			result.checkReturnCode();
			jobLogger.log("git-lfs found");
		} else { 
			jobLogger.warning("WARNING: Executable 'git-lfs' not found. You will not be able to retrieve LFS files");
		}
	}

	public static void testTmuxAvailability(Commandline tmux, TaskLogger jobLogger) {
		jobLogger.log("Checking if tmux exists...");

		tmux.args("-V");

		var result = tmux.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.error(line);
			}
			
		});
		if (result.getReturnCode() == 0) {
			jobLogger.log("tmux found");
		} else {
			throw new ExplicitException("tmux not found");
		}
	}

	public static void changeOwner(File dir, String owner) {
		var chown = new Commandline("chown");
		chown.addArgs("-R", owner, dir.getAbsolutePath());
		chown.execute(new LineConsumer() {
			@Override
			public void consume(String line) {
				logger.info(line);
			}
		}, new LineConsumer() {
			@Override
			public void consume(String line) {
				logger.error(line);
			}

		}).checkReturnCode();
	}

	public static boolean sidecar(String serverUrl, String jobToken, boolean test) {
		LeafHandler commandHandler = new LeafHandler() {

			@Override
			public boolean execute(LeafFacade facade, List<Integer> position) {
				String positionStr = stringifyStepPosition(position);
				File file;
				File stepScriptFile = null;
				for (File eachFile: getCommandDir().listFiles()) {
					if (eachFile.getName().startsWith("step-" + positionStr + ".")) {
						stepScriptFile = eachFile;
						break;
					}
				}
				Preconditions.checkState(stepScriptFile != null);

				try {
					String stepScript = readFileToString(stepScriptFile, UTF_8);

					if (facade instanceof CommandFacade) {
						CommandFacade commandFacade = (CommandFacade) facade;
						commandFacade.generatePauseCommand(getBuildDir());
						if (!commandFacade.getRunAs().equals("0:0"))
							changeOwner(getBuildDir(), commandFacade.getRunAs());
					}

					stepScript = replacePlaceholders(stepScript, getBuildDir());
					FileUtils.writeFile(stepScriptFile, stepScript, UTF_8);
					
					file = new File(getMarkDir(), positionStr + ".start");
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (Exception e) {
					file = new File(getMarkDir(), positionStr + ".error");

					ExplicitException explicitException = ExceptionUtils.find(e, ExplicitException.class);
					String errorMessage;
					if (explicitException != null)
						errorMessage = explicitException.getMessage().trim();
					else 
						errorMessage = Throwables.getStackTraceAsString(e).trim();
					errorMessage += "\n";
					FileUtils.writeFile(file, errorMessage, UTF_8);
				}
			
				File successfulFile = new File(getMarkDir(), positionStr + ".successful");
				File failedFile = new File(getMarkDir(), positionStr + ".failed");
				while (!successfulFile.exists() && !failedFile.exists()) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
				return successfulFile.exists();
			}

			@Override
			public void skip(LeafFacade facade, List<Integer> position) {
				File file = new File(getMarkDir(), stringifyStepPosition(position) + ".skip");
				try {
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			
		};
		
		if (test) {
			CommandFacade facade = new CommandFacade(
					"this does not matter", "0:0", null, new HashMap<>(), false, "this does not matter");
			return facade.execute(commandHandler, Lists.newArrayList(0));
		} else {
			var jobData = readJobData();
			var actions = jobData.getActions();

			var successful = new CompositeFacade(actions).execute(commandHandler, new ArrayList<>());

			if (successful) {
				var sslFactory = buildSSLFactory(getTrustCertsDir());
				var cacheInfos = readCacheInfos();
				for (var cacheInfo : cacheInfos) {
					var cacheConfig = cacheInfo.getLeft();
					var uploadStrategy = cacheConfig.getUploadStrategy();
					var exactMatchPaths = cacheInfo.getRight();
					if (uploadStrategy == UPLOAD_IF_NOT_EXACT_MATCH) {
						for (var path: cacheConfig.getPaths()) {
							if (!exactMatchPaths.contains(path)) 
								uploadCacheThenLog(serverUrl, jobToken, cacheConfig, path, getCacheDir(path), sslFactory);
						}
					} else {
						for (var path : cacheConfig.getPaths()) {
							if (FileUtils.hasChangedFiles(getCacheDir(path), cacheInfo.getMiddle(), cacheConfig.getChangeDetectionExcludes())) {
								logger.info("Changes detected in " + cacheConfig.describe(path));
								uploadCacheThenLog(serverUrl, jobToken, cacheConfig, path, getCacheDir(path), sslFactory);
							}
						}
					}
				}
			}
			return successful;
		}
	}

	private static void uploadCacheThenLog(String serverUrl, String jobToken, CacheConfigFacade cacheConfig,
							 String path, File cacheDir, @Nullable SSLFactory sslFactory) {
		if (uploadCache(serverUrl, jobToken, cacheConfig, path, cacheDir, sslFactory))
			logger.info("Uploaded " + cacheConfig.describeUpload(path));
		else
			logger.warn("Not authorized to upload " + cacheConfig.describeUpload(path));
	}

	public static void writeInt(OutputStream os, int value) {
		try {
			os.write(ByteBuffer.allocate(4).putInt(value).array());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void writeString(OutputStream os, String value) {
		try {
			byte[] valueBytes = value.getBytes(UTF_8);
			os.write(ByteBuffer.allocate(4).putInt(valueBytes.length).array());
			os.write(valueBytes);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static String readString(InputStream is) {
		try {
			byte[] lengthBytes = new byte[4];
			if (IOUtils.readFully(is, lengthBytes) != lengthBytes.length)
				throw new ExplicitException("Invalid input stream");
			int length = ByteBuffer.wrap(lengthBytes).getInt();
			byte[] stringBytes = new byte[length];
			if (IOUtils.readFully(is, stringBytes) != stringBytes.length)
				throw new ExplicitException("Invalid input stream");
			return new String(stringBytes, UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static int readInt(InputStream is) {
		try {
			byte[] intBytes = new byte[4];
			if (IOUtils.readFully(is, intBytes) != intBytes.length)
				throw new ExplicitException("Invalid input stream");
			return ByteBuffer.wrap(intBytes).getInt();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	static void checkoutCode(String serverUrl, String jobToken, String positionStr,
			boolean withLfs, boolean withSubmodules, int cloneDepth, CloneInfo cloneInfo, 
			@Nullable String checkoutPath) throws IOException {
		K8sJobData jobData = readJobData();
		
		logger.info("Checking out code from {}...", cloneInfo.getCloneUrl());

		LineConsumer infoLogger = newInfoLogger();
		LineConsumer errorLogger = newErrorLogger();
		
		File workDir = getWorkDir();
		Commandline git = new Commandline("git");
		if (checkoutPath != null) {
			if (checkoutPath.contains(".."))
				throw new ExplicitException("Checkout path should not contain '..'");
			git.workingDir(new File(workDir, checkoutPath));
			FileUtils.createDir(git.workingDir());
		} else {
			git.workingDir(workDir);
		}

		initRepository(git, infoLogger, errorLogger);
		
		git.clearArgs();
		File trustCertsFile = new File(getBuildDir(), "trust-certs.pem");
		setupGitCerts(git, getTrustCertsDir(), trustCertsFile,
				trustCertsFile.getAbsolutePath(), infoLogger, errorLogger);

		var buildDir = getBuildDir();
		cloneInfo.setupGitAuth(git, buildDir, buildDir.getAbsolutePath(), infoLogger, errorLogger);

		cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(), 
				jobData.getRefName(), jobData.getCommitHash(), withLfs, withSubmodules, cloneDepth, 
				infoLogger, errorLogger);
	}

	static boolean runServerStep(String serverUrl, String jobToken, String positionStr) {
		return runServerStep(buildSSLFactory(getTrustCertsDir()), serverUrl, jobToken, positionStr);
	}

	private static boolean runServerStep(SSLFactory sslFactory, String serverUrl,
										 String jobToken, String positionStr) {
		List<Integer> position = parseStepPosition(positionStr);
		var actions = readJobData().getActions();
		var serverSideFacade = (ServerSideFacade) new CompositeFacade(actions).getFacade(position);
		TaskLogger logger = new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				KubernetesHelper.logger.info(message);
			}

		};
		return runServerStep(sslFactory, serverUrl, jobToken, position, serverSideFacade, getBuildDir(), logger);
	}

	public static boolean runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
										List<Integer> position, ServerSideFacade serverSideFacade,
										File buildDir, TaskLogger logger) {
		Map<String, String> placeholderValues = readPlaceholderValues(buildDir, serverSideFacade.getPlaceholders());
		File baseDir = new File(buildDir, "work");
		if (serverSideFacade.getSourcePath() != null)
			baseDir = new File(baseDir, replacePlaceholders(serverSideFacade.getSourcePath(), placeholderValues));
		
		var includeFiles = replacePlaceholders(serverSideFacade.getIncludeFiles(), placeholderValues);
		var excludeFiles = replacePlaceholders(serverSideFacade.getExcludeFiles(), placeholderValues);
		
		var result = runServerStep(sslFactory, serverUrl, jobToken, position, baseDir,
				includeFiles, excludeFiles, placeholderValues, logger);
		for (Map.Entry<String, byte[]> entry: result.getOutputFiles().entrySet()) {
			try {
				FileUtils.writeByteArrayToFile(
						new File(buildDir, entry.getKey()), 
						entry.getValue());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return result.isSuccessful();
	}
	
	public static ServerStepResult runServerStep(SSLFactory sslFactory, String serverUrl, String jobToken,
												 List<Integer> position, File baseDir,
												 Collection<String> includeFiles, Collection<String> excludeFiles,
												 Map<String, String> placeholderValues, TaskLogger logger) {
		Client client = buildRestClient(sslFactory);
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/run-server-step")
					.queryParam("jobToken", jobToken);
			Invocation.Builder builder = target.request();

			StreamingOutput output = os -> {
				writeInt(os, position.size());
				for (int each : position)
					writeInt(os, each);

				writeInt(os, placeholderValues.size());
				for (Map.Entry<String, String> entry : placeholderValues.entrySet()) {
					writeString(os, entry.getKey());
					writeString(os, entry.getValue());
				}

				TarUtils.tar(baseDir, includeFiles, excludeFiles, os, false);
			};

			try (Response response = builder.post(Entity.entity(output, MediaType.APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					while (readInt(is) == 1) {
						logger.log(readString(is));
					}
					byte[] bytes = new byte[readInt(is)];
					IOUtils.readFully(is, bytes);
					return deserialize(bytes);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			client.close();
		}
	}

	private static File getCacheInfosFile() {
		return new File(getMarkDir(), "cache-infos");
	}

	private static void writeCacheInfos(List<Triple<CacheConfigFacade, Date, Set<String>>> cacheInfos) {
		try {
			writeByteArrayToFile(getCacheInfosFile(), serialize((Serializable) cacheInfos));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static List<Triple<CacheConfigFacade, Date, Set<String>>> readCacheInfos() {
		var file = getCacheInfosFile();
		if (file.exists()) {
			try {
				return deserialize(readFileToByteArray((file)));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			return new ArrayList<>();
		}
	}

	private static File getCacheDir(String path) {
		if (FilenameUtils.getPrefixLength(path) > 0)
			return new File(path);
		else 
			return new File(getWorkDir(), path);
	}

	static void setupCache(String serverUrl, String jobToken, String positionStr) {
		var position = parseStepPosition(positionStr);
		var jobData = readJobData();
		var actions = jobData.getActions();
		var cacheConfig = ((SetupCacheFacade) new CompositeFacade(actions).getFacade(position)).getCacheConfig();

        cacheConfig.replacePlaceholders(getBuildDir());
		cacheConfig.computeChecksum(getWorkDir(), new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				KubernetesHelper.logger.info(message);
			}

		});
		
		var cachePaths = cacheConfig.getPaths();

		var sslFactory = buildSSLFactory(getTrustCertsDir());

		var exactMatchPaths = new HashSet<String>();
		for (var path: cachePaths) {
			File cacheDir = getCacheDir(path);
			FileUtils.createDir(cacheDir);
			var availability = downloadCache(serverUrl, jobToken, cacheConfig.getKey(),
					cacheConfig.getChecksum(), path, cacheDir, sslFactory);
			if (availability == CacheAvailability.EXACT_MATCH)
				logger.info("Exact matched " + cacheConfig.describe(path));
			else if (availability == CacheAvailability.PARTIAL_MATCH)
				logger.info("Partial matched " + cacheConfig.describe(path));

			if (availability == CacheAvailability.EXACT_MATCH)
				exactMatchPaths.add(path);
		}

		var cacheInfos = readCacheInfos();
		cacheInfos.add(new ImmutableTriple<>(cacheConfig, new Date(), exactMatchPaths));
		writeCacheInfos(cacheInfos);
	}

	public static Collection<String> parsePlaceholders(String string) {
		Collection<String> placeholderFiles = new HashSet<>();
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(string);  
        while (matcher.find())   
        	placeholderFiles.add(matcher.group(1));
		return placeholderFiles;
	}

	public static Map<String, String> readPlaceholderValues(File dir, Collection<String> placeholders) {
		Map<String, String> placeholderValues = new HashMap<>();
		for (String placeholder: placeholders) {
			File file = new File(dir, placeholder);
			if (file.exists()) {
				try {
					placeholderValues.put(placeholder, readFileToString(file, UTF_8).trim());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return placeholderValues;
	}

	public static void downloadDependencies(String serverUrl, String jobToken,
											File targetDir, SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/download-dependencies")
					.queryParam("jobToken", jobToken);
			Invocation.Builder builder =  target.request();
			try (Response response = builder.get()){
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					TarUtils.untar(is, targetDir, false);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			client.close();
		}
	}

	public static String replacePlaceholders(String string, Map<String, String> placeholderValues) {
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(string);  
        StringBuffer buffer = new StringBuffer();  
        while (matcher.find()) {  
        	String placeholder = matcher.group(1);
        	String placeholderValue = placeholderValues.get(placeholder);
        	if (placeholderValue != null) {
        		matcher.appendReplacement(buffer, Matcher.quoteReplacement(placeholderValue));
        	} else if (placeholder.startsWith(WORKDIR + "/")) {
        		throw new ExplicitException("Error replacing placeholder: unable to find file '" 
        				+ placeholder.substring(WORKDIR.length() + 1) + "' in workdir");
        	} else if (placeholder.startsWith(ATTRIBUTES + "/")) {
        		throw new ExplicitException("Error replacing placeholder: agent attribute '" 
        				+ placeholder.substring(ATTRIBUTES.length() + 1) + "' does not exist");
        	} else if (placeholder.equals(BUILD_VERSION)){ 
        		throw new ExplicitException("Error replacing placeholder: build version not set yet");
        	}
         }  
         matcher.appendTail(buffer);  
         return buffer.toString();
	}
	
	public static String replacePlaceholders(String string, File buildDir) {
		Collection<String> placeholders = parsePlaceholders(string);
		Map<String, String> placeholderValues = readPlaceholderValues(buildDir, placeholders);
		return replacePlaceholders(string, placeholderValues);
	}
	
	public static Collection<String> replacePlaceholders(Collection<String> collection, 
			Map<String, String> placeholderValues) {
		Collection<String> replacedCollection = new ArrayList<>();
		for (String each: collection) 
			replacedCollection.add(replacePlaceholders(each, placeholderValues));
		return replacedCollection;
	}
	
	public static Collection<String> replacePlaceholders(Collection<String> collection, File buildDir) {
		Collection<String> replacedCollection = new ArrayList<>();
		for (String each: collection)
			replacedCollection.add(replacePlaceholders(each, buildDir));
		return replacedCollection;
	}

	public static SSLFactory buildSSLFactory(File trustCertsDir) {
		SSLFactory.Builder builder = SSLFactory.builder().withDefaultTrustMaterial();
		if (trustCertsDir.exists()) {
			for (var file: trustCertsDir.listFiles()) {
				if (file.isFile() && !file.isHidden()) {
					String certContent = null;
					try {
						certContent = readFileToString(file, UTF_8);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					var certificates = CertificateUtils.parsePemCertificate(certContent);
					if (!certificates.isEmpty()) {
						builder.withTrustMaterial(certificates);
					} else {
						throw new ExplicitException("Base64 encoded PEM certificate beginning with -----BEGIN CERTIFICATE----- and ending with -----END CERTIFICATE----- is expected: " + file.getAbsolutePath());
					}
				}
			}

			HostnameVerifier basicVerifier = HostnameVerifierUtils.createBasic();
			HostnameVerifier fenixVerifier = HostnameVerifierUtils.createDefault();
			builder.withHostnameVerifier((hostname, session) -> basicVerifier.verify(hostname, session) || fenixVerifier.verify(hostname, session));
		}
		return builder.build();
	}

	public static CacheAvailability downloadCache(String serverUrl, String jobToken, String key,
										@Nullable String checksum, String path,
										File cacheDir, @Nullable SSLFactory sslFactory) {
		Client client = KubernetesHelper.buildRestClient(sslFactory);
		try {
			var target = client.target(serverUrl)
					.path("~api/k8s/download-cache")
					.queryParam("jobToken", jobToken)
					.queryParam("key", key)
					.queryParam("checksum", checksum)
					.queryParam("path", path);
			Invocation.Builder builder =  target.request();
			try (Response response = builder.get()){
				checkStatus(response);
				try (var is = response.readEntity(InputStream.class)) {
					var cacheAvailability = CacheAvailability.values()[is.read()];
					if (cacheAvailability != CacheAvailability.NOT_FOUND)
						TarUtils.untar(is, cacheDir, false);
					return cacheAvailability;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		} finally {
			client.close();
		}
	}

	public static boolean uploadCache(String serverUrl, String jobToken, CacheConfigFacade cacheConfig,
									  String path, File cacheDir, @Nullable SSLFactory sslFactory) {
		var key = cacheConfig.getKey();
		var checksum = cacheConfig.getChecksum();
		var projectPath = cacheConfig.getUploadProjectPath();
		var accessToken = cacheConfig.getUploadAccessToken();
		Client client = KubernetesHelper.buildRestClient(sslFactory);
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/k8s/upload-cache")
					.queryParam("jobToken", jobToken)
					.queryParam("projectPath", projectPath);
			Invocation.Builder builder = target.request();
			if (accessToken != null)
				builder.header(AUTHORIZATION, BEARER + " " + accessToken);
			try (Response response = builder.get()) {
				if (response.getStatus() == UNAUTHORIZED.getStatusCode())
					return false;
				checkStatus(response);
			}

			builder = target
					.queryParam("key", key)
					.queryParam("checksum", checksum)
					.queryParam("path", path)
					.request();
			if (accessToken != null)
				builder.header(AUTHORIZATION, BEARER + " " + accessToken);
			StreamingOutput output = os -> TarUtils.tar(cacheDir, os, false);
			try (Response response = builder.post(entity(output, APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				return true;
			}
		} finally {
			client.close();
		}
	}

	public static Client buildRestClient(@Nullable SSLFactory sslFactory) {
		var builder = ClientBuilder.newBuilder();
		if (sslFactory != null)
			builder.sslContext(sslFactory.getSslContext()).hostnameVerifier(sslFactory.getHostnameVerifier());
		return builder.build();
	}

	public static String formatDuration(long durationMillis) {
		if (durationMillis < 0)
			durationMillis = 0;
		return DurationFormatUtils.formatDurationWords(durationMillis, true, true);
	}

}
