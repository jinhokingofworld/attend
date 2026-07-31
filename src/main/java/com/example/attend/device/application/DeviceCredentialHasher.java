package com.example.attend.device.application;

import com.example.attend.config.DeviceApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 장치 키를 생성하고 별도 pepper를 사용한 HMAC-SHA-256 값만 저장하게 한다.
 */
@Component
public final class DeviceCredentialHasher {

	private static final int KEY_BYTES = 32;
	private static final int MIN_PEPPER_BYTES = 32;
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private final SecureRandom secureRandom = new SecureRandom();
	private final byte[] pepper;

	/**
	 * 외부 설정의 pepper를 메모리에서 HMAC key로 준비한다.
	 *
	 * @param properties 장치 API 외부 설정
	 */
	public DeviceCredentialHasher(DeviceApiProperties properties) {
		this.pepper = properties.credentialPepper() == null
				? new byte[0]
				: properties.credentialPepper().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * 추측하기 어려운 256-bit 원문 장치 키를 만든다.
	 *
	 * @return URL·헤더에 안전한 Base64 URL 문자열
	 */
	public String generateKey() {
		byte[] bytes = new byte[KEY_BYTES];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * 원문 키와 pepper로 DB 저장용 lowercase 16진수 HMAC을 계산한다.
	 *
	 * @param rawKey 원문 장치 키
	 * @return 64자 lowercase HMAC-SHA-256
	 */
	public String hash(String rawKey) {
		requireConfiguredPepper();
		if (rawKey == null || rawKey.isEmpty()) {
			throw new IllegalArgumentException("device key must not be empty");
		}
		return HexFormat.of().formatHex(mac(rawKey));
	}

	/**
	 * 비교 시간 차이로 HMAC 내용이 드러나지 않도록 상수 시간 비교를 사용한다.
	 *
	 * @param rawKey 요청 header의 원문 키
	 * @param storedHash DB에 저장된 lowercase 16진수 HMAC
	 * @return 같은 키이면 {@code true}
	 */
	public boolean matches(String rawKey, String storedHash) {
		if (pepper.length < MIN_PEPPER_BYTES) {
			return false;
		}
		if (rawKey == null || storedHash == null) {
			return false;
		}
		byte[] expected;
		try {
			expected = HexFormat.of().parseHex(storedHash);
		} catch (IllegalArgumentException exception) {
			return false;
		}
		return MessageDigest.isEqual(mac(rawKey), expected);
	}

	/** 원문 키를 외부 pepper로 HMAC 처리해 복원 불가능한 byte 배열을 만든다. */
	private byte[] mac(String rawKey) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
			return mac.doFinal(rawKey.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
		}
	}

	/** 장치 키를 발급하기 전에 운영 수준의 pepper가 설정됐는지 확인한다. */
	private void requireConfiguredPepper() {
		if (pepper.length < MIN_PEPPER_BYTES) {
			throw new IllegalStateException(
					"DEVICE_CREDENTIAL_PEPPER must contain at least 32 bytes");
		}
	}
}
