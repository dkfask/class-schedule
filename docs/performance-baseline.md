# 求解性能基线（2026-09-12，收敛修复后更新）

记录当前版本在真实数据集与黄金数据集上的求解基线，作为后续优化对比与商业化试点
Go/No-Go 中"真实性能基线"的证据基础。
环境：本地 docker compose 栈（api + worker + postgres 16），默认求解配置（30s 预算）+
`TimetableConflictRepairer` 定向冲突修复（见下"收敛修复"）。

## 收敛修复：定向冲突修复器（2026-09-13）

**问题**：芙蓉真实实例（486 课次、95.3% 占用率）上，Timefold 本地搜索 30 秒乃至 5 分钟预算
都稳定停在 -5hard：全满班级下绝大多数移动都会制造新冲突，改进移动在数十万级移动空间里
占比极低，均匀随机采样永远抽不中（JVM 探针证实：卡死状态下存在单步改进移动，但 39k 步
从未被采样到；换种子/贪心初始化/接受器均无效）。注：多线程移动（move thread count AUTO）
是 Timefold 企业版权凭特性，社区版不可用。

**修复**：`TimetableConflictRepairer` 在求解结束后做定向修复——只对结构冲突中的课次
穷举 (节次×教室) 重分配与同班交换候选，只接受硬分严格改进的移动，循环至无改进、冲突数
连续两轮不下降（停滞，结构性不可行实例秒级退出）或时间预算（默认 300s，
`solver.worker.repair-budget-ms`）耗尽。对卡死的芙蓉实例：-5hard → **0hard**（JVM 探针
两种子一致；端到端 job 99 / version 119 同样收敛 0hard，全程 207s，其中求解 30s）。

**代价**：无冲突解短路返回（O(n) 结构扫描，微秒级）；有冲突时每个候选全量重打分，
修复时长与冲突规模成正比（芙蓉约 2~3 分钟；黄金数据集 SI/OC 这类结构性不可修实例
在数秒内停滞退出，K12 中等规模实例耗满预算后停止并保留已改进的最优解）。

## 真实学校数据集（芙蓉校区）

数据：`datasets/real-school-furong/`（17 个行政班、46 名教师、14 门课程、
486 周课次、224 条教学需求、占用率 95.3%，含 101 个 FLEXIBLE 走班课次）。
参考解：真实课表本身零冲突（单双周按第二位教师建模）。

| 指标 | 修复前（2026-09-12） | 修复后（2026-09-13，job 99 / version 119） |
| --- | --- | --- |
| 求解完成 | COMPLETED ~32.5s | COMPLETED 全程 207s（30s 求解 + 定向修复） |
| 最终分数 | -5hard / 0medium / 0soft | **0hard / 0medium / 0soft** |
| 残留冲突 | 班级 4 + 教室 1 | 无 |

复现：`bash datasets/real-school-furong/run-efficiency-test.sh 2026-FALL-FR`。

## 黄金数据集

数据：`datasets/{small-feasible,small-infeasible,over-constrained,k12-sample}/`，
由 `scripts/generate-golden.py` 生成（可重现，随机种子固定）。
导入预览均 VALIDATED（0 issues）；学期 GOLD-* 与 5×6 节次模板需先创建（见下）。
验收：`bash scripts/golden-solve-check.sh`（需本地 compose 栈）。

2026-09-13 验收（含修复器，求解预算 30s、修复预算 300s）：

| 数据集 | 规模 | 预期 | 结果 |
| --- | --- | --- | --- |
| small-feasible | 2 班 5 师 36 课次 | 收敛 0hard | COMPLETED 31s，0hard ✓ |
| small-infeasible | 1 班，数学 35 > 30 节 | 必然冲突、快速终止 | COMPLETED 34s，-25hard，停滞检测秒级退出 ✓ |
| over-constrained | 6 班 108 课次，36 个走班需求要求无人具备的"体育器材"特征 | 36 条特征缺失冲突 | COMPLETED 31s，-36hard，校验报告恰为 36 条 ✓ |
| k12-sample | 9 班 30 师 252 课次 | 中等规模效率基准 | COMPLETED 181s，-35hard（耗修复预算后停止，保留改进解） |

学期与节次模板创建方式（每个 GOLD-* 学期复制 2026-FALL 的 30 个节次）：

```sql
INSERT INTO academic_term(code, name, status) VALUES ('GOLD-K12-SAMPLE', '黄金基准', 'DRAFT') ON CONFLICT (code) DO NOTHING;
INSERT INTO period_template(term_id, code, weekday, period_no, label, start_time, end_time, continuity_group, break_after)
SELECT t.id, p.code, p.weekday, p.period_no, p.label, p.start_time, p.end_time, p.continuity_group, p.break_after
FROM academic_term t JOIN academic_term s ON s.code='2026-FALL' JOIN period_template p ON p.term_id=s.id
WHERE t.code='GOLD-K12-SAMPLE' ON CONFLICT DO NOTHING;
```

## 与商业化门槛的关系

`docs/commercialization-plan.md` 第 7 节 Go/No-Go 之一是"真实学校数据性能基线"。
芙蓉数据集基线（上表）即为该证据的起点；正式试点前应补充目标学校的真实规模复测。
