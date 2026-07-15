import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// 포트폴리오 소개용 SVG 아키텍처 다이어그램을 렌더링하는 정적 자원 생성기입니다.

const outputDir = join(process.cwd(), 'docs/assets/architecture');
mkdirSync(outputDir, { recursive: true });

const baseStyle = `
  <defs>
    <linearGradient id="hero" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="#111827"/>
      <stop offset="58%" stop-color="#1f2937"/>
      <stop offset="100%" stop-color="#0f766e"/>
    </linearGradient>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#f8fafc"/>
      <stop offset="100%" stop-color="#eef4f0"/>
    </linearGradient>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%">
      <feDropShadow dx="0" dy="8" stdDeviation="11" flood-color="#0f172a" flood-opacity="0.12"/>
    </filter>
    <marker id="arrow" markerWidth="12" markerHeight="12" refX="9" refY="6" orient="auto" markerUnits="strokeWidth">
      <path d="M2,2 L10,6 L2,10 Z" fill="#475569"/>
    </marker>
    <marker id="arrow-teal" markerWidth="12" markerHeight="12" refX="9" refY="6" orient="auto" markerUnits="strokeWidth">
      <path d="M2,2 L10,6 L2,10 Z" fill="#0f766e"/>
    </marker>
    <style>
      .page { fill: url(#bg); }
      .hero { fill: url(#hero); filter: url(#shadow); }
      .title { font: 800 40px "Apple SD Gothic Neo", "Noto Sans KR", Arial, sans-serif; fill: #ffffff; letter-spacing: 0; }
      .subtitle { font: 600 18px "Apple SD Gothic Neo", "Noto Sans KR", Arial, sans-serif; fill: #dbeafe; letter-spacing: 0; }
      .section { font: 800 20px "Apple SD Gothic Neo", "Noto Sans KR", Arial, sans-serif; fill: #0f172a; letter-spacing: 0; }
      .label { font: 800 17px "Apple SD Gothic Neo", "Noto Sans KR", Arial, sans-serif; fill: #0f172a; letter-spacing: 0; }
      .body { font: 500 14px "Apple SD Gothic Neo", "Noto Sans KR", Arial, sans-serif; fill: #475569; letter-spacing: 0; }
      .tiny { font: 700 12px "Apple SD Gothic Neo", "Noto Sans KR", Arial, sans-serif; fill: #64748b; letter-spacing: 0; }
      .white { fill: #ffffff; }
      .panel { fill: rgba(255,255,255,0.86); stroke: #cbd5e1; stroke-width: 1.2; rx: 8; filter: url(#shadow); }
      .node { fill: #ffffff; stroke: #cbd5e1; stroke-width: 1.5; rx: 8; }
      .teal { stroke: #0f766e; }
      .blue { stroke: #2563eb; }
      .orange { stroke: #b45309; }
      .violet { stroke: #4f46e5; }
      .slate { stroke: #64748b; }
      .chip { rx: 8; }
      .chipText { font: 800 13px "Apple SD Gothic Neo", "Noto Sans KR", Arial, sans-serif; fill: #ffffff; letter-spacing: 0; }
      .line { stroke: #475569; stroke-width: 2.4; fill: none; marker-end: url(#arrow); }
      .event { stroke: #0f766e; stroke-width: 3; fill: none; marker-end: url(#arrow-teal); }
      .dash { stroke: #94a3b8; stroke-width: 2; fill: none; stroke-dasharray: 7 8; marker-end: url(#arrow); }
    </style>
  </defs>`;

function page(title, subtitle, content, width = 1400, height = 900) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img">
${baseStyle}
<rect class="page" x="0" y="0" width="${width}" height="${height}"/>
<rect class="hero" x="48" y="36" width="${width - 96}" height="112" rx="8"/>
<text class="title" x="82" y="90">${title}</text>
<text class="subtitle" x="84" y="124">${subtitle}</text>
${content}
</svg>
`;
}

function rect(cls, x, y, w, h, title, lines = []) {
  const body = lines.map((line, index) => `<text class="body" x="${x + 24}" y="${y + 56 + index * 24}">${line}</text>`).join('\n');
  return `<rect class="node ${cls}" x="${x}" y="${y}" width="${w}" height="${h}"/>
<text class="label" x="${x + 24}" y="${y + 32}">${title}</text>
${body}`;
}

const sagaFlow = page(
  '주문 Saga 흐름',
  '성공, 실패, 지연 결제 취소가 하나의 상태 전이로 수렴하는 흐름',
  `
