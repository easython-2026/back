package com.easython.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** 명세의 페이지네이션: page(1부터), size(기본 20, 최대 100), pageInfo{currentPage,totalPages,totalCount}. */
public record PageInfo(int currentPage, int totalPages, long totalCount) {

	public static PageInfo of(Page<?> p) {
		return new PageInfo(p.getNumber() + 1, p.getTotalPages(), p.getTotalElements());
	}

	/** 잘못된 값(0 이하, 100 초과)은 400. */
	public static Pageable request(Integer page, Integer size) {
		int pg = page == null ? 1 : page;
		int sz = size == null ? 20 : size;
		if (pg < 1 || sz < 1 || sz > 100) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		return PageRequest.of(pg - 1, sz);
	}
}
