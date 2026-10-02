package io.tapstate.cloud.iac.ssh;

/**
 * Result of a remote command executed over an {@link SshSession}.
 *
 * @param exitCode remote process exit code (0 = success by convention)
 * @param stdout   captured standard output
 * @param stderr   captured standard error
 */
public record CommandResult(int exitCode, String stdout, String stderr) {

    /** Whether the remote process exited successfully. */
    public boolean succeeded() {
        return exitCode == 0;
    }
}
