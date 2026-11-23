package com.javastorm.shop.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/** 员工账号：角色 ADMIN / CATALOG / SUPPORT / OPERATIONS；改密码、角色或停用时 credentialVersion 加一，旧登录失效。 */
@Entity
public class StaffAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 80)
    private String username;

    @JsonIgnore
    private String passwordHash;

    private String role;

    private boolean enabled = true;

    private int credentialVersion;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCredentialVersion() {
        return credentialVersion;
    }

    public void setCredentialVersion(int credentialVersion) {
        this.credentialVersion = credentialVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
