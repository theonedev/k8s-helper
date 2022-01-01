package io.onedev.k8shelper;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.SystemUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;

public class KubernetesHelper {

	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";
	
	public static final String ENV_JOB_TOKEN = "ONEDEV_JOB_TOKEN";
	
	public static final String BEARER = "Bearer";
	
	public static final String LOG_END_MESSAGE = "===== End of OneDev K8s Helper Log =====";
	
	public static final String BUILD_VERSION = "buildVersion";
	
	public static final String WORKSPACE = "workspace";
	
	public static final String ATTRIBUTES = "attributes";
	
	public static final String PLACEHOLDER_PREFIX = "<&onedev#";
	
	public static final String PLACEHOLDER_SUFFIX = "#onedev&>";
	
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(PLACEHOLDER_PREFIX + "(.*?)" + PLACEHOLDER_SUFFIX);
	
	private static final Logger logger = LoggerFactory.getLogger(KubernetesHelper.class);
	
	private static final Object cacheHomeCreationLock = new Object();
	
	private static File getBuildHome() {
		if (SystemUtils.IS_OS_WINDOWS) 
			return new File("C:\\onedev-build");
		else 
			return new File("/onedev-build");
	}
	
	private static File getJobDataFile() {
		return new File(getBuildHome(), "job-data");
	}
	
	private static File getTrustCertsHome() {
		if (SystemUtils.IS_OS_WINDOWS) 
			return new File("C:\\onedev-build\\trust-certs");
		else 
			return new File("/onedev-build/trust-certs");
	}
	
	private static File getCacheHome() {
		File file;
		if (SystemUtils.IS_OS_WINDOWS) 
			file = new File("C:\\onedev-build\\cache");
		else
			file = new File("/onedev-build/cache");
		if (!file.exists()) synchronized (cacheHomeCreationLock) {
			FileUtils.createDir(file);
		}
		return file;
	}
	
	private static File getWorkspace() {
		return new File(getBuildHome(), WORKSPACE);
	}
	
	private static File getCommandHome() {
		return new File(getBuildHome(), "command");
	}
	
	private static File getMarkHome() {
		return new File(getBuildHome(), "mark");
	}
	
	public static Map<CacheInstance, Date> getCacheInstances(File cacheHome) {
		Map<CacheInstance, Date> instances = new HashMap<>();
		
		FileFilter dirFilter = new FileFilter() {

			@Override
			public boolean accept(File pathname) {
				return pathname.isDirectory();
			}
			
		}; 
		for (File keyDir: cacheHome.listFiles(dirFilter)) {
			for (File instanceDir: keyDir.listFiles(dirFilter)) 
				instances.put(
						new CacheInstance(instanceDir.getName(), keyDir.getName()), 
						new Date(instanceDir.lastModified()));
		}
		
		return instances;
	}
	
	public static void checkCacheAllocations(File cacheHome, Map<CacheInstance, String> cacheAllocations, Consumer<File> cacheCleaner) {
		for (Iterator<Map.Entry<CacheInstance, String>> it = cacheAllocations.entrySet().iterator(); it.hasNext();) {
			Map.Entry<CacheInstance, String> entry = it.next();
			File cacheDirectory = entry.getKey().getDirectory(cacheHome);
			if (entry.getValue() != null) {
				if (!cacheDirectory.exists())
					FileUtils.createDir(cacheDirectory);
				File tempFile = null;
				try {
					tempFile = File.createTempFile("update-cache-last-modified", null, cacheDirectory);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					if (tempFile != null)
						tempFile.delete();
				}
			} else {
				if (cacheDirectory.exists()) {
					cacheCleaner.accept(cacheDirectory);
					FileUtils.deleteDir(cacheDirectory);
				}
				it.remove();
			}
		}
	}
	
