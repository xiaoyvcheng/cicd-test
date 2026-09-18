# GitLab CI 实施部署手册

> 配套方案文档：[gitlab-ci-small-company.md](./gitlab-ci-small-company.md)  
> 演示应用：本仓库 `app/`（Spring Boot，浏览器访问端口 **8090 起**）  
> 默认环境：内网 IP + SSH，无 HTTPS；目标 OS：**Ubuntu 22.04**

---

## 0. 你将得到什么

完成 **阶段 0 + 阶段 1** 后：

| 阶段 | 结果 |
|------|------|
| 0 | `main` 推送 → 测试 → jar 部署到 dev 机 **8090**，systemd 托管 |
| 1 | 同一镜像部署 dev **8090** / test **8091** / prod **8092**；prod 手动审批 |

浏览器验证：

- 首页：`http://<IP>:8090/`（test 用 8091，prod 用 8092）
- API：`/api/info`、`/api/hello`、`/actuator/health`

---

## 1. 机器与 IP 规划（示例）

| 主机名 | 角色 | 示例 IP | 说明 |
|--------|------|---------|------|
| platform | GitLab CE + Runner | 192.168.1.10 | 8C16G |
| app-dev | dev + test Compose | 192.168.1.20 | 安装 Docker |
| app-prod | 仅 prod | 192.168.1.30 | 安装 Docker |

下文用以上 IP 举例，请替换为你的实际地址。

---

## 2. 前置条件

### 2.1 各机通用

```bash
# Ubuntu 22.04
sudo apt update && sudo apt install -y curl git openjdk-21-jdk
java -version   # 应显示 21
```

### 2.2 业务机（app-dev / app-prod）

```bash
sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker deploy   # 见下文创建 deploy 用户
```

### 2.3 平台机（platform）

- 开放端口：22、80、443（GitLab HTTP/HTTPS）
- 磁盘 ≥ 100G

---

## 3. 安装 GitLab CE + Runner

### 3.1 安装 GitLab（platform）

```bash
curl -fsSL https://packages.gitlab.com/install/repositories/gitlab/gitlab-ce/script.deb.sh | sudo bash
sudo EXTERNAL_URL="http://192.168.1.10" apt install -y gitlab-ce
sudo gitlab-ctl reconfigure
```

首次登录：浏览器打开 `http://192.168.1.10`，设置 root 密码。

内存紧张时在 `/etc/gitlab/gitlab.rb` 限制 worker，改后 `sudo gitlab-ctl reconfigure`。

### 3.2 创建项目并推送本仓库

```bash
# 在开发机
git clone <你的仓库地址> cicd-test && cd cicd-test
git remote add gitlab http://192.168.1.10/root/cicd-test.git
git push gitlab main
```

在 GitLab 新建项目 `cicd-test`，导入或 push 代码。

### 3.3 注册 GitLab Runner（platform 或 app-dev）

```bash
curl -L https://packages.gitlab.com/install/repositories/runner/gitlab-runner/script.deb.sh | sudo bash
sudo apt install -y gitlab-runner
```

GitLab 项目 → **Settings → CI/CD → Runners** → 复制 registration token，然后：

```bash
sudo gitlab-runner register
# URL: http://192.168.1.10/
# Token: <项目 token>
# Executor: docker
# Default image: maven:3.9-eclipse-temurin-21
# Tags: docker,maven
```

编辑 `/etc/gitlab-runner/config.toml`，限制并发：

```toml
concurrent = 2
```

部署 job 需要 SSH：可为 deploy 阶段单独注册 **shell** executor，或在 docker job 中挂载 SSH 密钥（本仓库 `.gitlab-ci.yml` 采用 CI 变量注入私钥）。

---

## 4. 准备业务机用户与目录

在 **app-dev** 和 **app-prod** 执行：

```bash
sudo useradd -m -s /bin/bash deploy
sudo usermod -aG docker deploy

# dev + test（app-dev）
sudo mkdir -p /opt/cicd-demo-dev /opt/cicd-demo-test
sudo cp -r deploy/compose/dev/* /opt/cicd-demo-dev/
sudo cp -r deploy/compose/test/* /opt/cicd-demo-test/
sudo cp deploy/compose/dev/.env.example /opt/cicd-demo-dev/.env
sudo cp deploy/compose/test/.env.example /opt/cicd-demo-test/.env
# 编辑 .env 中的 REGISTRY_IMAGE

# prod（app-prod）
sudo mkdir -p /opt/cicd-demo-prod
sudo cp -r deploy/compose/prod/* /opt/cicd-demo-prod/
sudo cp deploy/compose/prod/.env.example /opt/cicd-demo-prod/.env
sudo chown -R deploy:deploy /opt/cicd-demo-*
```

为 deploy 配置 **免密 SSH**（从 Runner 能连入）：在 GitLab CI 变量中保存 `SSH_PRIVATE_KEY`，公钥写入 `deploy@192.168.1.20` 与 `deploy@192.168.1.30` 的 `~/.ssh/authorized_keys`。

