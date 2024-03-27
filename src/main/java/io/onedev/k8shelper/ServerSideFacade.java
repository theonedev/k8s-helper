package io.onedev.k8shelper;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import org.apache.tools.ant.DirectoryScanner;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static io.onedev.k8shelper.KubernetesHelper.readPlaceholderValues;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;

public class ServerSideFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private transient Object step;
	
	private final String sourcePath;
	
	private final Set<String> includeFiles;
	
	private final Set<String> excludeFiles;
	
	private final Collection<String> placeholders;
	
	public ServerSideFacade(Object step, @Nullable String sourcePath, 
			Set<String> includeFiles, Set<String> excludeFiles,  Collection<String> placeholders) {
		this.step = step;
		this.sourcePath = sourcePath;
		this.includeFiles = includeFiles;
		this.excludeFiles = excludeFiles;
		this.placeholders = placeholders;
	}

	public Object getStep() {
		return step;
	}

	public String getSourcePath() {
		return sourcePath;
	}

	public Set<String> getIncludeFiles() {
		return includeFiles;
	}

	public Set<String> getExcludeFiles() {
		return excludeFiles;
	}

	public Collection<String> getPlaceholders() {
		return placeholders;
	}

	public boolean execute(File buildHome, Runner runner) {
		File filesDir = FileUtils.createTempDir();
		try {
			Collection<String> placeholders = getPlaceholders();
			Map<String, String> placeholderValues = readPlaceholderValues(buildHome, placeholders);
			
			File sourceDir = new File(buildHome, "workspace");
			if (getSourcePath() != null) {
				String sourcePath = replacePlaceholders(getSourcePath(), placeholderValues);
				if (sourcePath.contains(".."))
					throw new ExplicitException("Source path should not contain '..'");
				sourceDir = new File(sourceDir, sourcePath);
			}
			
			Collection<String> includeFiles = replacePlaceholders(getIncludeFiles(), placeholderValues);
			Collection<String> excludeFiles = replacePlaceholders(getExcludeFiles(), placeholderValues);

	    	DirectoryScanner scanner = new DirectoryScanner();
	    	scanner.setBasedir(sourceDir);
	    	scanner.setIncludes(includeFiles.toArray(new String[0]));
	    	scanner.setExcludes(excludeFiles.toArray(new String[0]));
	    	scanner.scan();
			
			for (String scanned: scanner.getIncludedFiles()) {
				try {
					FileUtils.copyFile(new File(sourceDir, scanned), new File(filesDir, scanned));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			var result = runner.run(filesDir, placeholderValues);
			
			for (Map.Entry<String, byte[]> entry: result.getOutputFiles().entrySet()) {
				FileUtils.writeByteArrayToFile(
						new File(buildHome, entry.getKey()),
						entry.getValue());
			}
			return result.isSuccessful();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			FileUtils.deleteDir(filesDir);
		}
	}
	
	public interface Runner {
		
		ServerStepResult run(File inputDir, Map<String, String> placeholderValues);
		
	}
	
}
