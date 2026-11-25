-- 每日支付对账异常记录表（兼容 H2 与 MySQL 8）
CREATE TABLE payment_reconciliation_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_date DATE NOT NULL,
    order_id BIGINT NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    payment_session_id VARCHAR(255),
    issue_type VARCHAR(40) NOT NULL,
    detail VARCHAR(1000),
    resolved BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6)
);

CREATE INDEX idx_recon_run_date ON payment_reconciliation_issue (run_date);
CREATE INDEX idx_recon_resolved_created ON payment_reconciliation_issue (resolved, created_at);
CREATE INDEX idx_recon_order_type ON payment_reconciliation_issue (order_id, issue_type);