	private static void generateCommandScript(List<Integer> position, String stepNames, 
			List<String> setupCommands, CommandExecutable commandExecutable, File workspace) {
		try {
			String positionStr = stringifyPosition(position);
			File commandHome = getCommandHome();
			File stepScriptFile = new File(commandHome, "step-" + positionStr + commandExecutable.getScriptExtension());
			FileUtils.writeLines(stepScriptFile, commandExecutable.getCommands(), commandExecutable.getEndOfLine());
			
 			if (SystemUtils.IS_OS_WINDOWS) { 
				StringBuilder escapedStepNames = new StringBuilder();
				for (int i=0; i<stepNames.length(); i++)
					escapedStepNames.append('^').append(stepNames.charAt(i));
				
				File setupScriptFile = new File(commandHome, "setup-" + positionStr + ".bat");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\r\n");
				
				File scriptFile = new File(commandHome, positionStr + ".bat");
				String markPrefix = getMarkHome().getAbsolutePath() + "\\" + positionStr;
				List<String> scriptContent = Lists.newArrayList(
						"@echo off",
						":wait",
						"if exist \"" + markPrefix + ".skip\" (",
						"  echo " + TaskLogger.wrapWithAnsiNotice("Step ^\"" + escapedStepNames + "^\" is skipped"),
						"  echo " + LOG_END_MESSAGE,
						"  goto :eof",
						")",
						"if exist \"" + markPrefix + ".error\" (",
						"  echo " + TaskLogger.wrapWithAnsiNotice("Running step ^\"" + escapedStepNames + "^\"..."),
						"  type " + markPrefix + ".error",
						"  copy /y nul " + markPrefix + ".failed > nul",
						"  echo " + LOG_END_MESSAGE,
						"  exit 1",
						")",
						"if exist \"" + markPrefix + ".start\" goto start",
						"ping 127.0.0.1 -n 2 > nul",
						"goto wait",
						":start",
						"cd " + workspace.getAbsolutePath() 
								+ " && cmd /c " + setupScriptFile.getAbsolutePath()
								+ " && cmd /c echo " + TaskLogger.wrapWithAnsiNotice("Running step ^\"" + escapedStepNames + "^\"...")
								+ " && " + commandExecutable.getInterpreter() + " " + stepScriptFile.getAbsolutePath(), 
						"set exit_code=%errorlevel%",
						"if \"%exit_code%\"==\"0\" (",
						"	echo " + TaskLogger.wrapWithAnsiSuccess("Step ^\"" + escapedStepNames + "^\" is successful"),
						"	copy /y nul " + markPrefix + ".successful > nul",
						") else (",
						"	copy /y nul " + markPrefix + ".failed > nul",
						")",
						"echo " + LOG_END_MESSAGE,
						"exit %exit_code%");
				FileUtils.writeLines(scriptFile, scriptContent, "\r\n");
			} else {
				String escapedStepNames = stepNames.replace("'", "'\\''");
				
				File setupScriptFile = new File(commandHome, "setup-" + positionStr + ".sh");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\n");
				
				File scriptFile = new File(commandHome, positionStr + ".sh");
				String markPrefix = getMarkHome().getAbsolutePath() + "/" + positionStr;
				List<String> wrapperScriptContent = Lists.newArrayList(
						"while [ ! -f " + markPrefix + ".start ] && [ ! -f " + markPrefix + ".skip ] && [ ! -f " + markPrefix + ".error ]",
						"do",
						"  sleep 0.1",
						"done",
						"if [ -f " + markPrefix + ".skip ]",
						"then",
						"  echo '" + TaskLogger.wrapWithAnsiNotice("Step \"" + escapedStepNames + "\" is skipped") + "'",
						"  echo " + LOG_END_MESSAGE,
						"  exit 0",
						"fi",
						"if [ -f " + markPrefix + ".error ]",
						"then",
						"  echo '" + TaskLogger.wrapWithAnsiNotice("Running step \"" + escapedStepNames + "\"...") + "'",
						"  cat " + markPrefix + ".error",
						"  touch " + markPrefix + ".failed",
						"  echo " + LOG_END_MESSAGE,
						"  exit 1",
						"fi",
						"cd " + workspace.getAbsolutePath() 
								+ " && sh " + setupScriptFile.getAbsolutePath()
								+ " && echo '" + TaskLogger.wrapWithAnsiNotice("Running step \"" + escapedStepNames + "\"...") + "'" 
								+ " && " + commandExecutable.getInterpreter() + " " + stepScriptFile.getAbsolutePath(), 
						"exitCode=\"$?\"", 
						"if [ $exitCode -eq 0 ]",
						"then",
						"  echo '" + TaskLogger.wrapWithAnsiSuccess("Step \"" + escapedStepNames + "\" is successful") + "'",
						"  touch " + markPrefix + ".successful",
						"else",
						"  touch " + markPrefix + ".failed",
						"fi",						
						"echo " + LOG_END_MESSAGE,
						"exit $exitCode");
				FileUtils.writeLines(scriptFile, wrapperScriptContent, "\n");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static void installJVMCert() {
		File trustCertsHome = getTrustCertsHome();
		if (trustCertsHome.exists()) {
			String keystore = System.getProperty("java.home") + "/lib/security/cacerts";
			for (File each: trustCertsHome.listFiles()) {
				if (each.isFile()) {
					Commandline keytool = new Commandline("keytool");
					keytool.addArgs("-import", "-alias", each.getName(), "-file", each.getAbsolutePath(), 
							"-keystore", keystore, "-storePass", "changeit", "-noprompt");
					
					keytool.execute(newInfoLogger(), new LineConsumer() {

						@Override
						public void consume(String line) {
							if (!line.contains("Warning: use -cacerts option to access cacerts keystore"))
								logger.error(TaskLogger.wrapWithAnsiError(line));
						}
						
					}).checkReturnCode();
				}
			}
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
	
	public static String encodeAsCommandArg(Collection<String> list) {
		Collection<String> base64 = new ArrayList<>();
		for (String each: list) 
			base64.add(Base64.getEncoder().encodeToString(each.getBytes(UTF_8)));
		String commandArg = StringUtils.join(base64, "-");
		if (commandArg.length() == 0)
			commandArg = "-";
		return commandArg;
	}
	
	public static Collection<String> decodeCommandArgAsCollection(String commandArg) {
		Collection<String> decoded = new HashSet<>();
		for (String each: Splitter.on('-').trimResults().omitEmptyStrings().split(commandArg)) 
			decoded.add(new String(Base64.getDecoder().decode(each), UTF_8));
		return decoded;
	}
	
	public static void installGitCert(File certFile, List<String> certLines, 
			Commandline git, LineConsumer infoLogger, LineConsumer errorLogger) {
		try {
			FileUtils.writeLines(certFile, certLines, "\n");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		git.clearArgs();
		git.addArgs("config", "--global", "http.sslCAInfo", certFile.getAbsolutePath());
		git.execute(infoLogger, errorLogger).checkReturnCode();
	}
	
	public static void init(String serverUrl, String jobToken, boolean test) {
		installJVMCert();
		
		Client client = ClientBuilder.newClient();
		try {
			File cacheHome = getCacheHome();
			FileUtils.createDir(getCommandHome());
			FileUtils.createDir(getMarkHome());
			if (test) {
				logger.info("Connecting to server '{}'...", serverUrl);
				WebTarget target = client.target(serverUrl).path("api/k8s/test");
				Invocation.Builder builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				try (Response response = builder.get()) {
					checkStatus(response);
				} 
				File tempFile = null;
				try {
					tempFile = File.createTempFile("test", null, cacheHome);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					if (tempFile != null)
						tempFile.delete();
				}
				FileUtils.createDir(getWorkspace());
				List<String> commands = new ArrayList<>();
				if (SystemUtils.IS_OS_WINDOWS)  
					commands.add("@echo off");
				commands.add("echo hello from container");
				generateCommandScript(Lists.newArrayList(0), "test", Lists.newArrayList(), 
						new CommandExecutable("any", commands, true), getWorkspace());
			} else {
				WebTarget target = client.target(serverUrl).path("api/k8s/job-data");
				Invocation.Builder builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				
				logger.info("Retrieving job data from {}...", serverUrl);
				
				JobData jobData;
				byte[] jobDataBytes;
				try (Response response = builder.post(
						Entity.entity(getWorkspace().getAbsolutePath(), MediaType.APPLICATION_OCTET_STREAM))) {
					checkStatus(response);
					jobDataBytes = response.readEntity(byte[].class);
				}
				
				FileUtils.writeByteArrayToFile(getJobDataFile(), jobDataBytes);
				jobData = SerializationUtils.deserialize(jobDataBytes);
				
				File workspace = getWorkspace();
				
				logger.info("Allocating job caches from {}...", serverUrl);
				target = client.target(serverUrl).path("api/k8s/allocate-job-caches");
				builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				Map<CacheInstance, String> cacheAllocations;
				try (Response response = builder.post(Entity.entity(
						new CacheAllocationRequest(new Date(), getCacheInstances(cacheHome)).toString(),
						MediaType.APPLICATION_OCTET_STREAM))) {
					checkStatus(response);
					cacheAllocations = SerializationUtils.deserialize(response.readEntity(byte[].class));
				}
				
				checkCacheAllocations(cacheHome, cacheAllocations, new Consumer<File>() {

					@Override
					public void accept(File dir) {
						FileUtils.cleanDir(dir);
					}
					
				});
				FileUtils.createDir(workspace);
				
				logger.info("Generating command scripts...");
				
				CompositeExecutable entryExecutable = new CompositeExecutable(jobData.getActions());
				entryExecutable.traverse(new LeafVisitor<Void>() {

					@Override
					public Void visit(LeafExecutable executable, List<Integer> position) {
						String stepNames = entryExecutable.getNamesAsString(position);

						List<String> setupCommands = new ArrayList<>();
						if (SystemUtils.IS_OS_WINDOWS) { 
							setupCommands.add("@echo off");							
							setupCommands.add("xcopy /Y /S /K /Q /H /R C:\\Users\\%USERNAME%\\auth-info\\* C:\\Users\\%USERNAME% > nul");
						} else { 
							setupCommands.add("cp -r -f -p /root/auth-info/. /root");
						}
						
						for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
							if (!PathUtils.isCurrent(entry.getValue())) {
								String link = PathUtils.resolve(workspace.getAbsolutePath(), entry.getValue()); 
								File linkTarget = entry.getKey().getDirectory(cacheHome);
								// create possible missing parent directories
								if (SystemUtils.IS_OS_WINDOWS) { 
									setupCommands.add(String.format("echo Setting up cache \"%s\"...", link));							
									setupCommands.add(String.format("if not exist \"%s\" mkdir \"%s\"", link, link)); 
									setupCommands.add(String.format("rmdir /q /s \"%s\"", link));							
									setupCommands.add(String.format("mklink /D \"%s\" \"%s\"", link, linkTarget.getAbsolutePath()));
								} else {
									setupCommands.add(String.format("echo Setting up cache \"%s\"...", link));							
									setupCommands.add(String.format("mkdir -p \"%s\"", link)); 
									setupCommands.add(String.format("rm -rf \"%s\"", link));
									setupCommands.add(String.format("ln -s \"%s\" \"%s\"", linkTarget.getAbsolutePath(), link));
								}
							} else {
								throw new ExplicitException("Invalid cache path: " + entry.getValue());
							}
						}
						
						String positionStr = stringifyPosition(position);

						File workingDir = getWorkspace();
						CommandExecutable commandExecutable;
						if (executable instanceof CommandExecutable) {
							commandExecutable = (CommandExecutable) executable;
						} else if (executable instanceof ContainerExecutable) {
							ContainerExecutable containerExecutable = (ContainerExecutable) executable;
							if (containerExecutable.getWorkingDir() != null)
								workingDir = new File(containerExecutable.getWorkingDir());
							// We will inspect container image and populate appropriate commands in sidecar as 
							// container images are not pulled at init stage
							commandExecutable = new CommandExecutable("any", Lists.newArrayList(), true);
						} else {
							String command;
							String classPath;
							if (SystemUtils.IS_OS_WINDOWS) 
								classPath = "C:\\k8s-helper\\*";
							else 
								classPath = "/k8s-helper/*";
							if (executable instanceof CheckoutExecutable) {
								CheckoutExecutable checkoutExecutable = (CheckoutExecutable) executable;
								checkoutExecutable.getCloneInfo();
								command = String.format("java -classpath \"%s\" io.onedev.k8shelper.CheckoutCode %s %b %b %d %s", 
										classPath, positionStr, checkoutExecutable.isWithLfs(), checkoutExecutable.isWithSubmodules(), 
										checkoutExecutable.getCloneDepth(), checkoutExecutable.getCloneInfo().toString());
							} else {
								ServerExecutable serverExecutable = (ServerExecutable) executable;
								
								String includeFiles = encodeAsCommandArg(serverExecutable.getIncludeFiles());
								String excludeFiles = encodeAsCommandArg(serverExecutable.getExcludeFiles());
								String placeholders = encodeAsCommandArg(serverExecutable.getPlaceholders());
								command = String.format("java -classpath \"%s\" io.onedev.k8shelper.RunServerStep %s %s %s %s", 
										classPath, positionStr, includeFiles, excludeFiles, placeholders);
							}							
							
							List<String> commands = new ArrayList<>();
							if (SystemUtils.IS_OS_WINDOWS)
								commands.add("@echo off");
							commands.add(command);
							
							commandExecutable = new CommandExecutable("any", commands, true);
						} 
						
						generateCommandScript(position, stepNames, setupCommands, commandExecutable, workingDir);
						
						return null;
					}
					
				}, new ArrayList<>());
				
				logger.info("Downloading job dependencies from {}...", serverUrl);
				
				target = client.target(serverUrl).path("api/k8s/download-dependencies");
				builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				
				try (Response response = builder.get()){
					checkStatus(response);
					try (InputStream is = response.readEntity(InputStream.class)) {
						FileUtils.untar(is, workspace, false);
					} 
				}
				logger.info("Job workspace initialized");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			client.close();
		}
	}
	
	public static String stringifyPosition(List<Integer> position) {
		return StringUtils.join(position, "-");
	}
	
	public static List<Integer> parsePosition(String position) {
		return Splitter.on('-').splitToList(position)
				.stream()
				.map(it->Integer.parseInt(it))
				.collect(Collectors.toList());
	}
	
	public static void checkStatus(Response response) {
		int status = response.getStatus();
		if (status != 200 && status != 204) {
			String errorMessage = response.readEntity(String.class);
			if (StringUtils.isNotBlank(errorMessage)) {
				throw new RuntimeException(String.format("Http request failed (status code: %d, error message: %s)", 
						status, errorMessage));
			} else {
				throw new RuntimeException("Http request failed with status " + status 
						+ ", check server log for detaiils");
			}
		} 
	}

	public static void cloneRepository(Commandline git, String cloneUrl, String remoteUrl, 
			String commitHash, boolean withLfs, boolean withSubmodules, int cloneDepth, 
			LineConsumer infoLogger, LineConsumer errorLogger) {
		git.clearArgs();
		if (!new File(git.workingDir(), ".git").exists()) {
			git.addArgs("init", ".");
			git.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.startsWith("Initialized empty Git repository"))
						infoLogger.consume(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.startsWith("hint:"))
						errorLogger.consume(line);
				}
				
			}).checkReturnCode();
		}								
		
		git.clearArgs();
		git.addArgs("fetch", cloneUrl, "--force", "--quiet");
		if (cloneDepth != 0)
			git.addArgs("--depth=" + cloneDepth);
		git.addArgs(commitHash);
		git.execute(infoLogger, errorLogger).checkReturnCode();

		AtomicBoolean originExists = new AtomicBoolean(false);
		git.clearArgs();
		git.addArgs("remote", "add", "origin", remoteUrl);
		ExecutionResult result = git.execute(infoLogger, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.equals("error: remote origin already exists."))
					originExists.set(true);
				else
					errorLogger.consume(line);
			}
			
		});
		if (!originExists.get())
			result.checkReturnCode();
		
