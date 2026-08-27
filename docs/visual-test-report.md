# 桌面浏览器可视化测试报告

- 测试日期：2026-08-27
- 测试环境：本地 Docker Desktop 29.6.1、PostgreSQL 16、API `:8080`、Worker、Vite `:5173`
- 浏览器：ZCode In-app Browser
- 角色：Planner（已认证）
- 视口：1280 × 720
- 测试方式：DOM 快照、DOM CUA 黑盒交互和截图；未修改业务代码、未提交业务数据
- API 健康检查：`GET http://127.0.0.1:8080/actuator/health` 返回 `UP`

## 通过项

1. 登录页显示品牌、用户名、密码和登录操作；空状态、填写状态和认证失败提示均可观察。使用临时 Planner 账号登录成功。
2. 工作台页面可访问，显示当前学期、同步状态、排课状态、任务统计、H/M/S 评分占位、三种课表视图和禁用的发布按钮。
3. 工作台在无求解结果时点击“教师课表”保持稳定，并明确显示“完成一次求解后选择资源”，没有错误跳转或布局跳变。
4. 教学计划页面独立加载，显示当前学期、5 条教学需求、字段表头、编辑/停用操作和分页状态。
5. “新增教学需求”对话框可以打开，DOM 中包含编码、班级、课程、教师、周课时、时长、人数、特征、固定节次以及取消/保存控件；本轮未保存。
6. 规则事实页面显示资源可用性、教室特征、需求特征、活动组和质量规则配置区，资源可用性列表显示 2 条事实。
7. 版本与发布页面显示多种版本状态（PUBLISHED、CANDIDATE、DRAFT、ARCHIVED、CANCELLED），并加载 v24 的 revision、5 项差异、差异条目和归档控件。
8. 版本差异“只看变化”筛选可切换，v24 的 5 条 ADDED 差异条目可见。
9. 已发布课表页面显示只读标识、已发布版本和 Excel/PDF/打印/刷新控件；导出按钮均可见且未禁用。点击 Excel/PDF 后没有错误提示或意外导航，符合下载型操作的页面表现；本轮未获得文件下载回执。
10. 已保存截图均为 1280 × 720 PNG，可用于桌面页面复核。

## 修复后复测结果

### 规则事实分块加载与接口错误

已修复规则事实页的错误可诊断性：各请求仅在成功时更新对应数据，失败区块保留已有数据，并在提示中包含具体区块和后端错误。浏览器复测时，页面不再显示笼统的“部分规则事实无法加载”；当前数据显示资源可用性 2 条、特征目录 0 条、需求特征 0 条、活动组 0 条、质量规则 0 条，0 条与当前数据库配置一致。

复测中进一步定位到 room-features 和 requirement-features 无筛选 GET 在 PostgreSQL 下因 `? IS NULL` 参数类型无法推断而返回 HTTP 500。`RuleFactRepository` 已将无筛选和带筛选请求拆为独立 SQL 路径，并新增可选筛选集成测试。专项 `RuleFactControllerIntegrationTest` 和后端全量 Maven 回归均通过。

### 教学计划桌面布局

已增加教学计划专用横向滚动容器，并将操作列设为右侧固定列。1280 × 720 浏览器复测确认“编辑”和“停用”按钮均在视口内可见且可达；教学计划列表正常加载 5 条记录，字段、分页和 CRUD 入口未回归。

### 版本和已发布页评分兼容

已新增统一评分解析工具，支持旧版 `0hard/0soft` 和新版 `0hard/0medium/0soft`，并在列表接口缺少拆分字段时从 raw `score` 回退解析。浏览器复测确认版本页和已发布页均显示 `H0 / M0 / S0`；未评分的 DRAFT/CANCELLED 记录仍显示 `H— / M— / S—`。

## 回归证据

- 前端 Vitest：9 个测试文件、45 个测试全部通过。
- 前端生产构建：Vite 构建通过；仅报告 bundle 超过 500 kB 的既有优化警告。
- 后端全量 `mvn -q test`：通过，exit code 0；Testcontainers PostgreSQL 16、Flyway V1-V27 和既有集成测试均完成。
- 浏览器复测：工作台、教学计划、规则事实、版本与发布、已发布课表均能在 1280 × 720 下访问并渲染；当前 Planner 会话保持有效。

## 工具限制和未覆盖项

- IAB 当前没有暴露 viewport setter，因此未执行移动视口测试，不能声称移动端通过。
- IAB 当前没有可用的 network/console 日志 API；本报告不声称已完成浏览器 console 错误和请求级证据采集。
- 当前截图服务后续出现 `browser screenshot activity capture failed for guest`，因此规则事实和工作台的后续截图未重新生成；报告引用的既有截图仍是成功保存的 PNG。
- 版本卡片和教学计划对话框的部分按钮在 IAB 语义定位层反复超时；已改用新鲜 DOM CUA 节点或直接导航完成独立检查，未把自动化点击超时直接判为业务失败。
- Excel/PDF/打印控件未在本轮取得下载文件或打印预览回执，结论限于“控件可见、启用、点击后当前页无错误反馈”。

## 截图证据

目录：`/Users/a1234/Documents/class schedule/gui-test-screenshots/`

- `t01-login-empty.png`、`t01b-login-empty.png`：登录空状态
- `t02-login-username.png`：用户名填写状态
- `t03-login-filled.png`：登录表单填写状态
- `t04-login-button-ready.png`：登录按钮可用状态
- `t05-workspace-desktop.png`：排课工作台
- `t06-teaching-plan-desktop.png`：教学计划列表
- `t07-rule-facts-desktop.png`：规则事实页面
- `t08-versions-desktop.png`：版本与发布页面
- `t09-published-desktop.png`：已发布课表页面
- `t10-teaching-plan-create-dialog.png`：新增教学需求对话框

## 结论

**PASS WITH RESIDUAL RISK**。本轮修复后的桌面端核心只读流程、规则事实加载状态、评分兼容显示和教学计划操作列均已通过 1280 × 720 浏览器复测；前端完整测试、生产构建和后端全量回归也通过。

该结论不覆盖以下未取得证据的范围：移动视口、浏览器级 network/console 日志、真实 Excel/PDF 下载文件、打印预览回执，以及 Playwright 截图型自动化。阶段六的 SBOM、固定 digest 镜像、隔离备份恢复和 staging-like 健康已有独立证据，详见 `docs/phase6-release-evidence.md`；真实中型性能基准、runtime 数据库角色隔离、正式 RPO/RTO、许可证人工确认和生产运维能力仍未完成。
