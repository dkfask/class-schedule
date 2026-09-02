# Class Schedule

独立中小学排课系统，首期面向单校、行政班排课和排课员编辑。

## 当前状态

已完成阶段 1 的可运行技术闭环，并推进到以下 MVP 能力：

- Spring Boot + PostgreSQL + Flyway
- Timefold 数据库教学需求求解
- 教学需求的 `durationPeriods` 和 `pinnedPeriodCode` 已进入求解输入；连堂按连续节次区间检查冲突
- 教师、班级、课程、教室和节次样例主数据
- 异步求解 Job、候选版本和发布门禁
- PostgreSQL 持久化 Job、幂等提交、SKIP LOCKED Worker、租约/心跳/取消、过期恢复、重试退避和 15 分钟 Job deadline
- 排队取消立即终止、运行中取消安全收口、求解完成事务保留 assignment 扩展字段
- 教师、班级、课程、教室和教学需求 CRUD API
- Vue Router、基础数据管理页面和教学工作台
- 版本 fork、手工调整和调整审计记录
- V15 版本 revision、编辑锁、归档状态和命令组
- 调整/交换幂等键、乐观 revision 校验、统一冲突响应和审计事件
- 命令历史、交换原子撤销/重做和新命令后的 redo 分支失效
- assignment 结果契约包含稳定资源 code、`source`、`locked` 和 `duration`
- 版本 options、按班级/教师/教室 code 过滤和调整预览 API
- 调整确认会在后端再次校验冲突和锁定状态，并返回 `commandId`
- Excel 统一模板导入：下载 `MASTER_DATA v1` 模板，使用中文列名导入教师、班级、课程和教学需求；教室、资源可用性、特征及活动组数据为可选项，可省略或留空。缺少的可选 Sheet 和数据行不会删除已有配置，只有明确提交的停用/解绑行才会修改对应关系。
- 导入失败事务回滚和重复确认保护
- Vue 排课工作台、真实求解状态、动态课表网格、班级/教师/教室三维视图和发布操作
- 点击课程或拖动课程打开调整 Drawer，调用后端预览并在允许后确认调整
- `/versions` 提供版本列表和稳定 occurrence key 差异查看
- `/versions` 显示 revision、锁/归档状态，支持任意基线和只看变化
- `/versions` 支持锁定/解锁、归档、命令历史与撤销/重做
- Flyway 数据库迁移已执行到 V31：包含教学需求/assignment 身份快照、活动组成员索引、节次连续组和断点、typed rule TERM 作用域身份、已发布/归档版本数据库级不可变保护，以及版本、场景和求解任务的对象级所有权约束。
- 版本输入快照哈希和规则快照哈希用于检测候选结果是否过期；输入或规则变化后发布会返回 `INPUT_SNAPSHOT_STALE`。
- 发布前按目标学期教学需求对账，拒绝漏排、重复、额外 occurrence、需求身份/周课时/时长/固定节次篡改；活动组按每个 activity index 检查整体缺失、成员漏排/重复/额外、member index 和类型快照。
- 已发布/归档版本在数据库层不可变：assignment、版本行和命令历史的直接写入均被触发器拒绝，legacy 身份未验证的候选不能发布。
- 活动组编码按 `term_id + code` 唯一；一个教学需求最多属于一个活动组，旧库若存在跨活动组重复成员会由 V30 阻止升级并报告需求 ID，需先人工确认归属。

## Solver benchmark

普通测试默认跳过规模基准。需要评估真实规模求解时，可显式开启约 240 个课次、5 个教学日、12 间教室和 typed rules 的 benchmark。3 秒模式用于快速检查是否完成分配；需要验证硬约束收敛时使用更长预算并开启 `require-zero-hard`：

```bash
mvn -Drun.solver.benchmark=true -Dsolver.benchmark.termination-ms=3000 -Dtest=SolverBenchmarkTest test
mvn -Drun.solver.benchmark=true -Dsolver.benchmark.termination-ms=10000 -Dsolver.benchmark.require-zero-hard=true -Dtest=SolverBenchmarkTest test
```
- typed rule 支持 `TEACHER_DAILY_MAX`、`STUDENT_GROUP_DAILY_MAX`、`SUBJECT_DAILY_MAX`、`SUBJECT_MIN_SPREAD_DAYS`、`TEACHER_GAP_POLICY`、`TEACHER_PREFERRED_PERIOD`，以及 `HARD/MEDIUM/SOFT`、`weight`、整数/文本参数和资源作用域校验；规则已进入求解器按 severity 分层计分，HARD 阻止发布，MEDIUM/SOFT 进入验证报告。

