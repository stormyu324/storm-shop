package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.AdminDtos.LoginRequest;
import com.javastorm.shop.api.AdminDtos.SessionInfo;
import com.javastorm.shop.api.ApiError;
import com.javastorm.shop.config.LoginThrottle;
import com.javastorm.shop.config.SecurityConfig;
import com.javastorm.shop.config.TotpVerifier;
import com.javastorm.shop.repository.StaffRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 后台登录状态（Cookie 会话）。 */
@RestController
@RequestMapping("/api/admin/session")
@Tag(name = "后台 - 登录", description = "先 GET 拿 CSRF 令牌，再 POST 登录")
public class AdminSessionApi {

    private final AuthenticationProvider authProvider;
    private final StaffRepository staff;
    private final LoginThrottle throttle;
    private final String ownerUsername;
    private final boolean ownerPasswordWeak;
    private final String ownerTotpSecret;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AdminSessionApi(UserDetailsService users, PasswordEncoder encoder, StaffRepository staff,
                           LoginThrottle throttle,
                           @Value("${shop.admin.username}") String ownerUsername,
                           @Value("${shop.admin.password}") String ownerPassword,
                           @Value("${shop.admin.totp-secret:}") String ownerTotpSecret) {
        this.staff = staff;
        this.throttle = throttle;
        this.ownerUsername = ownerUsername;
        this.ownerPasswordWeak = SecurityConfig.isWeak(ownerPassword);
        this.ownerTotpSecret = ownerTotpSecret == null ? "" : ownerTotpSecret.trim();
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
    @Operation(summary = "登录", description = "需要请求头 X-XSRF-TOKEN；开启 ADMIN_TOTP_SECRET 时管理员登录还需请求头 X-Admin-TOTP")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                   @RequestHeader(value = "X-Admin-TOTP", required = false) String totpCode,
                                   CsrfToken csrf,
                                   HttpServletRequest request, HttpServletResponse response) {
        String address = request.getRemoteAddr();
        if (throttle.isBlocked(address, req.username())) {
            return ResponseEntity.status(429).body(ApiError.of("密码错误次数过多，请 15 分钟后再试"));
        }
        Authentication auth;
        try {
            auth = authProvider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(req.username(), req.password()));
        } catch (AuthenticationException e) {
            throttle.recordFailure(address, req.username());
            return ResponseEntity.status(401).body(ApiError.of("用户名或密码错误"));
        }
        if (!ownerTotpSecret.isBlank() && ownerUsername.equals(auth.getName())
                && !TotpVerifier.verify(ownerTotpSecret, totpCode, Instant.now())) {
            throttle.recordFailure(address, req.username());
            return ResponseEntity.status(401).body(ApiError.of("双因素动态验证码无效或缺失（请提供请求头 X-Admin-TOTP）"));
        }
        throttle.recordSuccess(address, req.username());
        // 店主账号还在用默认 / 过短密码时，只允许在本机登录，防止忘了改密码就直接放到服务器上
        if (ownerPasswordWeak && ownerUsername.equals(auth.getName()) && !isLoopback(address)) {
            return ResponseEntity.status(403).body(ApiError.of(
                    "后台还在使用默认或过短的密码，只能在本机登录。请在 config.env 里设置 ADMIN_PASSWORD（至少 8 位）后重启"));
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

    static boolean isLoopback(String address) {
        return address != null && (address.startsWith("127.") || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address));
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