<rect class="panel" x="64" y="188" width="1272" height="620"/>
<text class="section" x="94" y="230">CARD 정상 흐름</text>
<text class="tiny" x="94" y="254">Order Service가 Saga 중심을 잡고, Inventory와 Payment는 결과 이벤트를 돌려준다.</text>
${rect('blue', 96, 300, 170, 92, '고객 앱', ['주문 생성', 'Idempotency-Key'])}
${rect('violet', 306, 300, 170, 92, 'Gateway', ['JWT 검증', 'subject 전달'])}
${rect('teal', 516, 300, 178, 92, 'Order', ['OrderCreated', 'Saga STARTED'])}
${rect('teal', 734, 300, 178, 92, 'Inventory', ['재고 선점', 'Reserved'])}
${rect('teal', 952, 300, 178, 92, 'Payment', ['결제 승인', 'Authorized'])}
${rect('teal', 1160, 300, 128, 92, '완료', ['CONFIRMED', 'COMPLETED'])}
<path class="line" d="M266 346 L306 346"/>
<path class="line" d="M476 346 L516 346"/>
<path class="event" d="M694 346 L734 346"/>
<path class="event" d="M912 346 L952 346"/>
<path class="event" d="M1130 346 L1160 346"/>

<text class="section" x="94" y="480">실패/복구 흐름</text>
${rect('orange', 96, 532, 246, 96, '재고 부족', ['InventoryReservationFailed', '주문 CANCELLED'])}
${rect('orange', 394, 532, 246, 96, '결제 실패', ['PaymentAuthorizationFailed', '예약 재고 복구'])}
${rect('orange', 692, 532, 246, 96, '지연 결제', ['PaymentAuthorizationDelayed', '관리자 취소 대기'])}
${rect('slate', 990, 532, 246, 96, '관리자 취소', ['PaymentCancelRequested', 'PaymentCanceled'])}
<path class="dash" d="M342 580 L394 580"/>
<path class="dash" d="M640 580 L692 580"/>
<path class="dash" d="M938 580 L990 580"/>

<rect class="chip" x="96" y="708" width="152" height="34" fill="#0f766e"/>
<text class="chipText" x="118" y="731">핵심 검증</text>
<text class="body" x="270" y="731">동일 SKU 동시 주문, 대량 멱등성 replay, DELAY_CARD 관리자 취소, 최종 Outbox 잔여분 0</text>
`
);

const outboxFlow = page(
  'Outbox 복구 흐름',
  'Kafka 발행 실패와 중복 수신을 운영 가능한 상태로 남기는 구조',
  `
<rect class="panel" x="64" y="188" width="1272" height="620"/>
${rect('teal', 96, 300, 220, 110, '서비스 트랜잭션', ['도메인 상태 저장', 'Outbox row 저장', '같은 DB 트랜잭션'])}
${rect('teal', 374, 300, 210, 110, 'Outbox Table', ['PENDING', 'retry_count', 'error_message'])}
${rect('blue', 642, 300, 210, 110, 'Relay Scheduler', ['claim', 'publish', '상태 전이'])}
${rect('blue', 910, 300, 190, 110, 'Apache Kafka', ['topic 발행', 'partition key'])}
${rect('teal', 1154, 300, 150, 110, 'Consumer', ['processed', '중복 방지'])}
<path class="line" d="M316 356 L374 356"/>
<path class="line" d="M584 356 L642 356"/>
<path class="event" d="M852 356 L910 356"/>
<path class="event" d="M1100 356 L1154 356"/>

<text class="section" x="96" y="500">실패 시 운영 경로</text>
${rect('orange', 96, 552, 250, 106, '발행 실패', ['PENDING 재시도', '한계 초과 시 FAILED'])}
${rect('orange', 408, 552, 250, 106, '관리자 화면', ['FAILED 조회', 'retry / requeue'])}
${rect('teal', 720, 552, 250, 106, '재처리', ['FAILED -> PENDING', '에러 정보 초기화'])}
${rect('slate', 1032, 552, 250, 106, '감사 기록', ['operator id', 'admin action row'])}
<path class="dash" d="M346 606 L408 606"/>
<path class="dash" d="M658 606 L720 606"/>
<path class="dash" d="M970 606 L1032 606"/>

<rect class="chip" x="96" y="728" width="152" height="34" fill="#0f766e"/>
<text class="chipText" x="118" y="751">설계 포인트</text>
<text class="body" x="270" y="751">장애를 숨기지 않고 조회 가능한 상태로 남겨 재시도, requeue, 감사 로그로 이어지게 한다.</text>
`
);

const securityFlow = page(
  '보안 경계',
  '클라이언트가 보낸 식별자를 믿지 않고 Gateway에서 검증한 주체만 내부로 전달',
  `