尚未完成的生产功能和发布前人工门槛：

- 完整用户管理和正式跨用户 RBAC 策略
- 复杂合班拆分、分层走班和跨午休可配置规则
- 更复杂的规则求解优化、完整教师空档可视化和教室容量/特征业务边界
- Playwright 浏览器自动化验收和真实中型数据性能基准
- Worker deadline、异常告警、Outbox 和生产部署编排
- 许可证负责人对应用/基础镜像许可证的最终确认、runtime 数据库最小权限隔离和正式 RPO/RTO/灾备方案

阶段六已形成可审计的本地发布候选：前端依赖已锁定，后端/前端/镜像 SBOM 已生成，Java 17 镜像和固定 digest staging-like Compose 已验证，隔离数据库备份恢复已完成。上述证据不等同于生产发布批准。

项目统一使用 Java 17。当前本机 Java 17.0.20、Maven 编译配置和 Docker 构建/运行时基础镜像均保持一致。

## 技术栈

- Backend: Java, Spring Boot, Timefold Solver Community, PostgreSQL, Flyway
- Frontend: Vue 3, TypeScript, Vite, Pinia, Vue Router, Element Plus
- Local services: Docker Compose

## 启动开发环境

```bash
cd "/Users/a1234/Documents/class schedule"
docker compose up -d postgres api worker

# 只在本地分别运行时：
# cd backend && mvn spring-boot:run
# SPRING_PROFILES_ACTIVE=worker SERVER_PORT=8081 mvn -f backend/pom.xml spring-boot:run

cd frontend
npm install
npm run dev
```

默认地址：

- Backend: http://localhost:8080
- Frontend: http://localhost:5173（若端口占用，Vite 会切换到 5174 等可用端口）
- `APP_AUTH_BOOTSTRAP_USERNAME` / `APP_AUTH_BOOTSTRAP_PASSWORD`：首次本地启动时创建排课员账号；生产环境必须通过密钥管理注入并及时修改。
- `APP_PDF_FONT_PATH`：可选 CJK TTF/TTC 字体路径。未配置时 PDF 使用西文字体回退，中文字符不保证可显示。

## 设计原则

- 业务数据模型与求解器模型分离。
- 求解任务异步化，结果先进入候选版本。
- 只有全部任务已分配、硬约束为零并通过独立校验才允许发布。
- 已发布版本不可原地覆盖。
- 不复制 OpenEDU/FET/UniTime 的 GPL/AGPL 代码、页面、数据库脚本或图片。
- 不使用随机贪心或毕业设计遗传算法作为生产核心。

## 云端前端构建

仓库根目录已提供 npm workspace 入口，适用于默认从仓库根目录安装依赖的托管平台：

```bash
npm ci
npm run build
```

发布目录为 `dist/`。构建命令会调用 `frontend` 的 Vite 构建，并将生成的静态资源复制到根目录 `dist/`。Cloudflare Pages 应使用静态站点部署，不要使用 Workers 的 `wrangler deploy`：

- Build command：`npm run build`
- Build output directory：`dist`
- Deploy command（可选）：`npm run deploy:pages`
- `CLOUDFLARE_PAGES_PROJECT`：绑定到 Cloudflare Pages 项目名称，并由部署平台的环境变量提供

项目已包含 `frontend/public/_redirects`，用于 Vue SPA 路由直接访问时回退到 `index.html`。前端 API 使用同源 `/api/*`，Cloudflare Pages 只托管静态资源；生产环境还必须在 Pages 项目或边缘反向代理中把 `/api/*` 路由到可访问的 Spring Boot API，不能指向本机 `localhost`。如果托管平台支持设置 Base directory，也可以将 Base directory 设为 `frontend`，使用 `npm ci`、`npm run build`，发布目录设为 `frontend/dist`。

## 验证

```bash
cd backend
mvn test
mvn package

cd ../frontend
npm run test:run
npm run build
```

镜像构建使用 `docker-build` Maven profile，运行不依赖 Docker daemon 的单元测试；Testcontainers/Flyway 集成测试必须先在具备 Docker daemon 的 CI 测试任务中通过，再构建后端镜像：

```bash
docker build -t class-schedule-api:local .
```
