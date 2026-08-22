# Caddy 소개와 Attend 프로젝트에서의 역할

이 문서는 Caddy가 무엇인지, 왜 필요한지, Attend 서버에서 어떤 설정으로 동작하는지를 설명한다.
실제 설치·배포 명령은 [ARM64 Docker·Cloudflare Tunnel·Arduino 배포 가이드](./ARM64_DOCKER_CLOUDFLARE_ARDUINO_DEPLOYMENT_GUIDE.md)를 따른다.

## 1. 한 문장으로 설명하면

**Caddy는 외부 또는 앞단 프록시에서 들어온 HTTP 요청을 받아 내부 애플리케이션으로 전달하는 웹 서버이자 리버스 프록시다.**

Attend 프로젝트에서는 Spring App을 인터넷에 직접 노출하지 않고 Caddy 뒤에 둔다.
Caddy는 요청을 검사·정리한 뒤 Docker 내부의 `app:8080`으로 전달한다.

```text
요청을 보내는 클라이언트
        │
        ▼
      Caddy
        │  요청 크기 제한, 전달 헤더 정리, 보안 토큰 추가
        ▼
Spring App (app:8080)
```

Caddy 공식 문서에서 `reverse_proxy`는 앞에서 받은 요청을 실행 중인 백엔드 서비스로 전달하는 기능으로 설명한다.
Caddy는 공개 도메인과 80·443 포트 조건이 갖춰진 직접 공개 구성에서는 인증서 발급과 갱신도 자동화할 수 있다.

