package com.example.attend.access.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.attend.config.TrustedProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Caddy 인증 전후의 forwarded client IP 신뢰 경계를 검증한다. */
class TrustedProxyClientIpFilterTest {

	private static final String PROXY_TOKEN =
			"test-proxy-token-that-is-at-least-32-bytes";

	/** 개발 direct 요청은 forwarded header를 무시하고 socket 주소를 유지한다. */
	@Test
	void ignoresForwardedAddressWhenProxyTrustIsDisabled() throws Exception {
		TrustedProxyClientIpFilter filter = filter(null);
		MockHttpServletRequest request = request("192.0.2.10", "198.51.100.20");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> downstreamAddress = new AtomicReference<>();

		filter.doFilter(request, response,
				(nextRequest, nextResponse) -> downstreamAddress.set(
						nextRequest.getRemoteAddr()));

		assertThat(downstreamAddress).hasValue("192.0.2.10");
	}

	/** 올바른 내부 token과 단일 IP가 있을 때만 downstream source를 복원한다. */
	@Test
	void exposesCanonicalClientAddressFromAuthenticatedCaddy() throws Exception {
		TrustedProxyClientIpFilter filter = filter(PROXY_TOKEN);
		MockHttpServletRequest request = request("172.18.0.4", "198.51.100.20");
		request.addHeader(TrustedProxyClientIpFilter.PROXY_TOKEN_HEADER, PROXY_TOKEN);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> downstreamAddress = new AtomicReference<>();
		AtomicReference<String> downstreamProxyToken = new AtomicReference<>();

		filter.doFilter(request, response,
				(nextRequest, nextResponse) -> {
					downstreamAddress.set(nextRequest.getRemoteAddr());
					downstreamProxyToken.set(((HttpServletRequest) nextRequest).getHeader(
							TrustedProxyClientIpFilter.PROXY_TOKEN_HEADER));
				});

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(downstreamAddress).hasValue("198.51.100.20");
		assertThat(downstreamProxyToken).hasNullValue();
	}

	/** 외부 사용자가 X-Forwarded-For만 위조해 Caddy 신뢰 경계를 넘지 못한다. */
	@Test
	void rejectsMissingOrIncorrectProxyToken() throws Exception {
		TrustedProxyClientIpFilter filter = filter(PROXY_TOKEN);
		for (String suppliedToken : new String[]{null, "incorrect-token"}) {
			MockHttpServletRequest request = request(
					"172.18.0.4", "198.51.100.20");
			if (suppliedToken != null) {
				request.addHeader(
						TrustedProxyClientIpFilter.PROXY_TOKEN_HEADER,
						suppliedToken);
			}
			MockHttpServletResponse response = new MockHttpServletResponse();
			AtomicBoolean invoked = new AtomicBoolean();

			filter.doFilter(request, response,
					(nextRequest, nextResponse) -> invoked.set(true));

			assertThat(response.getStatus()).isEqualTo(403);
			assertThat(invoked).isFalse();
		}
	}

	/** 여러 주소나 hostname을 허용하지 않아 첫 값 선택에 의한 spoofing을 막는다. */
	@Test
	void rejectsNonLiteralOrMultipleForwardedAddresses() throws Exception {
		TrustedProxyClientIpFilter filter = filter(PROXY_TOKEN);
		for (String forwarded : new String[]{
				"198.51.100.20, 172.18.0.4", "attacker.example",
				"127.1", "999.1.1.1"}) {
			MockHttpServletRequest request = request("172.18.0.4", forwarded);
			request.addHeader(
					TrustedProxyClientIpFilter.PROXY_TOKEN_HEADER, PROXY_TOKEN);
			MockHttpServletResponse response = new MockHttpServletResponse();

			filter.doFilter(request, response,
					(nextRequest, nextResponse) -> {
						throw new AssertionError("invalid forwarding reached chain");
					});

			assertThat(response.getStatus()).isEqualTo(400);
		}
	}

