# Cloudflare 소개와 Attend 프로젝트에서의 역할

이 문서는 Cloudflare와 Cloudflare Tunnel의 기본 개념, Attend 서버에서의 정확한 역할,
장애를 확인하는 방법을 설명한다. 실제 배포 순서는
[ARM64 Docker·Cloudflare Tunnel·Arduino 배포 가이드](./ARM64_DOCKER_CLOUDFLARE_ARDUINO_DEPLOYMENT_GUIDE.md)를 따른다.

## 1. Cloudflare는 무엇인가

Cloudflare는 사용자와 원본 서버(origin) 사이에서 동작하는 글로벌 네트워크 서비스다.
DNS, 공개 HTTPS, 프록시, 보안 정책, 캐시, Tunnel 등의 기능을 제공한다.

```text
사용자 ──HTTPS──> Cloudflare ──────> 원본 서버
                  공개 접점             실제 App이 있는 곳
```

하지만 Cloudflare가 Docker 이미지를 실행하거나 Spring App을 배포하는 것은 아니다.
서버 Mac에서 컨테이너와 DB를 먼저 정상적으로 실행해야 Cloudflare가 전달할 대상이 생긴다.

## 2. 먼저 알아야 할 용어

| 용어 | 의미 | Attend의 예 |
| --- | --- | --- |
| 도메인 | 사람이 읽는 인터넷 주소 체계 | `jinhokingoftheworld.cloud` |
| Zone | Cloudflare가 관리하는 하나의 DNS 영역 | `jinhokingoftheworld.cloud` zone |
| Hostname | 특정 서비스에 붙이는 전체 호스트 이름 | `test.jinhokingoftheworld.cloud` |
| DNS | hostname을 실제 연결 대상으로 해석하는 체계 | Tunnel route를 가리키는 레코드 |
| Edge | 사용자가 처음 연결하는 Cloudflare의 공개 서버 | Nano가 TLS 연결하는 지점 |
| Origin | Cloudflare가 최종적으로 요청을 넘기는 서버 | 전용 서버 Mac의 Caddy |
| `cloudflared` | Cloudflare Tunnel connector | Docker Compose 서비스 |
| Tunnel | Cloudflare Edge와 `cloudflared` 사이의 연결 | Attend 테스트용 Tunnel |
| Public hostname | 공개 hostname과 내부 서비스를 연결하는 규칙 | hostname → `http://caddy:80` |

## 3. 일반 DNS 공개와 Tunnel 공개의 차이

### 3.1 서버 IP를 직접 공개하는 방식

일반적인 직접 공개 구조는 다음과 같다.

```text
도메인 A/AAAA 레코드
       │ 서버 공인 IP
       ▼
라우터/방화벽의 80·443
       ▼
Caddy
       ▼
App
```

이 방식에서는 공인 IP, 라우터 포트 포워딩, 방화벽, 서버의 80·443 바인딩이 필요할 수 있다.

### 3.2 Cloudflare Tunnel 방식

Tunnel에서는 서버 IP를 공개 hostname의 A 레코드에 직접 넣지 않는다. 서버의 `cloudflared`가
Cloudflare로 **나가는 연결**을 먼저 만들고, Cloudflare가 그 연결을 통해 요청을 전달한다.

```text
Arduino 또는 브라우저
        │ HTTPS
        ▼
Cloudflare Edge
        │ 암호화된 Tunnel
        ▼
cloudflared 컨테이너
        │ HTTP, Docker network의 caddy:80
        ▼
Caddy
        │ Docker network, app:8080
        ▼
Spring App
```

Cloudflare 공식 문서의 published application은 공개 hostname을 로컬 서비스 URL에 연결한다.
로컬 서비스는 `http://localhost:<port>` 형태도 공식 지원된다.

