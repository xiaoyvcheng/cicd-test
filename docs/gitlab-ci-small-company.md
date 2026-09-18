# 小公司 CI/CD：GitLab CI + Java（Spring Boot）

> 面向 5～20 人、无专职 DevOps 的小团队。读完并完成 **阶段 0 + 阶段 1** 即可按小公司标准上线；文末选修仅为成长地图，不要求一次做完。
>
> 本文档与《Jenkins + GitLab 仓》方案**相互独立**，只看本文即可落地。  
> **实施步骤**见 [implement-gitlab-ci.md](./implement-gitlab-ci.md)（含演示应用 `app/` 与端口 8090 起）。

---

## 1. 定位与边界

### 1.1 目标

- 演示从代码提交到 **dev / test / prod** 的完整链路
- 工具以 **GitLab（自建 CE）+ GitLab CI + Runner** 为主
- 应用示例：**Java 21 + Spring Boot 3.5.x + Maven + JUnit**

### 1.2 适用

- 希望少运维、流水线与代码仓一体
- 能接受一台中等配置机器自建 GitLab

### 1.3 明确不做（主交付范围）

- 不上 Kubernetes / Argo CD / 金丝雀（见文末选修）
- 不用 Maven Profile 打出多套制品
- 不把 Jenkins 写进本方案主路径

---

## 2. 设计原则

1. **一份制品，配置外置**：一次构建，多环境部署；环境差异只靠配置与密钥注入。
2. **密钥运行时解析**：jar / 镜像内不含数据库密码等敏感信息。
3. **prod 必须人工确认**：dev / test 尽量自动。
4. **主交付停在阶段 1**：能稳定发版再考虑选修。

---

## 3. 环境与机器规划

| 角色 | 建议规格 | 用途 |
|------|----------|------|
| 平台机 | 8 核 16G，磁盘 ≥100G | GitLab CE + GitLab Runner |
| 业务机 A | 按应用定（建议 ≥2C4G） | **dev + test**（Docker Compose 两套 stack 或不同项目名） |
| 业务机 B | 按应用定 | **仅 prod** |

说明：

- GitLab 较吃内存：限制 Sidekiq/Puma 并发，Runner `concurrent` 建议先设为 `1`～`2`。
- 阶段 1 镜像仓库先用 **本机/内网简易 registry**（或 GitLab Container Registry）；上云后再迁阿里云 ACR。

### 3.1 分支与晋级（trunk）

| 环境 | 触发 | 策略 |
|------|------|------|
| dev | `main` 分支 push（且流水线成功） | 自动构建并部署 |
| test | 同一镜像晋级（手动 job 或受控规则） | 自动或一键晋级 |
| prod | 打 `v*` tag + **手动 job**（保护环境） | 审批后部署同一镜像 |

版本与镜像 tag 建议：`{version}-{short_sha}`，禁止生产使用 `latest` 作为唯一依据。

---

## 4. 阶段 0：最小可用 CI（仅 dev，systemd）

### 4.1 目标

一周内跑通：**提交 → 构建 → 测试 → 部署到开发机 → systemd 托管进程**。

### 4.2 范围

| 做 | 不做 |
|----|------|
| `.gitlab-ci.yml`：checkout、`mvn verify`、部署 jar | Docker / 三环境 / 镜像仓 |
| 开发机 systemd unit | 生产审批、多 Runner 池 |

### 4.3 应用侧约定

- 构建：`mvn -B clean verify`（一份 jar）
- 启动：`SPRING_PROFILES_ACTIVE=dev`
- 敏感配置：开发机 `/etc/myapp/dev.env`（或等价路径），由 systemd `EnvironmentFile=` 加载
- `application-dev.yml` 只放非敏感项（端口、日志级别、库主机名占位说明等）

### 4.4 流水线逻辑（伪代码）