---

## 5. 阶段 0 实施：systemd（仅 dev）

### 5.1 目标

只跑 **build_jar + deploy_dev（systemd 版）**，暂不启用 Docker 镜像阶段。

**做法**：在 `.gitlab-ci.yml` 中暂时注释 `build_image`、`deploy_test`、`deploy_prod`，并新增 systemd 部署 job（或用手动部署验证）。

### 5.2 app-dev 准备 systemd

```bash
sudo useradd -r -s /usr/sbin/nologin cicd || true
sudo mkdir -p /opt/cicd-demo /etc/cicd-demo
sudo cp deploy/systemd/dev.env.example /etc/cicd-demo/dev.env
sudo chmod 600 /etc/cicd-demo/dev.env
sudo cp deploy/systemd/cicd-demo.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable cicd-demo
```

### 5.3 手动验证（首次）

```bash
cd app
mvn -B clean verify
scp target/cicd-demo-*.jar deploy@192.168.1.20:/opt/cicd-demo/cicd-demo.jar
ssh deploy@192.168.1.20 'sudo systemctl restart cicd-demo'
curl http://192.168.1.20:8090/actuator/health
```

浏览器打开 `http://192.168.1.20:8090/`，应看到环境徽章 **dev**。

### 5.4 GitLab CI 变量（阶段 0 最少）

| 变量 | 说明 | Protected |
|------|------|-----------|
| `SSH_PRIVATE_KEY` | 部署用私钥 | 是 |
| `APP_DEV_HOST` | `192.168.1.20` | 否 |

### 5.5 阶段 0 验收

- [ ] `main` 推送触发 pipeline，测试失败则不部署
- [ ] `http://192.168.1.20:8090/` 可访问
- [ ] `/api/info` 中 `env` 为 `dev`

---

## 6. 阶段 1 实施：Docker 三环境

### 6.1 启用 Container Registry

GitLab 项目 → **Deploy → Container Registry**，记下地址，形如：

`192.168.1.10:5050/root/cicd-test`

在 `/opt/cicd-demo-*/.env` 中设置：

```env
REGISTRY_IMAGE=192.168.1.10:5050/root/cicd-test
SPRING_PROFILES_ACTIVE=dev   # 各环境目录分别 dev/test/prod
```

### 6.2 GitLab CI 变量（阶段 1 完整）

| 变量 | 说明 |
|------|------|
| `CI_REGISTRY_*` | GitLab 内置，通常自动可用 |
| `SSH_PRIVATE_KEY` | 部署 SSH 私钥 |
| `APP_DEV_HOST` | `192.168.1.20` |
| `APP_PROD_HOST` | `192.168.1.30` |

### 6.3 启用完整 `.gitlab-ci.yml`

恢复仓库根目录 `.gitlab-ci.yml` 中所有 stage。推送 `main` 后应：

1. `build_jar` → `build_image` → push Registry  
2. `deploy_dev` → 8090  
3. `deploy_test` → 8091  

### 6.4 生产发布

```bash
git tag v1.0.0
git push gitlab v1.0.0
```

在 GitLab Pipeline 中 **手动点击** `deploy_prod` → 8092。

将 **production** 环境设为 Protected（Settings → CI/CD → Protected environments）。

### 6.5 浏览器验收

| 环境 | URL | 预期 |
|------|-----|------|
| dev | `http://192.168.1.20:8090/` | 徽章 dev |
| test | `http://192.168.1.20:8091/` | 徽章 test |
| prod | `http://192.168.1.30:8092/` | 徽章 prod |

同一 tag 下，三环境 **version / gitCommit 相同**，**env 不同**。

### 6.6 回滚演练

在 prod 机：

```bash
cd /opt/cicd-demo-prod
# 改 .env 中 IMAGE_TAG 为上一成功版本
nano .env
docker compose pull && docker compose up -d
curl http://192.168.1.30:8092/api/info
```

---

## 7. 通知（钉钉 / 飞书）

在 GitLab 项目 → Integrations，或 pipeline `after_script` 中调用 Webhook URL（存为 CI 变量 `NOTIFY_WEBHOOK`）。

---

## 8. 常见问题

| 现象 | 处理 |
|------|------|
| Runner docker job 无法连 SSH | 检查 `SSH_PRIVATE_KEY` 换行、known_hosts |
| Registry push 失败 | Runner 需能访问 GitLab Registry；DinD 需 privileged |
| 8090 无法访问 | 检查防火墙 `ufw allow 8090`；systemd 是否用 dev profile |
| 三环境镜像不一致 | 核对 `.env` 的 `IMAGE_TAG` 是否同一 tag |

---

## 9. 与 Jenkins 版关系

- Jenkins 实施见 [implement-jenkins.md](./implement-jenkins.md)  
- **勿同时对同一 prod 启用两套自动部署**

---

## 10. 选修

见 [gitlab-ci-small-company.md §8](./gitlab-ci-small-company.md#8-选修成长路径大纲无细步骤)。
