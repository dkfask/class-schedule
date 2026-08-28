# 实施审计

日期：2026-08-26（本地运行日志使用 UTC+08:00）

## 已完成并验证

### 工程与基础设施

- Git 仓库已初始化。
- 根目录 README、`.gitignore`、`.env.example`、`THIRD_PARTY_NOTICES.md` 已建立。
- Docker Compose 已配置 PostgreSQL 16。
- 后端 Maven 工程已建立。
- 前端 Vue 3/Vite 工程已建立。
- Flyway 已执行到版本 27。

证据：

- `docker compose ps` 显示 PostgreSQL healthy。
- Spring Boot 日志显示 PostgreSQL 16.15 连接成功。
- Docker Compose 已加入 `api` 和 `worker` 服务，使用同一镜像的默认/worker profile 分进程运行。
- Dockerfile 使用 Java 17 基础镜像；本机 Java 17.0.20 与 Maven `release 17` 配置一致。

### 求解器闭环

- Timefold Solver Community 已接入。
- `LessonOccurrence`、`Timeslot`、`Room`、`Timetable` 已建立。
- 教师、班级、教室冲突约束已建立。
- 未分配时间或教室会产生 Hard 惩罚。
- 业务求解输入已经从硬编码示例切换为 PostgreSQL 的 `teaching_requirement` 查询和 occurrence 展开。
- 求解结果写入 `schedule_assignment`。
- 结果状态写入 `solve_job` 和 `schedule_version`。
- 发布前有独立 `ScheduleValidation` 检查。
- 已接入 `duration_periods` 和 `pinned_period_code` 到求解输入。
- 固定节次使用 Timefold `@PlanningPin` 保持不移动。
- 连堂任务按同一天连续节次区间参与教师、班级和教室冲突判断。
- V11-V27 增加学生组人数、学期可用性、教室特征目录/需求特征、活动组关系、assignment 规则快照、版本生命周期/命令组、Solve Job deadline、typed schedule rules、认证/RBAC、所有权和审计索引、教学需求身份快照、版本输入/规则哈希、活动组成员索引、节次连续组和断点、TERM 规则作用域稳定身份、已发布快照不可变保护。
- Solver 与独立 validator 已覆盖教师/班级/教室 availability、容量、特征、JOINED/SYNCHRONIZED/CONSECUTIVE 基础规则；CONSECUTIVE 按活动组成员索引和同一周次配对，duration 使用当前学期 period continuity/break 边界。

运行证据：

- 数据库教学需求展开为 3 个 occurrence：100、200、300。
- Job 4 返回 `CANDIDATE`。
- 结果可读为 `0hard/0medium/0soft`，并兼容历史 `0hard/0soft` 记录。
- 每个 occurrence 均有 timeslot 和 room。
- `publishable` 返回 `true`。

### 版本与调整