- [Cloudflare Tunnel routing 공식 문서](https://developers.cloudflare.com/tunnel/routing/)
- [Cloudflare Tunnel 설정 공식 문서](https://developers.cloudflare.com/tunnel/setup/)

## 4. Attend에서 Cloudflare가 담당하는 일

### 4.1 공개 DNS hostname 제공

외부에서는 `test.jinhokingoftheworld.cloud`를 사용한다. Tunnel에 public hostname route를 만들면
이 hostname이 특정 Tunnel과 로컬 서비스로 연결된다.

따라서 Wi-Fi의 `en0` 주소나 `ifconfig`에서 찾은 사설 IP를 Cloudflare DNS의 공개 A 레코드에
넣는 것이 아니다. `192.168.x.x`, `10.x.x.x` 같은 사설 IP는 인터넷에서 직접 라우팅되지 않는다.

Dashboard에서 published application route를 만들면 해당 Tunnel을 가리키는 DNS 레코드도 함께
생성된다. 같은 hostname에 기존 A, AAAA 또는 CNAME 레코드가 있으면 충돌할 수 있으므로 먼저
기존 레코드의 목적을 확인하고 정리해야 한다.

- [Cloudflare Published applications 공식 문서](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/routing-to-tunnel/)

### 4.2 공개 HTTPS 종료

Arduino Nano와 브라우저는 다음 주소에 HTTPS로 연결한다.

```text
https://test.jinhokingoftheworld.cloud
```

클라이언트에게 인증서를 제시하고 TLS 연결을 종료하는 지점은 Cloudflare Edge다.
Nano의 WiFiNINA에 등록하는 Root Certificate도 **이 공개 hostname의 인증서 체인을 검증하기 위한
공개 신뢰 Root**다. Caddy의 내부 인증서가 아니다.

Cloudflare가 사용하는 인증기관이나 중간 인증서 체인은 서비스 수명 동안 바뀔 수 있다. Arduino에
현재 leaf 인증서나 공개키 하나를 영구 고정하지 말고, Arduino IDE의 Root Certificate 업로드
기능으로 공개 신뢰 체인을 관리한 뒤 실제 장치에서 주기적으로 확인한다.

- [Cloudflare Universal SSL 공식 문서](https://developers.cloudflare.com/ssl/edge-certificates/universal-ssl/)

### 4.3 Tunnel을 통한 origin 전달

`cloudflared` 컨테이너는 Docker Compose 네트워크의 다음 주소로 요청을 전달한다.

```text
http://caddy:80
```

Caddy는 host port를 전혀 publish하지 않는다. `caddy:80`은 Compose 네트워크 내부 DNS 이름이므로
인터넷이나 서버 Mac의 `localhost`에서 접근할 수 없다.

### 4.4 원래 클라이언트 IP 전달

Tunnel 뒤에서 Caddy가 보는 TCP 연결 상대는 Arduino가 아니라 `cloudflared`다. 실제 클라이언트
IP를 보존하기 위해 Cloudflare의 `CF-Connecting-IP` 헤더를 사용한다.

Tunnel 전용 Caddyfile은 이 값을 App이 요구하는 단일 `X-Forwarded-For` 값으로 덮어쓴다.
이를 통해 rate limit과 감사 정보가 모든 장치를 하나의 `cloudflared` IP로 오인하지 않게 한다.

Cloudflare는 Edge에서 origin으로 전달할 때 `CF-Connecting-IP`를 제공한다.

- [Cloudflare HTTP 요청 헤더 공식 문서](https://developers.cloudflare.com/fundamentals/reference/http-headers/)

## 5. Attend용 Public Hostname 값

Cloudflare Dashboard에서 Tunnel의 Public Hostname을 연결할 때 핵심 값은 다음과 같다.

| 항목 | 값 |
| --- | --- |
| 공개 hostname | `test.jinhokingoftheworld.cloud` |
| Service type | `HTTP` |
| Service URL | `http://caddy:80` |
| HTTP Host Header | `test.jinhokingoftheworld.cloud` |

HTTP Host Header가 중요한 이유는 Caddy 사이트 블록이 `.env`의 `PUBLIC_HOST`와 일치하는 요청만
처리하기 때문이다.

```dotenv
PUBLIC_HOST=test.jinhokingoftheworld.cloud
PUBLIC_BASE_URL=https://test.jinhokingoftheworld.cloud
```

Service type이 HTTP이므로 `No TLS Verify`는 필요하지 않다. 이 옵션은 origin HTTPS 인증서 검증과
관련된 옵션이며 현재 구조에는 적용 대상 자체가 없다.

### 5.1 HTTP를 HTTPS로 강제한다

Cloudflare의 Edge 인증서가 `Active`라는 사실만으로 모든 HTTP 요청이 자동으로 HTTPS로
리다이렉트되는 것은 아니다. 이 프로젝트의 공개 hostname에는 Cloudflare의 `Always Use HTTPS`
또는 동등한 hostname 전용 Redirect Rule을 적용한다.

설정 후 다음 요청이 `https://test.jinhokingoftheworld.cloud/...`로 리다이렉트되는지 확인한다.

```bash
curl -I http://test.jinhokingoftheworld.cloud
```

현재 Tunnel Caddyfile은 App에 `X-Forwarded-Proto: https`를 고정해서 전달하므로, Edge에서 HTTP를
그대로 허용하면 App이 원래 HTTP 요청을 구분하지 못한다. HTTPS 강제는 선택적인 장식이 아니라
현재 신뢰 모델을 완성하는 설정이다.

- [Cloudflare Always Use HTTPS 공식 문서](https://developers.cloudflare.com/ssl/edge-certificates/additional-options/always-use-https/)

### 5.2 Compose 버전을 확인한다

Tunnel override가 사용하는 `!override` 태그는 Docker Compose 2.24.4 이상이 필요하다.
현재 서버에서 확인한 v5.3.1은 이 조건을 충족한다.

```bash
docker compose version
```

- [Docker Compose 파일 병합 공식 문서](https://docs.docker.com/reference/compose-file/merge/)

## 6. 왜 origin은 HTTP인데 공개 사이트는 HTTPS인가

하나의 요청에는 서로 다른 연결 구간이 있다.

| 구간 | 프로토콜 | 설명 |
| --- | --- | --- |
| Arduino → Cloudflare Edge | HTTPS | 공개 인터넷 구간, Nano가 Cloudflare 인증서 검증 |
| Cloudflare Edge → `cloudflared` | Tunnel 내부 암호화 연결 | Cloudflare가 관리하는 connector 연결 |
| `cloudflared` → Caddy | HTTP, `caddy:80` | 같은 Compose network 구간 |
| Caddy → Spring App | HTTP, Docker private network | 컨테이너 내부 구간 |

공개 인터넷 구간이 HTTPS라는 사실과 로컬 origin이 HTTP라는 사실은 모순이 아니다.
다만 `cloudflared → Caddy` HTTP를 안전하게 쓰는 전제는 Caddy가 host port로 전혀 열려 있지
않고, 같은 Compose network 안에서만 접근된다는 것이다.

origin이 다른 물리 서버나 신뢰할 수 없는 네트워크에 있다면 origin HTTPS를 별도로 설계해야 한다.
그 경우 `originServerName`, 신뢰할 CA, 인증서 수명주기까지 함께 다뤄야 하며 단순히
`noTLSVerify`를 켜는 것으로 끝내면 안 된다.

- [Cloudflare Tunnel origin parameters 공식 문서](https://developers.cloudflare.com/tunnel/advanced/origin-parameters/)

## 7. `cloudflared`의 역할

`cloudflared`는 `restart: unless-stopped`로 실행되는 Docker Compose connector다.

```text
cloudflared가 실행 중
    ├─ Cloudflare와 Tunnel 연결 유지
    ├─ 들어온 요청의 public hostname route 확인
    └─ 요청을 Docker network의 caddy:80으로 전달
```

`cloudflared`를 host macOS 서비스로 별도 설치하지 않는다. Docker Desktop이 시작되고 Compose
프로젝트가 복구되면 `restart: unless-stopped`가 컨테이너를 다시 시작한다.

Tunnel token은 해당 Tunnel을 실행할 권한이 있는 비밀값이다.

- Git 저장소, Markdown 문서, 메신저에 넣지 않는다.
- `.env`에 공개 설정처럼 기록하지 않는다.
- 화면 공유와 터미널 기록에 노출하지 않는다.
- 유출이 의심되면 Cloudflare에서 교체한다.

이 프로젝트에서는 token을 서버의 `secrets/cloudflared-tunnel-token` 파일에만 저장하고,
Compose secret으로 `/run/secrets/cloudflared_tunnel_token`에 읽기 전용 mount한다. `cloudflared`
명령행에는 token 문자열이 아니라 `--token-file` 경로만 남긴다.

- [Cloudflare Tunnel token 관리 공식 문서](https://developers.cloudflare.com/tunnel/advanced/tunnel-tokens/)

## 8. Tunnel 상태가 Healthy여도 App은 실패할 수 있다

Cloudflare Dashboard의 Tunnel `Healthy` 또는 connector 연결 성공은 다음 한 구간만 증명한다.

```text
Cloudflare Edge <──── 연결됨 ────> cloudflared
```

다음은 별도로 확인해야 한다.

```text
cloudflared ──> Caddy ──> Spring App ──> DB
```

예를 들어 `cloudflared`가 실행 중이어도 Caddy가 꺼져 있거나 서비스 URL의 포트가 틀리면 외부에서는
502가 발생한다. 따라서 확인 순서는 다음이 정확하다.

1. Docker App이 `healthy`인지 확인한다.
2. Compose 네트워크에서 `http://caddy:80`의 Caddy 응답을 확인한다.
3. `cloudflared` 서비스가 실행 중인지 확인한다.
4. 외부 네트워크에서 공개 HTTPS 주소를 확인한다.

## 9. 외부 연결 검증

### 9.1 기본 HTTPS 확인

서버 자신의 로컬 결과만 보지 말고 휴대전화 셀룰러 또는 다른 외부 네트워크에서도 확인한다.

```bash
curl -I https://test.jinhokingoftheworld.cloud
```

상태 코드가 반드시 200이어야 하는 것은 아니다. 로그인 페이지 리다이렉트 등 App의 정상 정책이
있을 수 있다. 중요한 것은 DNS, TLS, Tunnel을 지나 App 계열 응답이 오는지다.

### 9.2 장치 API 경로 확인

인증 헤더 없이 요청한다.

```bash
curl -i \
  -X POST \
  https://test.jinhokingoftheworld.cloud/api/v1/device/credential-tests
```

이 프로젝트에서 기대하는 무자격 응답은 Spring App의 JSON `401 DEVICE_UNAUTHORIZED`다.
이는 장치 인증 성공이 아니라 다음 경로가 연결되었다는 좋은 증거다.

```text
Cloudflare → Tunnel → Caddy → Spring App
```

반대로 다음 응답은 문제다.

- Cloudflare Access 로그인 HTML
- 브라우저 Challenge HTML
- 다른 hostname으로의 인증 리다이렉트
- Cloudflare 502/1033 오류 페이지

Arduino firmware는 브라우저 화면을 조작하거나 Cloudflare Access의 로그인 절차를 수행할 수 없다.

## 10. Cloudflare Access와 WAF 주의사항

Cloudflare Access는 브라우저 관리자 화면 보호에는 유용할 수 있다. 그러나 현재 Arduino firmware는
Access service token 헤더를 보내지 않는다.

따라서 `/api/v1/device/**`에 다음을 요구하면 장치 요청은 App에 도달하지 못한다.

- 이메일 OTP 로그인
- SSO 로그인
- 브라우저 기반 Managed Challenge
- JavaScript Challenge
- CAPTCHA
- Access service token 전용 정책

장치 API는 Cloudflare를 통과해 Spring App까지 공개적으로 도달할 수 있어야 한다. 공개적이라는
말은 인증이 없다는 뜻이 아니다. 실제 인증은 App의 `X-Device-Code`와 `X-Device-Key`, rate limit,
장치 상태 검증이 담당한다.

관리자 UI와 장치 API에 서로 다른 Cloudflare 정책이 필요하면 hostname 또는 path별 정책을
명시적으로 설계해야 한다. 테스트 단계에서는 전체 hostname에 Access를 걸어 놓고 Arduino가 왜
실패하는지 찾는 구성을 피한다.

## 11. Cloudflare와 Caddy의 책임 비교

| 작업 | Cloudflare | Caddy | Spring App |
| --- | --- | --- | --- |
| 공개 DNS hostname | O | X | X |
| 인터넷 사용자에게 HTTPS 인증서 제시 | O, Tunnel 구성 | 직접 공개 방식일 때 O | X |
| 서버와 Edge 사이 Tunnel | O | X | X |
| Docker network origin 수신 | X | O | X |
| 요청 크기·응답 보안 헤더 | 일부 가능하지만 현재 Caddy 담당 | O | 보조 |
| trusted proxy token 삽입 | X | O | 검증 |
| 장치 code/key 인증 | X | X | O |
| 출석 업무 규칙 | X | X | O |
| DB 저장 | X | X | O |
| Docker 컨테이너 실행 | X | X | X, Docker가 담당 |

기능이 일부 겹칠 수 있어도 현재 구성에서 실제 책임자가 누구인지 기준으로 문제를 찾아야 한다.

## 12. 자주 생기는 오해

### “Cloudflare에 도메인을 연결하려면 서버 배포가 먼저 완료되어야 한다”

절반만 맞다. Cloudflare zone과 Tunnel은 서버 App이 완성되기 전에도 만들 수 있다. 그러나 공개
요청이 정상 응답하려면 서버의 App, Caddy, `cloudflared`가 모두 실행되어야 한다.

즉 설정 생성 순서와 서비스 정상화 순서를 구분해야 한다.

### “Cloudflare Tunnel을 쓰려면 Caddy가 먼저 공개 HTTPS를 제공해야 한다”

틀렸다. 현재 구성에서는 Cloudflare가 공개 HTTPS를 담당하고 Caddy는 Docker network HTTP origin이다.
Caddy가 공개 443 포트나 공개 인증서를 가질 필요가 없다.

### “`ifconfig`의 `en0` IP를 Cloudflare에 넣으면 된다”

틀렸다. `en0`에서 보이는 주소는 대개 LAN 사설 IP다. Tunnel route는 서버 IP 대신 Tunnel을
가리키며, `cloudflared`가 Docker network의 `caddy:80`으로 전달한다.

### “Tunnel이 Healthy면 배포가 끝났다”

틀렸다. Healthy는 connector 연결 상태다. Caddy, App, DB, 장치 API 정책은 별도 검증 대상이다.

### “Cloudflare가 장치 인증도 해 준다”

현재 프로젝트에서는 그렇지 않다. Cloudflare는 전송 경로와 Edge 정책을 담당하고,
장치의 `DEVICE_CODE`와 `DEVICE_KEY` 검증은 Spring App이 수행한다.

### “Cloudflare를 쓰면 Arduino 인증서 업로드가 필요 없다”

틀렸다. Nano 33 IoT의 WiFiNINA는 공개 HTTPS 서버의 인증서 체인을 신뢰해야 한다.
Cloudflare가 정상 인증서를 제공해도 Nano의 신뢰 저장소가 자동으로 갱신되는 것은 아니다.

## 13. 대표 오류와 확인 위치

| 증상 | 의미 또는 가능성 | 확인할 곳 |
| --- | --- | --- |
| Compose가 `!override`를 모름 | Compose 2.24.4 미만 | `docker compose version` |
| DNS 이름을 찾지 못함 | hostname route/DNS 미설정 | Cloudflare DNS, Tunnel public hostname |
| route 저장 시 DNS 충돌 | 같은 hostname 레코드가 이미 존재 | 기존 A/AAAA/CNAME의 목적 확인 |
| Cloudflare 1033 | Cloudflare가 연결된 connector를 찾지 못함 | `cloudflared` 서비스와 Tunnel 상태 |
| Cloudflare 502 | connector는 있으나 Docker origin 연결 실패 가능 | Service URL, Caddy 상태, `caddy:80` |
| 외부 404 | Host Header 또는 App route 불일치 가능 | HTTP Host Header, `PUBLIC_HOST`, App endpoint |
| HTTP 주소가 그대로 서비스됨 | HTTPS 강제 규칙 미설정 | Always Use HTTPS 또는 Redirect Rule |
| Cloudflare HTML/로그인 페이지 | Access/WAF/Challenge가 먼저 응답 | Cloudflare 정책 |
| JSON 401 `DEVICE_UNAUTHORIZED` | 무자격 probe라면 정상 경로 증거 | 다음 단계에서 실제 credential 시험 |
| 실제 credential도 401 | 장치 code/key 불일치 | 관리자 장치 정보와 `config.h` |
| Nano만 TLS 연결 실패 | Root Certificate/NINA firmware 문제 가능 | Arduino IDE 인증서 업로드 |

Cloudflare 공식 문서는 Tunnel이 연결되지 않은 경우와 connector가 origin에 도달하지 못하는 경우를
구분해 진단하도록 안내한다.

- [Cloudflare Tunnel 문제 해결 공식 문서](https://developers.cloudflare.com/tunnel/troubleshooting/)

## 14. 보안 운영 원칙

- Tunnel token, 장치 key, DB password, pepper, proxy token을 서로 다른 비밀값으로 사용한다.
- Tunnel token을 Git에 커밋하지 않는다.
- Caddy의 80 포트를 Docker host에 publish하지 않는다.
- App의 8080 포트를 서버 외부에 publish하지 않는다.
- `CF-Connecting-IP`는 host port 없이 Compose network로 격리된 Caddy에서만 신뢰한다.
- 장치 API에 브라우저 Challenge를 적용하지 않는다.
- Cloudflare 보안 기능이 App 인증을 대체한다고 가정하지 않는다.
- `cloudflared`, Caddy, App 로그에 비밀 헤더가 남지 않도록 한다.

## 15. Cloudflare가 하지 않는 일

Cloudflare는 다음 작업을 하지 않는다.

- ARM64 Docker 이미지 빌드
- 이미지 tar 전송과 `docker load`
- Compose 파일 실행
- `.env` 작성
- DB migration과 최초 관리자 생성
- 부서·교사·장치 데이터 생성
- 장치 credential 검증과 출석 처리
- Arduino sketch 컴파일·업로드
- WiFiNINA Root Certificate 자동 설치

Cloudflare 설정만 먼저 끝내도 origin이 없으면 정상 서비스가 되지 않는다.

## 16. 이 프로젝트의 관련 파일

| 파일 | Cloudflare와의 관계 |
| --- | --- |
| [`compose.cloudflare-tunnel.yaml`](../compose.cloudflare-tunnel.yaml) | Caddy host port를 제거하고 Docker-managed cloudflared 추가 |
| [`ops/caddy/Caddyfile.tunnel`](../ops/caddy/Caddyfile.tunnel) | Cloudflare 헤더를 App용 전달 헤더로 정규화 |
| [`compose.prod.yaml`](../compose.prod.yaml) | Caddy와 App의 Docker 서비스 정의 |
| [`firmware/attend-nfc/config.example.h`](../firmware/attend-nfc/config.example.h) | Arduino 공개 hostname과 장치 credential 예시 |
| [`docs/ARDUINO_CLOUDFLARE_TEST_GUIDE.md`](./ARDUINO_CLOUDFLARE_TEST_GUIDE.md) | 초기 Arduino 테스트 참고 문서. Tunnel 네트워크 구성은 아래 주의사항 적용 |

> 주의: 초기 Arduino 가이드의 `tls internal`과 `https://localhost:443` 설명은 현재 통합 구성과
> 맞지 않는다. 현재 기준은 `http://caddy:80`, `compose.cloudflare-tunnel.yaml`,
> `ops/caddy/Caddyfile.tunnel`이다.

## 17. 핵심 요약

- Cloudflare는 공개 DNS·HTTPS·Edge와 Tunnel 경로를 담당한다.
- `cloudflared`는 Docker 컨테이너에서 Cloudflare로 나가는 연결을 만드는 connector다.
- Tunnel hostname은 `en0`의 사설 IP를 가리키는 것이 아니라 Tunnel route를 가리킨다.
- 현재 public hostname은 `test.jinhokingoftheworld.cloud`, origin은 `http://caddy:80`이다.
- 공개 TLS는 Cloudflare에서 종료되고, 서버 내부에서는 Docker network Caddy를 거쳐 App으로 전달된다.
- Tunnel이 Healthy여도 Caddy와 App이 정상이라는 보장은 없다.
- `/api/v1/device/**`에 브라우저 로그인이나 Challenge를 강제하면 현재 Arduino firmware는 동작하지 않는다.
- 인증 없는 장치 API probe가 Spring JSON 401을 반환하면 네트워크 경로가 연결된 것이다.
