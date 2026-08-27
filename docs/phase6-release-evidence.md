# 阶段六发布准备证据

- 日期：2026-08-27
- 项目根目录：`/Users/a1234/Documents/class schedule`
- 运行边界：本地 Docker Desktop，不提交、不推送、不部署生产
- Java：OpenJDK 17.0.20
- Maven：3.9.16
- Node/npm：Node 24.19.0、npm 11.17.0
- Docker：Docker Desktop 29.6.1，PostgreSQL 16.15

## 依赖可复现性

- 将前端运行时和开发依赖从 `latest`/范围版本固定为 lockfile 当前解析版本。
- `cd frontend && npm ci`：通过，196 个包安装完成，npm audit 报告 0 vulnerabilities。
- 依赖固定后 `npm run test:run`：9 个测试文件、45 个测试通过。
- 依赖固定后 `npm run build`：通过；保留 JavaScript bundle 超过 500 kB 的非阻塞警告。
- `cd backend && mvn -q package -DskipTests`：通过，Java 编译目标为 release 17。

## SBOM 与许可证

SBOM 生成物位于本地忽略目录 `.phase6-evidence/sbom/`：

- `backend-runtime.json` / `backend-runtime.xml`：CycloneDX Maven 插件 2.9.1，排除 test scope。
- `frontend-production.json`：CycloneDX npm 1.19.0，生产依赖。
- `frontend-all.json`：CycloneDX npm 1.19.0，含开发依赖。
- `class-schedule-api-image.json`：Syft 1.51.0，镜像包级 SBOM。
- `class-schedule-frontend-image.json`：Syft 1.51.0，镜像包级 SBOM。

镜像基础层已使用 digest 固定：

- Maven：`maven:3.9.6-eclipse-temurin-17@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5f939d4`
- Java runtime：`eclipse-temurin:17-jre-jammy@sha256:e17d77fb030dd4b642dc078d048a5fb9efcb3676ee20305d905949105a6ccd5a`
- Nginx：`nginx:1.26-alpine@sha256:1eadbb07820339e8bbfed18c771691970baee292ec4ab2558f1453d26153e22d`
- PostgreSQL：`postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`

最终本地镜像身份记录在 `.phase6-evidence/image-identities.txt`：

- API：`class-schedule-api:phase6-20260827@sha256:f10ad5d7e8ff28ef0b3167111daa687db2d44af1be0679aeb6fede19e37a9a2c`
- Frontend：`class-schedule-frontend:phase6-20260827@sha256:4df9f05b07686a57dcc76a2e476a52a2817b573c6589ae3de0e3196d12ca1d91`

许可证复核结论：应用 SBOM 主要包含 Apache-2.0、MIT、BSD、EPL 等许可证；后端 `jakarta.annotation-api 2.1.1` 同时标识 EPL-2.0 和 GPL-2.0-with-classpath-exception，需要在正式分发前由许可证负责人确认适用范围。镜像 SBOM 还包含基础系统包及其 GPL/LGPL、OpenSSL、字体和其他系统许可证，不能仅用应用依赖清单替代。前端 SBOM 有 `speakingurl 14.0.1` 缺少许可证元数据，npm registry 查询为 BSD-3-Clause，已记录为人工复核项。

## staging-like Compose

配置文件：`docker-compose.staging.yml`。

- API、Worker、Frontend 使用固定 digest 镜像；PostgreSQL 使用固定 digest。
- staging 配置缺少数据库或 bootstrap 凭据时会被 Compose 拒绝。
- 提供非默认凭据后 `docker compose config --quiet`：通过。
- 隔离项目 `class-schedule-phase6-final`：PostgreSQL、API、Worker、Frontend 均达到 healthy。
- API 容器内 `/actuator/health`：`{"status":"UP"}`。
- Frontend 首页返回 402 字节 HTML。
- 未认证访问前端代理 `/api/auth/me` 返回 401，认证边界符合预期。
- 演练使用独立卷和网络，完成后已 `down -v --remove-orphans` 清理；未影响开发 Compose 或开发数据库。

## 备份恢复与 RPO/RTO

- 备份脚本支持 `COMPOSE_FILE`、`COMPOSE_PROJECT_NAME`、`POSTGRES_SERVICE`，默认行为仍指向开发 Compose。
- 备份文件：`.phase6-evidence/backups/class_schedule_20260827-153022.dump`
- 备份大小：136084 字节。
- SHA-256：`9efebb70de5d914cc25a282be4cd42b7b3dc1c6f82bb82aad6617e43e92746d2`
- 源库基线：PostgreSQL 16.15、Flyway 27、24 个 `schedule_version`、98 个 `schedule_assignment`。
- 目标为独立 PostgreSQL 16 容器和临时卷；恢复耗时约 2 秒，备份耗时约 1 秒。
- 恢复后校验：PostgreSQL 16.15、Flyway 27、24 个版本、98 个 assignment；结果与源库一致。
- RPO/RTO：本次只记录本地演练实测耗时和零记录差异，不等同于正式业务 RPO/RTO 目标；正式目标、异地备份、加密和灾备演练仍需运维负责人确认。

## 性能与安全门禁

- `TimetableConstraintProviderTest` 在可重复环境运行通过；示例求解达到 `0hard/0medium/0soft`，单次求解阶段约 2 秒。
- 该数据规模仅为测试样例，不是脱敏中型 K-12 基准；真实排队时间、首解、总时长、不可行率、取消率、Worker CPU/内存和规则耗时仍为 UNVERIFIED。
- `AuthSecurityIntegrationTest` 和 `ScheduleVersionImmutabilityIntegrationTest` 通过，覆盖匿名认证、CSRF、角色写入边界、Job 所有权和已发布/归档不可变性。
- 这些是应用层/数据库触发器测试，不等同于生产数据库 runtime role 最小权限隔离；当前 Compose 仍使用单一数据库账号，runtime role 隔离为残余风险。

## 当前结论

**PASS WITH RESIDUAL RISK**：本地发布候选的依赖锁定、SBOM 生成、固定 digest 镜像、staging-like 健康、隔离备份恢复和现有安全回归均有证据。

仍不能宣称生产就绪，原因包括：许可证人工确认、真实中型性能基准、正式数据库 runtime role 隔离、Playwright 截图型自动化证据、移动视口、浏览器 network/console、真实导出文件/打印回执、复杂规则覆盖、生产告警/Outbox 和正式 RPO/RTO 目标尚未完成。
