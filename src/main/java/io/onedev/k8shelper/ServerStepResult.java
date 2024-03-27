package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class ServerStepResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final boolean successful;

    private final Map<String, byte[]> outputFiles;

    public ServerStepResult(boolean successful, Map<String, byte[]> outputFiles) {
        this.successful = successful;
        this.outputFiles = outputFiles;
    }

    public ServerStepResult(boolean successful) {
        this(successful, new HashMap<>());
    }

    public boolean isSuccessful() {
        return successful;
    }

    public Map<String, byte[]> getOutputFiles() {
        return outputFiles;
    }

}