- 求解任务创建场景、版本和 Job。
- 候选版本可以发布。
- 版本可以 fork 为草稿。
- 草稿/候选版本支持调整 occurrence 的节次和教室；V15 通过版本行锁、revision/expected revision 和幂等键保护多人编辑。
- 每次单条调整形成一个命令组；交换的两个 assignment 共享一个命令组并原子提交。
- 命令历史、事件审计、整组 undo/redo、ARCHIVED 状态和版本级 lock/unlock 已接入 API。
- `/versions` 页面提供锁定/解锁、归档、命令历史查看和撤销/重做按钮，并在操作后刷新列表与历史。
- 规则事实提供 `GET /api/rule-facts/availability` 查询当前学期已配置可用性，前端规则事实页可回显列表。
- 调整前提供只读预览，返回目标节次/教室是否存在、教师/班级/教室冲突、受影响 assignment 和锁定冲突。
- 调整确认会再次执行同一套基础校验，写入 `adjustment_command` 并返回 `commandId`，然后将版本重置为草稿。
- V22 assignment 结果保存 `teaching_requirement_id/code`、`activity_index`、`activity_member_index`、固定节次和活动类型快照；fork、手调、交换、undo/redo 均保留这些字段。
- 发布前基线校验对照目标学期 active teaching requirement，拒绝 missing、duplicate、extra、weekly/duration mismatch、需求身份替换和固定节次篡改。
- V23 为真实求解版本保存目标学期、输入快照哈希和规则快照哈希；基础数据或规则变化后发布返回 `INPUT_SNAPSHOT_STALE`。
- V24/V25/V26 为 activity group 保存成员顺序、period template continuity group/break-after 和 TERM rule scope 稳定身份；validator 会按目标学期和每个 activity index 检查活动组整体缺失、成员漏排/重复/额外、member index 和 activity type snapshot；solver、validator 和报告使用同一边界语义。
- typed rule 当前支持 `TEACHER_DAILY_MAX`、`STUDENT_GROUP_DAILY_MAX`、`SUBJECT_DAILY_MAX`、`SUBJECT_MIN_SPREAD_DAYS`、`TEACHER_GAP_POLICY`、`TEACHER_PREFERRED_PERIOD`，并支持 `HARD/MEDIUM/SOFT`、`weight`、整数/文本参数和 TERM/TEACHER/STUDENT_GROUP/SUBJECT 作用域校验；HARD 阻断发布，MEDIUM/SOFT 保留在冲突报告中。规则配置目录通过 `GET /api/schedule-rules/catalog` 提供，规则页使用同一学期上下文。
- V27 已增加数据库级终态快照不可变保护：PUBLISHED/ARCHIVED 版本、assignment、command history 不能通过直接 JDBC 修改或删除；仅允许受控的 PUBLISHED -> ARCHIVED。
- legacy identity marker 默认 fail-closed；身份不完整的候选发布返回 `LEGACY_IDENTITY_UNVERIFIED`。
- typed rules 已作为 solver problem facts 按 HARD/MEDIUM/SOFT 分层计分，并与 validator 的资源作用域、duration-aware daily max 和学期 sentinel 对齐。

- 最新修复版 JAR 真实验证：Job 16 在取消竞态下最终为 `CANCELLED`，Version 也为 `CANCELLED`；Job 17 完成为 `COMPLETED/CANDIDATE`，`progress=100`、`attempt=1`、score 为 `0hard/0soft`。
- Version 19 返回 5 个 assignment，`publishable=true`，结果包含稳定 code、`source=SOLVER`、`locked=false`、`duration=1`。
- 历史 V14 运行实例：Job 18 完成为 `COMPLETED/CANDIDATE`，Version 20 返回稳定 occurrence key、规则快照字段、`publishable=true`；版本列表返回候选版本及 `publishable`，diff 返回 5 条稳定 key 条目，availability 写入 API 返回 `UPDATED`。
- V15 新增命令组/版本锁/归档/undo/redo 的 Testcontainers MockMvc 验证，见测试证据。
- 最新 JAR 真实 HTTP 验证：求解 Job 完成 `COMPLETED/CANDIDATE`，版本可发布并成功进入 `PUBLISHED`；legacy candidate 发布返回 `409 LEGACY_IDENTITY_UNVERIFIED`；直接 JDBC 修改已发布 assignment 返回 `VERSION_IMMUTABLE`；版本归档、fork、调整、undo/redo、revision、锁和幂等边界均有回归覆盖。
- 真实规则事实查询：写入 `ROOM A101 MON-2 不可用` 后，`GET /api/rule-facts/availability?termCode=2026-FALL` 返回历史教师与新增教室两条记录。
- V17-V19 认证/RBAC、HttpOnly session 登录、VIEWER/PLANNER 权限边界和跨用户对象级访问已接入；V21 按提交者隔离活动 Job 的幂等键。
- 导出模块提供 XLSX、PDF、冲突报告和打印 HTML 接口；PDF 支持通过 `APP_PDF_FONT_PATH` 嵌入 CJK TTF/TTC 字体。
- 早期阶段定向验证：后端全量 78 项测试通过，干净 PostgreSQL 执行 Flyway V1-V27；当时前端测试 28 项通过且 production build 通过。阶段六依赖固定后已重新回归为 9 个测试文件、45 项测试全部通过。

### 持久化 Solve Worker

已实现：

