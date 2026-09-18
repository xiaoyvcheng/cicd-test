# Jenkins 实施部署手册

> 面向**运维 / 实施工程师**：按本文从上到下执行即可落地。  
> 配套方案：[jenkins-gitlab-small-company.md](./jenkins-gitlab-small-company.md)  
> 演示应用：本仓库 `app/`（Spring Boot，浏览器端口 **8090 起**）  
> 默认环境：内网 IP + SSH，无 HTTPS；目标 OS：**Ubuntu 22.04**  
>
> **动手前必须先完成 [§2 资源准备](#2-资源准备必须有--建议有--可选)** 并勾选 [§2.9 检查清单](#29-实施前检查清单)。

---

## 0. 你将得到什么

| 阶段 | 结果 |
|------|------|
| 0 | GitLab 仓 push → Jenkins → jar → app-dev **8090**（systemd） |
| 1 | 同一镜像 → app-dev **8090** / **8091** + app-prod **8092**；prod 需 Jenkins `input` 审批 |

验收 URL：

- 首页：`http://<业务机IP>:8090/`（test=8091，prod=8092）
- API：`/api/info`、`/api/hello`、`/actuator/health`

---

## 1. 机器与 IP 规划

### 1.1 角色与规格

| 机器 | 角色 | 建议规格 | 要装什么 | 阶段 0 | 阶段 1 |
|------|------|----------|----------|--------|--------|
| **platform** | GitLab CE（仓）+ Jenkins Controller | 8 核 16G，磁盘 ≥100G | GitLab、Jenkins、JDK 21、Maven；阶段 1 加 Docker | 必需 | 必需 |
| **app-dev** | dev + test | ≥2C4G | 阶段 0：systemd + JDK；阶段 1：Docker + Compose | 必需 | 必需 |
| **app-prod** | 仅 prod | 按业务定 | Docker + Compose | 不需要 | 必需 |

同机跑 GitLab + Jenkins 时：限制 GitLab 内存；Jenkins 执行器先设 **1～2**。

### 1.2 IP 规划表（实施前必填）

下文示例用 `192.168.1.x`，**请改成你的实际地址并填入下表**，后续命令全部按此表替换。

| 主机 | 示例 IP | **实际 IP（手填）** | 备注 |
|------|---------|---------------------|------|
| platform | 192.168.1.10 | | GitLab + Jenkins |
| app-dev | 192.168.1.20 | | 阶段 0/1 |
| app-prod | 192.168.1.30 | | 仅阶段 1 |

| 决策项 | 选项 | **你的决定（手填）** |
|--------|------|----------------------|
| 目标阶段 | 仅阶段 0 / 阶段 0+1 | |
| Jenkins HTTP 端口 | 8080 或 8081 | |
| GitLab 项目路径 | 如 `root/cicd-test` | |
| prod 审批人（Jenkins 账号） | | |

---

## 2. 资源准备（必须有 / 建议有 / 可选）

按档核对。未勾完「必须有」不要开始 §3。

### 2.1 【必须有】三台机器（或 VM）

- OS：**Ubuntu 22.04**（三台一致）
- 内网互通；实施人能用 **SSH** 登录三台（root 或 sudo）
- 阶段 0 最少 **2 台**（platform + app-dev）；阶段 1 需要 **3 台**（+ app-prod）
- **本仓库 `cicd-test` 已具备** `app/`、`Jenkinsfile`、`deploy/`，不必另写应用

### 2.2 【必须有】网络与端口

三台机器之间能 **SSH**；GitLab 能访问 Jenkins（Webhook）。

| 端口 | 机器 | 用途 | 阶段 |
|------|------|------|------|
| 22 | 全部 | SSH | 0 + 1 |
| 80 / 443 | platform | GitLab Web UI | 0 + 1 |
| 8080（或 8081） | platform | Jenkins Web UI | 0 + 1 |
| 5050 | platform | GitLab Container Registry | **仅 1** |
| 8090 | app-dev | dev 应用 | 0 + 1 |
| 8091 | app-dev | test 应用 | **仅 1** |
| 8092 | app-prod | prod 应用 | **仅 1** |

防火墙 / 安全组按上表放行。Jenkins 改端口后，Webhook URL 必须同步改。

### 2.3 【必须有】SSH 与部署账号

实施人需要：

1. **三台机器的 root 或 sudo**（装 GitLab / Jenkins / Docker）
2. **业务机上的 `deploy` 用户**（§5 创建；阶段 1 加入 `docker` 组）
3. **一对部署专用 SSH 密钥**（勿与个人日常密钥混用）：
   - **私钥** → 写入 Jenkins 凭据 `deploy-ssh`
   - **公钥** → 写入 `deploy@app-dev`、`deploy@app-prod` 的 `~/.ssh/authorized_keys`
   - 阶段 0：`deploy` 需能 **免密 sudo** 执行 `systemctl restart cicd-demo`
   - 阶段 1：`deploy` 能执行 `docker compose`（在 `docker` 组即可）

**在实施人电脑或 platform 上生成密钥（示例）：**

```bash
ssh-keygen -t ed25519 -f ./cicd-deploy -N "" -C "cicd-deploy"
# 得到：cicd-deploy（私钥）  cicd-deploy.pub（公钥）
```

**把公钥装到业务机（app-dev / app-prod，创建 deploy 后执行）：**

```bash
# 在业务机上，以 root/sudo：
sudo mkdir -p /home/deploy/.ssh
sudo chmod 700 /home/deploy/.ssh
# 把 cicd-deploy.pub 内容追加到 authorized_keys
echo 'PASTE_PUBLIC_KEY_HERE' | sudo tee -a /home/deploy/.ssh/authorized_keys
sudo chown -R deploy:deploy /home/deploy/.ssh
sudo chmod 600 /home/deploy/.ssh/authorized_keys
```

**阶段 0：给 deploy 免重启权限（app-dev）：**

```bash
echo 'deploy ALL=(root) NOPASSWD: /bin/systemctl restart cicd-demo, /bin/systemctl status cicd-demo' \
  | sudo tee /etc/sudoers.d/cicd-deploy
sudo chmod 440 /etc/sudoers.d/cicd-deploy
```

从 platform / 实施机验证：

```bash
ssh -i ./cicd-deploy deploy@192.168.1.20 'hostname'
# 阶段 0 验证 sudo：
ssh -i ./cicd-deploy deploy@192.168.1.20 'sudo systemctl status cicd-demo'
```

### 2.4 【必须有】GitLab / Jenkins 凭据与变量

**一律不要写入 Git 仓库。** 在安装后于 Jenkins / GitLab UI 配置。

#### Jenkins Credentials（ID 必须与下表一致，`Jenkinsfile` 已引用）

| ID | 类型 | 用途 | 阶段 |
|----|------|------|------|
| `gitlab-credentials` | Username+Password 或 SSH | Jenkins 拉 GitLab 代码 | 0 + 1 |
| `deploy-ssh` | SSH Username with private key | scp / ssh 部署 | 0 + 1 |
| `registry-credentials` | Username+Password | 推/拉镜像 | **仅 1** |

#### Jenkins 全局环境变量（Manage Jenkins → System → Global properties）

| 变量 | 示例值 | 阶段 |
|------|--------|------|
| `APP_DEV_HOST` | `192.168.1.20` | 0 + 1 |
| `APP_PROD_HOST` | `192.168.1.30` | **仅 1** |
| `REGISTRY_IMAGE` | `192.168.1.10:5050/root/cicd-test` | **仅 1** |
| `NOTIFY_WEBHOOK` | 钉钉/飞书 URL | 可选 |

#### GitLab 侧

| 项 | 用途 | 何时准备 |
|----|------|----------|
| root 密码 | 首次登录 GitLab | 安装后设置 |
| 项目 `cicd-test` | 存放本仓库代码 | §4.1 |
| Access Token / 用户密码 | 填入 `gitlab-credentials` | 拉代码 |
| Deploy Token（read_registry + write_registry） | 填入 `registry-credentials` | **阶段 1** |
| Webhook Secret | 与 Jenkins 插件 token 一致 | §4.5 |

#### Jenkins 侧

| 项 | 用途 |
|----|------|
| 初始 admin 密码 | `sudo cat /var/lib/jenkins/secrets/initialAdminPassword` |
| 审批账号 | 阶段 1 能在 `input` 点 Deploy 的用户 |

### 2.5 【必须有】业务机目录与用户（最终形态）

仓库模板路径 → 业务机目标路径：

| 业务机 | 最终要有 | 仓库来源 | 阶段 |
|--------|----------|----------|------|
| app-dev | 用户 `deploy` | §5 创建 | 0 + 1 |
| app-dev | `/opt/cicd-demo/` + systemd `cicd-demo` | `deploy/systemd/` | 0 |
| app-dev | `/opt/cicd-demo-dev/`、`/opt/cicd-demo-test/` + `.env` | `deploy/compose/dev|test/` | 1 |
| app-prod | 用户 `deploy` | §5 创建 | 1 |
| app-prod | `/opt/cicd-demo-prod/` + `.env` | `deploy/compose/prod/` | 1 |

env 文件权限 **600**；密钥只放 env，不打进 jar/镜像。

### 2.6 【建议有】开发机

| 项 | 说明 |
|----|------|
| Git | 能 `git push` 到内网 GitLab（触发 Webhook） |
| Java 21 + Maven | 本地可先跑：`cd app && mvn -B clean verify` |
| 浏览器 | 打开 GitLab、Jenkins、8090/8091/8092 做验收 |

### 2.7 【可选】

| 项 | 何时需要 |
|----|----------|
| 钉钉 / 飞书 Webhook | 构建成功/失败通知 |
| 域名 + HTTPS | 公网暴露时；本文默认内网 HTTP |
| 独立 Jenkins Agent | 阶段 0～1 可与 Controller 同机 |
| 阿里云 ACR / ECS | 选修；主路径用 GitLab Registry |

### 2.8 【不必准备】

- 重写演示应用、手写 `Jenkinsfile`（仓库已有）
- Kubernetes / Argo CD / 云厂商资源（主路径不用）
- 专职 DevOps 编制（本文面向 5～20 人小团队）

### 2.9 实施前检查清单

```text
【必须】
□ Ubuntu 22.04：阶段 0 ≥2 台 / 阶段 1 =3 台，内网互通
□ platform：8C16G，磁盘 ≥100G
□ §1.2 IP 表与决策表已填
□ 防火墙已按 §2.2 放行
□ 三台 SSH（root/sudo）可用
□ deploy SSH 密钥已生成；计划写入 Jenkins deploy-ssh
□ 已决定：仅阶段 0 还是 0+1
□ 已决定：禁用 GitLab CI，勿与 Jenkins 同时打 prod

【安装过程中补齐】
□ GitLab root 密码已设置
□ Jenkins 初始 admin 密码已读取并改密
□ GitLab 项目已建并 push 本仓库
□ 阶段 1：Deploy Token（Registry）已建
□ prod 审批人 Jenkins 账号已明确

【建议】
□ 开发机 Git / JDK21 / Maven / 浏览器可用
```

### 2.10 分阶段最小集（对照）

**阶段 0：** platform + app-dev；端口 22/80/8080/8090；凭据 `gitlab-credentials` + `deploy-ssh`；变量 `APP_DEV_HOST`；systemd。

**阶段 1 额外：** app-prod；platform Docker；端口 5050/8091/8092；`registry-credentials` + Deploy Token；变量 `APP_PROD_HOST` + `REGISTRY_IMAGE`；三套 Compose。

### 2.11 推荐执行顺序

| 步骤 | 做什么 | 章节 |
|------|--------|------|
| 1 | 勾完 §2.9，填好 §1.2 | §1–2 |
| 2 | 各机装基础软件 | §3 |
| 3 | platform 装 GitLab + Jenkins，建项目、推代码 | §4 |
| 4 | 配凭据、工具、Job、Webhook | §4.3–4.5 |
| 5 | 业务机用户 / 目录 / systemd | §5 |
| 6 | 阶段 0：简化流水线 → 验证 8090 | §6 |
| 7 | 阶段 1：Docker + Registry + 三环境 → tag 发 prod | §7 |

---

## 3. 前置条件（各机软件）

### 3.1 各机通用

```bash
sudo apt update && sudo apt install -y curl git openjdk-21-jdk
java -version   # 应显示 21
```

### 3.2 业务机（app-dev / app-prod）

- **阶段 0（仅 app-dev）**：需要 JDK（systemd 跑 jar）；可不装 Docker。
- **阶段 1**：两台都装 Docker：

```bash
sudo apt install -y docker.io docker-compose-plugin
# 先按 §5 创建 deploy，再执行：
sudo usermod -aG docker deploy
```

### 3.3 平台机（platform）

- 端口与磁盘见 §2.2、§1.1
- 阶段 1 再装 Docker，并把 `jenkins` 加入 `docker` 组（§7.1）

---

## 4. 安装 GitLab（仅代码仓）+ Jenkins

### 4.1 安装 GitLab CE（platform）

```bash
curl -fsSL https://packages.gitlab.com/install/repositories/gitlab/gitlab-ce/script.deb.sh | sudo bash
sudo EXTERNAL_URL="http://192.168.1.10" apt install -y gitlab-ce
sudo gitlab-ctl reconfigure
```

1. 浏览器打开 `http://192.168.1.10`，设置 **root 密码**。
2. 新建项目 **`cicd-test`**（可见性按内网策略）。
3. 在开发机推送本仓库：

```bash
cd /path/to/cicd-test
git remote add gitlab http://192.168.1.10/root/cicd-test.git
git push -u gitlab main
```

4. **禁用 GitLab CI 对本仓的自动部署**（本方案只用 Jenkins）：
   - Settings → CI/CD → Auto DevOps → **关闭**
   - 或暂时移除 / 重命名仓库根目录 `.gitlab-ci.yml`（实验期）
   - 确认 **不要** 与 Jenkins 同时向同一 prod 自动发布

内存紧张时编辑 `/etc/gitlab/gitlab.rb` 降低 worker，再 `sudo gitlab-ctl reconfigure`。

### 4.2 安装 Jenkins（platform）

```bash
curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key \
  | sudo tee /usr/share/keyrings/jenkins-keyring.asc > /dev/null
echo deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] \
  https://pkg.jenkins.io/debian-stable binary/ \
  | sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt update && sudo apt install -y jenkins
sudo systemctl enable jenkins && sudo systemctl start jenkins
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```

- 浏览器：`http://192.168.1.10:8080`（与 GitLab 或其他服务冲突则改 `/etc/default/jenkins` 中 `HTTP_PORT=8081`，再 `sudo systemctl restart jenkins`）
- 用初始密码解锁 → 安装推荐插件，并确保有：**Git、Pipeline、GitLab、SSH Agent、Credentials Binding**
- 创建管理员账号并改密

### 4.3 Jenkins 工具与凭据

**Manage Jenkins → Tools：**

| 名称（与流水线约定） | 配置 |
|----------------------|------|
| `jdk-21` | `/usr/lib/jvm/java-21-openjdk-amd64`（或本机 `update-java-alternatives -l` 查到的路径） |
| `maven-3.9` | 勾选自动安装，或填本机 Maven 路径 |

**Manage Jenkins → Credentials**（Global），按 §2.4 创建三条凭据：

1. `gitlab-credentials`：GitLab 用户名 + 密码 / Personal Access Token（`read_repository`）
2. `deploy-ssh`：Username=`deploy`，Private Key=§2.3 生成的私钥全文
3. `registry-credentials`：（阶段 1）GitLab Deploy Token 用户名 + 密码

**Manage Jenkins → System → Global properties → Environment variables：**

按 §2.4 填入 `APP_DEV_HOST` 等（阶段 0 至少 `APP_DEV_HOST`）。

### 4.4 创建 Multibranch Pipeline

1. **New Item** → 名称 `cicd-demo` → **Multibranch Pipeline** → OK  
2. **Branch Sources** → Add source → **Git**  
   - Project Repository：`http://192.168.1.10/root/cicd-test.git`  
   - Credentials：`gitlab-credentials`  
3. **Build Configuration** → Mode: **by Jenkinsfile**，Script Path：`Jenkinsfile`  
4. **Scan Multibranch Pipeline Triggers**：勾选周期性扫描，或依赖 Webhook（§4.5）  
5. Save → 等待首次扫描出 `main`

### 4.5 GitLab Webhook → Jenkins

GitLab 项目 → **Settings → Webhooks**：

| 项 | 值 |
|----|-----|
| URL | `http://192.168.1.10:8080/project/cicd-demo`（若用 Generic Webhook 插件则按其文档 URL） |
| Secret token | 自拟强随机串，与 Jenkins GitLab 插件 / Generic Webhook 配置一致 |
| Trigger | **Push events**、**Tag push events** |

保存后用 GitLab「Test」按钮测连通；失败则查 platform 防火墙与 Jenkins 是否监听。

---

## 5. 准备业务机（用户、目录、SSH）

以下命令在对应业务机执行。仓库文件需先拷到机器（`scp -r deploy ...` 或 git clone 后复制）。

### 5.1 创建 deploy 用户（app-dev 与 app-prod）

```bash
sudo useradd -m -s /bin/bash deploy || true
# 阶段 1 再执行：
sudo usermod -aG docker deploy
```

按 **§2.3** 写入公钥与（阶段 0）sudoers。

### 5.2 阶段 0：systemd（仅 app-dev）

```bash
sudo useradd -r -s /usr/sbin/nologin cicd || true
sudo mkdir -p /opt/cicd-demo /etc/cicd-demo
sudo chown cicd:cicd /opt/cicd-demo

# 假设当前目录为本仓库根，或已拷贝 deploy/
sudo cp deploy/systemd/dev.env.example /etc/cicd-demo/dev.env
sudo chmod 600 /etc/cicd-demo/dev.env
sudo cp deploy/systemd/cicd-demo.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable cicd-demo
```

首次 jar 到位前 `systemctl start` 会失败，属正常；等 §6 流水线部署后再启。

确认 `deploy` 可写 jar 目录（流水线用 scp 到 `/opt/cicd-demo/cicd-demo.jar`）：

```bash
sudo chown -R deploy:deploy /opt/cicd-demo
# 若服务以 cicd 用户运行，需保证 jar 对 cicd 可读：
# 部署后可：sudo chown cicd:cicd /opt/cicd-demo/cicd-demo.jar
```

> 若 `cicd-demo.service` 中 `User=cicd`，建议在 Deploy 脚本里 scp 后 `chown cicd:cicd`；或临时将服务 `User=`/`Group=` 改为 `deploy` 以简化演示。

### 5.3 阶段 1：Compose 目录

**在 app-dev：**

```bash
sudo mkdir -p /opt/cicd-demo-dev /opt/cicd-demo-test
sudo cp deploy/compose/dev/compose.yaml /opt/cicd-demo-dev/
sudo cp deploy/compose/dev/.env.example /opt/cicd-demo-dev/.env
sudo cp deploy/compose/test/compose.yaml /opt/cicd-demo-test/
sudo cp deploy/compose/test/.env.example /opt/cicd-demo-test/.env
# 编辑两个 .env：REGISTRY_IMAGE、SPRING_PROFILES_ACTIVE（dev/test）
sudo chown -R deploy:deploy /opt/cicd-demo-dev /opt/cicd-demo-test
sudo chmod 600 /opt/cicd-demo-dev/.env /opt/cicd-demo-test/.env
```

**在 app-prod：**

```bash
sudo mkdir -p /opt/cicd-demo-prod
sudo cp deploy/compose/prod/compose.yaml /opt/cicd-demo-prod/
sudo cp deploy/compose/prod/.env.example /opt/cicd-demo-prod/.env
# 编辑 .env：REGISTRY_IMAGE、SPRING_PROFILES_ACTIVE=prod
sudo chown -R deploy:deploy /opt/cicd-demo-prod
sudo chmod 600 /opt/cicd-demo-prod/.env
```

业务机拉取私有 Registry 时，用同一 Deploy Token 在 `deploy` 用户下执行一次：

```bash
docker login 192.168.1.10:5050
```

---

## 6. 阶段 0 实施：systemd（仅 dev）

### 6.1 简化 Jenkinsfile（首次）

临时注释仓库根目录 `Jenkinsfile` 中：

- `Build & Push Image`
- Docker 版 Deploy Dev / Test / Prod / Approve

仅保留：**Checkout → Build & Test → Deploy Dev（scp jar + systemctl）**。

### 6.2 Deploy Dev（systemd）示例

```groovy
sshagent(credentials: ['deploy-ssh']) {
  sh '''
    JAR=$(ls app/target/cicd-demo-*.jar | head -n1)
    scp "$JAR" deploy@$APP_DEV_HOST:/opt/cicd-demo/cicd-demo.jar
    ssh deploy@$APP_DEV_HOST 'sudo systemctl restart cicd-demo'
  '''
}
```

提交并 push 到 GitLab `main`。

### 6.3 验收

- [ ] GitLab push 后 Jenkins Multibranch 被触发（或手动 Scan）
- [ ] Build & Test 绿；失败时**不会**部署
- [ ] `http://192.168.1.20:8090/` 打开且环境显示 **dev**
- [ ] `/api/info` 中 env 为 `dev`；密码不在 jar 内

---

## 7. 阶段 1 实施：Docker 三环境

### 7.1 platform 安装 Docker（供 Jenkins 构建镜像）

```bash
sudo apt install -y docker.io
sudo usermod -aG docker jenkins
sudo systemctl restart jenkins
# 验证（切换到 jenkins 用户或重启后）：
sudo -u jenkins -H docker ps
```

### 7.2 启用 GitLab Container Registry

- GitLab 项目 → **Deploy → Container Registry**，记下地址（形如 `192.168.1.10:5050/root/cicd-test`）
- 创建 **Deploy Token**（`read_registry` + `write_registry`）→ 填入 Jenkins `registry-credentials`
- 全局变量 / 业务机 `.env` 中 `REGISTRY_IMAGE` 与之一致

若 Registry 为 HTTP，需在 platform 与业务机配置 insecure-registries（Docker daemon），再重启 docker。

### 7.3 启用完整 Jenkinsfile

恢复 `Jenkinsfile` 全部 stage。push `main` 应执行：

- Build & Push Image  
- Deploy Dev（8090）  
- Deploy Test（8091）  

### 7.4 生产发布

```bash
git tag v1.0.0
git push gitlab v1.0.0
```

Jenkins 扫描到 tag → 构建并推送镜像 → 停在 **Approve Prod** → 审批人点击 Deploy → Deploy Prod（8092）。

### 7.5 审批注意

`input` 会长时间占用构建。若 Agent 有 idle 超时，将 Approve 放在 `agent none` 的 stage，或拆独立「晋级 Job」。

### 7.6 验收与回滚

**验收：**

- [ ] 一次构建一个 `IMAGE_TAG`，dev/test/prod 可共用
- [ ] prod 与 test 可用同一 tag 核对
- [ ] prod 必须经过 `input`（或等价手动步骤）
- [ ] `http://<app-dev>:8090` / `:8091`、`http://<app-prod>:8092` 均可访问
- [ ] 改业务机 `.env` 中非镜像内容（如密钥）无需重建镜像

**回滚：**

1. Jenkins 参数化 Job 填入上一成功 `IMAGE_TAG`，只跑对应环境 Deploy；或  
2. SSH 到目标机改 `.env` 中 `IMAGE_TAG` 后：

```bash
cd /opt/cicd-demo-prod   # 或 -dev / -test
docker compose pull && docker compose up -d
```

---

## 8. 常见问题

| 现象 | 处理 |
|------|------|
| Webhook 不触发 | GitLab→Jenkins 网络、URL、Secret、Multibranch 是否已 Scan |
| `Permission denied (publickey)` | 公钥是否在 `deploy` 的 authorized_keys；Jenkins 凭据是否为对应私钥 |
| `sudo: a password is required` | 检查 `/etc/sudoers.d/cicd-deploy` |
| `docker: permission denied` | `jenkins`/`deploy` 是否在 docker 组并已重新登录/重启服务 |
| input 后 Agent 消失 | 分离审批 stage 或增大 idle |
| 与 GitLab CI 冲突 | 关闭 Auto DevOps / 禁用 `.gitlab-ci.yml` |
| Registry HTTP 拉取失败 | 配置 insecure-registries |

---

## 9. 与 GitLab CI 版关系

- GitLab CI 实施见 [implement-gitlab-ci.md](./implement-gitlab-ci.md)（另一套方案）  
- **两套 CI 不要同时自动部署同一 prod**  
- 本文已自包含业务机与 GitLab 安装步骤，不必依赖 GitLab CI 手册才能做完 Jenkins 方案

---

## 10. 选修

见 [jenkins-gitlab-small-company.md §8](./jenkins-gitlab-small-company.md#8-选修成长路径大纲无细步骤)。
