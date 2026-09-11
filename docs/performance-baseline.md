# 求解性能基线（2026-09-12）

记录当前版本（HEAD 含 V41 教室绑定/走班需求）在真实数据集与黄金数据集上的求解基线，
作为后续优化对比与商业化试点 Go/No-Go 中"真实性能基线"的证据基础。
环境：本地 docker compose 栈（api + worker + postgres 16），默认求解配置。

## 真实学校数据集（芙蓉校区）

数据：`datasets/real-school-furong/`（17 个行政班、46 名教师、14 门课程、
486 周课次、224 条教学需求、占用率 95.3%，含 101 个 FLEXIBLE 走班课次）。
参考解：真实课表本身零冲突（单双周按第二位教师建模）。

| 指标 | 值 |
| --- | --- |
| 导入 | MASTER_DATA v1 预览 VALIDATED、确认导入成功 |
| 求解完成 | COMPLETED，墙钟约 32.5s（job 69，version 87） |
| 最终分数 | -5hard / 0medium / 0soft |
| 残留冲突 | 班级时段冲突 4、教室时段冲突 1（校验报告一致） |

结论：30 秒级预算内基本收敛（486 课次中仅 5 个冲突残留）；参考解存在说明延长预算可到 0hard。
基线复现：`bash datasets/real-school-furong/run-efficiency-test.sh 2026-FALL-FR`。

## 黄金数据集

数据：`datasets/{small-feasible,small-infeasible,over-constrained,k12-sample}/`，
由 `scripts/generate-golden.py` 生成（可重现，随机种子固定）。
导入预览均 VALIDATED（0 issues）；学期 GOLD-* 与 5×6 节次模板需先创建（见下）。
验收：`bash scripts/golden-solve-check.sh`（需本地 compose 栈）。

2026-09-12 首次验收（HEAD 含 V41，求解预算约 30s）：

| 数据集 | 规模 | 预期 | 结果 |
| --- | --- | --- | --- |
| small-feasible | 2 班 5 师 36 课次 | 收敛 0hard | COMPLETED，0hard/0medium/0soft ✓ |
| small-infeasible | 1 班，数学 35 > 30 节 | 必然冲突、正常终止 | COMPLETED，-25hard ✓ |
| over-constrained | 6 班 108 课次，36 个走班需求要求无人具备的"体育器材"特征 | 36 条特征缺失冲突 | COMPLETED，-36hard，校验报告恰为 36 条"教室缺少必需特征" ✓ |
| k12-sample | 9 班 30 师 252 课次 | 中等规模效率基准 | COMPLETED，-36hard（参考解存在，属预算内未收敛，用作优化对比） |

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