- V8 增加幂等键、Worker、租约、心跳、attempt、取消请求、重试时间和 claim 索引。
- HTTP `/api/solve-jobs` 只创建 `QUEUED` Job，不直接启动 SolverManager。
- Worker profile 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 领取任务。
- Job 状态返回 `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED` 及 version 状态、进度、错误和时间戳。
- 完成、取消和失败使用状态条件更新，迟到结果不能覆盖终态。
- 任务完成写回 assignments、version 和 job 在同一事务内完成。
- 取消请求持久化；`QUEUED` 取消立即进入 `CANCELLED`，`RUNNING/COMPLETING` 由 Worker 安全点收口。
- Worker 求解期间每 10 秒续租并更新进度，完成/失败/取消后停止续租。
- V9 增加最大重试次数和租约索引。
- 失败任务在重试次数未达上限时退回 `QUEUED` 并设置退避时间，达到上限才进入 `FAILED`。
- 完成回写使用 `RUNNING -> COMPLETING -> COMPLETED` CAS，取消请求不会被迟到完成覆盖。
- V16 增加 Job deadline（创建时 +15 分钟）：Worker 领取跳过超期任务，租约过期未超期任务回收为 `QUEUED`，租约过期且超期任务标记 `FAILED/DEADLINE_EXCEEDED` 并写审计；失败重试在超期后不再重试；`details` 返回 `deadlineAt`。
- V21 按 `submitted_by_user_id` 隔离活动 Job 幂等键；Job 详情和取消仅允许提交者或系统服务访问。
- V23 为每个新求解版本记录输入/规则快照哈希，Worker 仍通过 `loadForVersion(versionId)` 按版本所属学期加载数据。

运行证据：

- API 返回 `QUEUED`。
- Worker 跨进程领取后数据库显示 `attempt=1`、非空 `worker_id`、`progress=100`。
- 最终 Job 为 `COMPLETED`，Version 为 `CANDIDATE`，assignment 已写入。
- Worker repository Testcontainers 测试覆盖幂等、SKIP LOCKED、取消和完成回滚。

### 数据库与主数据

已建立并迁移：

- `academic_term`
- `period_template`
- `teacher`
- `student_group`
- `subject`
- `room`
- `room_feature`
- `teacher_availability`
- `room_availability`
- `teaching_requirement`
- `schedule_scenario`
- `schedule_version`
- `schedule_assignment`
- `solve_job`
- `import_batch`
- `adjustment_command`
- `audit_event`

主数据 API：

- `GET /api/master-data/overview`
- `GET/POST/PATCH/DELETE /api/master-data/{teachers|student-groups|subjects|rooms}`
- `GET/POST/PATCH/DELETE /api/master-data/teaching-requirements`

前端：

- Vue Router `/workspace`、`/master-data`、`/teaching-plan`、`/import`、`/versions`、`/published`
- 基础数据分页列表、新增、编辑、软停用和错误提示
- `GET/POST/PATCH/DELETE /api/master-data/{teachers|student-groups|subjects|rooms}`
- `GET/POST/PATCH/DELETE /api/master-data/teaching-requirements`

前端：

- Vue Router `/workspace`、`/master-data`、`/teaching-plan`、`/import`、`/versions`、`/published`
- 基础数据分页列表、新增、编辑、软停用和错误提示

运行证据：

- 返回 1 个学期、4 个节次、3 位教师、2 个班级、3 门课程和 2 个教室。
- 新增规则事实 API 集成测试验证 availability、room feature、requirement feature 和 activity group 写入及错误码。

### 主数据 CRUD 与前端路由

已实现：

- 教师、班级、课程、教室分页列表、新增、编辑和软停用 API。
- 教学需求列表、新增、编辑和软停用 API。
- 重复 code、无效引用和已停用引用返回冲突。
- 主数据写入记录 `audit_event`。
- Vue Router 路由和 App Shell。
- 基础数据管理页面支持资源 Tab、分页表格、表单和停用。

运行证据：

- Testcontainers MockMvc 主数据测试 3 个通过。
- 真实 API 教师新增、详情查询和软停用分别返回 201、200、204。


已实现：

- `POST /api/imports/preview` 计算 SHA-256 并创建持久化导入批次。
- `POST /api/imports/confirm` 只接受 `VALIDATED` 批次。
- 确认阶段重新计算摘要并二次解析/校验原始 XLSX。
- 支持下载 `MASTER_DATA v1` 统一模板，模板使用中文列名，包含教师、班级、课程、教室、教学需求、资源可用性、特征目录、两类特征绑定和活动组 Sheet。
- 检查必需 Sheet、表头、重复编码、容量/课时格式和跨表引用。
- 单事务提交，任何数据库写入错误整体回滚。
- 导入批次状态从 `VALIDATED` 变为 `IMPORTED`。
- 重复确认已导入批次返回 HTTP 409。

运行证据：