<rect class="panel" x="64" y="188" width="1272" height="620"/>
${rect('blue', 96, 300, 210, 112, 'Web / Mobile', ['Authorization Code + PKCE', 'Bearer token 전달'])}
${rect('violet', 366, 300, 210, 112, 'Keycloak', ['issuer / jwks', 'CUSTOMER / ADMIN'])}
${rect('violet', 636, 300, 220, 112, 'Gateway', ['JWT 검증', 'role route', 'spoof header 제거'])}
${rect('teal', 916, 300, 190, 112, 'Customer API', ['subject 기준', '주문 소유권 검사'])}
${rect('teal', 1158, 300, 150, 112, 'Admin API', ['ROLE_ADMIN', 'operator 전파'])}
<path class="line" d="M306 356 L366 356"/>
<path class="line" d="M576 356 L636 356"/>
<path class="line" d="M856 356 L916 356"/>
<path class="line" d="M856 386 C960 456, 1060 356, 1158 356"/>

<text class="section" x="96" y="510">Gateway가 재작성하는 내부 헤더</text>
<rect class="node slate" x="96" y="552" width="1180" height="118"/>
<text class="body" x="126" y="590">X-StockRush-Subject: JWT sub에서 생성</text>
<text class="body" x="126" y="618">X-StockRush-Roles: realm/client role을 애플리케이션 role로 정규화</text>
<text class="body" x="126" y="646">X-Operator-Id: 관리자 감사 row에 사용할 인증 주체 기반 operator</text>

<rect class="chip" x="96" y="730" width="152" height="34" fill="#0f766e"/>
<text class="chipText" x="118" y="753">검증 항목</text>
<text class="body" x="270" y="753">401, 403, 다른 고객 주문 조회 차단, client spoof header 덮어쓰기, 관리자 감사 주체 기록</text>
`
);

const cicdFlow = page(
  'CI/CD와 실행 환경',
  '개인 AWS 없이 GitHub Actions, GHCR, Docker Compose, kind로 재현 가능한 배포 흐름',
  `
<rect class="panel" x="64" y="188" width="1272" height="620"/>
${rect('slate', 96, 300, 180, 106, 'main push', ['문서/코드 변경', 'GitHub trigger'])}
${rect('blue', 326, 300, 214, 106, 'CI', ['Maven / Vitest', 'mobile typecheck', 'Architecture Guard'])}
${rect('orange', 590, 300, 214, 106, '보안 게이트', ['secret scan', 'Trivy fs scan', 'AWS 사용 차단'])}
${rect('teal', 854, 300, 214, 106, 'Release Images', ['Docker build', 'GHCR push', 'image scan'])}
${rect('teal', 1118, 300, 170, 106, 'Local Deploy', ['compose', 'kind smoke'])}
<path class="line" d="M276 354 L326 354"/>
<path class="line" d="M540 354 L590 354"/>
<path class="line" d="M804 354 L854 354"/>
<path class="line" d="M1068 354 L1118 354"/>

<text class="section" x="96" y="506">로컬 재현 경로</text>
${rect('teal', 96, 552, 260, 106, 'Docker Compose Demo', ['인프라 + 서비스 + 웹앱', 'macOS / Windows 11'])}
${rect('teal', 426, 552, 260, 106, 'kind Kubernetes', ['GHCR image 배포', 'Gateway / Keycloak smoke'])}
${rect('slate', 756, 552, 260, 106, 'Runbook', ['실행/점검/종료 명령', '포트와 준비물 정리'])}
${rect('orange', 1086, 552, 190, 106, 'Non-goal', ['회사 AWS 미사용', '개인 cloud 의존 없음'])}
<path class="dash" d="M356 606 L426 606"/>
<path class="dash" d="M686 606 L756 606"/>
<path class="dash" d="M1016 606 L1086 606"/>

<rect class="chip" x="96" y="730" width="152" height="34" fill="#0f766e"/>
<text class="chipText" x="118" y="753">설명 포인트</text>
<text class="body" x="270" y="753">빌드 검증, 이미지 발행, 로컬 배포 smoke가 분리되어 어디서 실패했는지 설명할 수 있다.</text>
`
);

const files = new Map([
  ['stockrush-saga-flow.svg', sagaFlow],
  ['stockrush-outbox-recovery.svg', outboxFlow],
  ['stockrush-security-boundary.svg', securityFlow],
  ['stockrush-cicd-runtime.svg', cicdFlow],
]);

for (const [fileName, contents] of files) {
  writeFileSync(join(outputDir, fileName), contents);
}

console.log(`Generated ${files.size} portfolio SVG files in ${outputDir}`);
