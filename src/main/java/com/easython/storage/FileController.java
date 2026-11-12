package com.easython.storage;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서명 URL로 증거 파일 내려주기 (로그인 불필요, 서명·만료로 보호). */
@RestController
@RequestMapping("/api/v1/files")
class FileController {
	private final FileStorage storage;

	FileController(FileStorage storage) {
		this.storage = storage;
	}

	@GetMapping("/{key:.+}")
	ResponseEntity<byte[]> get(@PathVariable String key, @RequestParam long exp, @RequestParam String sig) {
		byte[] body = storage.read(key, exp, sig);
		MediaType type = FileStorage.contentType(key).map(MediaType::parseMediaType).orElse(MediaType.APPLICATION_OCTET_STREAM);
		return ResponseEntity.ok().contentType(type).header("Cache-Control", "private, no-store").body(body);
	}
}