- 完整五 Sheet XLSX 预检返回 `VALIDATED`、`batchId=2` 和 SHA-256。
- 确认接口返回 `IMPORTED`、`importedRows=5`。
- 数据库可查询新增教师、班级、课程、教室和教学需求。
- 重复确认返回 HTTP 409，批次保持 `IMPORTED` 且业务数据只写入一次。
- Testcontainers PostgreSQL 集成测试覆盖成功导入、状态原子性和数据库异常触发的整体回滚。

### 前端

- Vue 工作台已建立。
- 展示学期、班级、教师摘要。
- 展示求解状态、进度、尝试次数、错误和取消操作。
- 根据 options API 动态生成实际星期和节次网格，不再固定五天六节。
- 支持班级、教师、教室三维视图和稳定 code 资源筛选。
- assignment 卡片支持点击打开调整 Drawer。
- Drawer 调用调整预览 API，展示硬冲突、受影响 assignment 和锁定状态。
- 只有后端预览 `allowed=true` 且填写调整原因时才允许确认。
- 确认成功后重新读取版本、assignment 和发布门禁。
- 支持选择 XLSX 文件并调用预检接口。
- 支持启动自动排课。
- 支持发布候选版本。
- 支持候选/已发布状态显示。

运行证据：

- `http://localhost:5174/` 返回排课工作台 HTML。
- 浏览器实际打开 `http://127.0.0.1:5173/workspace`，首屏显示动态节次空态、发布门禁和资源视图按钮。
- 浏览器点击“教师课表”后，页面视图标识切换为 `TEACHER VIEW`，证明三维视图状态已接入。
- `npm run test:run` 通过，9 个测试文件共 45 项前端测试通过。
- 浏览器工作台回归覆盖：启动求解、候选结果、三维视图、课程卡片、调整 Drawer、冲突预览、允许预览和确认按钮门禁。
- 版本页面组件测试覆盖稳定 occurrence key diff 加载；工作台测试覆盖交换候选请求。
- `npm run build` 通过。

## 测试证据

后端：

- `mvn test package` 通过。
- 共 78 个后端测试通过：认证/RBAC/CSRF/所有权、规则事实 API、课表查询/调整/交换/diff/发布门禁、V15-V27 生命周期/命令/身份/不可变性、typed rule 作用域与 solver scoring、Excel 事务、导出、Timefold 约束/求解、Worker 状态/租约/回写/恢复、主数据 CRUD 和数据库求解输入均有覆盖。
- Testcontainers 1.21.4 已成功连接 Docker Desktop，干净 PostgreSQL 执行 Flyway V1-V27。
- 后端可打包为 Spring Boot JAR。

前端：

- `npm run test:run` 通过，9 个测试文件共 45 项前端测试通过。
- 测试覆盖 auth store、登录、路由权限边界、统一 CSRF HTTP client、工作台、规则事实 CRUD 列表、版本生命周期和导出入口。
- `npm run build` 通过。
- Vite 输出 production bundle。
- 有 chunk size warning，未阻塞构建。

数据库：

- PostgreSQL 16 容器 healthy。
- Flyway 版本 1 至 27 全部成功。

## 部分完成

### 方案中的发布门禁

已实现：

- 所有 assignment 必须有时间和教室。
- score 使用 `HardMediumSoftScore`；hard 为零才满足发布门禁，旧 `0hard/0soft` 记录仍兼容读取。
- 教师、班级和教室区间冲突时拒绝发布。
- availability、容量、房间特征和活动组基础规则由统一 validator 检查。
- 空任务拒绝发布。
- 发布操作写入审计。

仍需扩展：

- 合班同步的复杂拆分、分层走班和跨午休自定义规则。
- 更完整的 Hard/Medium/Soft 求解质量优化、偏好和分数解释。
- 更完整的数据库 runtime 角色权限隔离和 staging 演练。
- 完整用户管理、审计查询界面和跨用户授权策略。
- Playwright 浏览器端自动化验收和真实中型 K-12 性能基准。
- Worker 生产告警、Outbox 和部署监控。

### 版本模型

已具备候选、草稿、发布、归档和失败状态；版本列表携带 revision/锁/归档元数据，父版本和稳定 occurrence key diff 已接入；人工调整和交换通过命令组、幂等键、版本行锁和 expected revision 保护。

已实现：

