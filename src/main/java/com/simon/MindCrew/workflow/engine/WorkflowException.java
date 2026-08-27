package com.simon.MindCrew.workflow.engine;

/** 工作流执行/校验异常 */
public class WorkflowException extends RuntimeException {
    public WorkflowException(String message) { super(message); }
    public WorkflowException(String message, Throwable cause) { super(message, cause); }
}
