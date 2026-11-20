CREATE TABLE a2a_task (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    conversation_uuid VARCHAR(128) NOT NULL,
    agent_key VARCHAR(40) NOT NULL,
    caller VARCHAR(40) NOT NULL,
    parent_task_id VARCHAR(64),
    trace_id VARCHAR(64),
    state VARCHAR(40) NOT NULL,
    snapshot MEDIUMTEXT NOT NULL,
    input_snapshot MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_a2a_conversation (tenant_id, conversation_uuid, updated_at),
    INDEX idx_a2a_trace (tenant_id, trace_id)
);
CREATE TABLE a2a_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    snapshot MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_a2a_message (tenant_id, message_id),
    INDEX idx_a2a_message_task (tenant_id, task_id)
);
CREATE TABLE a2a_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    state VARCHAR(40) NOT NULL,
    snapshot MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_a2a_event_task (tenant_id, task_id, id)
);
