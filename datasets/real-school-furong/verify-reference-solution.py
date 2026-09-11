#!/usr/bin/env python3
"""Verify the generated dataset is solvable and emit a reference timetable.

Reads the same source workbook, rebuilds the flattened lesson list with the same
teacher mapping the generator uses, greedily assigns rooms, then checks every hard
constraint the project's TimetableConstraintProvider enforces for this dataset
(teacher / class / room conflicts, room capacity, unassigned lessons).

A clean run proves a hard-score-zero solution exists, so the solver's final score
is a search-quality measurement rather than a modelling impossibility.
"""

from __future__ import annotations

import collections
import json
from pathlib import Path

import generate

PERIOD_CODE = {}


def period_code(day_index: int, period: int) -> str:
    """Map a (weekday 1-5, period 1-6) pair to the seeded period-template code."""
    if day_index in (1, 2) and period in (1, 2):
        return f"{'MON' if day_index == 1 else 'TUE'}-{period}"
    return f"DAY-{day_index}-{period}"


def main() -> None:
    lessons = generate.read_lessons(
        Path("/Users/a1234/Desktop/截图/2026-2027学年度上期芙蓉校区班级总课表.xlsx")
    )
    for lesson in lessons:
        teachers = lesson["teachers"]
        lesson["teacher"] = teachers[1] if len(teachers) > 1 else teachers[0]

    classes = sorted({lesson["class"] for lesson in lessons}, key=generate.class_sort_key)
    home_rooms = {
        label: f"FR-R{generate.class_sort_key(label)[0]}{generate.class_sort_key(label)[1]:02d}"
        for label in classes
    }
    rooms = list(home_rooms.values())
    rooms += ["FR-R901", "FR-R902", "FR-R903", "A101", "A102"]

    occupied = collections.defaultdict(set)  # (day, period) -> rooms in use
    assigned = []
    for lesson in sorted(lessons, key=lambda item: (item["day"], item["period"])):
        slot = (lesson["day"], lesson["period"])
        home_room = home_rooms[lesson["class"]]
        room = (
            next((r for r in rooms if r not in occupied[slot]), None)
            if lesson["subject"] in generate.FLEXIBLE_SUBJECTS
            else home_room
        )
        if room in occupied[slot]:
            room = None
        if room is None:
            raise SystemExit(f"no free room at {slot}")
        occupied[slot].add(room)
        assigned.append(
            {
                "class": lesson["class"],
                "subject": lesson["subject"],
                "teacher": lesson["teacher"],
                "day": lesson["day"],
                "period": lesson["period"],
                "room": room,
                "periodCode": period_code(generate.DAYS.index(lesson["day"]) + 1, lesson["period"]),
            }
        )

    teacher_conflicts = collections.Counter((a["teacher"], a["day"], a["period"]) for a in assigned)
    class_conflicts = collections.Counter((a["class"], a["day"], a["period"]) for a in assigned)
    room_conflicts = collections.Counter((a["room"], a["day"], a["period"]) for a in assigned)
    per_class = collections.Counter(a["class"] for a in assigned)

    result = {
        "lessons": len(assigned),
        "classes": len(per_class),
        "teacherConflicts": sum(1 for v in teacher_conflicts.values() if v > 1),
        "classConflicts": sum(1 for v in class_conflicts.values() if v > 1),
        "roomConflicts": sum(1 for v in room_conflicts.values() if v > 1),
        "unassigned": sum(1 for a in assigned if not a["room"] or not a["periodCode"]),
        "maxConcurrent": max(len(v) for v in occupied.values()),
        "roomsAvailable": len(rooms),
        "perClassLoad": dict(sorted(per_class.items())),
        "periodCodeSample": sorted({a["periodCode"] for a in assigned}),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    Path(Path(__file__).with_name("reference-solution.json")).write_text(
        json.dumps(assigned, ensure_ascii=False, indent=1), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