		if (withLfs) {
			if (SystemUtils.IS_OS_MAC_OSX) {
				String path = System.getenv("PATH") + ":/usr/local/bin";
				git.environments().put("PATH", path);
			}
			
			git.clearArgs();
			git.addArgs("lfs", "install");
			git.execute(infoLogger, errorLogger).checkReturnCode();
		}
		
		git.clearArgs();
		git.addArgs("checkout", "--quiet", commitHash);
		git.execute(infoLogger, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("Filtering content:"))
					infoLogger.consume(line);
				else
					errorLogger.consume(line);
			}
			
		}).checkReturnCode();
		
		if (withSubmodules && new File(git.workingDir(), ".gitmodules").exists()) {
			// deinit submodules in case submodule url is changed
			git.clearArgs();
			git.addArgs("submodule", "deinit", "--all", "--force", "--quiet");
			git.execute(infoLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.contains("error: could not lock config file") && 
							!line.contains("warning: Could not unset core.worktree setting in submodule")) {
						errorLogger.consume(line);
					}
				}
				
			}).checkReturnCode();
			
			infoLogger.consume("Retrieving submodules...");
			
			git.clearArgs();
			git.addArgs("submodule", "update", "--init", "--recursive", "--force", "--quiet");
			if (cloneDepth != 0)
				git.addArgs("--depth=" + cloneDepth);						
			git.execute(infoLogger, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Submodule") && line.contains("registered for path")
							|| line.startsWith("From ") || line.startsWith(" * branch")
							|| line.startsWith(" +") && line.contains("->")) {
						infoLogger.consume(line);
					} else {
						errorLogger.consume(line);
					}
				}
				
			}).checkReturnCode();
		}
	}
	
	private static JobData readJobData() {
		byte[] jobDataBytes;
		try {
			jobDataBytes = FileUtils.readFileToByteArray(getJobDataFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return SerializationUtils.deserialize(jobDataBytes);
	}
	
	public static void testGitLfsAvailability(Commandline git, TaskLogger jobLogger) {
		File userHome = FileUtils.createTempDir("user");
		try {
			jobLogger.log("Checking if git-lfs exists...");
			git.clearArgs();
			git.environments().put("HOME", userHome.getAbsolutePath());
			if (SystemUtils.IS_OS_MAC_OSX) {
				String path = System.getenv("PATH") + ":/usr/local/bin";
				git.environments().put("PATH", path);
			}
			
			git.addArgs("lfs", "version");

			AtomicBoolean lfsExists = new AtomicBoolean(true);
			ExecutionResult result = git.execute(new LineConsumer() {

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
		} finally {
			FileUtils.deleteDir(userHome, 3);
		}
	}

	@Nullable
	private static ContainerCommand getContainerCommand(String image, Commandline inspect) {
		AtomicBoolean imageNotAvailable = new AtomicBoolean(false);
		
		StringBuilder builder = new StringBuilder();
		ExecutionResult result = inspect.addArgs("image", "inspect", image).execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				builder.append(line).append("\n");
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("Error: No such image:") 
						|| line.contains("[no such object:") 
						|| line.contains("open \\\\\\\\.\\\\pipe\\\\containerd-containerd: The system cannot find the file specified")
						|| line.contains("open //./pipe/docker_engine: The system cannot find the file specified")) {
					imageNotAvailable.set(true);
				} else {
					logger.error(TaskLogger.wrapWithAnsiError(line));
				}
			}
			
		});
		
 		if (!imageNotAvailable.get()) {
			result.checkReturnCode();
		
			try {
				JsonNode rootNode = new ObjectMapper().readTree(new StringReader(builder.toString()));
				for (JsonNode imageNode: rootNode) {
					JsonNode configNode = imageNode.get("Config");
					
					JsonNode entrypointNode = configNode.get("Entrypoint");
					List<String> entrypoint =  new ArrayList<>();
					if (entrypointNode != null && !entrypointNode.isNull()) {
						for (JsonNode elementNode: entrypointNode)
							entrypoint.add(elementNode.asText());
					}
					
					JsonNode cmdNode = configNode.get("Cmd");
					List<String> cmd =  new ArrayList<>();
					if (cmdNode != null && !cmdNode.isNull()) {
						for (JsonNode elementNode: cmdNode)
							cmd.add(elementNode.asText());
					}
					
					return new ContainerCommand(entrypoint, cmd);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
 		} 
 		return null;
	}
	
	private static String getContainerRunScript(String image, @Nullable String args) {
		while (true) {
			Commandline inspect = new Commandline("nerdctl").addArgs("-n", "k8s.io");
			ContainerCommand command = getContainerCommand(image, inspect);
			if (command == null) {
				inspect = new Commandline("docker");
				command = getContainerCommand(image, inspect);
			}
			if (command == null) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			} else {
				List<String> effectiveCommand = new ArrayList<>();
				List<String> parsedArgs;
				if (args != null) 
					parsedArgs = Arrays.asList(StringUtils.parseQuoteTokens(args));
				else
					parsedArgs = new ArrayList<>();
				
				if (!command.getEntrypoint().isEmpty()) {
					effectiveCommand.addAll(command.getEntrypoint());
					if (!parsedArgs.isEmpty())
						effectiveCommand.addAll(parsedArgs);
					else
						effectiveCommand.addAll(command.getCmd());
				} else if (!parsedArgs.isEmpty()) {
					effectiveCommand.addAll(parsedArgs);
				} else if (!command.getCmd().isEmpty()) {
					effectiveCommand.addAll(command.getCmd());
				} else {
					throw new ExplicitException("No command specified for image " + image);
				}
				
				if (SystemUtils.IS_OS_WINDOWS) {
					StringBuilder commandString = new StringBuilder("@echo off\r\n");
					for (String element: effectiveCommand) {
						if (element.contains(" "))
							commandString.append("\"" + element + "\" ");
						else
							commandString.append(element + " ");
					}
					return commandString.toString().trim();
				} else {
					StringBuilder commandString = new StringBuilder();
					for (String element: effectiveCommand) 
						commandString.append("\"" + StringUtils.replace(element, "\"", "\\\"") + "\" ");
					
					return ""
							+ "_sigterm() {\n"
							+ "  kill -TERM \"$child\"\n"
							+ "  wait \"$child\"\n"
							+ "  exit 1\n"
							+ "}\n"
							+ "\n"
							+ "trap _sigterm TERM\n"
							+ "trap _sigterm INT\n"
							+ "\n"
							+ commandString.toString().trim() + "&\n"
							+ "child=$!\n"
							+ "wait \"$child\"";
				}
			}
		}
	}
	
	public static void sidecar(String serverUrl, String jobToken, boolean test) {
		LeafHandler commandHandler = new LeafHandler() {

			@Override
			public boolean execute(LeafExecutable executable, List<Integer> position) {
				String positionStr = stringifyPosition(position);
				
				File file;
				
				File stepScriptFile = null;
				for (File eachFile: getCommandHome().listFiles()) {
					if (eachFile.getName().startsWith("step-" + positionStr + ".")) {
						stepScriptFile = eachFile;
						break;
					}
				}
				Preconditions.checkState(stepScriptFile != null);

				try {
					String stepScript = FileUtils.readFileToString(stepScriptFile, UTF_8);
					if (executable instanceof ContainerExecutable) {
						ContainerExecutable containerExecutable = (ContainerExecutable) executable;
						stepScript = getContainerRunScript(containerExecutable.getImage(), containerExecutable.getArgs());
					} else {
						stepScript = FileUtils.readFileToString(stepScriptFile, UTF_8);
					}

					stepScript = replacePlaceholders(stepScript, getBuildHome());
					
					FileUtils.writeFile(stepScriptFile, stepScript, UTF_8.name());
					
					file = new File(getMarkHome(), positionStr + ".start");
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (Exception e) {
					file = new File(getMarkHome(), positionStr + ".error");

					ExplicitException explicitException = ExceptionUtils.find(e, ExplicitException.class);
					String errorMessage;
					if (explicitException != null)
						errorMessage = explicitException.getMessage().trim();
					else 
						errorMessage = Throwables.getStackTraceAsString(e).trim();
					errorMessage += "\n";
					if (SystemUtils.IS_OS_WINDOWS)
						errorMessage = errorMessage.replace("\n", "\r\n");
					
					FileUtils.writeFile(file, errorMessage, UTF_8.name());
				}
			
				File successfulFile = new File(getMarkHome(), positionStr + ".successful");
				File failedFile = new File(getMarkHome(), positionStr + ".failed");
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
			public void skip(LeafExecutable executable, List<Integer> position) {
				File file = new File(getMarkHome(), stringifyPosition(position) + ".skip");
				try {
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			
		};
		
		if (test) {
			CommandExecutable executable = new CommandExecutable(
					"this does not matter", Lists.newArrayList("this does not matter"), false);
			executable.execute(commandHandler, Lists.newArrayList(0));
		} else {
			JobData jobData = readJobData();
			
			List<Action> actions = jobData.getActions();
			
			new CompositeExecutable(actions).execute(commandHandler, new ArrayList<>());
		} 
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
	
	public static void checkoutCode(String serverUrl, String jobToken, String positionStr, 
			boolean withLfs, boolean withSubmodules, int cloneDepth, CloneInfo cloneInfo) throws IOException {
		JobData jobData = readJobData();
		
		logger.info("Checking out code from {}...", cloneInfo.getCloneUrl());

		LineConsumer infoLogger = newInfoLogger();
		LineConsumer errorLogger = newErrorLogger();
		
		File userHome;
		if (SystemUtils.IS_OS_WINDOWS)
			userHome = new File(System.getProperty("user.home"));
		else
			userHome = new File("/root");
		
		File workspace = getWorkspace();
		Commandline git = new Commandline("git").workingDir(workspace);
		
		cloneInfo.writeAuthData(userHome, git, infoLogger, errorLogger);

		// Also populate auth info into authInfoHome which will be shared 
		// with other containers. The setup script of other contains will 
		// move all auth data from authInfoHome into the user home so that 
		// git pull/push can be done without asking for credentials
		File authInfoHome = new File(userHome, "auth-info");
		Commandline anotherGit = new Commandline("git");
		anotherGit.environments().put("HOME", authInfoHome.getAbsolutePath());
		cloneInfo.writeAuthData(authInfoHome, anotherGit, infoLogger, errorLogger);
		
		File trustCertsHome = getTrustCertsHome();
		if (trustCertsHome.exists()) {
			List<String> trustCertContent = new ArrayList<>();
			for (File file: trustCertsHome.listFiles()) {
				if (file.isFile()) 
					trustCertContent.addAll(FileUtils.readLines(file, Charset.defaultCharset()));
			}
			installGitCert(new File(getBuildHome(), "trust-cert.pem"), trustCertContent, git, 
					infoLogger, errorLogger);
		}
		
		cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(), 
				jobData.getCommitHash(), withLfs, withSubmodules, cloneDepth, 
				infoLogger, errorLogger);
	}
	
	public static void runServerStep(String serverUrl, String jobToken, String positionStr, 
			String encodedIncludeFiles, String encodedExcludeFiles, String encodedPlaceholders) {
		installJVMCert();

		List<Integer> position = parsePosition(positionStr);
		Collection<String> includeFiles = decodeCommandArgAsCollection(encodedIncludeFiles);
		Collection<String> excludeFiles = decodeCommandArgAsCollection(encodedExcludeFiles);
		Collection<String> placeholders = decodeCommandArgAsCollection(encodedPlaceholders);
		
		TaskLogger logger = new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				KubernetesHelper.logger.info(message);
			}
			
		};
		runServerStep(serverUrl, jobToken, position, includeFiles, excludeFiles, placeholders, 
				getBuildHome(), getWorkspace(), logger);
	}

	public static void runServerStep(String serverUrl, String jobToken, List<Integer> position, 
			Collection<String> includeFiles, Collection<String> excludeFiles, 
			Collection<String> placeholders, File buildHome, File workspace, TaskLogger logger) {
		Client client = ClientBuilder.newClient();
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl).path("api/k8s/run-server-step");
			Invocation.Builder builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
			
			Map<String, String> placeholderValues = readPlaceholderValues(buildHome, placeholders);

			StreamingOutput os = new StreamingOutput() {

				@Override
			   public void write(OutputStream os) throws IOException {
					writeInt(os, position.size());
					for (int each: position) 
						writeInt(os, each);
					
					writeInt(os, placeholderValues.size());
					for (Map.Entry<String, String> entry: placeholderValues.entrySet()) {
						writeString(os, entry.getKey());
						writeString(os, entry.getValue());
					}
					
					FileUtils.tar(
							workspace, 
							replacePlaceholders(includeFiles, placeholderValues), 
							replacePlaceholders(excludeFiles, placeholderValues), 
							os, false);
			   }				   
			   
			};
			
			try (Response response = builder.post(Entity.entity(os, MediaType.APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					while (readInt(is) == 1) {
						logger.log(readString(is));
					}
					byte[] bytes = new byte[readInt(is)];
					IOUtils.readFully(is, bytes);
					Map<String, byte[]> files = SerializationUtils.deserialize(bytes);
					for (Map.Entry<String, byte[]> entry: files.entrySet()) {
						try {
							FileUtils.writeByteArrayToFile(
									new File(buildHome, entry.getKey()), 
									entry.getValue());
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				} 
			}
		} finally {
			client.close();
		}
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
					placeholderValues.put(placeholder, FileUtils.readFileToString(file, UTF_8).trim());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return placeholderValues;
	}
	
	public static String replacePlaceholders(String string, Map<String, String> placeholderValues) {
		Matcher matcher = PLACEHOLDER_PATTERN.matcher(string);  
        StringBuffer buffer = new StringBuffer();  
        while (matcher.find()) {  
        	String placeholder = matcher.group(1);
        	String placeholderValue = placeholderValues.get(placeholder);
        	if (placeholderValue != null) {
        		matcher.appendReplacement(buffer, placeholderValue);
        	} else if (placeholder.startsWith(WORKSPACE + "/")) {
        		throw new ExplicitException("Error replacing placeholder: unable to find file '" 
        				+ placeholder.substring(WORKSPACE.length()+1) + "' in workspace");
        	} else if (placeholder.startsWith(ATTRIBUTES + "/")) {
        		throw new ExplicitException("Error replacing placeholder: agent attribute '" 
        				+ placeholder.substring(ATTRIBUTES.length()+1) + "' does not exist");
        	} else if (placeholder.equals(BUILD_VERSION)){ 
        		throw new ExplicitException("Error replacing placeholder: build version not set yet");
        	}
         }  
         matcher.appendTail(buffer);  
         return buffer.toString();
	}
	
	public static String replacePlaceholders(String string, File buildHome) {
		Collection<String> placeholders = parsePlaceholders(string);
		Map<String, String> placeholderValues = readPlaceholderValues(buildHome, placeholders);
		return replacePlaceholders(string, placeholderValues);
	}
	
	public static Collection<String> replacePlaceholders(Collection<String> collection, 
			Map<String, String> placeholderValues) {
		Collection<String> replacedCollection = new ArrayList<>();
		for (String each: collection) 
			replacedCollection.add(replacePlaceholders(each, placeholderValues));
		return replacedCollection;
	}
	
	public static Collection<String> replacePlaceholders(Collection<String> collection, File buildHome) {
		Collection<String> replacedCollection = new ArrayList<>();
		for (String each: collection)
			replacedCollection.add(replacePlaceholders(each, buildHome));
		return replacedCollection;
	}
	
}
