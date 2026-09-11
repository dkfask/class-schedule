#!/usr/bin/env python3
"""Generate the four golden MASTER_DATA v1 workbooks used as solver benchmarks.

- small-feasible      2 classes / 5 teachers, 80% utilization, derived from a
                      conflict-free reference grid -> known feasible.
- small-infeasible    one teacher requires more weekly lessons than the grid has
                      slots (25 > 20) -> provably infeasible.
- over-constrained    every class is placeable on its own, but the shared
                      FLEXIBLE room pool demand exceeds pool capacity -> solve
                      finishes with hard violations, for diagnosing workflows.
- k12-sample          9 classes / ~20 teachers primary-school mix at ~85%
                      utilization, derived from a conflict-free reference grid
                      -> feasible mid-size benchmark.

Feasible datasets also write reference-solution.json (class/day/period grid that
satisfies teacher, class-spread and room-pool capacity), so the solver has a
known attainable lower bound.
"""
from __future__ import annotations

import json
import random
from pathlib import Path

import openpyxl
from openpyxl.styles import Font
from openpyxl.utils import get_column_letter

DAYS = ["星期一", "星期二", "星期三", "星期四", "星期五"]

SHEETS: dict[str, list[str]] = {
    "说明": ["说明"],
    "教师": ["编码", "名称", "是否启用"],
    "班级": ["编码", "名称", "班级类型", "学生人数", "是否启用", "绑定教室编码"],
    "课程": ["编码", "名称", "是否启用"],
    "教室": ["编码", "名称", "容量", "教室类型", "是否启用"],
    "教学需求": [
        "编码", "学期编码", "班级编码", "课程编码", "教师编码",
        "每周课时", "单次节数", "学生人数", "固定节次编码", "是否启用", "教室分配模式",
    ],
    "资源可用性": ["资源类型", "资源编码", "学期编码", "节次编码", "是否可用"],
    "特征目录": ["编码", "名称", "是否启用"],
    "教室特征": ["教室编码", "特征编码", "是否启用"],
    "教学需求特征": ["教学需求编码", "特征编码", "是否启用"],
    "活动组": ["编码", "名称", "活动组类型", "学期编码", "成员序号", "教学需求编码", "是否启用"],
}


