-- 生产级复合索引：支撑日均 1,000-2,000 单及数十万历史订单下的高频查询与统计

-- 订单查询、超时清理、Webhook 核对、会员历史订单与运营统计
CREATE INDEX idx_order_status_created ON customer_order (status, created_at);
CREATE INDEX idx_order_email_created ON customer_order (email, created_at);
CREATE INDEX idx_order_created_at ON customer_order (created_at);
CREATE INDEX idx_order_payment_session ON customer_order (payment_session_id);

-- 订单明细与热销统计
CREATE INDEX idx_order_item_order_id ON order_item (order_id);
CREATE INDEX idx_order_item_product_id ON order_item (product_id);

-- 商品前台浏览、变体聚合、低库存预警
CREATE INDEX idx_product_status_category ON product (status, category_id);
CREATE INDEX idx_product_parent_sku_status ON product (parent_sku, status);
CREATE INDEX idx_product_status_stock ON product (status, stock);

-- 邮件发件箱轮询
CREATE INDEX idx_order_mail_pending ON order_mail (sent_at, next_attempt, attempts);

-- 售后查询与退款重试
CREATE INDEX idx_after_sale_order_id ON after_sale (order_id);
CREATE INDEX idx_after_sale_state_next ON after_sale (state, next_attempt);
CREATE INDEX idx_after_sale_history_case ON after_sale_history (case_id);

-- 后台审计日志
CREATE INDEX idx_audit_created_at ON audit_entry (created_at);
CREATE INDEX idx_audit_actor_created ON audit_entry (actor, created_at);

-- 会员地址、验证码与运费分区
CREATE INDEX idx_member_address_member ON member_address (member_id, active);
CREATE INDEX idx_member_challenge_email ON member_challenge (email, expires_at);
CREATE INDEX idx_order_access_order ON order_access (order_id, expires_at);
CREATE INDEX idx_shipping_zone_currency ON shipping_zone (currency);
