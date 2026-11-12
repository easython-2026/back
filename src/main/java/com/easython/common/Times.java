package com.easython.common;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 서버 기준 시간은 KST. 응답에 나가는 일시는 초 단위 ISO 8601(+09:00). */
@Configuration
public class Times {
	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Bean
	Clock clock() {
		return Clock.system(KST);
	}

	public static OffsetDateTime kst(Instant i) {
		return i == null ? null : i.truncatedTo(ChronoUnit.SECONDS).atZone(KST).toOffsetDateTime();
	}

	public static String iso(Instant i) {
		return i == null ? null : kst(i).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}
}
