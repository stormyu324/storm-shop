-- 部分退款与退货：售后单记录退款金额和退货物流，订单记录累计已退金额，订单明细记录已退回入库的数量
ALTER TABLE after_sale ADD COLUMN refund_amount DECIMAL(12, 2);
ALTER TABLE after_sale ADD COLUMN return_required BIT(1) DEFAULT 0 NOT NULL;
ALTER TABLE after_sale ADD COLUMN return_carrier VARCHAR(100);
ALTER TABLE after_sale ADD COLUMN return_tracking VARCHAR(100);
ALTER TABLE after_sale ADD COLUMN returned_at DATETIME(6);
ALTER TABLE customer_order ADD COLUMN refunded_amount DECIMAL(12, 2) DEFAULT 0 NOT NULL;
ALTER TABLE order_item ADD COLUMN returned_quantity INTEGER DEFAULT 0 NOT NULL;