```yaml
# .gitlab-ci.yml（阶段 0 示意）
stages:
  - build
  - deploy

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"

build:
  stage: build
  image: maven:3.9-eclipse-temurin-21
  script:
    - mvn -B clean verify
  artifacts:
    paths:
      - target/*.jar
    expire_in: 7 days
  rules:
    - if: $CI_COMMIT_BRANCH == "main"

deploy_dev:
  stage: deploy
  image: alpine:3.20
  needs: ["build"]
  environment:
    name: dev
  before_script:
    # 使用 CI 变量中的 SSH 私钥部署到业务机 A
    - apk add --no-cache openssh-client
    - eval $(ssh-agent -s) && ssh-add <(echo "$SSH_PRIVATE_KEY")
  script:
    - scp target/*.jar deploy@app-dev:/opt/myapp/myapp.jar
    - ssh deploy@app-dev 'sudo systemctl restart myapp'
  rules:
    - if: $CI_COMMIT_BRANCH == "main"
```

> 收紧 SSH 用户权限（仅部署所需目录 + systemctl）。

### 4.5 systemd 单元示意

```ini
# /etc/systemd/system/myapp.service
[Unit]
Description=MyApp Spring Boot
After=network.target

[Service]
User=myapp
EnvironmentFile=/etc/myapp/dev.env
WorkingDirectory=/opt/myapp
ExecStart=/usr/bin/java -jar /opt/myapp/myapp.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

### 4.6 通知

- GitLab 集成邮件，或失败时调用钉钉/飞书 Webhook（CI 变量保存 URL）。

### 4.7 阶段 0 验收

- [ ] `main` 推送自动跑测试；失败不部署
- [ ] 成功后 dev 机服务可访问
- [ ] jar 内无数据库密码；密码仅在 EnvironmentFile
- [ ] 失败有通知

---

## 5. 阶段 1：Docker 三环境（主交付终点）

### 5.1 目标

**同一镜像**部署到 dev / test / prod；prod 人工确认；配置外置；可回滚。

### 5.2 机器与 Compose

- **业务机 A**：`docker compose` 管理 `dev`、`test` 两套（不同 project name / 端口 / 数据卷）
- **业务机 B**：仅 `prod` 一套 Compose
- 镜像来源：阶段内先用 **GitLab Container Registry** 或内网 registry；不必一开始上 ACR

### 5.3 构建约定

- Dockerfile：多阶段或 Spring Boot layered jar（推荐 layered）
- 流水线：`mvn verify` → `docker build` → `docker push`（tag = 版本 + short sha）
- 运行：`SPRING_PROFILES_ACTIVE=dev|test|prod` + env_file / Compose `environment`

**禁止**：为每个环境单独 `mvn -Pxxx package` 打出不同业务 jar 再分别构建镜像。

### 5.4 流水线逻辑（伪代码）

```yaml
stages:
  - build
  - deploy_dev
  - deploy_test
  - deploy_prod

build_image:
  stage: build
  # 使用 Kaniko/Buildah/Docker-in-Docker 之一；小公司可用 shell executor + 主机 Docker
  script:
    - mvn -B clean verify
    - docker build -t $CI_REGISTRY_IMAGE:$IMAGE_TAG .
    - docker push $CI_REGISTRY_IMAGE:$IMAGE_TAG
  rules:
    - if: $CI_COMMIT_BRANCH == "main"
    - if: $CI_COMMIT_TAG =~ /^v/

deploy_dev:
  stage: deploy_dev
  environment: { name: dev }
  script:
    - ssh deploy@app-dev "cd /opt/myapp-dev && IMAGE_TAG=$IMAGE_TAG docker compose pull && docker compose up -d"
  rules:
    - if: $CI_COMMIT_BRANCH == "main"

deploy_test:
  stage: deploy_test
  environment: { name: test }
  needs: ["deploy_dev"]   # 或改为手动；按团队习惯
  script:
    - ssh deploy@app-dev "cd /opt/myapp-test && IMAGE_TAG=$IMAGE_TAG docker compose pull && docker compose up -d"
  rules:
    - if: $CI_COMMIT_BRANCH == "main"

