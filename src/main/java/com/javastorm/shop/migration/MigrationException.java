package com.javastorm.shop.migration;

/** 数据迁移不能继续，消息直接显示给店主。 */
public class MigrationException extends Exception {

    public MigrationException(String message) {
        super(message);
    }
}
