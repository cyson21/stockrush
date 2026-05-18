# CI/CD 운영 기준

StockRush CI/CD는 회사 AWS 계정이나 로컬 AWS profile을 사용하지 않는다. 개인 GitHub 저장소, GitHub Actions, GitHub Container Registry, Docker Compose 기반 로컬 배포로 구성한다.

## 파이프라인 구조

```text
push / pull request
  -> CI
     -> backend Maven tests
     -> web app tests and builds
     -> mobile app tests and typecheck
     -> local-e2e runner unit tests
     -> Architecture Guard
     -> secret scan and Trivy dependency scan
  -> Release Images
     -> GHCR image publish after CI success on main
     -> Trivy image scan
  -> Local Deploy
     -> pull GHCR images
     -> docker compose up with image override
     -> demo smoke
```

## GitHub Actions

| Workflow | Trigger | Purpose |
|---|---|---|
| `CI` | `push`, `pull_request`, `workflow_dispatch` | 코드 품질 관문 |
| `Release Images` | successful `CI` on `main`, `workflow_dispatch` | Docker image 발행 |

`CI`는 `infra/local/docker-compose.yml`로 PostgreSQL과 Kafka를 띄운 뒤 서비스별 Maven 테스트를 실행한다. 웹앱은 `npm ci`, `npm test`, `npm run build`를 실행하고, 모바일 앱은 Jest, TypeScript typecheck, scaffold validation을 실행한다.

또한 `CI` Tools 관문에서는 `./scripts/check-no-committed-secrets.sh`로 커밋된 비밀을 차단하고, Trivy `fs` 스캔으로 `services`와 `apps` 경로를 `HIGH/CRITICAL` 기준으로 점검한다.

`Release Images`는 backend 8개 서비스와 customer/admin web app을 GHCR에 발행한다. 이미지는 `linux/amd64`, `linux/arm64` 두 platform manifest로 발행해 Windows 11/일반 x86 PC와 Apple Silicon Mac에서 같은 태그를 pull할 수 있게 한다. 발행된 태그는 Trivy 이미지 스캔을 통과해야 release job이 성공한다.

이미지 이름:

- `ghcr.io/cyson21/stockrush-catalog-service`
- `ghcr.io/cyson21/stockrush-inventory-service`
- `ghcr.io/cyson21/stockrush-order-service`
- `ghcr.io/cyson21/stockrush-payment-service`
- `ghcr.io/cyson21/stockrush-promotion-service`
- `ghcr.io/cyson21/stockrush-fulfillment-service`
- `ghcr.io/cyson21/stockrush-read-model-service`
- `ghcr.io/cyson21/stockrush-gateway`
- `ghcr.io/cyson21/stockrush-customer-app`
- `ghcr.io/cyson21/stockrush-admin-app`

기본 태그:

- `latest-demo`: main 기준 최신 데모 이미지
- `git-<short-sha>`: 특정 커밋 이미지

## 로컬 배포

GHCR image pull 권한이 필요하면 먼저 Docker에 로그인한다.

```bash
docker login ghcr.io -u cyson21 --password-stdin < ~/.config/stockrush/github-token
```

로컬 pull에 쓰는 개인 토큰에는 `read:packages` 권한이 필요하다. package visibility를 public으로 바꾸기 전까지 private GHCR image는 이 권한 없이는 `denied`가 반환된다.

Docker login 성공은 package pull 권한을 보장하지 않는다. `deploy-local`이 image manifest preflight에서 멈추면 아래를 먼저 확인한다.

- Fine-grained PAT라면 repository access에 `cyson21/stockrush`가 포함되어 있는지 확인한다.
- package 권한에 `Read`가 포함되어 있는지 확인한다.
- 외부 공유용이면 GitHub package visibility를 public으로 바꿔 토큰 없이 pull 가능한 상태인지 확인한다.

배포:

```bash
./scripts/deploy-local.sh --tag latest-demo
```

Windows 11 PowerShell:

```powershell
.\scripts\deploy-local.ps1 --tag latest-demo
```

배포 스크립트는 `infra/demo/docker-compose.yml`에 `infra/demo/docker-compose.images.yml`을 겹쳐서 사용한다. 로컬 build 대신 GHCR 이미지를 pull하고 `--no-build`로 컨테이너를 기동한 뒤 `demo-smoke`를 실행한다.

토큰 파일로 Docker login까지 함께 실행하려면:

```bash
./scripts/deploy-local.sh --login --tag latest-demo
```

```powershell
.\scripts\deploy-local.ps1 --login --tag latest-demo
```

## AWS 차단 기준

- GitHub Actions workflow에 AWS credential, AWS CLI, AWS action을 사용하지 않는다.
- 로컬 배포 스크립트는 `~/.aws`와 환경 변수 `AWS_*`를 읽지 않는다.
- 이미지는 GHCR에만 발행한다.
- 배포 대상은 현재 PC의 Docker Compose runtime이다.
- `./scripts/check-no-aws-usage.sh`가 workflow, script, demo compose에서 AWS 관련 참조를 검사한다.

## 운영상 주의

- private repository의 GHCR package는 기본적으로 private일 수 있다. 외부 공유 전에 package visibility를 확인한다.
- 로컬 배포 토큰에 `read:packages`가 없으면 `deploy-local`은 image manifest preflight에서 중단한다.
- `latest-demo`는 편의 태그이므로 이력 추적과 문제 재현에는 `git-<short-sha>` 태그를 우선 사용한다.
- self-hosted runner는 아직 사용하지 않는다. 추가할 경우 protected branch, 수동 dispatch, 전용 runner label을 먼저 설정한다.