	/** 한 client가 로그인 bucket을 소진해도 다른 client의 bucket은 독립적이다. */
	@Test
	void keepsLoginRateLimitBucketsIndependentBehindCaddy() throws Exception {
		TrustedProxyClientIpFilter proxyFilter = filter(PROXY_TOKEN);
		LoginRateLimitFilter loginFilter = new LoginRateLimitFilter(Clock.fixed(
				Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));
		AtomicBoolean secondClientReachedApplication = new AtomicBoolean();

		for (int sequence = 0; sequence < 6; sequence++) {
			MockHttpServletRequest request = authenticatedRequest("198.51.100.20");
			MockHttpServletResponse response = new MockHttpServletResponse();
			proxyFilter.doFilter(request, response,
					(proxyRequest, proxyResponse) -> loginFilter.doFilter(
							proxyRequest, proxyResponse,
							(nextRequest, nextResponse) -> { }));
			assertThat(response.getStatus())
					.isEqualTo(sequence < 5 ? 200 : 429);
		}

		MockHttpServletRequest otherClient = authenticatedRequest("203.0.113.30");
		MockHttpServletResponse otherResponse = new MockHttpServletResponse();
		proxyFilter.doFilter(otherClient, otherResponse,
				(proxyRequest, proxyResponse) -> loginFilter.doFilter(
						proxyRequest, proxyResponse,
						(nextRequest, nextResponse) ->
								secondClientReachedApplication.set(true)));

		assertThat(otherResponse.getStatus()).isEqualTo(200);
		assertThat(secondClientReachedApplication).isTrue();
	}

	/** health 예외는 loopback 요청에만 적용되고 원격 direct 요청에는 적용되지 않는다. */
	@Test
	void bypassesProxyTokenOnlyForLoopbackHealthProbe() throws Exception {
		TrustedProxyClientIpFilter filter = filter(PROXY_TOKEN);
		MockHttpServletRequest loopback = new MockHttpServletRequest();
		loopback.setRequestURI("/actuator/health");
		loopback.setRemoteAddr("127.0.0.1");
		MockHttpServletResponse loopbackResponse = new MockHttpServletResponse();
		AtomicBoolean loopbackInvoked = new AtomicBoolean();

		filter.doFilter(loopback, loopbackResponse,
				(nextRequest, nextResponse) -> loopbackInvoked.set(true));

		MockHttpServletRequest remote = new MockHttpServletRequest();
		remote.setRequestURI("/actuator/health");
		remote.setRemoteAddr("198.51.100.20");
		MockHttpServletResponse remoteResponse = new MockHttpServletResponse();
		filter.doFilter(remote, remoteResponse,
				(nextRequest, nextResponse) -> {
					throw new AssertionError("remote health bypassed proxy token");
				});

		assertThat(loopbackInvoked).isTrue();
		assertThat(loopbackResponse.getStatus()).isEqualTo(200);
		assertThat(remoteResponse.getStatus()).isEqualTo(403);
	}

	private static TrustedProxyClientIpFilter filter(String token) {
		return new TrustedProxyClientIpFilter(new TrustedProxyProperties(token));
	}

	private static MockHttpServletRequest request(
			String remoteAddress,
			String forwardedAddress) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRequestURI("/authentication");
		request.setRemoteAddr(remoteAddress);
		request.addHeader(
				TrustedProxyClientIpFilter.FORWARDED_FOR_HEADER,
				forwardedAddress);
		return request;
	}

	private static MockHttpServletRequest authenticatedRequest(
			String forwardedAddress) {
		MockHttpServletRequest request = request("172.18.0.4", forwardedAddress);
		request.setMethod("POST");
		request.addParameter("usernameInput", "same-account");
		request.addHeader(
				TrustedProxyClientIpFilter.PROXY_TOKEN_HEADER, PROXY_TOKEN);
		return request;
	}
}
