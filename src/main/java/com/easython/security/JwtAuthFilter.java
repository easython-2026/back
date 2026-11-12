package com.easython.security;

import com.easython.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bearer 액세스 토큰이 유효하면 인증 정보를 세팅. 유효하지 않으면 그냥 통과(보호 경로는 401이 됨). */
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
	private final JwtService jwt;
	private final UserRepository users;

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		String h = req.getHeader("Authorization");
		if (h != null && h.startsWith("Bearer ")) {
			jwt.parse(h.substring(7).trim())
					.filter(p -> JwtService.ACCESS.equals(p.type()))
					.flatMap(p -> users.findById(p.userId()))
					.filter(u -> u.getDeletedAt() == null)
					.ifPresent(u -> {
						var principal = new AuthUser(u.getId(), u.getRole().name(), u.getLanguage());
						SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
								principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
					});
		}
		chain.doFilter(req, res);
	}
}
