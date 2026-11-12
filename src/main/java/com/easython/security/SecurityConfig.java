package com.easython.security;

import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.user.UserRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
public class SecurityConfig {

	private static final String[] PUBLIC = {
			"/api/v1/languages", "/api/v1/files/**", // files: 서명 URL 자체가 인증
			"/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/email/check",
			"/api/v1/auth/password/reset", "/api/v1/auth/password/reset-request",
			"/api/v1/admin/auth/login" };

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, JwtService jwt, UserRepository users,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
		http.csrf(c -> c.disable())
				.cors(Customizer.withDefaults())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers(PUBLIC).permitAll()
						.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				// 에러 응답은 GlobalExceptionHandler 한 곳에서 만들도록 위임
				.exceptionHandling(e -> e
						.authenticationEntryPoint((req, res, ex) ->
								resolver.resolveException(req, res, null, new ApiException(ErrorCode.UNAUTHORIZED)))
						.accessDeniedHandler((req, res, ex) ->
								resolver.resolveException(req, res, null, new ApiException(ErrorCode.FORBIDDEN))))
				.addFilterBefore(new JwtAuthFilter(jwt, users), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** 폼 로그인을 쓰지 않으므로 빈 UserDetailsService(자동 생성 비밀번호 로그 방지). */
	@Bean
	UserDetailsService noUsers() {
		return u -> { throw new UsernameNotFoundException(u); };
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
		CorsConfiguration c = new CorsConfiguration();
		c.setAllowedOrigins(origins);
		c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		c.setAllowedHeaders(List.of("*"));
		UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
		s.registerCorsConfiguration("/**", c);
		return s;
	}
}