- 归档 API 及已发布版本归档门禁。
- 版本级 lock/unlock 和 owner 冲突响应。
- 发布、fork、调整、交换的事务边界和审计事件。
- 命令历史、exchange 原子 undo/redo、LIFO 和 redo 分支失效。
- HttpOnly session 登录、PLANNER/VIEWER 基础角色和已发布版本读取限制。
- 导入批次与 Solve Job 的提交者所有权检查。

仍需扩展：

- 更完整的数据库 runtime 角色权限隔离和 staging 演练。
- 完整用户管理、AUDIT_READ 查询界面和跨用户授权策略。

### 前端工作台

已具备动态课表网格、班级/教师/教室 code 筛选、点击和拖放调整 Drawer、交换候选、搜索过滤、真实求解状态和 `/versions` 差异面板。
已具备前端 Vitest 纯逻辑、工作台交互、主数据分页/错误和版本 diff 回归测试。

仍需扩展：

- 更复杂的多步交换和拖拽回滚体验。
- Playwright 浏览器端自动化测试。

## 阶段六发布准备证据（2026-08-27）

已完成并记录于 `docs/phase6-release-evidence.md`：

- 前端依赖已固定为 lockfile 当前解析的精确版本；`npm ci` 通过且 npm audit 报告 0 vulnerabilities。
- 后端 CycloneDX SBOM、前端生产/全量 CycloneDX SBOM、API/Frontend 镜像 Syft SBOM 已生成，文件位于本地忽略目录 `.phase6-evidence/sbom/`。
- Dockerfile 基础镜像已按 digest 固定；API Java 17 镜像和 Nginx 前端静态镜像已构建，最终镜像 digest 已记录。
- `docker-compose.staging.yml` 使用隔离卷、固定镜像和非默认必填凭据；PostgreSQL、API、Worker、Frontend 均通过 healthy 检查，API health 返回 `UP`，未认证代理请求返回 401。
- `scripts/backup.sh` 和 `scripts/restore.sh` 支持隔离 Compose 文件/项目参数；独立 PostgreSQL 目标恢复成功，Flyway 27、24 个版本和 98 个 assignment 与源库一致。备份文件 SHA-256、备份/恢复耗时已记录。
- `AuthSecurityIntegrationTest`、`ScheduleVersionImmutabilityIntegrationTest` 和 `TimetableConstraintProviderTest` 阶段六专项回归通过。

许可证审查已开始但尚未闭环：应用 SBOM 中 `jakarta.annotation-api 2.1.1` 的 EPL-2.0/GPL-2.0-with-classpath-exception 双标识、镜像基础系统包许可证以及前端 `speakingurl 14.0.1` 元数据缺失均需正式分发前人工确认。

## 未完成或有残余风险

以下内容尚未达到方案中的完整 MVP 退出条件：

1. 复杂合班拆分、分层走班、跨午休和更完整的课程偏好/固定课规则。
2. typed rule 的部分 gap/preferred 语义仍是简化实现，复杂约束、约束解释、均匀分布和教师空档优化仍未完成。
3. 完整用户管理、审计查询界面、runtime 数据库权限隔离和生产告警/Outbox 未完成。
4. 浏览器验收已用真实 DOM 流程完成，但尚未形成 Playwright 截图型自动化证据；移动视口、浏览器 network/console 和真实导出/打印回执仍未获得证据。
5. 真实脱敏中型 K-12 性能基准、正式 RPO/RTO 目标、异地加密备份和灾备演练尚未完成。
6. 许可证人工复核尚未闭环；已生成的 SBOM 和本地镜像证据不等同于生产分发批准。

## 当前结论

项目已经从空目录落地为一个可运行的排课技术原型和 MVP 核心闭环，阶段六已形成可审计的本地发布候选：

```text
PostgreSQL 主数据
  -> 教学需求展开
  -> Timefold 自动排课
  -> 候选版本
  -> 独立基础校验
  -> fork/手工调整
  -> 发布门禁
  -> Vue 工作台
  -> 固定 digest 镜像 / staging-like / 备份恢复证据
```

当前结论为 **PASS WITH RESIDUAL RISK**。本地发布候选的依赖锁定、SBOM 生成、镜像构建、staging-like 健康、隔离备份恢复和安全回归均有证据；但它还不是可以直接投入学校生产的完整系统。下一步应由许可证负责人、运维负责人和性能测试负责人分别闭合上述残余门槛。
