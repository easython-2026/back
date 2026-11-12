package com.easython.security;

/** 인증된 요청의 principal. 컨트롤러에서 @AuthenticationPrincipal 로 받는다. */
public record AuthUser(String id, String role, String language) {}
