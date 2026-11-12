package com.easython.complaint;

import com.easython.complaint.ComplaintDtos.Detail;
import com.easython.report.EvidenceService.EvidenceView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 진정서 PDF: ① 한국어 진정서와 모국어 번역본 나란히 대조 ② 증거 목록(종류·일시·해시) ③ 법률 면책 문구.
 * 레이아웃은 단순 텍스트 흐름(줄바꿈·쪽 넘김)만 지원한다.
 */
@Component
public class ComplaintPdf {
	private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final float W = PDRectangle.A4.getWidth();
	private static final float H = PDRectangle.A4.getHeight();
	private static final float M = 50, BOTTOM = 55, GAP = 14;
	private static final float COL_W = (W - 2 * M - GAP) / 2;
	private static final float X1 = M, X2 = M + COL_W + GAP;

	private final PdfFonts fonts;
	private final MessageSource messages;

	public ComplaintPdf(PdfFonts fonts, MessageSource messages) {
		this.fonts = fonts;
		this.messages = messages;
	}

	/** koEvidences: 한국어 제목의 증거 목록(노동청 제출용이라 한국어로). */
	public byte[] render(Detail d, List<EvidenceView> koEvidences) {
		try (PDDocument doc = new PDDocument()) {
			Canvas c = new Canvas(doc, fonts.load(doc));
			String lang = d.draft().translated() == null ? "ko" : d.draft().translated().language();
			boolean twin = !"ko".equals(lang) && d.draft().translated() != null;
			Locale ko = Locale.KOREAN, tr = Locale.forLanguageTag(lang);

			// ① 제목
			c.pair(d.draft().ko().title(), twin ? d.draft().translated().title() : "", 17, true);
			c.gap(6);
			// 당사자·기간
			c.pair(label("pdf.complainant", ko) + ": " + d.complainant().name(), twin ? label("pdf.complainant", tr) + ": " + d.complainant().name() : "", 10, false);
			c.pair(label("pdf.respondent", ko) + ": " + d.respondent().name() + " / " + d.respondent().ownerName(),
					twin ? label("pdf.respondent", tr) + ": " + d.respondent().name() + " / " + d.respondent().ownerName() : "", 10, false);
			c.pair(label("pdf.address", ko) + ": " + d.respondent().address(), twin ? label("pdf.address", tr) + ": " + d.respondent().address() : "", 10, false);
			c.pair(label("pdf.period", ko) + ": " + d.periodStart() + " ~ " + d.periodEnd(), twin ? label("pdf.period", tr) + ": " + d.periodStart() + " ~ " + d.periodEnd() : "", 10, false);
			c.pair(label("pdf.unpaid", ko) + ": " + won(d.draft().ko().unpaidAmount()), twin ? label("pdf.unpaid", tr) + ": " + won(d.draft().ko().unpaidAmount()) : "", 10, true);
			c.gap(8);

			// 본문: 문단 단위로 한·모국어 짝을 맞춘다
			String[] kp = d.draft().ko().body().split("\n\n");
			String[] tp = twin ? d.draft().translated().body().split("\n\n") : new String[0];
			for (int i = 0; i < kp.length; i++) c.pair(kp[i], i < tp.length ? tp[i] : "", 9.5f, false);
			c.gap(6);

			// 체불 내역
			c.pair(label("pdf.claims", ko), twin ? label("pdf.claims", tr) : "", 11, true);
			for (var cl : d.draft().ko().claims()) c.row(new float[] { X1, X2 }, new float[] { COL_W + 30, 90 }, new String[] { cl.label(), won(cl.amount()) }, 9.5f);
			c.gap(10);

			// ② 증거 목록
			c.heading(label("pdf.evidences", ko) + (twin ? " / " + label("pdf.evidences", tr) : "") + " (" + koEvidences.size() + ")", 11);
			float[] xs = { M, M + 85, M + 175, M + 285 };
			float[] ws = { 80, 85, 105, W - 2 * M - 285 };
			c.row(xs, ws, new String[] { label("pdf.type", ko), label("pdf.date", ko), label("pdf.item", ko), "SHA-256" }, 8);
			for (EvidenceView e : koEvidences) {
				c.row(xs, ws, new String[] { typeLabel(e.type(), ko), e.occurredAt().format(DT), e.title(), e.hash() == null ? "-" : e.hash() }, 7);
			}
			c.gap(12);

			// ③ 면책
			c.pair(d.disclaimer(), "", 9, true);
			if (twin) c.pair(messages.getMessage("disclaimer.default", null, tr), "", 9, true);
			c.close();

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("PDF 생성 실패", e);
		}
	}

	private String label(String key, Locale l) {
		return messages.getMessage(key, null, key, l);
	}

	private String typeLabel(String type, Locale l) {
		return messages.getMessage("ev.type." + type, null, type, l);
	}

	private static String won(Integer v) {
		return String.format("%,d", v == null ? 0 : v);
	}

