# 小公司 CI/CD：Jenkins + GitLab（代码仓）+ Java（Spring Boot）

> 面向 5～20 人、无专职 DevOps 的小团队。读完并完成 **阶段 0 + 阶段 1** 即可按小公司标准上线；文末选修仅为成长地图，不要求一次做完。
>
> 本文档与《GitLab CI》方案**相互独立**，只看本文即可落地。GitLab 在本方案中**只作代码仓库**（及可选 Container Registry），流水线由 **Jenkins** 编排。  
> **实施步骤**见 [implement-jenkins.md](./implement-jenkins.md)（含演示应用 `app/` 与端口 8090 起）。

---

## 1. 定位与边界

### 1.1 目标

- 演示从代码提交到 **dev / test / prod** 的完整链路
- 工具以 **GitLab（仓）+ Jenkins + Agent** 为主
- 应用示例：**Java 21 + Spring Boot 3.5.x + Maven + JUnit**

### 1.2 适用

- 需要熟悉 Jenkins 编排、凭据、Webhook、手动审批
- 能接受维护 Controller 与插件的成本（通常高于纯 GitLab CI）

### 1.3 明确不做（主交付范围）

- 不上 Kubernetes 动态 Agent 信任池 / Argo CD / 金丝雀（见文末选修）
- 不用 Maven Profile 打出多套制品
- 不把 GitLab CI 写进本方案主路径

### 1.4 运维诚实说明

小公司若「从零选型、只求少运维」，业界更常直接用 GitLab CI 或云效。选择本文是因为：**要掌握 Jenkins**，或已有 Jenkins 资产。请预留插件升级与备份 `JENKINS_HOME` 的时间。

---

## 2. 设计原则

1. **一份制品，配置外置**：一次构建，多环境部署。
2. **密钥运行时解析**：jar / 镜像不含生产密钥。
3. **Controller 尽量不跑重构建**：阶段 0～1 可用同机 Agent；有余力再拆 Agent 节点。
4. **prod 必须人工确认**（`input` 或独立手动 Job）。
5. **主交付停在阶段 1**。

---

## 3. 环境与机器规划

| 角色 | 建议规格 | 用途 |
|------|----------|------|
| 平台机 | 8 核 16G，磁盘 ≥100G | GitLab CE（仓）+ Jenkins Controller（+ 可选同机 Agent） |
| 业务机 A | 建议 ≥2C4G | **dev + test**（Docker Compose） |
| 业务机 B | 按应用定 | **仅 prod** |

说明：

- 同机部署 GitLab + Jenkins 时：限制 GitLab 内存，Jenkins 执行器先设 **1～2**，避免 DinD 与 GitLab 抢光内存。
- 阶段 1 镜像仓先用 **本地/内网 registry** 或 GitLab Registry；上云后再迁阿里云 ACR。

### 3.1 分支与晋级（trunk）

| 环境 | 触发 | 策略 |
|------|------|------|
| dev | `main` 推送经 Webhook 触发 Jenkins | 自动构建并部署 |
| test | 同一镜像晋级 | 自动或参数化 Job 一键部署 |
| prod | 打 `v*` tag + **手动批准** | 审批后部署同一镜像 |

镜像 tag 建议：`{version}-{short_sha}`，生产避免只依赖 `latest`。

### 3.2 GitLab ↔ Jenkins 连接

1. GitLab 项目创建 Access Token / Deploy 权限（按最小权限）
2. Jenkins 安装 GitLab 插件（或通用 Git + Generic Webhook）
3. 在 GitLab **Webhook** 指向 Jenkins 触发 URL
4. Jenkins 凭据存储：Git clone、SSH 部署、Registry 登录

### 3.3 实施前资源清单（运维必读）

