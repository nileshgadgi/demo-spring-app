# demo-spring-app

Spring Boot REST API with a full CI/CD and GitOps setup:

- **App:** Spring Boot 3.3 (Java 17), built with Maven
- **Container:** multi-stage Docker image, pushed to Docker Hub
- **CI/CD:** GitHub Actions (build, test, push image, bump manifest tag)
- **Kubernetes:** k3s single-node cluster on an AWS EC2 instance
- **GitOps:** Argo CD watching the `k8s/` folder with auto-sync, prune and self-heal

Live URL: http://13.239.9.198:8080 (may be offline after the review period)

## Architecture

```
 git push ──> GitHub Actions ──────────────────────────────┐
               1. mvn verify (unit tests)                  │
               2. docker build + push  ──> Docker Hub      │
               3. update image tag in k8s/kustomization ───┘ (commit back to main)
                                                │
                                                ▼
 EC2 (Ubuntu) ── k3s ── Argo CD  <── polls repo, detects new tag, syncs
                         │
                         ▼
                 Deployment (2 pods) ──> Service NodePort 30080
                                               ▲
 Browser ──> :8080 ──> Nginx (reverse proxy) ──┘
```

Argo CD runs inside the cluster and pulls the desired state from Git, so the
pipeline never needs cluster credentials. The only thing CI writes is the image
tag in `k8s/kustomization.yaml`.

## Repository layout

```
.
├── src/                          Spring Boot source and tests
├── pom.xml, mvnw, .mvn/          Maven build (wrapper included)
├── Dockerfile, .dockerignore     Container image
├── .github/workflows/ci-cd.yml   GitHub Actions pipeline
├── k8s/                          Kubernetes manifests (Kustomize)
├── argocd/application.yaml       Argo CD Application
├── infra/                        Server config (k3s, Nginx)
└── screenshots/                  Evidence for each step
```

## API endpoints

| Endpoint                         | Description                                 |
|----------------------------------|---------------------------------------------|
| `GET /`                          | Homepage showing version and serving pod    |
| `GET /api/health`                | Health check (status, version, hostname)    |
| `GET /api/info`                  | App, version, environment, Java version     |
| `GET /api/greeting?name=X`       | Sample endpoint                             |
| `GET /actuator/health/liveness`  | Liveness probe used by Kubernetes           |
| `GET /actuator/health/readiness` | Readiness probe used by Kubernetes          |

## 1. Run locally

Requires Java 17.

```bash
./mvnw clean verify                 # build + run tests
java -jar target/demo-spring-app.jar
curl http://localhost:8080/api/health
```

## 2. Docker

```bash
docker build -t nileshgadgi/demo-spring-app:local .
docker run --rm -p 8080:8080 nileshgadgi/demo-spring-app:local

docker login -u nileshgadgi          # use a Docker Hub access token
docker push nileshgadgi/demo-spring-app:local
```

Image notes:
- Build stage uses the JDK image; runtime uses `eclipse-temurin:17-jre-alpine`.
- Dependencies are resolved in a separate layer so code changes don't re-download them.
- The jar is extracted into Spring Boot layers for better layer caching.
- Container runs as a non-root user.

## 3. CI/CD (GitHub Actions)

Workflow: `.github/workflows/ci-cd.yml`

Triggers on every push to `main` (and manually via *Run workflow*). Pull requests
run only the build and test job.

| Job                 | What it does                                                        |
|---------------------|---------------------------------------------------------------------|
| `build-test`        | `./mvnw clean verify`, uploads Surefire reports                     |
| `docker`            | Builds and pushes `nileshgadgi/demo-spring-app:<short-sha>` and `latest` |
| `update-manifests`  | Writes the new tag into `k8s/kustomization.yaml` and pushes to `main` |

Required repository secrets (Settings > Secrets and variables > Actions):

| Secret               | Value                              |
|----------------------|------------------------------------|
| `DOCKERHUB_USERNAME` | Docker Hub username                |
| `DOCKERHUB_TOKEN`    | Docker Hub access token (read/write) |

