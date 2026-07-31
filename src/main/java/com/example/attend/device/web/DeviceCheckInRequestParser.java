package com.example.attend.device.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * check-in body의 media type, 실제 byte 크기와 strict JSON schema를 검증한다.
 */
@Component
public final class DeviceCheckInRequestParser {

	private static final int MAX_BODY_BYTES = 1024;
	private static final Pattern UID_PATTERN = Pattern.compile(
			"(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})");
	private static final Pattern REQUEST_ID_PATTERN = Pattern.compile(
			"[A-Za-z0-9_-]{1,64}");
	private static final Set<String> ALLOWED_FIELDS =
			Set.of("uid", "requestId");
	private final ObjectMapper strictMapper;

	/** 중복 JSON member를 탐지하는 별도 parser 설정을 만든다. */
	public DeviceCheckInRequestParser() {
		JsonFactory factory = JsonFactory.builder()
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.build();
		this.strictMapper = new ObjectMapper(factory);
	}

	/**
	 * Content-Length와 무관하게 stream을 최대 1025 bytes까지만 읽고 검증한다.
	 */
	public ParsedCheckIn parse(HttpServletRequest request) throws IOException {
		if (!supportedMediaType(request)) {
			return ParsedCheckIn.failure(
					415,
					"UNSUPPORTED_MEDIA_TYPE",
					"지원하지 않는 요청 본문 형식입니다.");
		}
		byte[] body = readLimited(request);
		if (body.length > MAX_BODY_BYTES) {
			return ParsedCheckIn.failure(
					413,
					"PAYLOAD_TOO_LARGE",
					"요청 본문은 1024 bytes를 넘을 수 없습니다.");
		}
		JsonNode root;
		try (JsonParser parser = strictMapper.getFactory().createParser(body)) {
			root = strictMapper.readTree(parser);
			if (root == null || parser.nextToken() != null) {
				return malformed();
			}
		} catch (RuntimeException | com.fasterxml.jackson.core.JacksonException exception) {
			return malformed();
		}
		if (!root.isObject()
				|| root.size() != 2
				|| !onlyAllowedFields(root)
				|| !root.path("uid").isTextual()
				|| !root.path("requestId").isTextual()) {
			return invalid();
		}
		String uid = root.path("uid").textValue();
		String requestId = root.path("requestId").textValue();
		if (!UID_PATTERN.matcher(uid).matches()
				|| !REQUEST_ID_PATTERN.matcher(requestId).matches()) {
			return invalid();
		}
		return ParsedCheckIn.success(new CheckInRequest(uid, requestId));
	}

	/** application/json·UTF-8·비압축 body만 허용하는지 확인한다. */
	private static boolean supportedMediaType(HttpServletRequest request) {
		String encoding = request.getHeader("Content-Encoding");
		if (encoding != null && !"identity".equalsIgnoreCase(encoding.trim())) {
			return false;
		}
		String contentType = request.getContentType();
		if (contentType == null) {
			return false;
		}
		try {
			MediaType mediaType = MediaType.parseMediaType(contentType);
			return MediaType.APPLICATION_JSON.includes(mediaType)
					&& (mediaType.getCharset() == null
					|| StandardCharsets.UTF_8.equals(mediaType.getCharset()));
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	/** Content-Length를 신뢰하지 않고 실제 stream을 최대 1025 bytes까지만 읽는다. */
	private static byte[] readLimited(HttpServletRequest request)
			throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] chunk = new byte[256];
		int total = 0;
		while (total <= MAX_BODY_BYTES) {
			int read = request.getInputStream().read(
					chunk, 0, Math.min(chunk.length, MAX_BODY_BYTES + 1 - total));
			if (read < 0) {
				break;
			}
			output.write(chunk, 0, read);
			total += read;
		}
		return output.toByteArray();
	}

	/** UID와 requestId 외의 추가 JSON member가 없는지 검사한다. */
	private static boolean onlyAllowedFields(JsonNode root) {
		Iterator<String> names = root.fieldNames();
		while (names.hasNext()) {
			if (!ALLOWED_FIELDS.contains(names.next())) {
				return false;
			}
		}
		return true;
	}

	/** JSON 문서 자체를 해석할 수 없는 400 결과를 만든다. */
	private static ParsedCheckIn malformed() {
		return ParsedCheckIn.failure(
				400,
				"MALFORMED_REQUEST",
				"요청 JSON을 해석할 수 없습니다.");
	}

	/** JSON은 유효하지만 외부 schema를 위반한 422 결과를 만든다. */
	private static ParsedCheckIn invalid() {
		return ParsedCheckIn.failure(
				422,
				"INVALID_REQUEST",
				"요청 값이 허용된 형식이 아닙니다.");
	}
}
