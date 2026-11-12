package com.easython.common;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/languages")
public class LanguageController {

	@GetMapping
	ApiResponse<Map<String, List<Languages.Language>>> list() {
		return ApiResponse.ok(Map.of("languages", Languages.ALL));
	}
}
