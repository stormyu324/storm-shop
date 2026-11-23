package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.PageDto;
import com.javastorm.shop.domain.AuditEntry;
import com.javastorm.shop.domain.StaffAccount;
import com.javastorm.shop.repository.AuditRepository;
import com.javastorm.shop.repository.StaffRepository;
import com.javastorm.shop.service.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
public class AdminStaffApi {
    private final StaffRepository staff;
    private final AuditRepository audit;
    private final PasswordEncoder passwords;
    private final String owner;

    public AdminStaffApi(
            StaffRepository staff,
            AuditRepository audit,
            PasswordEncoder passwords,
            @Value("${shop.admin.username:admin}") String owner) {
        this.staff = staff;
        this.audit = audit;
        this.passwords = passwords;
        this.owner = owner;
    }

    public record Request(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,80}") String username,
            @Size(max = 128) String password,
            @NotBlank String role,
            boolean enabled) {}

    public record View(
            Long id, String username, String role, boolean enabled, LocalDateTime createdAt) {}

    private View view(StaffAccount s) {
        return new View(s.getId(), s.getUsername(), s.getRole(), s.isEnabled(), s.getCreatedAt());
    }

    @GetMapping("/staff")
    public PageDto<View> list(@RequestParam(defaultValue = "0") int page) {
        return PageDto.of(
                staff.findAll(PageRequest.of(Math.max(0, page), 20, Sort.by("id").descending())),
                this::view);
    }

    @PostMapping("/staff")
    @Transactional
    public View create(@Valid @RequestBody Request req, Authentication auth) {
        return save(null, req, auth);
    }

    @PutMapping("/staff/{id}")
    @Transactional
    public View save(
            @PathVariable(required = false) Long id,
            @Valid @RequestBody Request req,
            Authentication auth) {
        if (!Set.of("ADMIN", "CATALOG", "SUPPORT", "OPERATIONS").contains(req.role())) {
            throw new BusinessException("角色无效");
        }
        if (owner.equals(req.username())) {
            throw new BusinessException("此账号由部署配置管理");
        }
        StaffAccount s =
                id == null
                        ? new StaffAccount()
                        : staff.lock(id).orElseThrow(() -> new BusinessException("员工不存在"));
        if (s.getId() != null && !s.getUsername().equals(req.username())) {
            throw new BusinessException("员工用户名不能修改");
        }
        if (auth != null
                && req.username().equals(auth.getName())
                && (!req.enabled() || !"ADMIN".equals(req.role()))) {
            throw new BusinessException("不能禁用自己或移除自己的管理员权限");
        }
        staff
                .findByUsername(req.username())
                .filter(other -> !Objects.equals(other.getId(), id))
                .ifPresent(
                        other -> {
                            throw new BusinessException("用户名已存在");
                        });
        if (req.password() != null && !req.password().isBlank()) {
            if (req.password().length() < 12
                    || req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new BusinessException("密码至少 12 个字符且不超过 72 字节");
            }
            s.setPasswordHash(passwords.encode(req.password()));
        } else if (id == null) {
            throw new BusinessException("请设置初始密码");
        }
        if (s.isEnabled() != req.enabled()
                || !Objects.equals(s.getRole(), req.role())
                || (req.password() != null && !req.password().isBlank())) {
            s.setCredentialVersion(s.getCredentialVersion() + 1);
        }
        s.setUsername(req.username());
        s.setRole(req.role());
        s.setEnabled(req.enabled());
        return view(staff.save(s));
    }

    @GetMapping("/audit")
    public PageDto<AuditEntry> audit(@RequestParam(defaultValue = "0") int page) {
        return PageDto.of(
                audit.findAll(PageRequest.of(Math.max(0, page), 30, Sort.by("id").descending())), a -> a);
    }
}
