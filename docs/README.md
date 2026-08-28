# Attend 문서 안내

`docs/` 루트에는 현재 코드를 실행·배포·연동할 때 직접 사용하는 문서만 둔다.
현재 동작의 최종 기준은 애플리케이션 코드, 자동 테스트와 Flyway migration이다.

## 현재 사용 문서

| 문서 | 용도 |
|---|---|
| [device-api.yaml](./device-api.yaml) | NFC 장치 HTTP 계약 |
| [LOCAL_HTTP_DEMO.md](./LOCAL_HTTP_DEMO.md) | 하드웨어 없는 로컬 장치 API 시험 |
| [ARDUINO_CLOUDFLARE_TEST_GUIDE.md](./ARDUINO_CLOUDFLARE_TEST_GUIDE.md) | Arduino 실기기 출석 시험 |
| [ARM64_DOCKER_CLOUDFLARE_ARDUINO_DEPLOYMENT_GUIDE.md](./ARM64_DOCKER_CLOUDFLARE_ARDUINO_DEPLOYMENT_GUIDE.md) | ARM64 Docker·Cloudflare·Arduino 배포 |
| [CADDY_INTRODUCTION.md](./CADDY_INTRODUCTION.md) | Caddy 구성의 배경과 역할 |
| [CLOUDFLARE_INTRODUCTION.md](./CLOUDFLARE_INTRODUCTION.md) | Cloudflare Tunnel 구성의 배경과 역할 |

## 설계·계획 기록

초기 요구사항, 설계 판단과 구현 계획은
[design-and-plans](./design-and-plans/README.md)에 보관한다. 이 문서들은 의사결정의
배경을 설명하기 위한 기록이며, 현재 코드와 충돌하면 코드·테스트·Flyway migration을
우선한다.

