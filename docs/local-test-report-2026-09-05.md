# 本地测试报告（2026-09-05）

## 结论

**PASS WITH RESIDUAL RISK**：本次执行的后端自动化测试、前端组件测试、前后端构建和合成求解基准全部通过。未执行真实浏览器端到端验收、生产部署或真实学校数据验收，不能据此宣称生产交付验收完成。

## 测试身份与环境

- 执行时间：2026-09-05 23:06—23:08，Asia/Shanghai。
- 工作目录：`/Users/a1234/Documents/class schedule`。
- 分支：`feat/optional-master-data-sheets`。
- HEAD：`6d8eac3ff48c918f403a19e5ccae47329793cad6`。
- 已合并 main：`08d585a87d30fcb53039818bec8678e0422fd578`。
- 两者 Git tree 均为 `f4fdaddc38b40bef9951a8bb0f59bafeeede39ab`，源码内容相同。
- macOS 15.7.7 arm64；Java 17.0.20；Maven 3.9.16；Node 24.19.0；npm 11.17.0；Docker 29.6.1。
- 数据库集成测试使用 Testcontainers 隔离 PostgreSQL；没有修改生产数据。
- 初始未跟踪内容 `.codegraph/`、`.release-4431c28/`、`.release-cda3230/`、`AGENTS.md`、`datasets/` 保留，不作为本次验证通过的交付内容。

## 执行结果

所有命令均从项目根目录执行。

| 验证 | 命令 | 结果 |
| --- | --- | --- |
| 后端测试及打包 | `mvn -f backend/pom.xml verify` | 139 项发现，138 项通过，1 项基准默认跳过；0 失败、0 错误；59.644 秒；JAR 打包成功 |
| 前端组件测试 | `npm run test:run` | 11 个文件、53 项全部通过；3.14 秒 |
| 前端生产构建 | `npm run build` | 成功；Vite 构建 5.53 秒 |
| 求解基准 | `mvn -f backend/pom.xml -Drun.solver.benchmark=true -Dsolver.benchmark.termination-ms=10000 -Dsolver.benchmark.require-zero-hard=true -Dtest=SolverBenchmarkTest test` | 1 项通过；240 课次全部分配；实际求解 10008 ms；`0hard/0medium/-86soft` |
| 差异格式 | `git diff --check` | 通过 |

基准包含 5 个教学日、30 个节次、12 间教室，是固定合成数据单次测量。硬约束为零不代表软约束最优，也不代表真实学校数据的性能承诺。

## 覆盖范围

- PostgreSQL/Flyway：32 个迁移、所有权迁移及版本不可变保护。
- 安全：20 项认证集成测试，包含会话登录、CSRF、角色权限、对象所有权及并发幂等提交。
- 排课：约束计分、连续节次、准备状态、数据库输入、任务领取及恢复、版本查询、修改命令、规则校验。
- 导入导出：工作簿导入、真实 PostgreSQL 导入、外部数据转换、导出集成测试。
- PDF 回归：空课表返回一页；100 行课表生成多页且全部课程标记可提取。
- 前端：登录、基础数据、教学计划、导入面板、排课工作台、版本页、已发布页、规则页及工具函数。测试使用 Vitest/jsdom 和组件桩，并非真实浏览器。

## 风险与未验证项

1. 当前基础数据测试验证 CRUD、停用、分页及错误显示，尚未直接断言新启用接口及按钮的完整恢复流程。
2. 教学计划测试验证按学期加载及创建需求，尚未直接覆盖切换学期后节次选项更新与异步请求竞争。
3. PDF 测试未覆盖中文字体嵌入、长文本换行和逐页视觉布局；冲突报告 PDF 不属于新增分页测试覆盖范围。
4. 前端产物单个 JS 文件约 1135.54 kB（gzip 369.06 kB），构建提示超过 500 kB；本轮未测首屏性能。
5. 未执行真实浏览器桌面/移动端 E2E、完整导入到发布的人工 UAT、生产连接、备份恢复、镜像运行及真实数据负载测试。
6. 本轮使用已有本地依赖，未验证全新机器的依赖安装；前端 Vite 构建也不等同于独立 TypeScript 类型检查。

建议发布验收前补齐启用与学期切换回归，并执行真实浏览器流程和中文 PDF 视觉验收。

## 证据索引

- 后端完整日志：`/tmp/class-schedule-local-backend.log`。
- 前端测试日志：`/tmp/class-schedule-local-frontend.log`。
- 前端构建日志：`/tmp/class-schedule-local-build.log`。
- 基准日志：`/tmp/class-schedule-local-benchmark.log`。
- JUnit 结果：`backend/target/surefire-reports/`；单独基准执行已更新其对应结果文件。
- 后端产物：`backend/target/class-schedule-backend-0.1.0-SNAPSHOT.jar`。
- JAR SHA-256：`3e4edf966ff4e2ca19dc70af1f56e6084531c98092b50ff699452ef866a4a73e`。
- 前端产物：根目录 `dist/` 与 `frontend/dist/`。

日志位于临时目录，可能被系统清理；本报告保存关键结果。此次仅新增测试报告，未修改生产源码，未提交、推送或部署。