Commits that only touch `k8s/`, `argocd/`, `infra/`, `screenshots/` or Markdown
files don't trigger the pipeline, so the tag-bump commit doesn't cause a loop.

## 4. Server setup (k3s + Nginx)

Tested on Ubuntu, 2 vCPU / 2 GB RAM / 20 GB disk. Security group allows 22 and 8080.
The box is small, so the setup is kept minimal: k3s instead of full Kubernetes,
Traefik/ServiceLB/metrics-server disabled, and a 2 GB swap file.

```bash
# swap
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# k3s binary (verify checksum)
V=v1.31.4%2Bk3s1
curl -fsSLO https://github.com/k3s-io/k3s/releases/download/$V/k3s
curl -fsSLO https://github.com/k3s-io/k3s/releases/download/$V/sha256sum-amd64.txt
grep ' k3s$' sha256sum-amd64.txt | sha256sum -c -
sudo install -m 755 k3s /usr/local/bin/k3s
sudo ln -sf /usr/local/bin/k3s /usr/local/bin/kubectl

# config + service (files in infra/k3s/)
sudo mkdir -p /etc/rancher/k3s
sudo cp infra/k3s/config.yaml /etc/rancher/k3s/config.yaml
sudo cp infra/k3s/k3s.service /etc/systemd/system/k3s.service
sudo systemctl daemon-reload && sudo systemctl enable --now k3s

mkdir -p ~/.kube && cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
kubectl get nodes

# Nginx reverse proxy: :8080 -> NodePort 30080
sudo apt-get install -y nginx
sudo cp infra/nginx/demo-spring-app.conf /etc/nginx/sites-available/demo-spring-app
sudo ln -s /etc/nginx/sites-available/demo-spring-app /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl restart nginx
```

## 5. Kubernetes manifests

`k8s/` contains the namespace, Deployment (2 replicas, rolling update with
`maxUnavailable: 0`, startup/liveness/readiness probes, CPU and memory limits)
and a NodePort Service. Kustomize sets the image tag and app version.

Manual deploy without Argo CD:

```bash
kubectl apply -k k8s/
kubectl -n demo-app get pods,svc
```

## 6. GitOps (Argo CD)

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.13.3/manifests/install.yaml

# not needed for this setup, scaled down to save memory
kubectl -n argocd scale deploy argocd-dex-server argocd-notifications-controller \
  argocd-applicationset-controller --replicas=0

kubectl apply -f argocd/application.yaml
kubectl -n argocd get applications
```

The Application uses `automated` sync with `prune` and `selfHeal`, so:
- a new tag committed by CI is deployed automatically (Argo CD polls every ~3 min),
- resources removed from Git are deleted from the cluster,
- manual changes in the cluster (for example `kubectl scale`) are reverted to what Git says.

Argo CD UI (not exposed publicly, use an SSH tunnel):

```bash
# on the server
kubectl -n argocd port-forward svc/argocd-server 8443:443
# on your machine
ssh -i <key>.pem -L 8443:localhost:8443 ubuntu@<server-ip>
# open https://localhost:8443  (user: admin)
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
```

## 7. Verification

```bash
curl http://13.239.9.198:8080/api/health
kubectl -n demo-app get pods -o wide
kubectl -n demo-app get svc
kubectl -n argocd get applications
```

End-to-end check: push a change to `main`, wait for the pipeline to finish,
then refresh the homepage. The version shown matches the new commit SHA and
the pods are replaced through a rolling update.

## Screenshots

See `screenshots/`:

1. App running on localhost:8080
2. Docker build output
3. Docker push to Docker Hub
4. GitHub Actions pipeline run
5. `kubectl apply` output
6. `kubectl get pods` (2 running pods)
7. `kubectl get svc`
8. Argo CD application synced and healthy
9. Homepage in browser
10. `/api/health` response

## Security notes

- No credentials in the repo; Docker Hub credentials live in GitHub Actions secrets.
- The pipeline uses the built-in `GITHUB_TOKEN` to push the tag bump.
- `.gitignore` excludes keys, `.env` files, kubeconfigs, logs and build output.
- Argo CD UI is only reachable through an SSH tunnel.
