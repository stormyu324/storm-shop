-- Storm 商城初始表结构（MySQL 8）

CREATE TABLE category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    name_en VARCHAR(50),
    sort_order INTEGER NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    sku VARCHAR(64) NOT NULL UNIQUE,
    category_id BIGINT,
    price DECIMAL(12, 2) NOT NULL,
    original_price DECIMAL(12, 2),
    stock INTEGER NOT NULL,
    description MEDIUMTEXT,
    parent_sku VARCHAR(64),
    title_en VARCHAR(200),
    description_en MEDIUMTEXT,
    bullet_points_en MEDIUMTEXT,
    brand VARCHAR(100),
    color VARCHAR(50),
    size VARCHAR(50),
    material VARCHAR(100),
    weight_grams INTEGER,
    length_cm DECIMAL(8, 1),
    width_cm DECIMAL(8, 1),
    height_cm DECIMAL(8, 1),
    barcode VARCHAR(32),
    price_usd DECIMAL(12, 2),
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    version BIGINT NOT NULL,
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE product_image (
    product_id BIGINT NOT NULL,
    url VARCHAR(1000),
    position INTEGER NOT NULL,
    PRIMARY KEY (product_id, position),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE customer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    customer_name VARCHAR(50) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    address VARCHAR(300) NOT NULL,
    note VARCHAR(500),
    country VARCHAR(2),
    total DECIMAL(12, 2) NOT NULL,
    currency VARCHAR(3),
    status VARCHAR(20) NOT NULL,
    payment_session_id VARCHAR(255),
    paid_at DATETIME(6),
    shipping_fee DECIMAL(12, 2),
    created_at DATETIME(6),
    email VARCHAR(255),
    carrier VARCHAR(255),
    tracking_number VARCHAR(255),
    tracking_url VARCHAR(1000),
    shipped_at DATETIME(6),
    coupon_id BIGINT,
    coupon_code VARCHAR(255),
    discount_amount DECIMAL(12, 2),
    coupon_released BIT(1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT,
    product_id BIGINT,
    product_name VARCHAR(200) NOT NULL,
    sku VARCHAR(64),
    unit_price DECIMAL(12, 2) NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES customer_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    kind VARCHAR(255),
    discount_value DECIMAL(12, 2),
    minimum DECIMAL(12, 2),
    currency VARCHAR(255),
    starts_at DATETIME(6),
    ends_at DATETIME(6),
    enabled BIT(1) NOT NULL,
    usage_limit INTEGER NOT NULL,
    used_count INTEGER NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE after_sale (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    reason VARCHAR(1000),
    decision_note VARCHAR(1000),
    state VARCHAR(255),
    refund_id VARCHAR(255),
    attempts INTEGER NOT NULL,
    refund_generation INTEGER NOT NULL,
    provider_failed BIT(1) NOT NULL,
    next_attempt DATETIME(6),
    created_at DATETIME(6),
    updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE after_sale_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT,
    state VARCHAR(255),
    note VARCHAR(1000),
    created_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE audit_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor VARCHAR(255),
    action VARCHAR(300),
    target VARCHAR(300),
    result INTEGER NOT NULL,
    created_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE staff_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    role VARCHAR(255),
    enabled BIT(1) NOT NULL,
    credential_version INTEGER NOT NULL,
    created_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE member_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    enabled BIT(1) NOT NULL,
    created_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE member_address (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT,
    name VARCHAR(255),
    phone VARCHAR(255),
    country VARCHAR(255),
    address VARCHAR(300),
    preferred BIT(1) NOT NULL,
    active BIT(1) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE member_challenge (
    id VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255),
    code_hash VARCHAR(255),
    attempts INTEGER NOT NULL,
    used BIT(1) NOT NULL,
    expires_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE member_favorite (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT,
    product_id BIGINT,
    active BIT(1) NOT NULL,
    CONSTRAINT uk_member_favorite_member_product UNIQUE (member_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE order_access (
    id VARCHAR(255) PRIMARY KEY,
    order_id BIGINT,
    code_hash VARCHAR(255),
    attempts INTEGER NOT NULL,
    consumed BIT(1) NOT NULL,
    expires_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE order_mail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_key VARCHAR(180) NOT NULL UNIQUE,
    recipient VARCHAR(254) NOT NULL,
    subject VARCHAR(255),
    body MEDIUMTEXT,
    attempts INTEGER NOT NULL,
    next_attempt DATETIME(6),
    sent_at DATETIME(6),
    expires_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE shipping_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    currency VARCHAR(3) NOT NULL UNIQUE,
    enabled BIT(1) NOT NULL,
    base_fee DECIMAL(12, 2) NOT NULL,
    included_grams INTEGER NOT NULL,
    extra_per_kg DECIMAL(12, 2),
    free_threshold DECIMAL(12, 2),
    zones_only BIT(1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE shipping_zone (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    currency VARCHAR(3) NOT NULL,
    name VARCHAR(50) NOT NULL,
    countries VARCHAR(1000) NOT NULL,
    base_fee DECIMAL(12, 2) NOT NULL,
    included_grams INTEGER NOT NULL,
    extra_per_kg DECIMAL(12, 2),
    free_threshold DECIMAL(12, 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
