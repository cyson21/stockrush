# Portfolio Visual Assets

## 현재 선택

아키텍처 이미지는 저장소 안에서 관리 가능한 SVG 원본과 PNG 산출물로 둔다.

| 파일 | 용도 |
|---|---|
| `docs/assets/architecture/stockrush-architecture.svg` | 전체 구조 대표 이미지 원본 |
| `docs/assets/architecture/stockrush-architecture.png` | README, Notion, 발표 자료에 바로 넣는 대표 이미지 |
| `docs/assets/architecture/stockrush-saga-flow.svg` | 주문 성공/실패/지연 결제 취소 흐름 원본 |
| `docs/assets/architecture/stockrush-saga-flow.png` | Saga 설명용 PNG |
| `docs/assets/architecture/stockrush-outbox-recovery.svg` | Outbox relay, retry, requeue, consumer 중복 처리 원본 |
| `docs/assets/architecture/stockrush-outbox-recovery.png` | 복구 흐름 설명용 PNG |
| `docs/assets/architecture/stockrush-security-boundary.svg` | Keycloak, Gateway, 신뢰 헤더, 권한 경계 원본 |
| `docs/assets/architecture/stockrush-security-boundary.png` | 보안 설명용 PNG |
| `docs/assets/architecture/stockrush-cicd-runtime.svg` | GitHub Actions, GHCR, Docker Compose, kind 흐름 원본 |
| `docs/assets/architecture/stockrush-cicd-runtime.png` | CI/CD와 실행 환경 설명용 PNG |
| `tools/portfolio-visuals/generate-portfolio-visuals.mjs` | 보조 시각 자료 SVG 생성 스크립트 |

이 방식은 외부 서비스에 의존하지 않고 문서/자산 변경 이력을 추적하기 쉽다. 포트폴리오 README처럼 공개 저장소에서 바로 보여줄 자료에는 이 방식이 가장 단순하다.

## Figma/Canva는 언제 쓰나

현재 이미지는 자체 SVG로 충분하다. 다만 아래 상황에서는 Figma나 Canva를 쓰는 편이 낫다.

| 도구 | 쓰기 좋은 상황 |
|---|---|
| Figma | 면접 발표용 슬라이드에서 레이아웃을 세밀하게 조정하거나, 여러 장의 다이어그램을 같은 디자인 시스템으로 맞출 때 |
| Canva | 자기소개/프로젝트 요약/성과 중심 PPT를 빠르게 만들고, 템플릿 기반으로 시각 밀도를 낮출 때 |

원본은 저장소 SVG로 유지하고, 발표용으로만 Figma/Canva에 가져가는 흐름을 권장한다. 그래야 공개 README, 문서, 발표 자료가 서로 다른 내용을 말하지 않는다.

## 이미지 문구 기준

- 한국어를 기본으로 쓴다.
- Kafka, Saga, Outbox, OIDC, PKCE, Gateway, schema, command/event처럼 업계에서 영어가 더 자연스러운 용어는 그대로 둔다.
- 면접관이 처음 봐도 흐름을 읽을 수 있도록 한 박스에는 한 책임만 적는다.
- 운영 복구, 보안, 검증 게이트가 이미지 안에 같이 보여야 한다.

## PNG 재생성

macOS Chrome이 설치된 환경에서는 아래 방식으로 PNG를 다시 만든다.

```bash
node tools/portfolio-visuals/generate-portfolio-visuals.mjs

/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --headless \
  --disable-gpu \
  --no-sandbox \
  --screenshot=docs/assets/architecture/stockrush-architecture.png \
  --window-size=1600,1040 \
  "file://$PWD/docs/assets/architecture/stockrush-architecture.svg"

for svg in docs/assets/architecture/stockrush-saga-flow.svg \
  docs/assets/architecture/stockrush-outbox-recovery.svg \
  docs/assets/architecture/stockrush-security-boundary.svg \
  docs/assets/architecture/stockrush-cicd-runtime.svg; do
  png="${svg%.svg}.png"
  /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
    --headless \
    --disable-gpu \
    --no-sandbox \
    --screenshot="$png" \
    --window-size=1400,900 \
    "file://$PWD/$svg"
done
```

다른 PC에서는 SVG를 브라우저로 열어 1600x1040 기준으로 export하거나, Figma/Canva에 SVG를 가져온 뒤 PNG로 내보내면 된다.
