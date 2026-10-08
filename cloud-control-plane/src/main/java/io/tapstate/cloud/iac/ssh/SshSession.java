package io.tapstate.cloud.iac.ssh;

import java.io.IOException;

/**
 * Authenticated SSH session against a Cluster node.
 * <p>
 * Supports the two operations the deployment needs: run a shell command and upload
 * a file over SFTP. Sessions hold network resources and must be closed after use.
 */
public interface SshSession extends AutoCloseable {

    /**
     * Execute a command and block until it finishes.
     *
     * @param command shell command line
     * @param timeout maximum time to wait for completion
     * @return captured exit code and output
     * @throws IOException when the session or command fails
     */
    CommandResult exec(String command, java.time.Duration timeout) throws IOException;

    /**
     * Upload file content to a remote absolute path, creating parent directories.
     *
     * @param remotePath absolute remote file path
     * @param content    file bytes to write
     * @throws IOException when the upload fails
     */
    void uploadFile(String remotePath, byte[] content) throws IOException;

    @Override
    void close() throws IOException;
}