deploy_prod:
  stage: deploy_prod
  environment:
    name: production
    # GitLab：将该环境设为 Protected，仅 Maintainer 可跑
  when: manual
  script:
    - ssh deploy@app-prod "cd /opt/myapp-prod && IMAGE_TAG=$IMAGE_TAG docker compose pull && docker compose up -d"
  rules:
    - if: $CI_COMMIT_TAG =~ /^v/
```

### 5.5 Compose 示意（单环境一份）

```yaml
# 各环境目录各自一份 compose.yaml，仅环境变量与端口不同
services:
  app:
    image: registry.example.com/group/myapp:${IMAGE_TAG}
    env_file: .env          # 不进 Git；机器上维护或由保密变量生成
    ports:
      - "8080:8080"
    restart: unless-stopped
```

### 5.6 回滚

1. 记录上次成功的 `IMAGE_TAG`
2. 在对应机器上改 tag，`docker compose pull && up -d`
3. 或在 GitLab 对上一成功流水线的 prod 手动 job 使用旧 tag 变量重跑

### 5.7 阶段 1 验收

- [ ] 任意一次成功构建只产生 **一个** 镜像 tag，三环境可共用
- [ ] 可通过镜像 digest / tag 证明 prod 与 test 同源
- [ ] prod 仅手动（或保护环境）可部署
- [ ] 改库密码无需重新构建镜像
- [ ] 有回滚步骤且演练过至少一次
- [ ] 失败/成功可通知钉钉或飞书

---

## 6. 密钥与配置清单

| 类型 | 存放位置 | 示例 |
|------|----------|------|
| CI 密钥 | GitLab CI/CD Variables（Mask + Protect） | SSH 私钥、Registry 密码、Webhook |
| 运行时密钥 | 各业务机 env_file / 文件权限 600 | `SPRING_DATASOURCE_PASSWORD` |
| 非敏感配置 | 仓库内 `application-*.yml` 或 Compose 非密环境变量 | 日志级别、对外端口 |

---

## 7. 建议仓库结构（本方案）

```text
myapp/
  src/
  pom.xml
  Dockerfile
  .gitlab-ci.yml
  deploy/
    systemd/myapp.service          # 阶段 0
    compose/dev/compose.yaml
    compose/test/compose.yaml
    compose/prod/compose.yaml
  docs/                            # 可放本说明的副本或链接
  README.md
```

---

## 8. 选修成长路径（大纲，无细步骤）

主交付完成后，**仅当出现对应痛点**再开：

| 编号 | 方向 | 何时值得做 | 做什么（方向） |
|------|------|------------|----------------|
| G1 | 质量门禁 | 低级 bug / 依赖漏洞反复进主干 | MR 必过测；引入 Sonar 或依赖扫描；失败阻断合并 |
| G2 | Kubernetes | 多服务、要弹性、已有人运维 ACK | 同集群多 namespace + Helm；CI 仍可先 `helm upgrade` |
| G3 | GitOps | 多人改集群、要审计与防漂移 | Argo CD；CI 只更新 Git 中的镜像 tag |
| G4 | 灰度发布 | 故障影响面大且已有监控 | Argo Rollouts 或网关按比例切流 |
| G5 | 阿里云生产化 | 要稳定公网与备份、合规 | ACR + ECS/ACK；密钥进 KMS；IaC（Terraform）重建环境 |

以上条目**不是**本阶段必做清单；小公司长期停在阶段 1 完全正常。

---

## 9. 与「Jenkins 版」文档的关系

- 另有独立文档：`docs/jenkins-gitlab-small-company.md`
- 业务目标对齐，**工具链与操作步骤以本文为准**
- 不要求同时启用两套 CI 自动部署同一环境；实验时可分时使用，避免平台机过载
