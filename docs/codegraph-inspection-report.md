# CodeGraph 检查报告

日期：2026-09-06（本地时区 UTC+08:00）

- 检查对象：`.codegraph/codegraph.db`（CodeGraph v1.4.1，extraction version 24，schema version 1–8）
- 源码身份：分支 `feat/optional-master-data-sheets`，revision `5657e4a`
- 检查方式：只读。通过 SQLite 直查图谱（结构、覆盖、哈希比对、边完整性、未解析引用归类），并抽查源码核实；未修改任何文件、未运行构建或测试。

## 结论

图谱完整、与当前 revision 一致，可作为代码导航与影响分析工具使用；存在三类已识别的解析局限和一处覆盖盲区，特定查询场景需配合 grep。这些局限均为工具限制，不是项目代码缺陷。

## 新鲜度核验

- 索引时间 2026-09-05 09:11:59，晚于全部已索引源码的最后修改时间（最新为 `ScheduleRepository.java` 2026-09-03 10:43:10）。
- `project_metadata`：`index_state=complete`，`index_files_discovered=153`，`index_files_accounted=153`。
- 对 153 个已索引文件逐一计算 SHA-256 并与 `files.content_hash` 比对：153 个全部一致，0 个变更，0 个缺失。

结论：图谱对应当前 HEAD `5657e4a`，无过期风险。

## 覆盖统计

按语言的文件覆盖（与磁盘实际文件数 1:1 吻合）：

| 语言 | 文件数 | 备注 |
| --- | --- | --- |
| Java | 115 | 主代码 92 + 测试 23 |
| TypeScript | 21 | 含 `vite.config.ts`、`vitest.config.ts`、`test/setup.ts` |
| Vue | 11 | `App.vue` + 9 个视图 + `ImportPanel.vue` |
| XML | 1 | `backend/pom.xml` |
| YAML | 5 | `application.yml`、`application-worker.yml`、CI 与 compose 文件 |
| 合计 | 153 | |

图谱规模：

- 节点约 2,879 个：import 999、method 774、constant 250、function 176、field 167、file 148、namespace 115、class 86、route 69、property 31、interface 27、type_alias 12、component 11、其余（enum 等）16。
- 边 7,139 条：contains 3,007、calls 2,880、references 791、instantiates 234、imports 225、decorates 2。
- 完整性：孤儿边 0，重复边 0。

## 图谱质量发现

以下均为图谱工具的解析局限，查询时需知晓：

1. **未解析引用 10,703 条（status=failed）**。按引用类型：calls 7,539、references 1,120、imports 993、instantiates 480、decorates 473、function_ref 91、extends 4、implements 3。逐条归类后绝大多数为外部库符号：Spring/JdbcTemplate、assertj、MockMvc 链式调用（如 `mockMvc.perform().andExpect`）、Vue/vitest、`java.util.*` 等。tree-sitter 类提取器不具备完整类型解析，属预期行为。
2. **自环 import 边 47 条**（如 `App.vue imports App.vue`），为提取器 artifact，查询时应排除 `source.file_path = target.file_path` 的 import 边。
3. **41 条本应解析到项目内符号却失败的引用**，集中在三种模式：
   - 构造器链调用：`new WorkbookImportService(jdbc).preview`（7 处）；
   - 嵌套类/跨类限定名：`ImportIssue`（20 处）、`SolveReadiness`（11 处）、`ScheduleScoreView.parse`（10 处）；
   - Vue/TS 跨文件属性访问：`vm.loadVersion`、`term.loadTerms`、`handle.jobId` 等。
   用图谱反查这些符号的调用方时会漏报。

**覆盖盲区**：图谱仅索引 Java/TS/Vue/XML/YAML。以下未入图：

- `backend/src/main/resources/db/migration/` 全部 32 个 Flyway 迁移（V1–V32）；
- `docs/` 7 个文档、`scripts/` 3 个脚本、`frontend/package.json`、Dockerfile、`nginx.conf`、`styles.css`。

本后端大量使用 JdbcTemplate 手写 SQL（`jdbc.queryForObject/update` 分布在 30+ 文件），图谱无法将 SQL 列名与迁移文件关联，因此"修改表结构影响哪些查询"类问题图谱回答不了，必须人工核对迁移文件。

## 沿图谱的项目观察

- **API 面与授权规则一致**：69 条 route 节点与 `backend/src/main/java/com/classschedule/security/SecurityConfig.java:43` 起的授权配置逐一对得上。permitAll 仅 `/api/health`、`/api/auth/login`、`/api/auth/csrf`；`/api/master-data/**`、`/api/rule-facts/**`、`/api/schedule-rules/**`、`/api/imports/**`、`/api/solve-jobs/**`、`/api/legacy-solve-jobs/**` 及 publish/adjustments/lock/archive/fork 均要求 PLANNER 角色；其余请求需认证。
- **前端模块图规整**：`frontend/src/router.ts` 挂载 9 个视图；视图与 store 统一经 `frontend/src/api/http.ts` 发起请求（CSRF 令牌自动加载，401 统一派发 `auth:expired` 事件），import 边显示无视图绕过该层直接 fetch。注意前后端契约基于字符串 URL，图谱中前端到后端 route 节点的连边为 0，跨端改动需按路径人工对齐。
- **维护性热点**（文件规模与图谱出/入度双重印证）：

| 文件 | 大小 | 符号数 | 说明 |
| --- | --- | --- | --- |
| `ScheduleRepository.java` | 91 KB | 93 | 版本/调整/undo/redo 核心仓库 |
| `WorkbookImportService.java` | 87 KB | 119 | `validateMasterRows` 出度 62、`validateMasterReferences` 出度 52 |
| `ScheduleRuleValidator.java` | 47 KB | 33 | `validate()` 出度 41 |
| `ExternalTimetableConverter.java` | 34 KB | 69 | 外部课表转换 |
| `WorkspaceView.vue`（前端） | 37 KB | 117 | 工作台单文件组件 |

这些文件职责集中，是改动时回归风险最高的位置。

- **测试对应良好**：23 个测试源文件覆盖 importexport、schedule、solver、security、masterdata、rules、api 全部主要模块。

## 使用建议

- 适合用图谱：符号定位、模块依赖、路由清单、调用链概览、高耦合点识别。
- 需要 grep 兜底：上文第 3 条列出的三种失败模式涉及的符号；表结构与手写 SQL 的关联（以 `db/migration/` 为准）；前后端契约对齐（按 URL 字符串检索）。
- 查询 import 边时排除自环（47 条 artifact）。

## 残余风险与边界

- 本报告为静态只读检查；构建、测试、运行时行为均未执行（UNVERIFIED），不在本报告结论范围内。
- `.codegraph/.gitignore` 已配置为仅保留自身，数据库文件不会进入版本控制；当前工作区另有未跟踪的 `.release-4431c28/`、`.release-cda3230/`、`AGENTS.md`，与本次检查无关，未做处理。
