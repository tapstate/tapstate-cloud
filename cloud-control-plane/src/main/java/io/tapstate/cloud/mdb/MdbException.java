package io.tapstate.cloud.mdb;

/**
 * Checked exception for MDB allocation failures — wraps Atlas REST API errors,
 * capacity exhaustion, etc. Callers catch this and transition the allocation
 * record to {@link MdbAllocationStatus#PROVISIONING_FAILED}.
 */
public class MdbException extends RuntimeException {

    public MdbException(String message) { super(message); }
    public MdbException(String message, Throwable cause) { super(message, cause); }
}
