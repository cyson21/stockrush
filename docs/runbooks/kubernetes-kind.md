# Kubernetes kind Demo

`kind`는 로컬 Docker 위에 Kubernetes 클러스터를 만드는 도구다. StockRush에서는 클라우드 비용 없이 Kubernetes 배포 구조를 보여주기 위한 선택 실행 경로로 사용한다.

이 경로는 `main`에서 발행된 GHCR 이미지를 가져와 로컬 Kubernetes에 올린다. 평소 빠른 데모는 Docker Compose를 쓰고, Kubernetes 경험과 배포 구조를 보여줄 때 이 runbook을 사용한다.

## 준비

필요한 것:

- Docker Desktop
- `kind`
- `kubectl`
- GHCR image pull 권한

설치 예시:

```bash
brew install kind kubectl
```

Windows 11에서는 PowerShell 또는 WSL2 환경에서 아래처럼 설치할 수 있다.

```powershell
winget install Kubernetes.kind
winget install Kubernetes.kubectl
```

이미지가 private이면 먼저 Docker에 로그인한다.

```bash
docker login ghcr.io -u cyson21 --password-stdin < ~/.config/stockrush/github-token
```

package visibility가 public이면 로그인 없이 pull할 수 있다.

## 실행

기본 태그는 `latest-demo`다.

```bash
./scripts/kind-up.sh --tag latest-demo
```

스크립트가 하는 일:

- `stockrush` kind cluster 생성
- `stockrush` namespace 생성
- 로컬 Docker 로그인 정보를 Kubernetes image pull secret으로 연결
- PostgreSQL, Redis, Kafka, Keycloak 배포
- Gateway, 7개 backend service, customer/admin web app 배포
- Kafka topic 초기화 Job 완료 대기
- 모든 Deployment rollout 대기

특정 이미지 태그를 확인하려면 `git-<short-sha>` 태그를 사용한다.

```bash
./scripts/kind-up.sh --tag git-abc1234
```

## 점검

```bash
./scripts/kind-smoke.sh
```

smoke script는 port-forward를 열고 아래 endpoint를 확인한다.

| 대상 | 로컬 주소 |
|---|---|
| Gateway health | `http://localhost:38080/actuator/health` |
| Customer App | `http://localhost:35173/` |
| Admin App | `http://localhost:35174/` |
| Keycloak realm | `http://localhost:38088/realms/stockrush/.well-known/openid-configuration` |

수동으로 화면을 볼 때는 smoke script 실행 중에는 port-forward가 유지되지 않는다. 직접 확인하려면 별도 터미널에서 필요한 port-forward를 띄운다.

```bash
kubectl -n stockrush port-forward svc/customer-app 35173:8080
kubectl -n stockrush port-forward svc/admin-app 35174:8080
kubectl -n stockrush port-forward svc/gateway 38080:18080
kubectl -n stockrush port-forward svc/keycloak 38088:8080
```

## 종료

```bash
./scripts/kind-down.sh
```

cluster 전체를 삭제하므로 namespace, pod, volume도 함께 사라진다.

## CI/CD와의 관계

빌드는 GitHub Actions에서 한다.

- `CI`: 서비스 테스트, 웹/모바일 테스트, Architecture Guard, secret scan, Trivy filesystem scan
- `Release Images`: `main`의 CI 통과 후 GHCR image publish, image scan

배포는 두 가지 로컬 경로가 있다.

- Docker Compose: `./scripts/deploy-local.sh --tag latest-demo`
- Kubernetes kind: `./scripts/kind-up.sh --tag latest-demo`

Compose는 가장 단순한 포트폴리오 데모 경로다. kind는 Kubernetes를 처음 접하는 환경에서도 실제 Deployment, Service, Job, ConfigMap, Secret, image pull secret 흐름을 확인하기 위한 경로다.

## 문제 확인

pod 상태:

```bash
kubectl -n stockrush get pods
```

이벤트:

```bash
kubectl -n stockrush get events --sort-by=.lastTimestamp
```

특정 서비스 로그:

```bash
kubectl -n stockrush logs deployment/order-service --tail=200
kubectl -n stockrush logs job/kafka-init --tail=200
```

자주 보는 원인:

- Docker Desktop이 꺼져 있으면 cluster 생성이 실패한다.
- GHCR image가 private이고 Docker login이 없으면 `ImagePullBackOff`가 발생한다.
- `latest-demo`가 기대한 커밋이 아니면 `git-<short-sha>` 태그로 다시 실행한다.
- 처음 실행은 PostgreSQL, Keycloak, backend image pull 때문에 몇 분 걸릴 수 있다.