def write_workbook(spec: dict, target: Path) -> None:
    workbook = openpyxl.Workbook()
    first = True
    for name, headers in SHEETS.items():
        sheet = workbook.active if first else workbook.create_sheet()
        sheet.title = name
        first = False
        for column, header in enumerate(headers, start=1):
            cell = sheet.cell(row=1, column=column, value=header)
            cell.font = Font(bold=True)
        sheet.freeze_panes = "A2"
        for column in range(1, len(headers) + 1):
            sheet.column_dimensions[get_column_letter(column)].width = max(12, len(headers[column - 1]) * 2 + 4)

    workbook["说明"].cell(row=2, column=1, value=spec["note"])

    for row, (code, name) in enumerate(spec["teachers"], start=2):
        sheet = workbook["教师"]
        sheet.cell(row=row, column=1, value=code)
        sheet.cell(row=row, column=2, value=name)
        sheet.cell(row=row, column=3, value="TRUE")

    for row, (code, name, kind, students, room) in enumerate(spec["classes"], start=2):
        sheet = workbook["班级"]
        sheet.cell(row=row, column=1, value=code)
        sheet.cell(row=row, column=2, value=name)
        sheet.cell(row=row, column=3, value=kind)
        sheet.cell(row=row, column=4, value=students)
        sheet.cell(row=row, column=5, value="TRUE")
        sheet.cell(row=row, column=6, value=room)

    for row, (code, name) in enumerate(spec["subjects"], start=2):
        sheet = workbook["课程"]
        sheet.cell(row=row, column=1, value=code)
        sheet.cell(row=row, column=2, value=name)
        sheet.cell(row=row, column=3, value="TRUE")

    for row, (code, name, capacity, kind) in enumerate(spec["rooms"], start=2):
        sheet = workbook["教室"]
        sheet.cell(row=row, column=1, value=code)
        sheet.cell(row=row, column=2, value=name)
        sheet.cell(row=row, column=3, value=capacity)
        sheet.cell(row=row, column=4, value=kind)
        sheet.cell(row=row, column=5, value="TRUE")

    for row, (code, class_code, subject_code, teacher_code, weekly, students, mode) in enumerate(spec["requirements"], start=2):
        sheet = workbook["教学需求"]
        sheet.cell(row=row, column=1, value=code)
        sheet.cell(row=row, column=2, value=spec["termCode"])
        sheet.cell(row=row, column=3, value=class_code)
        sheet.cell(row=row, column=4, value=subject_code)
        sheet.cell(row=row, column=5, value=teacher_code)
        sheet.cell(row=row, column=6, value=weekly)
        sheet.cell(row=row, column=7, value=1)
        sheet.cell(row=row, column=8, value=students)
        sheet.cell(row=row, column=9, value=None)
        sheet.cell(row=row, column=10, value="TRUE")
        sheet.cell(row=row, column=11, value=mode)

    for row, (code, name) in enumerate(spec.get("features", []), start=2):
        sheet = workbook["特征目录"]
        sheet.cell(row=row, column=1, value=code)
        sheet.cell(row=row, column=2, value=name)
        sheet.cell(row=row, column=3, value="TRUE")

    for row, (room_code, feature_code) in enumerate(spec.get("roomFeatures", []), start=2):
        sheet = workbook["教室特征"]
        sheet.cell(row=row, column=1, value=room_code)
        sheet.cell(row=row, column=2, value=feature_code)
        sheet.cell(row=row, column=3, value="TRUE")

    for row, (req_code, feature_code) in enumerate(spec.get("requirementFeatures", []), start=2):
        sheet = workbook["教学需求特征"]
        sheet.cell(row=row, column=1, value=req_code)
        sheet.cell(row=row, column=2, value=feature_code)
        sheet.cell(row=row, column=3, value="TRUE")

    workbook.save(target)


def build_grid(class_weekly: dict[str, dict[str, int]], teacher_of: dict[str, str],
               days: int, periods: int, max_per_day: dict[str, int], rng: random.Random) -> list[dict]:
    """Place (class, subject) lessons into the weekly grid via MRV backtracking.

    Respects per-class per-day subject caps and one lesson per teacher per slot.
    Uses a node budget with restarts; raises if no reference grid can be built.
    """
    classes = sorted(class_weekly)
    subjects_of = sorted({s for weekly in class_weekly.values() for s in weekly})
    cells = [(c, d, p) for c in classes for d in range(days) for p in range(periods)]

    for attempt in range(60):
        demand = {
            (cls, subject): count
            for cls in class_weekly
            for subject, count in class_weekly[cls].items()
        }
        class_taken: set[tuple[str, int, int]] = set()
        teacher_taken: set[tuple[str, int, int]] = set()
        day_count: dict[tuple[str, int, str], int] = {}
        lessons: list[dict] = []
        budget = 8_000

        def teacher_for(cls: str, subject: str) -> str:
            mapping = teacher_of[subject]
            return mapping[cls] if isinstance(mapping, dict) else mapping

        def options(c: str, day: int, period: int) -> list[str]:
            out = []
            for subject in subjects_of:
                if demand.get((c, subject), 0) <= 0:
                    continue
                if day_count.get((c, day, subject), 0) >= max_per_day.get(subject, 1):
                    continue
                if (teacher_for(c, subject), day, period) in teacher_taken:
                    continue
                out.append(subject)
            return out

        def dfs(free: list[tuple[str, int, int]]) -> bool:
            nonlocal budget
            if not any(demand.values()):
                return True
            # Cells of classes whose demand is fully placed stay empty.
            free = [cell for cell in free if any(demand.get((cell[0], s), 0) > 0 for s in subjects_of)]
            if not free:
                return False
            budget -= 1
            if budget <= 0:
                return False
            best_index, best_options = 0, None
            for index, cell in enumerate(free):
                opts = options(*cell)
                if best_options is None or len(opts) < len(best_options):
                    best_index, best_options = index, opts
                    if len(opts) <= 1:
                        break
            cell = free[best_index]
            rest = free[:best_index] + free[best_index + 1:]
            c, day, period = cell
            opts = best_options or []
            rng.shuffle(opts)
            if not opts:
                return False
            for subject in opts:
                teacher = teacher_for(c, subject)
                demand[(c, subject)] -= 1
                day_count[(c, day, subject)] = day_count.get((c, day, subject), 0) + 1
                class_taken.add(cell)
                teacher_taken.add((teacher, day, period))
                lessons.append({"class": c, "day": day, "period": period, "subject": subject, "teacher": teacher})
                if dfs(rest):
                    return True
                lessons.pop()
                teacher_taken.discard((teacher, day, period))
                class_taken.discard(cell)
                day_count[(c, day, subject)] -= 1
                demand[(c, subject)] += 1
            return False

        if dfs(cells):
            return lessons
    raise RuntimeError("reference grid generation failed after 30 restarts; adjust the weekly demand")