动手安装前，完整按 [implement-jenkins.md §2](./implement-jenkins.md#2-资源准备必须有--建议有--可选) 分档准备（**必须有 / 建议有 / 可选**），并勾选其中的检查清单。摘要：

| 档 | 内容 |
|----|------|
| **必须有** | 2～3 台 Ubuntu 22.04；端口与防火墙；SSH + `deploy` 密钥；Jenkins 凭据 ID；业务机目录 |
| **建议有** | 开发机 Git / JDK21 / Maven / 浏览器 |
| **可选** | 钉钉通知、HTTPS、独立 Agent、云 ACR |
| **实施前决策** | 实际 IP、Jenkins 端口、阶段 0 或 0+1、禁用 GitLab CI 双开、prod 审批人 |

实施手册已自包含 GitLab 安装、业务机命令与验收步骤，运维只需跟 [implement-jenkins.md](./implement-jenkins.md) 即可落地。

---

## 4. 阶段 0：最小可用 CI（仅 dev，systemd）

### 4.1 目标

**提交 → Jenkins → `mvn verify` → 部署 jar → systemd**。

### 4.2 范围

| 做 | 不做 |
|----|------|
| 多分支或单分支 Pipeline + `Jenkinsfile` | Docker 三环境 |
| SSH/scp 发布到业务机 A | 生产审批流、Shared Library |

### 4.3 应用侧约定

- `mvn -B clean verify` 产出一份 jar
- `SPRING_PROFILES_ACTIVE=dev`
- 敏感项：`/etc/myapp/dev.env`，systemd `EnvironmentFile=`
- 非敏感：仓库内 `application-dev.yml`

### 4.4 Jenkinsfile 逻辑（伪代码）

```groovy
// Jenkinsfile（阶段 0 示意）
pipeline {
  agent any
  options {
    timestamps()
    disableConcurrentBuilds()
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scm
      }
    }
    stage('Build & Test') {
      tools {
        maven 'maven-3.9'
        jdk 'jdk-21'
      }
      steps {
        sh 'mvn -B clean verify'
        archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
      }
    }
    stage('Deploy Dev') {
      when { branch 'main' }
      steps {
        sshagent(credentials: ['deploy-ssh']) {
          sh '''
            JAR=$(ls target/*.jar | head -n1)
            scp "$JAR" deploy@app-dev:/opt/myapp/myapp.jar
            ssh deploy@app-dev 'sudo systemctl restart myapp'
          '''
        }
      }
    }
  }
  post {
    failure {
      // 钉钉/飞书 Webhook，URL 放在 Jenkins 凭据或环境变量
      sh 'curl -s -X POST -H "Content-Type: application/json" -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[Jenkins] $JOB_NAME #$BUILD_NUMBER failed\"}}" "$NOTIFY_WEBHOOK" || true'
    }
  }
}
```

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

### 4.6 阶段 0 验收

- [ ] `main` 推送经 Webhook 触发构建；测试失败不部署
- [ ] dev 服务可访问
- [ ] 密码不在 jar 中
- [ ] 失败可通知钉钉或飞书

---

## 5. 阶段 1：Docker 三环境（主交付终点）

### 5.1 目标

**同一镜像**到 dev / test / prod；prod 经 `input` 批准；配置外置；可回滚。

### 5.2 机器与 Compose

- 业务机 A：`myapp-dev`、`myapp-test` 两套 Compose
- 业务机 B：仅 prod
- 镜像：先推本地 registry / GitLab Registry

### 5.3 构建约定

- 一份 Dockerfile → 一个 `IMAGE_TAG`
- 运行时用 profile + env_file 区分环境
- **禁止**按环境打不同业务包再构建不同镜像作为晋级手段

### 5.4 Jenkinsfile 逻辑（伪代码）

```groovy
pipeline {
  agent any
  environment {
    REGISTRY = 'registry.example.com/group/myapp'
    IMAGE_TAG = "${env.GIT_COMMIT.take(7)}"  // 或结合 pom 版本
  }
  stages {
    stage('Checkout') {
      steps { checkout scm }
    }
    stage('Build & Test') {
      steps {
        sh 'mvn -B clean verify'
      }
    }
    stage('Build & Push Image') {
      when {
        anyOf {
          branch 'main'
          tag pattern: 'v.*', comparator: 'REGEXP'
        }
      }
      steps {
        sh '''
          docker build -t $REGISTRY:$IMAGE_TAG .
          echo "$REGISTRY_PASSWORD" | docker login -u "$REGISTRY_USER" --password-stdin registry.example.com
          docker push $REGISTRY:$IMAGE_TAG
        '''
      }
    }
    stage('Deploy Dev') {
      when { branch 'main' }
      steps {
        sshagent(credentials: ['deploy-ssh']) {
          sh 'ssh deploy@app-dev "cd /opt/myapp-dev && IMAGE_TAG=$IMAGE_TAG docker compose pull && docker compose up -d"'
        }
      }
    }
    stage('Deploy Test') {
      when { branch 'main' }
      steps {
        sshagent(credentials: ['deploy-ssh']) {
          sh 'ssh deploy@app-dev "cd /opt/myapp-test && IMAGE_TAG=$IMAGE_TAG docker compose pull && docker compose up -d"'
        }
      }
    }
    stage('Approve Prod') {
      when { tag pattern: 'v.*', comparator: 'REGEXP' }
      steps {
        input message: "Deploy $IMAGE_TAG to production?", ok: 'Deploy'
      }
    }
    stage('Deploy Prod') {
      when { tag pattern: 'v.*', comparator: 'REGEXP' }
      steps {
        sshagent(credentials: ['deploy-ssh']) {
          sh 'ssh deploy@app-prod "cd /opt/myapp-prod && IMAGE_TAG=$IMAGE_TAG docker compose pull && docker compose up -d"'
        }
      }
    }
  }
  post {
    success {
      sh 'curl -s -X POST -H "Content-Type: application/json" -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[Jenkins] $JOB_NAME #$BUILD_NUMBER OK tag=$IMAGE_TAG\"}}" "$NOTIFY_WEBHOOK" || true'
    }
    failure {
      sh 'curl -s -X POST -H "Content-Type: application/json" -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[Jenkins] $JOB_NAME #$BUILD_NUMBER failed\"}}" "$NOTIFY_WEBHOOK" || true'
    }
  }
}
```

注意：

- **人工审批不要长时间占用会超时的构建节点**；若 Agent 会回收，将 `input` 放在 `agent none` 的 stage，或使用独立「晋级 Job」只负责 prod。
- Registry 账号使用 Jenkins Credentials，勿写进仓库。

### 5.5 Compose 示意

```yaml
services:
  app:
    image: registry.example.com/group/myapp:${IMAGE_TAG}
    env_file: .env
    ports:
      - "8080:8080"
    restart: unless-stopped
```

### 5.6 回滚

1. 在 Jenkins 参数化 Job 填入上一成功 `IMAGE_TAG`，只跑对应环境的 deploy stage；或
2. SSH 到目标机改 tag 后 `docker compose pull && up -d`

### 5.7 阶段 1 验收

- [ ] 一次构建一个镜像 tag，三环境可共用
- [ ] prod 与 test 可用同一 tag/digest 核对
- [ ] prod 必须经过 `input`（或等价手动步骤）
- [ ] 轮换 DB 密码无需重建镜像
- [ ] 回滚演练通过
- [ ] 钉钉/飞书通知可用

---

## 6. 密钥与配置清单

| 类型 | 存放位置 | 示例 |
|------|----------|------|
| CI 密钥 | Jenkins Credentials | Git、SSH、Registry、Webhook |
| Webhook 密钥 | GitLab Webhook Secret + Jenkins 校验 | 防伪造触发 |
| 运行时密钥 | 业务机 env_file（权限 600） | 数据源密码 |
| 非敏感配置 | 仓库 yml / Compose | 端口、日志级别 |

建议：生产相关凭据限制为可部署 prod 的角色可见；普通开发者凭据与 prod SSH 分离（即使同机 Jenkins，也用不同 Credential ID）。

---

## 7. 建议仓库结构（本方案）

```text
myapp/
  src/
  pom.xml
  Dockerfile
  Jenkinsfile
  deploy/
    systemd/myapp.service
    compose/dev/compose.yaml
    compose/test/compose.yaml
    compose/prod/compose.yaml
  README.md
```

若与 GitLab CI 文件同仓并存：本方案**只维护并讲述 `Jenkinsfile`**；不要同时对同一环境开启两套自动部署。

---

## 8. 选修成长路径（大纲，无细步骤）

| 编号 | 方向 | 何时值得做 | 做什么（方向） |
|------|------|------------|----------------|
| G1 | 质量门禁 | 质量问题反复进主干 | SonarQube 插件/步骤；MR/合并前红灯 |
| G2 | Kubernetes | 多服务与弹性需求明确 | Helm + 三 namespace；Jenkins 先 `helm upgrade` |
| G3 | GitOps | 需审计、防漂移、收回集群写权限 | Argo CD；Jenkins 只改部署仓镜像 tag |
| G4 | 灰度 | 有监控且发布风险高 | Rollouts / 网关切流 |
| G5 | 阿里云生产化 | 公网稳定与合规 | ACR + ECS/ACK；KMS；Terraform |
| G6 | Jenkins 工程化 | 多项目复制流水线痛苦 | Shared Library、JCasC、K8s 动态 Agent |

小公司长期停在阶段 1 完全正常；G6 仅在「多服务共用 Jenkins」时再考虑。

---

## 9. 与「GitLab CI 版」文档的关系

- 另有独立文档：`docs/gitlab-ci-small-company.md`
- 阶段目标、机器划分、制品与审批原则对齐
- **操作以本文 Jenkins 步骤为准**；两套 CI 不要同时自动部署同一 prod
