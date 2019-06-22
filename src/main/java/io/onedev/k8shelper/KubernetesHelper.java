package io.onedev.k8shelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class KubernetesHelper {

	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";
	
	public static final String ENV_JOB_ID = "ONEDEV_JOB_ID";
	
	public static final String JOB_ID_HTTP_HEADER = "X-ONEDEV-JOB-ID";
	
	public static final String JOB_FINISH_FILE = "$OneDev-Job-Finished$";
	
	private static final String ENV_WORKSPACE = "ONEDEV_WORKSPACE";
	
	public static File getWorkspace() {
		String workspace = System.getenv(ENV_WORKSPACE);
		if (workspace != null) 
			return new File(workspace);
		else if (isWindows()) 
			return new File("C:\\onedev-workspace");
		else 
			return new File("/onedev-workspace");
	}
	
	public static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("windows");
	}
	
	private static LineConsumer newStdoutPrinter() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				System.out.println(line);
			}
			
		};
	}
	
	private static LineConsumer newStderrPrinter() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				System.err.println(line);
			}
			
		};
	}
	
	@SuppressWarnings("unchecked")
	public static void init(String serverUrl, String jobId, File workspace) {
		System.out.println("Initializing workspace from '" + serverUrl + "'...");
		
		Client client = ClientBuilder.newClient();
		try {
			WebTarget target = client.target(serverUrl).path("rest/k8s/job-context");
			Invocation.Builder builder =  target.request(MediaType.APPLICATION_JSON);
			builder.header(KubernetesHelper.JOB_ID_HTTP_HEADER, jobId);
			
			System.out.println("Retrieving job context...");
			
			Map<String, Object> jobContext;
			Response response = checkStatus(builder.get());
			try {
				String json = response.readEntity(String.class);
				jobContext = new ObjectMapper().readValue(json, Map.class);
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				response.close();
			}
			
			boolean cloneSource = (boolean) jobContext.get("cloneSource");
			if (cloneSource) {
				String projectName = (String) jobContext.get("projectName");
				String commitHash = (String) jobContext.get("commitHash");
				
				System.out.println("Cloning source code...");
				
				Commandline git = new Commandline("git");
				git.addArgs("init", ".");
				git.workingDir(workspace);
				git.execute(newStdoutPrinter(), newStderrPrinter()).checkReturnCode();
				
				git.clearArgs();
				String extraHeader = KubernetesHelper.JOB_ID_HTTP_HEADER + ": " + jobId;
				git.addArgs("-c", "http.extraHeader=" + extraHeader, "fetch", 
						serverUrl + "/projects/" + projectName, "--force", "--quiet", 
						/*"--depth=1",*/ commitHash);
				git.workingDir(workspace);
				git.execute(newStdoutPrinter(), newStderrPrinter()).checkReturnCode();
				
				git.clearArgs();
				git.addArgs("checkout", "--quiet", commitHash);
				git.workingDir(workspace);
				git.execute(newStdoutPrinter(), newStderrPrinter()).checkReturnCode();
			}	
			
			List<String> commands = (List<String>) jobContext.get("commands");
			
			System.out.println("Generating command script...");
			
			if (KubernetesHelper.isWindows()) {
				File scriptFile = new File(workspace, "onedev-job-commands.bat");
				try {
					FileUtils.writeLines(scriptFile, commands, "\r\n");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			} else {
				File scriptFile = new File(workspace, "onedev-job-commands.sh");
				try {
					FileUtils.writeLines(scriptFile, commands, "\n");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			
			System.out.println("Downloading job dependencies...");
			
			target = client.target(serverUrl).path("rest/k8s/download-dependencies");
			builder =  target.request(MediaType.APPLICATION_OCTET_STREAM);
			builder.header(KubernetesHelper.JOB_ID_HTTP_HEADER, jobId);
			response = checkStatus(builder.get());
			try {
				InputStream is = response.readEntity(InputStream.class);
				try {
					TarUtils.untar(is, workspace);
				} finally {
					try {
						is.close();
					} catch (IOException e) {
					}
				}
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}		
	}
	
	private static Response checkStatus(Response response) {
		int status = response.getStatus();
		if (status != 200) {
			String errorMessage = response.readEntity(String.class);
			if (StringUtils.isNotBlank(errorMessage)) {
				throw new RuntimeException(String.format("Http request failed (status code: %d, error message: %s)", 
						status, errorMessage));
			} else {
				throw new RuntimeException("Http request failed with status " + status 
						+ ", check server log for detaiils");
			}
		} else {
			return response;
		}
	}
	
	@SuppressWarnings("unchecked")
	public static void sidecar(String serverUrl, String jobId, File workspace) {
		System.out.println("Sending job outcomes to '" + serverUrl + "'...");
		
		Client client = ClientBuilder.newClient();
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl).path("rest/k8s/job-context");
			Invocation.Builder builder =  target.request(MediaType.APPLICATION_JSON);
			builder.header(KubernetesHelper.JOB_ID_HTTP_HEADER, jobId);
			
			File jobFinishedFile = new File(workspace, KubernetesHelper.JOB_FINISH_FILE);
			while (!jobFinishedFile.exists()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			
			System.out.println("Retrieving job context...");
			
			Map<String, Object> jobContext;
			Response response = checkStatus(builder.get());
			try {
				String json = response.readEntity(String.class);
				jobContext = new ObjectMapper().readValue(json, Map.class);
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				response.close();
			}
			
			List<String> includes = (List<String>) ((List<?>)jobContext.get("collectFiles.includes")).get(1);
			List<String> excludes = (List<String>) ((List<?>)jobContext.get("collectFiles.excludes")).get(1);
			
			System.out.println("Uploading job outcomes...");
			target = client.target(serverUrl).path("rest/k8s/upload-outcomes");

			StreamingOutput os = new StreamingOutput() {

				@Override
			   public void write(OutputStream os) throws IOException {
					TarUtils.tar(workspace, includes, excludes, os);
					os.flush();
			   }				   
			   
			};
			builder = target.request();
			builder.header(KubernetesHelper.JOB_ID_HTTP_HEADER, jobId);
			try {
				response = builder.post(Entity.entity(os, MediaType.APPLICATION_OCTET_STREAM_TYPE));
				checkStatus(response);
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}
}
