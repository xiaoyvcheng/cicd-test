# cicd-test

小公司 Java CI/CD 实战演示仓库：**一份 Spring Boot 应用**，两套独立 CI 方案（GitLab CI / Jenkins + GitLab 仓）。

## 演示应用

- 路径：`app/`
- 技术栈：Java 21、Spring Boot 3.4、Maven、JUnit
- 端口：**dev 8090** / **test 8091** / **prod 8092**（容器内 8080，宿主机映射）

### 本地运行

```bash
cd app
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 浏览器 http://localhost:8090/
```

### 接口

| 路径 | 说明 |
|------|------|
| `/` | 极简首页（环境徽章 + 版本信息） |
| `/api/info` | 环境、版本、git commit |
| `/api/hello` | 问候语 |
| `/actuator/health` | 健康检查 |

## 文档

| 类型 | GitLab CI 版 | Jenkins 版 |
|------|--------------|------------|
| 方案说明 | [docs/gitlab-ci-small-company.md](docs/gitlab-ci-small-company.md) | [docs/jenkins-gitlab-small-company.md](docs/jenkins-gitlab-small-company.md) |
| **实施手册** | [docs/implement-gitlab-ci.md](docs/implement-gitlab-ci.md) | [docs/implement-jenkins.md](docs/implement-jenkins.md)（运维照做；含 [§2 必须/建议/可选](docs/implement-jenkins.md#2-资源准备必须有--建议有--可选)） |

## 仓库结构

```text
app/                 # Spring Boot 演示应用
deploy/
  systemd/           # 阶段 0
  compose/           # 阶段 1（dev/test/prod）
.gitlab-ci.yml       # GitLab CI 流水线（勿与 Jenkins 同时打 prod）
Jenkinsfile          # Jenkins 流水线
docs/                # 方案 + 实施文档
```

## 重要提醒

- **Build Once, Promote Many**：三环境使用同一镜像 tag。
- **勿双开**：同一 prod 不要同时启用 GitLab CI 与 Jenkins 自动部署。
