package com.easython.complaint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PDF 에 임베드할 한글(+베트남어 등 라틴 확장) 폰트 탐색. 폰트 파일은 저장소에 넣지 않는다(라이선스·용량).
 * 우선순위: PDF_FONT_PATH 환경변수 → classpath fonts/NotoSansKR-Regular.ttf → 시스템 폰트.
 * 배포 서버(Linux)에는 Noto Sans KR(OFL) TTF 를 내려받아 PDF_FONT_PATH 로 지정하는 것을 권장(베트남어 글리프 포함).
 * ponytail: .ttf/.otf 만 지원(.ttc 미지원).
 */
@Component
public class PdfFonts {
	private static final String CLASSPATH_FONT = "fonts/NotoSansKR-Regular.ttf";
	private static final List<String> SYSTEM_CANDIDATES = List.of(
			"C:/Windows/Fonts/malgun.ttf",
			"/usr/share/fonts/truetype/noto/NotoSansKR-Regular.ttf",
			"/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
			"/usr/share/fonts/nanum/NanumGothic.ttf");

	private final String configured;

	public PdfFonts(@Value("${app.pdf.font-path:}") String configured) {
		this.configured = configured;
	}

	public boolean available() {
		return locate().isPresent() || getClass().getClassLoader().getResource(CLASSPATH_FONT) != null;
	}

	PDType0Font load(PDDocument doc) throws IOException {
		if (!configured.isBlank() && Files.isRegularFile(Path.of(configured))) return PDType0Font.load(doc, Path.of(configured).toFile());
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(CLASSPATH_FONT)) {
			if (in != null) return PDType0Font.load(doc, in);
		}
		Path p = locate().orElseThrow(() -> new IllegalStateException(
				"PDF 폰트를 찾을 수 없습니다. PDF_FONT_PATH 에 한글 지원 TTF(예: Noto Sans KR)를 지정하세요."));
		return PDType0Font.load(doc, p.toFile());
	}

	private Optional<Path> locate() {
		if (!configured.isBlank() && Files.isRegularFile(Path.of(configured))) return Optional.of(Path.of(configured));
		return SYSTEM_CANDIDATES.stream().map(Path::of).filter(Files::isRegularFile).findFirst();
	}
}
