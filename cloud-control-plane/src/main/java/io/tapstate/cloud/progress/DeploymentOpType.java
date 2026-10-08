package io.tapstate.cloud.progress;

/**
 * Deployment operation category — distinguishes the initial subscription
 * deployment from the release/reclamation after a subscription is cancelled.
 */
public enum DeploymentOpType {

    /** Initial deployment — EC2 node, DNS, Atlas databases, runtime bring-up. */
    INSTALL,

    /** Release and reclamation — CloudFormation stack, DNS, databases and user teardown. */
    UNINSTALL
}