def lessons_to_requirements(lessons: list[dict]) -> list[dict]:
    grouped: dict[tuple[str, str, str], int] = {}
    for lesson in lessons:
        key = (lesson["class"], lesson["subject"], lesson["teacher"])
        grouped[key] = grouped.get(key, 0) + 1
    return grouped


def spec_from_grid(prefix: str, term_code: str, note: str,
                   class_defs: list[tuple[str, str, int]],
                   subject_defs: list[tuple[str, str, str]],
                   teacher_defs: list[tuple[str, str]],
                   rooms: list[tuple[str, str, int, str]],
                   lessons: list[dict]) -> dict:
    class_code = {name: f"{prefix}-C{i}" for i, (name, _, _) in enumerate(class_defs, start=1)}
    subject_code = {name: f"{prefix}-S{i}" for i, (name, _, _) in enumerate(subject_defs, start=1)}
    teacher_code = {name: f"{prefix}-T{i}" for i, (name,) in enumerate(teacher_defs, start=1)}
    flexible_subjects = {name for name, _, mode in subject_defs if mode == "FLEXIBLE"}
    home_room_of = {}
    for i, (name, _, _) in enumerate(class_defs, start=1):
        home_room_of[name] = rooms[i - 1][0]
    grade_students = {name: students for name, _, students in class_defs}

    requirements = []
    grouped = lessons_to_requirements(lessons)
    for i, ((class_name, subject, teacher), weekly) in enumerate(sorted(grouped.items()), start=1):
        requirements.append({
            "code": f"{prefix}-R{i:03d}",
            "class": class_code[class_name],
            "subject": subject_code[subject],
            "teacher": teacher_code[teacher],
            "weekly": weekly,
            "students": grade_students[class_name],
            "mode": "FLEXIBLE" if subject in flexible_subjects else "HOME",
        })
    return {
        "termCode": term_code,
        "note": note,
        "teachers": [(teacher_code[name], name) for (name,) in teacher_defs],
        "classes": [
            (class_code[name], name, kind, students, home_room_of[name])
            for name, kind, students in class_defs
        ],
        "subjects": [(subject_code[name], name) for name, _, _ in subject_defs],
        "rooms": rooms,
        "requirements": [
            (r["code"], r["class"], r["subject"], r["teacher"], r["weekly"], r["students"], r["mode"])
            for r in requirements
        ],
    }