	/** 줄바꿈·쪽 넘김을 처리하는 아주 작은 텍스트 캔버스. */
	private static final class Canvas {
		private final PDDocument doc;
		private final PDType0Font font;
		private PDPage page;
		private PDPageContentStream cs;
		private float y;

		Canvas(PDDocument doc, PDType0Font font) throws IOException {
			this.doc = doc;
			this.font = font;
			newPage();
		}

		void newPage() throws IOException {
			if (cs != null) cs.close();
			page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			cs = new PDPageContentStream(doc, page);
			y = H - M;
		}

		void close() throws IOException {
			cs.close();
		}

		void gap(float h) {
			y -= h;
		}

		void heading(String text, float size) throws IOException {
			for (String line : wrap(text, W - 2 * M, size)) {
				ensure(size * 1.5f);
				draw(line, M, size);
				y -= size * 1.5f;
			}
			y -= 2;
		}

		/** 왼쪽·오른쪽 문단을 같은 높이에서 나란히(오른쪽이 비면 왼쪽만). 쪽을 넘겨도 줄 단위로 이어서 그린다. */
		void pair(String left, String right, float size, boolean bold) throws IOException {
			boolean twin = right != null && !right.isBlank();
			List<String> l = wrap(left, twin ? COL_W : W - 2 * M, size);
			List<String> r = twin ? wrap(right, COL_W, size) : List.of();
			float lh = size * 1.45f;
			for (int i = 0; i < Math.max(l.size(), r.size()); i++) {
				ensure(lh);
				if (i < l.size()) draw(l.get(i), X1, size);
				if (i < r.size()) draw(r.get(i), X2, size);
				y -= lh;
			}
			y -= bold ? 3 : 5;
		}

		/** 표 한 행: 셀마다 폭에 맞춰 줄바꿈하고 가장 긴 셀 높이만큼 내려간다. */
		void row(float[] xs, float[] ws, String[] cells, float size) throws IOException {
			List<List<String>> cols = new ArrayList<>();
			int lines = 1;
			for (int i = 0; i < cells.length; i++) {
				cols.add(wrap(cells[i], ws[i], size));
				lines = Math.max(lines, cols.get(i).size());
			}
			float lh = size * 1.4f;
			ensure(lh * lines);
			for (int i = 0; i < cells.length; i++) {
				for (int j = 0; j < cols.get(i).size(); j++) drawAt(cols.get(i).get(j), xs[i], y - j * lh, size);
			}
			y -= lh * lines + 2;
		}

		private void ensure(float h) throws IOException {
			if (y - h < BOTTOM) newPage();
		}

		private void draw(String s, float x, float size) throws IOException {
			drawAt(s, x, y, size);
		}

		private void drawAt(String s, float x, float yy, float size) throws IOException {
			cs.beginText();
			cs.setFont(font, size);
			cs.newLineAtOffset(x, yy - size);
			cs.showText(safe(s));
			cs.endText();
		}

		/** 폰트에 없는 글자는 '?'로 바꿔 PDF 생성이 실패하지 않게 한다. */
		private String safe(String s) {
			try {
				font.encode(s);
				return s;
			} catch (Exception e) {
				StringBuilder sb = new StringBuilder();
				s.codePoints().forEach(cp -> {
					String ch = new String(Character.toChars(cp));
					try {
						font.encode(ch);
						sb.append(ch);
					} catch (Exception ex) {
						sb.append('?');
					}
				});
				return sb.toString();
			}
		}

		private float width(String s, float size) throws IOException {
			return font.getStringWidth(safe(s)) / 1000f * size;
		}

		/** 단어 단위로 줄바꿈, 한 단어가 폭보다 길면(해시 등) 글자 단위로 자른다. */
		List<String> wrap(String text, float maxWidth, float size) throws IOException {
			List<String> out = new ArrayList<>();
			if (text == null) return out;
			for (String para : text.split("\n", -1)) {
				String line = "";
				for (String word : para.split(" ")) {
					for (String piece : splitLong(word, maxWidth, size)) {
						String candidate = line.isEmpty() ? piece : line + " " + piece;
						if (width(candidate, size) <= maxWidth) {
							line = candidate;
						} else {
							out.add(line);
							line = piece;
						}
					}
				}
				out.add(line);
			}
			return out;
		}

		private List<String> splitLong(String word, float maxWidth, float size) throws IOException {
			List<String> parts = new ArrayList<>();
			StringBuilder cur = new StringBuilder();
			for (int i = 0; i < word.length(); ) {
				int cp = word.codePointAt(i);
				String ch = new String(Character.toChars(cp));
				if (cur.length() > 0 && width(cur + ch, size) > maxWidth) {
					parts.add(cur.toString());
					cur = new StringBuilder();
				}
				cur.append(ch);
				i += Character.charCount(cp);
			}
			parts.add(cur.toString());
			return parts;
		}
	}
}