- [Caddy Reverse proxy 공식 문서](https://caddyserver.com/docs/quick-starts/reverse-proxy)
- [Caddy Automatic HTTPS 공식 문서](https://caddyserver.com/docs/automatic-https)

## 2. 정방향 프록시와 리버스 프록시의 차이

이름이 비슷하지만 대상이 다르다.

| 구분 | 누구를 대신하는가 | 예시 |
| --- | --- | --- |
| 정방향 프록시 | 요청을 보내는 사용자 | 사내 인터넷 프록시, 일부 VPN 형태 |
| 리버스 프록시 | 요청을 받는 서버 | Caddy, Nginx, HAProxy |

사용자는 Spring App의 실제 컨테이너 주소를 알 필요가 없다. 공개 호스트만 호출하고,
Caddy가 그 요청을 적절한 내부 서비스로 전달한다.

## 3. Attend에서 Caddy를 두는 이유

Cloudflare Tunnel을 사용해도 Caddy의 역할은 남는다.

### 3.1 Spring App을 직접 공개하지 않는다

`compose.prod.yaml`에서 App은 `8080`을 Docker 네트워크에 `expose`할 뿐, 서버 Mac의 포트로
`publish`하지 않는다. 따라서 외부 클라이언트가 `app:8080`에 직접 접속할 수 없다.

```text
인터넷에서 접근 가능       Caddy가 내부에서 접근 가능
app:8080        X          app:8080        O
```

이는 애플리케이션 앞단을 하나로 고정하고, 우회 접근 가능성을 줄인다.
Compose 네트워크 이름은 `private`이지만 설정값은 `internal: false`다. 즉 App 포트가 호스트에
공개되지 않는다는 뜻이지, 컨테이너의 외부 통신까지 완전히 차단된 격리망이라는 뜻은 아니다.

### 3.2 공통 HTTP 정책을 한곳에 둔다

현재 Caddy 설정은 다음 정책을 적용한다.

- 응답 압축: `zstd`, `gzip`
- `Server` 응답 헤더 제거
- HSTS와 `X-Content-Type-Options` 응답 헤더 추가
- 요청 본문을 최대 `1MB`로 제한
- App이 신뢰하는 `X-Attend-Proxy-Token` 추가
- 클라이언트 IP와 원래 프로토콜을 App이 이해할 수 있는 형태로 전달

이 정책을 Spring Controller마다 반복할 필요가 없다.

### 3.3 App이 신뢰할 수 있는 프록시 경계를 만든다

App은 단순히 `X-Forwarded-For`가 있다는 이유만으로 그 값을 신뢰하지 않는다.
Caddy가 `.env`의 `TRUSTED_PROXY_TOKEN`을 `X-Attend-Proxy-Token`으로 넣어야 요청을 신뢰한다.

```text
.env의 TRUSTED_PROXY_TOKEN
          │
          ├─ Caddy: 요청 헤더에 삽입
          └─ App: 같은 값인지 확인
```

두 값이 다르면 정상 사용자의 요청도 거부된다. 이 값은 공개 문자열이 아니라 비밀값으로 관리한다.
운영 프로필에서는 최소 32바이트의 암호학적 난수여야 하며, 다른 pepper나 장치 key를 재사용하지 않는다.

## 4. 이 프로젝트에는 Caddy 실행 방식이 두 가지다

두 방식을 섞으면 안 된다.

| 항목 | Caddy 직접 공개 방식 | Cloudflare Tunnel 방식 |
| --- | --- | --- |
| 사용하는 Compose | `compose.prod.yaml` | `compose.prod.yaml` + `compose.cloudflare-tunnel.yaml` |
| Caddy 공개 포트 | `0.0.0.0:80`, `0.0.0.0:443` | host port 없음 |
| 인터넷 HTTPS 종료 지점 | Caddy | Cloudflare Edge |
| Caddy가 받는 프로토콜 | HTTPS/HTTP | Docker network HTTP (`cloudflared` → `caddy:80`) |
| 공개 인증서 관리 | Caddy ACME | Cloudflare |
| 앞단 프록시 | 없음 | `cloudflared` |
| 클라이언트 IP 출처 | Caddy가 본 원격 주소 | Cloudflare의 `CF-Connecting-IP` |

현재 Arduino 외부 테스트 가이드는 **Cloudflare Tunnel 방식**을 사용한다.

### 4.1 Caddy 직접 공개 방식

기본 파일인 `ops/caddy/Caddyfile`은 Caddy가 공개 도메인의 HTTPS를 직접 담당하는 구성이다.

```text
Arduino/브라우저
       │ HTTPS
       ▼
Caddy :443
       │ HTTP, Docker private network
       ▼
App :8080
```

이 방식은 일반적으로 다음 조건이 필요하다.

- 도메인의 A/AAAA 레코드가 서버의 공인 IP를 가리킴
- 라우터·방화벽에서 80과 443을 서버로 전달
- Caddy가 80과 443을 사용할 수 있음
- Caddy 데이터 볼륨이 지속적으로 보존됨

Caddy가 공개 인증서를 자동 발급한다고 해서 네트워크 포트와 DNS 설정까지 자동으로 해결되는 것은 아니다.
`caddy_data`에는 Caddy가 관리하는 인증서와 개인키가 들어갈 수 있으므로 임시 캐시처럼 삭제하면 안 된다.

또한 이 표의 직접 공개 방식은 Caddy 앞에 다른 프록시가 없다는 전제다. Cloudflare의 일반
프록시를 Caddy 앞에 추가하면서 기본 Caddyfile을 그대로 쓰면 `{remote_host}`에는 실제 사용자
대신 Cloudflare Edge 주소가 들어간다. 그 구조는 Cloudflare IP 범위를 검증하는 별도
`trusted_proxies` 설계가 필요하다.

### 4.2 Cloudflare Tunnel 방식

Tunnel 전용 override와 Caddyfile을 사용한다.

```text
Arduino/브라우저
       │ HTTPS
       ▼
Cloudflare Edge
       │ Cloudflare Tunnel
       ▼
cloudflared 컨테이너
       │ HTTP, Docker network의 caddy:80
       ▼
Caddy 컨테이너
       │ HTTP, Docker private network
       ▼
Spring App :8080
```

여기서 공개 HTTPS 인증서는 Cloudflare가 제시한다. Caddy는 서버 Mac에 포트를 전혀 열지 않고,
Compose 네트워크의 `cloudflared` 컨테이너에서만 HTTP 요청을 받는다. 이 Caddy 포트를 host에
publish하면 안 된다.

Compose 네트워크에 연결된 컨테이너는 Caddy에 연결할 수 있다. 따라서 이 경계는 인터넷 접근을
차단하는 용도이며, 같은 Docker 프로젝트 안의 신뢰할 수 없는 컨테이너까지 격리하는 장치는 아니다.

Tunnel 방식에서 `tls internal`은 필수가 아니다. 오히려 내부 HTTPS를 추가하면 Caddy 내부 CA를
`cloudflared`가 신뢰하도록 별도 구성해야 한다. 현재 구조에서는 같은 Docker network의 HTTP가 더 단순하다.

## 5. Tunnel 전용 Caddyfile 읽기

실제 파일은 [Caddyfile.tunnel](../ops/caddy/Caddyfile.tunnel)이다.

```caddyfile
{
	admin off
}
```

- Caddy 관리 API를 끈다.
- 설정 변경은 파일을 수정한 뒤 컨테이너를 다시 생성하거나 재시작해 반영한다.

```caddyfile
http://{$PUBLIC_HOST} {
```

- `.env`의 `PUBLIC_HOST`와 일치하는 `Host` 요청만 이 사이트 블록이 처리한다.
- `http://`를 명시했으므로 Caddy 자체의 자동 HTTPS를 사용하지 않는다.
- Tunnel의 공개 호스트가 `test.jinhokingoftheworld.cloud`라면 `PUBLIC_HOST`도 같은 값이어야 한다.
- `PUBLIC_HOST`에는 `https://`, 포트, 경로를 붙이지 않고 hostname만 넣는다.

```caddyfile
	encode zstd gzip
```

- 클라이언트가 지원하면 응답을 압축한다.
- 압축은 전송량을 줄이지만 App의 업무 로직을 바꾸지는 않는다.

```caddyfile
	header {
		-Server
		Strict-Transport-Security "max-age=31536000; includeSubDomains"
		X-Content-Type-Options "nosniff"
	}
```

- 서버 제품 정보가 들어가는 `Server` 헤더를 제거한다.
- 브라우저가 공개 사이트를 HTTPS로만 사용하도록 HSTS 헤더를 보낸다.
- 브라우저의 MIME 유형 추측을 제한한다.

HSTS는 공개 클라이언트가 Cloudflare를 통해 HTTPS로 응답을 받을 때 의미가 있다.
Caddy와 `cloudflared` 사이가 HTTP라는 사실과 충돌하지 않는다.
다만 `includeSubDomains`는 해당 도메인 아래의 하위 도메인에도 영향을 줄 수 있으므로, 다른
하위 도메인이 HTTPS를 지원하지 않는 환경이라면 범위를 재검토해야 한다.

```caddyfile
	request_body {
		max_size 1MB
	}
```

- 과도하게 큰 요청 본문을 Caddy에서 차단한다.
- 파일 업로드 기능을 추가한다면 이 제한이 적절한지 다시 검토해야 한다.

```caddyfile
	reverse_proxy app:8080 {
```

- Docker의 `private` 네트워크에서 `app` 서비스의 8080 포트로 요청을 전달한다.
- `app`은 서버 Mac의 `localhost:8080`이 아니다. Docker Compose 서비스 이름이다.

```caddyfile
		header_up X-Forwarded-For {http.request.header.Cf-Connecting-Ip}
		header_up X-Forwarded-Proto https
		header_up X-Attend-Proxy-Token {$TRUSTED_PROXY_TOKEN}
```

- Cloudflare가 보낸 `CF-Connecting-IP` 한 개를 App의 `X-Forwarded-For`로 덮어쓴다.
- 공개 클라이언트가 사용한 원래 프로토콜은 HTTPS였다고 App에 알린다.
- App만 알고 있는 프록시 신뢰 토큰을 추가한다.

사용자가 보낸 임의의 `X-Forwarded-For`를 그대로 신뢰하지 않는 것이 중요하다. Cloudflare는
origin으로 전달할 때 `CF-Connecting-IP`에 클라이언트 IP를 제공한다. 이 값도 Caddy가 외부에
직접 노출되어 있다면 위조할 수 있으므로 `127.0.0.1` 바인딩과 함께 사용해야 한다.

- [Cloudflare HTTP 요청 헤더 공식 문서](https://developers.cloudflare.com/fundamentals/reference/http-headers/)

## 6. Compose override가 하는 일

[compose.cloudflare-tunnel.yaml](../compose.cloudflare-tunnel.yaml)은 단독 파일이 아니다.
반드시 `compose.prod.yaml` 뒤에 합쳐서 사용한다.

```yaml
services:
  caddy:
    ports: !override []
    volumes:
      - ./ops/caddy/Caddyfile.tunnel:/etc/caddy/Caddyfile:ro
  cloudflared:
    image: cloudflare/cloudflared:2026.7.3
    command: tunnel --no-autoupdate run --token-file /run/secrets/cloudflared_tunnel_token
```

핵심은 두 가지다.

1. 기본 80·443 공개 바인딩을 제거해 Caddy host port를 없앤다.
2. 기본 Caddyfile 대신 Tunnel 전용 Caddyfile을 마운트한다.
3. `cloudflared`가 같은 Compose 네트워크의 `caddy:80`으로 연결한다.

파일 순서를 반대로 쓰면 의도한 override가 적용되지 않는다.
`!override` 태그는 Docker Compose 2.24.4 이상에서 지원된다. 현재 확인한 서버의 Compose
v5.3.1은 이 조건을 충족한다.

- [Docker Compose 파일 병합 공식 문서](https://docs.docker.com/reference/compose-file/merge/)

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  config
```

렌더링 결과의 Caddy에는 `ports:`가 없어야 한다. `0.0.0.0:80`, `0.0.0.0:443` 또는
`127.0.0.1:8088`이 보이면 Tunnel 전용 격리가 적용되지 않은 것이다.

## 7. 운영 확인 명령

Tunnel 방식의 Compose 명령에는 이후에도 두 파일을 계속 붙인다.

### 설정 해석 확인

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  config --quiet
```

아무 출력 없이 종료 코드가 0이면 Compose 문법과 필수 환경변수 치환이 성공한 것이다.
이 검사는 컨테이너가 실제로 정상 동작한다는 뜻은 아니다.

### 컨테이너 상태 확인

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  ps
```

`app`은 `healthy`, `caddy`와 `cloudflared`는 실행 중이어야 한다.

### Caddy 시작 로그 확인

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  logs --tail=100 caddy
```

현재 Caddyfile에는 HTTP access log가 설정되어 있지 않다. 따라서 이 명령에서
`POST /api/v1/device/check-ins`가 반드시 보일 것이라고 기대하면 안 된다.

장치 비밀키인 `X-Device-Key`가 있으므로 access log를 추가할 때는 해당 헤더를 완전히 제거하거나
마스킹해야 한다. 일부 문자라도 남기지 않는 것이 안전하다.

### Caddyfile 자체 검증

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  run --rm --no-deps --pull never \
  --entrypoint caddy caddy \
  validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

### 실제 포트 바인딩 확인

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  port caddy 80
```

host port가 없으므로 이 명령은 실패하거나 빈 결과가 정상이다. 주소가 출력되면 Caddy가
불필요하게 host에 공개된 것이다.

### 서버 내부의 Caddy 경로 확인

```bash
docker compose --env-file .env \
  -f compose.prod.yaml \
  -f compose.cloudflare-tunnel.yaml \
  exec -T app curl -i \
  -X POST \
  -H 'Host: test.jinhokingoftheworld.cloud' \
  -H 'CF-Connecting-IP: 127.0.0.1' \
  http://caddy:80/api/v1/device/credential-tests
```

장치 인증 헤더를 일부러 보내지 않았으므로, App이 실행 중이고 경로가 연결되었다면 보통
Spring App의 JSON `401 DEVICE_UNAUTHORIZED`가 나온다. 이 확인의 목적은 인증 성공이 아니라
**Caddy를 지나 App까지 요청이 도달했는지** 보는 것이다.

## 8. 자주 생기는 오해

### “Caddy가 있으면 Cloudflare가 필요 없다”

항상 그런 것은 아니다. Caddy는 직접 공개 HTTPS를 담당할 수 있지만, Cloudflare Tunnel은 서버가
외부로 연결을 시작하게 해 공인 인바운드 포트 개방을 피하게 해 준다. 두 제품의 책임이 다르다.

### “Cloudflare가 리버스 프록시니까 Caddy는 중복이다”

단순 전달만 필요하다면 구조를 줄일 수도 있다. 그러나 이 프로젝트에서는 Caddy가 요청 크기 제한,
보안 응답 헤더, App 신뢰 토큰 주입, 클라이언트 IP 정규화라는 구체적인 역할을 수행한다.

### “Tunnel인데 기본 `compose.prod.yaml`만 실행하면 된다”

틀렸다. 기본 Compose만 실행하면 Caddy가 서버의 모든 인터페이스에서 80·443을 공개한다.
Tunnel 테스트에서는 반드시 `compose.cloudflare-tunnel.yaml`을 함께 사용한다.

### “`tls internal`을 켜면 무조건 더 안전하다”

내부 HTTPS는 별도의 신뢰 체인 관리가 필요하다. 현재처럼 같은 Compose network 안에서만 연결되는
구조에서는 HTTP origin이 명확하고 단순하다. 내부 구간이 다른 호스트나 신뢰할 수 없는 네트워크를
지나가도록 바뀐다면 그때 origin TLS를 설계해야 한다.

### “Caddy 로그에 요청이 없으면 요청이 오지 않은 것이다”

틀렸다. 현재는 access log를 켜지 않았다. 컨테이너 로그는 주로 시작·설정·오류 로그다.
App의 응답, 관리자 화면의 장치 시험 시각, DB 결과를 함께 확인해야 한다.

## 9. 장애를 단계별로 분리하는 법

| 증상 | 먼저 볼 곳 | 가능한 원인 |
| --- | --- | --- |
| Caddy 컨테이너가 재시작 반복 | Caddy 시작 로그 | Caddyfile 문법, 필수 환경변수 누락 |
| Caddy host port가 보임 | Compose `config`, `port caddy 80` | override 미적용 |
| 로컬 Caddy 응답이 404 | `Host` 헤더, `PUBLIC_HOST` | 사이트 주소 불일치 |
| App이 403/400 계열로 거부 | proxy token, 전달 IP | `TRUSTED_PROXY_TOKEN` 불일치, IP 헤더 형식 오류 |
| 외부 요청만 502 | `cloudflared` 로그와 service URL | Tunnel이 `caddy:80`에 도달하지 못함 |
| 외부 요청이 Cloudflare HTML | Cloudflare Access/WAF | 장치 API에 로그인·Challenge 정책 적용 |
| 인증 없는 probe가 JSON 401 | 정상적인 경로 증거 | Cloudflare → Caddy → App 도달 성공 |

한 번에 모든 구성요소를 의심하지 말고 다음 순서로 확인한다.

```text
App healthy
→ Docker network Caddy 응답
→ cloudflared 연결
→ 외부 HTTPS 응답
→ Arduino 인증서와 장치 credential
```

## 10. Caddy가 하지 않는 일

Caddy의 책임 범위를 정확히 아는 것이 중요하다.

Caddy는 다음 작업을 하지 않는다.

- Docker 이미지 빌드와 전송
- 컨테이너 배포 자체
- Cloudflare 계정·DNS·Tunnel 생성
- DB migration
- 사용자 로그인과 장치 credential의 업무 검증
- 출석 데이터 생성
- Arduino의 Wi-Fi 연결과 Root Certificate 설치

이 작업들은 각각 Docker Compose, `cloudflared`, Spring App, DB migration 도구, Arduino firmware가
담당한다.

## 11. 이 프로젝트의 관련 파일

| 파일 | 역할 |
| --- | --- |
| [`compose.prod.yaml`](../compose.prod.yaml) | App·Retention·기본 Caddy 서비스 정의 |
| [`compose.cloudflare-tunnel.yaml`](../compose.cloudflare-tunnel.yaml) | Caddy host port를 제거하고 Docker-managed Tunnel을 추가 |
| [`ops/caddy/Caddyfile`](../ops/caddy/Caddyfile) | Caddy 직접 공개 HTTPS 구성 |
| [`ops/caddy/Caddyfile.tunnel`](../ops/caddy/Caddyfile.tunnel) | Cloudflare Tunnel 전용 HTTP origin 구성 |
| [`.env.example`](../.env.example) | `PUBLIC_HOST`, `TRUSTED_PROXY_TOKEN` 등 환경변수 예시 |

현재 Compose는 `caddy:2-alpine`이라는 2.x 계열 가변 태그를 사용한다. 장기 운영에서 동일 빌드를
정확히 재현해야 한다면 검증한 명시적 버전 또는 이미지 digest 고정을 검토한다.

## 12. 핵심 요약

- Caddy는 Attend App 앞에 있는 리버스 프록시다.
- 직접 공개 방식에서는 Caddy가 HTTPS와 인증서를 담당한다.
- 현재 Tunnel 방식에서는 Cloudflare가 공개 HTTPS를 담당하고 Caddy는 Docker network의 HTTP origin이다.
- Caddy는 App으로 전달하기 전에 요청 크기, 보안 헤더, 클라이언트 IP, 프록시 신뢰 토큰을 처리한다.
- Tunnel 방식에서는 두 Compose 파일과 `Caddyfile.tunnel`을 반드시 함께 사용한다.
- 현재 Caddy 로그는 access log가 아니므로 HTTP 요청 경로가 항상 보이지 않는다.