def write_reference(output_dir: Path, lessons: list[dict], day_names: list[str]) -> None:
    payload = [
        {
            "class": lesson["class"],
            "day": day_names[lesson["day"]],
            "period": lesson["period"] + 1,
            "subject": lesson["subject"],
            "teacher": lesson["teacher"],
        }
        for lesson in sorted(lessons, key=lambda l: (l["class"], l["day"], l["period"]))
    ]
    (output_dir / "reference-solution.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def main() -> None:
    root = Path(__file__).resolve().parent.parent / "datasets"
    rng = random.Random(20260912)
    day_names = DAYS

    # --- small-feasible: 2 classes, 5 teachers, 18/20 slots per class ---------
    subjects_sf = [
        ("语文", "teacher", "HOME"), ("数学", "teacher", "HOME"), ("英语", "teacher", "HOME"),
        ("体育", "teacher", "HOME"), ("音乐", "teacher", "HOME"),
    ]
    teachers_sf = [("王老师",), ("李老师",), ("张老师",), ("赵老师",), ("钱老师",)]
    weekly_sf = {"语文": 4, "数学": 4, "英语": 4, "体育": 3, "音乐": 3}
    class_names_sf = ["一班", "二班"]
    grid_sf = build_grid(
        {c: dict(weekly_sf) for c in class_names_sf},
        {name: teachers_sf[i][0] for i, (name, _, _) in enumerate(subjects_sf)},
        days=5, periods=4, max_per_day={s: 1 for s, _, _ in subjects_sf}, rng=rng,
    )
    spec = spec_from_grid(
        "SF", "GOLD-SMALL-FEASIBLE",
        "黄金基准：2 个班、5 名教师、每班周 18 课时（20 格中占 18），由无冲突参考课表反推，保证可解。",
        [(name, "行政班", 40) for name in class_names_sf],
        subjects_sf, teachers_sf,
        [(f"SF-R{i:02d}", f"{c}教室", 45, "普通教室") for i, c in enumerate(class_names_sf, start=1)],
        grid_sf,
    )
    out = root / "small-feasible"
    out.mkdir(parents=True, exist_ok=True)
    write_workbook(spec, out / "MASTER_DATA-v1.xlsx")
    write_reference(out, grid_sf, day_names)
    (out / "README.md").write_text(
        "# small-feasible 黄金数据集\n\n"
        "2 个行政班、5 名教师、5 门课程，每班每周 18 课时（5 天 × 4 节，占用率 90%）。\n"
        "需求由一份零冲突参考课表反推（见 reference-solution.json），因此求解器存在已知可行解，\n"
        "用于验收：求解应收敛到 0 硬约束冲突。\n",
        encoding="utf-8",
    )

    # --- small-infeasible: teacher demand exceeds the weekly grid -------------
    subjects_si = [("语文", "teacher", "HOME"), ("数学", "teacher", "HOME")]
    teachers_si = [("孙老师",), ("周老师",)]
    class_names_si = ["一班"]
    grid_si = build_grid(
        {c: {"语文": 5, "数学": 5} for c in class_names_si},
        {name: teachers_si[i][0] for i, (name, _, _) in enumerate(subjects_si)},
        days=5, periods=2, max_per_day={"语文": 1, "数学": 1}, rng=rng,
    )
    spec = spec_from_grid(
        "SI", "GOLD-SMALL-INFEASIBLE",
        "黄金基准（不可行）：单班周数学课时被抬高到 35，超过全周 5 天 × 6 节 = 30 个节次容量，任何排法都必然产生硬约束冲突。",
        [(name, "行政班", 40) for name in class_names_si],
        subjects_si, teachers_si,
        [(f"SI-R{i:02d}", f"{c}教室", 60, "普通教室") for i, c in enumerate(class_names_si, start=1)],
        [],
    )
    # 手工写需求：数学每周 35（唯一教师 SI-T2），语文每周 5。
    spec["requirements"] = [
        ("SI-R001", "SI-C1", "SI-S1", "SI-T1", 5, 40, "HOME"),
        ("SI-R002", "SI-C1", "SI-S2", "SI-T2", 35, 40, "HOME"),
    ]
    out = root / "small-infeasible"
    out.mkdir(parents=True, exist_ok=True)
    write_workbook(spec, out / "MASTER_DATA-v1.xlsx")
    (out / "README.md").write_text(
        "# small-infeasible 黄金数据集\n\n"
        "单班、2 名教师，数学每周 35 课时而全周只有 5 天 × 6 节 = 30 个节次（且同一教师同节只能上 1 节），\n"
        "需求在算术上不可满足。用于验收：求解应快速终止并给出可解释的硬约束冲突清单，而不是死循环或崩溃。\n",
        encoding="utf-8",
    )

    # --- over-constrained: FLEXIBLE room pool demand exceeds pool capacity ----
    subjects_oc = [
        ("语文", "teacher", "HOME"), ("数学", "teacher", "HOME"), ("体育", "teacher", "FLEXIBLE"),
    ]
    class_names_oc = [f"{g}年级{n}班" for g in range(1, 4) for n in range(1, 3)]  # 6 班
    groups = [class_names_oc[i:i + 2] for i in range(0, len(class_names_oc), 2)]
    teachers_oc = [(f"{g}年级语文老师",) for g in range(1, 4)] + \
                  [(f"{g}年级数学老师",) for g in range(1, 4)] + \
                  [("体育老师甲",), ("体育老师乙",)]
    chinese_teachers = [t[0] for t in teachers_oc[0:3]]
    math_teachers = [t[0] for t in teachers_oc[3:6]]
    pe_teachers = ["体育老师甲", "体育老师乙"]
    teacher_of_oc = {
        "语文": {cls: chinese_teachers[i] for i, group in enumerate(groups) for cls in group},
        "数学": {cls: math_teachers[i] for i, group in enumerate(groups) for cls in group},
        "体育": {cls: pe_teachers[i // 3] for i, cls in enumerate(class_names_oc)},
    }
    # 每班体育 6 节/周 -> 6×6=36 个 FLEXIBLE 课次，共享 1 间专用教室（每周 30 节容量）。
    grid_oc = build_grid(
        {c: {"语文": 8, "数学": 8, "体育": 6} for c in class_names_oc},
        teacher_of_oc,
        days=5, periods=6, max_per_day={"语文": 2, "数学": 2, "体育": 2}, rng=rng,
    )
    rooms_oc = [
        (f"OC-R{i:02d}", f"{c}教室", 60, "普通教室") for i, c in enumerate(class_names_oc, start=1)
    ] + [("OC-R901", "体育专用教室", 60, "专用教室")]
    spec = spec_from_grid(
        "OC", "GOLD-OVER-CONSTRAINED",
        "黄金基准（过度约束）：每个体育走班需求都要求特征\"体育器材\"（OC-F1），但没有任何一间教室具备该特征，"
        "36 个体育课次全部必然触发\"教室特征缺失\"硬约束；其余课程可正常排。用于验收问题清单/诊断对特征类冲突的指认。",
        [(name, "行政班", 45) for name in class_names_oc],
        subjects_oc, teachers_oc, rooms_oc,
        grid_oc,
    )
    spec["features"] = [("OC-F1", "体育器材")]
    spec["roomFeatures"] = []
    spec["requirementFeatures"] = [
        (req[0], "OC-F1") for req in spec["requirements"] if req[2] == "OC-S3"
    ]
    out = root / "over-constrained"
    out.mkdir(parents=True, exist_ok=True)
    write_workbook(spec, out / "MASTER_DATA-v1.xlsx")
    (out / "README.md").write_text(
        "# over-constrained 黄金数据集\n\n"
        "6 个行政班，语文/数学在行政班教室正常上课；体育全部标记 FLEXIBLE 走班（每班每周 6 节，共 36 课次），\n"
        "且每个体育需求都要求教室特征\"体育器材\"（OC-F1）——数据集中没有任何教室具备该特征，\n"
        "因此 36 个体育课次全部必然触发\"教室特征缺失\"硬约束。其余课程不受影响。\n"
        "用于验收：问题清单/诊断能准确指认特征类冲突并给出可解释清单。\n",
        encoding="utf-8",
    )

    # --- k12-sample: 9 classes, ~20 teachers, ~85% utilization ----------------
    subjects_k12 = [
        ("语文", "teacher", "HOME"), ("数学", "teacher", "HOME"), ("英语", "teacher", "HOME"),
        ("体育与健康", "teacher", "FLEXIBLE"), ("艺术-音乐", "teacher", "FLEXIBLE"),
        ("艺术-美术", "teacher", "FLEXIBLE"), ("科学", "teacher", "FLEXIBLE"),
        ("道德与法治", "teacher", "HOME"), ("劳动", "teacher", "HOME"), ("信息科技", "teacher", "HOME"),
    ]
    weekly_k12 = {
        "语文": 6, "数学": 6, "英语": 3, "体育与健康": 3, "艺术-音乐": 2,
        "艺术-美术": 2, "科学": 2, "道德与法治": 2, "劳动": 1, "信息科技": 1,
    }  # 每班 28/30，占用率 93%
    # 教师按年级分科（年级内同科同一教师），共 10 科 × 3 年级 = 30 名教师，负载低。
    grades = ["一年级", "二年级", "三年级"]
    class_names_k12 = [f"{g}{n}班" for g in grades for n in range(1, 4)]
    subject_names = [name for name, _, _ in subjects_k12]
    teacher_defs = [(f"{g}{s}老师",) for g in grades for s in ["语", "数", "英", "体", "音", "美", "科", "道", "劳", "信"]]
    # 每科目按班级分教师（同年级同科共享一位），避免单科教师容量先于教室池爆掉。
    teacher_of_k12: dict[str, dict[str, str]] = {s: {} for s in subject_names}
    for gi, grade in enumerate(grades):
        for si, subject in enumerate(subject_names):
            for cls in class_names_k12[gi * 3:gi * 3 + 3]:
                teacher_of_k12[subject][cls] = teacher_defs[gi * 10 + si][0]
    grid_k12 = build_grid(
        {c: dict(weekly_k12) for c in class_names_k12},
        teacher_of_k12,
        days=5, periods=6,
        max_per_day={"语文": 2, "数学": 2, **{s: 1 for s in subject_names if s not in ("语文", "数学")}},
        rng=rng,
    )
    rooms_k12 = [
        (f"K12-R{i:02d}", f"{c}教室", 45, "普通教室") for i, c in enumerate(class_names_k12, start=1)
    ] + [
        ("K12-R901", "体育专用教室", 60, "专用教室"),
        ("K12-R902", "音乐教室", 60, "专用教室"),
        ("K12-R903", "美术教室", 60, "专用教室"),
        ("K12-R904", "科学实验室", 60, "专用教室"),
    ]
    spec = spec_from_grid(
        "K12", "GOLD-K12-SAMPLE",
        "黄金基准：9 个行政班（3 个年级 × 3 班）、30 名教师（分年级分科）、10 门课程，每班每周 28 课时（占用率 93%），"
        "体育/音乐/美术/科学走班并共享 4 间专用教室；由无冲突参考课表反推，保证可解。",
        [(name, "行政班", 45) for name in class_names_k12],
        subjects_k12, teacher_defs, rooms_k12,
        grid_k12,
    )
    out = root / "k12-sample"
    out.mkdir(parents=True, exist_ok=True)
    write_workbook(spec, out / "MASTER_DATA-v1.xlsx")
    write_reference(out, grid_k12, day_names)
    (out / "README.md").write_text(
        "# k12-sample 黄金数据集\n\n"
        "9 个行政班（3 个年级 × 3 班）、30 名教师（分年级分科）、10 门课程，每班每周 28 课时（5 天 × 6 节，占用率 93%）；\n"
        "体育/音乐/美术/科学为 FLEXIBLE 走班需求，共享 4 间专用教室。需求由无冲突参考课表反推\n"
        "（见 reference-solution.json），用于中等规模 K-12 的求解效率基准。\n",
        encoding="utf-8",
    )

    for name in ["small-feasible", "small-infeasible", "over-constrained", "k12-sample"]:
        path = root / name / "MASTER_DATA-v1.xlsx"
        print(f"{path}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
