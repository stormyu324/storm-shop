package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.AdminDtos.LoginRequest;
import com.javastorm.shop.api.AdminDtos.SessionInfo;
import com.javastorm.shop.api.ApiError;
import com.javastorm.shop.repository.StaffRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** 后台登录状态（Cookie 会话）。 */
@RestController
@RequestMapping("/api/admin/session")
@Tag(name = "后台 - 登录", description = "先 GET 拿 CSRF 令牌，再 POST 登录")
public class AdminSessionApi {

    private final AuthenticationProvider authProvider;
    private final StaffRepository staff;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AdminSessionApi(UserDetailsService users, PasswordEncoder encoder, StaffRepository staff) {
        this.staff = staff;
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        this.authProvider = provider;
    }

    @GetMapping
    @Operation(summary = "当前登录状态", description = "同时返回 CSRF 令牌，并写入 Cookie XSRF-TOKEN")
    public SessionInfo current(Authentication auth, CsrfToken csrf) {
        return info(auth, csrf);
    }

    @PostMapping
    @Operation(summary = "登录", description = "需要请求头 X-XSRF-TOKEN；成功后登录状态保存在 Cookie 会话里")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, CsrfToken csrf,
                                   HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            auth = authProvider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(req.username(), req.password()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(401).body(ApiError.of("用户名或密码错误"));
        }
        request.getSession(true);
        staff.findByUsername(auth.getName()).ifPresent(account ->
                request.getSession().setAttribute("staffCredentialVersion", account.getCredentialVersion()));
        request.changeSessionId(); // 防止会话固定攻击
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return ResponseEntity.ok(info(auth, csrf));
    }

    @DeleteMapping
    @Operation(summary = "退出登录")
    public ResponseEntity<Void> logout(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, auth);
        return ResponseEntity.noContent().build();
    }

    private static SessionInfo info(Authentication auth, CsrfToken csrf) {
        boolean admin = auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream()
                        .anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_STAFF").contains(a.getAuthority()));
        List<String> roles = admin
                ? auth.getAuthorities().stream().map(a -> a.getAuthority().replace("ROLE_", "")).toList()
                : List.of();
        return new SessionInfo(admin, admin ? auth.getName() : null, csrf == null ? null : csrf.getToken(), roles);
    }
}
