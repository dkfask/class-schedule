# 芙蓉校区真实课表数据集（排课效率基准）

本目录把学校真实课表转换成项目可导入的 `MASTER_DATA v1` 工作簿，用于测量当前
排课管线的真实效率。数据来源：`2026-2027学年度上期芙蓉校区班级总课表.xlsx`。

## 1. 源数据提取结果

源表是 17 个行政班各一张 5 天 × 6 节的课表格子，逐格提取后得到：

| 维度 | 数量 | 说明 |
| --- | --- | --- |
| 班级 | 17 | 一年级 3 个、二年级 3 个、三年级 3 个、四年级 3 个、五年级 3 个、六年级 2 个 |
| 教师 | 46 | 含语文、数学、英语、体育、音乐、美术、科学、道法、劳动、书法、信息科技等 |
| 课程 | 14 | 语文、数学、英语、体育与健康、艺术-美术、艺术-音乐、科学、道法/习近平新时代…读本、劳动、体育活动、班团队/综合实践/生生安/心理、书法、信息科技、校本（英语活动） |
| 周课次 | 486 | 每个格子 = 每周 1 次课（单双周轮换格也只占 1 个周课次） |
| 教学需求 | 224 | 按（班级, 课程, 教师）聚合，`每周课时` = 该组合的周课次数 |
| 教室 | 20 | 17 间行政班教室 + 音乐/美术/科学 3 间专用教室（导入后与既有 4 间合计 24 间） |
| 节次容量 | 510 | 17 班 × 5 天 × 6 节 |
| 占用率 | 95.3% | 三年级至六年级共 11 个班 30/30 满课，一、二年级 26/30 |

教师周课时分布（真实课表统计，非求解结果）：最高 18 节，13 名教师 15 节以上。

完整明细见 `extracted-data.json`（含每位教师、每门课、每个班的需求清单）。

教室按中小学常见场景建模：17 个行政班分别绑定自己的默认教室；源课表没有记录实际教室，
因此把音乐、美术、科学这类通常需要专用教室或走班的需求标记为 `FLEXIBLE`，其余需求标记为
`HOME`。本次生成结果包含 101 个 FLEXIBLE 周课次，导入后可在教室池中排课。

## 2. 数据集文件

| 文件 | 用途 |
| --- | --- |
| `MASTER_DATA-v1.xlsx` | 可直接在“数据导入”页上传的 11 个 Sheet 工作簿 |
| `generate.py` | 从源 Excel 重新生成工作簿与 `extracted-data.json` |
| `verify-reference-solution.py` | 用同一批数据构造一份零冲突课表，证明本实例可解 |
| `reference-solution.json` | 上述零冲突参考解（486 条，含节次编码与教室） |
| `create-term.sql` | 创建隔离学期 `2026-FALL-FR` 并复制 30 个节次模板 |
| `run-efficiency-test.sh` | 端到端跑真实管线：登录 → 导入预检 → 确认导入 → 就绪检查 → 提交求解 → 轮询结果 |
| `import-preview.json` / `last-solve-job.json` | 运行记录；若重新生成或迁移规则，需按复现步骤重新取得 |

## 3. 建模口径与限制

- **单双周轮换格**（如“劳动（常雨晴双/杨雪单）”）在模板里只能挂一位教师，生成器取
  **第二位教师**。该映射与真实课表逐格比对后为 0 教师冲突、0 班级冲突，因此真实课表
  本身就是本数据集的一个可行解。
- **行政班与走班教室**：每个行政班绑定一间普通教室；`HOME` 教学需求只能使用该绑定教室。
  音乐、美术、科学等需要专用教室或走班的需求使用 `FLEXIBLE`，从满足容量和特征的启用教室池中选择。
  若多个班通过 `JOINED` 活动组合并走班，成员必须同节次同教室，并按合计人数检查教室容量。
- **不写固定节次**：所有需求都不 pin，把自由度留给求解器。
- **不写资源可用性、特征、活动组**：本数据集只考察教师/班级/教室三类硬冲突与容量；教室模式仍按上面的真实场景口径生成。
- 教室容量统一 50，班级人数按年级 42–48，全部满足容量约束。

## 4. 复现步骤

```bash
cd "/Users/a1234/Documents/class schedule"

# 1) 生成数据集（可选，仓库已含生成结果）
python3 datasets/real-school-furong/generate.py

# 2) 创建隔离学期
docker compose exec -T postgres psql -U class_schedule -d class_schedule \
  -f - < datasets/real-school-furong/create-term.sql

# 3) 端到端导入并求解，观察耗时与最终分数
bash datasets/real-school-furong/run-efficiency-test.sh 2026-FALL-FR

# 4) 需要更长预算时，用一次性 worker 容器（不改变常驻配置）
docker compose stop worker
docker compose run -d --rm --no-deps --name furong-probe-worker \
  -e SOLVER_TERMINATION_SPENT=180s worker
POLL_INTERVAL=10 bash datasets/real-school-furong/run-efficiency-test.sh 2026-FALL-FR
docker rm -f furong-probe-worker && docker compose start worker
```

## 5. 变更后验证结果（2026-09-10）

`verify-reference-solution.py` 已按新的教室模式重新生成参考解：486 条课次，0 教师冲突、
0 班级冲突、0 教室冲突、0 未分配，证明“行政班绑定教室 + 走班需求教室池”的数据模型可行。

目录中 2026-09-09 的 `import-preview.json` 和 `last-solve-job.json` 产生于 V41 教室规则之前，
不再作为当前求解效率结论。需要性能结论时，先执行第 4 节的导入和求解步骤，再记录新的任务结果。
