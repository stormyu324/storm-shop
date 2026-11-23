package com.javastorm.shop.config;

import com.javastorm.shop.repository.StaffRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class StaffSessionFilter extends OncePerRequestFilter {
  private final StaffRepository staff;

  public StaffSessionFilter(StaffRepository staff) {
    this.staff = staff;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var context = SecurityContextHolder.getContext();
    var auth = context.getAuthentication();
    if (auth != null && auth.isAuthenticated())
      staff
          .findByUsername(auth.getName())
          .ifPresent(
              account -> {
                var session = req.getSession(false);
                if (!account.enabled
                    || session == null
                    || !Integer.valueOf(account.credentialVersion)
                        .equals(session.getAttribute("staffCredentialVersion"))) {
                  context.setAuthentication(null);

                  if (session != null) session.removeAttribute("SPRING_SECURITY_CONTEXT");
                } else {
                  context.setAuthentication(
                      UsernamePasswordAuthenticationToken.authenticated(
                          account.username,
                          null,
                          AuthorityUtils.createAuthorityList(
                              "ROLE_STAFF", "ROLE_" + account.role)));
                }
              });
    chain.doFilter(req, res);
  }
}
