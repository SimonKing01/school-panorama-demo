# -*- coding: utf-8 -*-
"""学校业务对象全景演示：接口自动化验收。

仅使用标准库，直接访问运行中的后端接口。
用法：
  python tests/api_acceptance.py                  # 主验收（会重置演示数据）
  python tests/api_acceptance.py --check-persistence  # 容器重启后执行，校验调课结果是否持久化
环境变量 API_BASE 可覆盖接口地址，默认 http://127.0.0.1:18080/api
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

BASE = os.environ.get("API_BASE", "http://127.0.0.1:18080/api").rstrip("/")
RESULTS = []
STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".persistence-state.json")
OUT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "acceptance-results.json")


def call(method, path, body=None, expect_error=False):
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json;charset=UTF-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8")
        try:
            payload = json.loads(payload)
        except json.JSONDecodeError:
            payload = {"raw": payload}
        return e.code, payload
    except Exception as e:  # 网络未就绪等
        return -1, {"error": str(e)}


def get(path):
    return call("GET", path)


def post(path, body=None):
    return call("POST", path, body if body is not None else {})


def check(name, ok, detail=""):
    RESULTS.append({"item": name, "passed": bool(ok), "detail": str(detail)[:500]})
    print(("  [通过] " if ok else "  [失败] ") + name + ((" — " + str(detail)[:220]) if detail else ""))
    return ok


def conflict_types(payload):
    return {c.get("type") for c in (payload.get("conflicts") or [])}


def lesson_of_schedule(payload, code):
    return next((x for x in (payload.get("schedule") or []) if x.get("code") == code), None)


def session_in_list(payload, code):
    return next((x for x in (payload.get("items") or []) if x.get("code") == code), None)


def run_persistence_check():
    print("\n=== 容器重启持久化校验 ===")
    if not os.path.exists(STATE_FILE):
        check("持久化校验前置状态文件存在", False, "请先运行主验收")
        return
    with open(STATE_FILE, encoding="utf-8") as f:
        state = json.load(f)
    code, sess = get("/schedule/3")
    check("重启后仍可访问接口", code == 200, f"HTTP {code}")
    lesson = (sess or {}).get("lesson") or {}
    ok = (lesson.get("teacherId") == 5 and lesson.get("classroomId") == 6
          and lesson.get("startTime") == "15:30" and lesson.get("endTime") == "16:15")
    check("重启后调课结果保留（教师/教室/时间）", ok,
          f"teacher={lesson.get('teacherId')} room={lesson.get('classroomId')} {lesson.get('startTime')}-{lesson.get('endTime')}")
    _, changes = get("/changes?limit=50")
    reason = state.get("reason", "")
    hit = any(c.get("reason") == reason for c in (changes or {}).get("items", []))
    check("重启后调课变更记录保留", hit, f"查找原因：{reason}")
    _, rev = get("/revision")
    check("重启后数据版本号不回退", (rev or {}).get("revision", 0) >= state.get("revision", 0),
          f"当前 revision={(rev or {}).get('revision')}, 重启前={state.get('revision')}")


def main():
    print("接口地址：" + BASE)
    print("\n=== 0. 健康与元数据 ===")
    code, health = get("/health")
    check("健康检查 /api/health 返回 UP", code == 200 and health.get("status") == "UP" and health.get("db") == "UP",
          f"HTTP {code} {health}")
    _, meta = get("/meta/options")
    meta = meta or {}
    check("元数据包含 2 所学校 / 4 个班 / 14 位教师 / 11 间教室",
          len(meta.get("schools", [])) == 2 and len(meta.get("classes", [])) == 4
          and len(meta.get("teachers", [])) == 14 and len(meta.get("classrooms", [])) == 11,
          f"schools={len(meta.get('schools', []))} classes={len(meta.get('classes', []))} "
          f"teachers={len(meta.get('teachers', []))} rooms={len(meta.get('classrooms', []))}")
    _, clock = get("/demo/clock")
    check("演示时钟可读取且含快捷时间点", bool((clock or {}).get("demoTime")) and len((clock or {}).get("presets", [])) >= 4,
          f"demoTime={(clock or {}).get('demoTime')}")

    print("\n=== 1. 恢复演示数据 ===")
    code, rst = post("/demo/reset")
    check("POST /api/demo/reset 成功", code == 200, f"HTTP {code}")
    _, clock = get("/demo/clock")
    demo_date = (clock.get("demoTime") or "")[:10]
    check("演示时间恢复到当天 10:15", (clock.get("demoTime") or "").endswith("10:15:00"), clock.get("demoTime"))
    _, ov = get("/overview")
    counts = (ov or {}).get("counts", {})
    check("总览统计：2 校 / 14 教师 / 4 班 / 32 学生 / 24 节课",
          counts.get("schools") == 2 and counts.get("teachers") == 14 and counts.get("classes") == 4
          and counts.get("students") == 32 and counts.get("sessionsToday") == 24, counts)

    print("\n=== 2. 无菜单直达：按姓名/编号查询对象及其下一节安排 ===")
    _, res = get("/search?q=%E9%99%88%E4%B8%80%E8%AF%BA&type=all")
    items = (res or {}).get("items", [])
    row = next((x for x in items if x.get("name") == "陈一诺"), None)
    check("按姓名搜索到学生 陈一诺（无须进入菜单）", row is not None, f"共 {len(items)} 条")
    if row:
        cur, nxt = row.get("currentLesson"), row.get("nextLesson")
        check("学生当前课程为 语文 / 张明远 / 10:00 / 明理楼 101",
              bool(cur) and cur.get("courseName") == "语文" and cur.get("teacherName") == "张明远"
              and cur.get("startTime") == "10:00" and "明理楼 101" in (cur.get("classroomName") or ""),
              json.dumps(cur, ensure_ascii=False))
        check("学生下一节课为 物理 / 赵建国 / 11:00，信息直接可见",
              bool(nxt) and nxt.get("courseName") == "物理" and nxt.get("teacherName") == "赵建国"
              and nxt.get("startTime") == "11:00" and bool(nxt.get("classroomName")),
              json.dumps(nxt, ensure_ascii=False))
    _, res2 = get("/search?q=STU-0001")
    check("按编号 STU-0001 搜索到同一学生", any(x.get("code") == "STU-0001" for x in (res2 or {}).get("items", [])))
    _, res3 = get("/search?q=%E5%BC%A0%E6%98%8E%E8%BF%9C&type=teacher")
    check("按姓名搜索教师 张明远", any(x.get("name") == "张明远" for x in (res3 or {}).get("items", [])))
    _, res4 = get("/search?schoolId=2&type=all")
    check("学校筛选生效（仅云岭实验学校对象）",
          len((res4 or {}).get("items", [])) > 0
          and all(x.get("schoolId") == 2 for x in (res4 or {}).get("items", [])),
          f"{len((res4 or {}).get('items', []))} 条")

    print("\n=== 3. 演示时间边界：当前课程 / 下一节课重算 ===")
    def set_clock(t):
        return post("/demo/clock", {"demoTime": demo_date + "T" + t, "running": False})

    set_clock("07:30:00")
    _, c0730 = get("/search?q=%E9%99%88%E4%B8%80%E8%AF%BA")
    r = (c0730 or {}).get("items", [{}])[0]
    _, ov0730 = get("/overview")
    check("07:30 上课前：无正在上课，下一节为 08:00 语文",
          r.get("currentLesson") is None and (r.get("nextLesson") or {}).get("startTime") == "08:00"
          and (ov0730 or {}).get("counts", {}).get("currentLessons") == 0,
          f"current={r.get('currentLesson')} next={(r.get('nextLesson') or {}).get('startTime')}")

    set_clock("10:15:00")
    _, c1015 = get("/search?q=%E9%99%88%E4%B8%80%E8%AF%BA")
    r = (c1015 or {}).get("items", [{}])[0]
    _, ov1015 = get("/overview")
    check("10:15 课中：当前为语文，下一节 11:00，总览 4 节课同时在进行",
          (r.get("currentLesson") or {}).get("courseName") == "语文"
          and (r.get("nextLesson") or {}).get("startTime") == "11:00"
          and (ov1015 or {}).get("counts", {}).get("currentLessons") == 4,
          f"current={(r.get('currentLesson') or {}).get('courseName')} "
          f"next={(r.get('nextLesson') or {}).get('startTime')} "
          f"ongoing={(ov1015 or {}).get('counts', {}).get('currentLessons')}")

    set_clock("10:45:00")
    _, c1045 = get("/search?q=%E9%99%88%E4%B8%80%E8%AF%BA")
    r = (c1045 or {}).get("items", [{}])[0]
    _, sch = get("/schedule?classId=1")
    ls3 = session_in_list(sch, "LS-003")
    check("10:45 下课边界：第三节已结束、当前无课、下一节 11:00",
          r.get("currentLesson") is None and (r.get("nextLesson") or {}).get("startTime") == "11:00"
          and (ls3 or {}).get("status") == "已结束",
          f"status={(ls3 or {}).get('status')} next={(r.get('nextLesson') or {}).get('startTime')}")

    set_clock("17:00:00")
    _, c1700 = get("/search?q=%E9%99%88%E4%B8%80%E8%AF%BA")
    r = (c1700 or {}).get("items", [{}])[0]
    _, objs = get("/objects?type=student")
    _, ov1700 = get("/overview")
    all_empty = all(x.get("currentLesson") is None and x.get("nextLesson") is None
                    for x in (objs or {}).get("items", []))
    check("17:00 全部课程结束后：当前/下一节均为空，总览无进行中课程（明确空状态数据）",
          r.get("currentLesson") is None and r.get("nextLesson") is None and all_empty
          and len((ov1700 or {}).get("currentLessons", [])) == 0,
          f"全局列表 {len((objs or {}).get('items', []))} 行均为空")

    # 跟随真实时间模式：演示时钟以真实时钟为基准推进。
    _, run1 = post("/demo/clock", {"running": True})
    t1 = run1.get("demoTime")
    time.sleep(1.5)
    _, run2 = get("/demo/clock")
    t2 = run2.get("demoTime")
    check("跟随真实时间：演示时间随真实时钟推进", run2.get("running") is True and bool(t2) and t2 > t1,
          f"{t1} -> {t2}")
    post("/demo/reset")

    # 同步到今天：把演示时间与课表统一拉回今天。
    post("/demo/clock", {"demoTime": demo_date + "T16:30:00", "running": False})
    _, synced = post("/demo/clock/sync-today")
    _, sch_today = get("/schedule")
    check("同步到今天：演示时间回到当天 10:15",
          (synced.get("demoTime") or "").endswith("T10:15:00"), synced.get("demoTime"))
    check("同步到今天：全部授课安排日期为今天",
          len((sch_today or {}).get("items", [])) == 24
          and all(x.get("lessonDate") == demo_date for x in (sch_today or {}).get("items", [])),
          f"{len((sch_today or {}).get('items', []))} 节")
    post("/demo/reset")

    print("\n=== 4. 冲突拒绝与原子性（失败不得产生任何写入） ===")
    _, base_sess = get("/schedule/3")
    _, base_changes = get("/changes?limit=50")
    _, base_rev = get("/revision")
    base_count = (base_changes or {}).get("total", len((base_changes or {}).get("items", [])))
    base_revision = (base_rev or {}).get("revision")
    before = (base_sess or {}).get("before") or {}

    base_body = {"teacherId": 1, "classroomId": 1, "lessonDate": demo_date,
                 "startTime": "10:00", "endTime": "10:45", "reason": "调课验收"}
    cases = [
        ("缺少原因被拒绝", dict(base_body, reason=""), "REASON_REQUIRED"),
        ("教师跨校被拒绝（云岭实验教师给星海一中上课）", dict(base_body, teacherId=13), "TEACHER_SCHOOL_MISMATCH"),
        ("教师科目不符被拒绝（数学教师教语文）", dict(base_body, teacherId=2), "TEACHER_SUBJECT_MISMATCH"),
        ("教师时间冲突被拒绝（吴嘉豪 10:00 已在初一（2）班）", dict(base_body, teacherId=8), "TEACHER_CONFLICT"),
        ("教室时间冲突被拒绝（明理楼 102 已被占用）", dict(base_body, classroomId=2), "CLASSROOM_CONFLICT"),
        ("班级时间冲突被拒绝（11:00 该班已有物理课）", dict(base_body, startTime="11:00", endTime="11:45"), "CLASS_CONFLICT"),
    ]
    for label, body, expected in cases:
        code, payload = post("/schedule/3/preview", body)
        p_ok = code == 200 and payload.get("valid") is False and expected in conflict_types(payload)
        code2, payload2 = post("/schedule/3/adjust", body)
        a_ok = code2 == 400 and expected in conflict_types(payload2)
        check("预览：" + label, p_ok, f"HTTP {code} conflicts={sorted(conflict_types(payload))} msg={payload.get('message','')}")
        check("提交：" + label, a_ok, f"HTTP {code2} conflicts={sorted(conflict_types(payload2))} msg={payload2.get('message','')}")
        _, ch = get("/changes?limit=50")
        _, rv = get("/revision")
        _, ss = get("/schedule/3")
        now_before = (ss or {}).get("before") or {}
        unchanged = (ch or {}).get("total", len((ch or {}).get("items", []))) == base_count \
            and (rv or {}).get("revision") == base_revision \
            and now_before.get("teacherId") == before.get("teacherId") \
            and now_before.get("classroomId") == before.get("classroomId") \
            and now_before.get("startTime") == before.get("startTime")
        check("失败后数据、变更日志、版本号均未改变：" + label, unchanged,
              f"changes={(ch or {}).get('total')}/{base_count} revision={(rv or {}).get('revision')}/{base_revision}")

    print("\n=== 5. 成功调课与全视角一致性 ===")
    success_body = {"teacherId": 5, "classroomId": 6, "lessonDate": demo_date,
                    "startTime": "15:30", "endTime": "16:15",
                    "reason": "张明远老师参加区级教研，改由陈思琪代课并调至第七节、多功能报告厅"}
    _, preview = post("/schedule/3/preview", success_body)
    check("提交前影响面板：校验通过、含差异与受影响人数",
          preview.get("valid") is True and len(preview.get("diff", [])) >= 3
          and (preview.get("impact") or {}).get("studentCount") == 8,
          f"diff={len(preview.get('diff', []))} 条, 受影响 {(preview.get('impact') or {}).get('studentCount')} 人")
    _, rev_before = get("/revision")
    code, adjusted = post("/schedule/3/adjust", success_body)
    check("调课提交成功（HTTP 200）", code == 200, f"HTTP {code} {adjusted.get('message','')}")
    lesson = (adjusted or {}).get("lesson") or {}
    check("返回的新课表：陈思琪 / 多功能报告厅 / 15:30-16:15",
          lesson.get("teacherId") == 5 and lesson.get("classroomId") == 6
          and lesson.get("startTime") == "15:30" and lesson.get("endTime") == "16:15",
          json.dumps(lesson, ensure_ascii=False))
    change = (adjusted or {}).get("change") or {}
    check("同一事务生成变更记录（含原因、前后差异、影响）",
          bool(change.get("id")) and change.get("reason") == success_body["reason"]
          and len(change.get("diff", [])) >= 3 and change.get("affectedStudentCount") == 8,
          f"changeId={change.get('id')} diff={len(change.get('diff', []))}")
    _, rev_after = get("/revision")
    check("数据版本号递增（供其他视角短轮询感知）",
          (rev_after or {}).get("revision", 0) > (rev_before or {}).get("revision", 0),
          f"{(rev_before or {}).get('revision')} -> {(rev_after or {}).get('revision')}")

    views = {
        "学生详情": ("/objects/student/1", "schedule"),
        "班级详情": ("/objects/class/1", "schedule"),
        "原教师详情": ("/objects/teacher/1", "schedule"),
        "新教师详情": ("/objects/teacher/5", "schedule"),
        "课表列表": ("/schedule?classId=1", "items"),
        "总览今日课程": ("/overview", "todayLessons"),
    }
    for label, (path, key) in views.items():
        _, payload = get(path)
        rows = (payload or {}).get(key, [])
        found = next((x for x in rows if x.get("code") == "LS-003"), None)
        if label == "原教师详情":
            ok = found is None
            check("原教师课表不再包含被调走的 LS-003", ok, f"匹配={found is not None}")
        else:
            ok = (found is not None and found.get("teacherId") == 5 and found.get("classroomId") == 6
                  and found.get("startTime") == "15:30" and found.get("endTime") == "16:15")
            check(label + " 读取到同一份新授课安排", ok,
                  json.dumps(found, ensure_ascii=False) if found else "未找到 LS-003")
    _, ov_after = get("/overview")
    check("总览最近变更包含本次调课",
          any(c.get("reason") == success_body["reason"] for c in (ov_after or {}).get("recentChanges", [])))
    _, ch_after = get("/changes?limit=50")
    newest = ((ch_after or {}).get("items", []) or [None])[0]
    check("变更记录列表可查看修改前后、原因、时间、受影响对象",
          bool(newest) and newest.get("reason") == success_body["reason"] and bool(newest.get("occurredAt"))
          and (newest.get("impact") or {}).get("studentCount") == 8
          and len((newest.get("impact") or {}).get("classNames", [])) >= 1
          and len((newest.get("impact") or {}).get("teacherNames", [])) >= 1,
          f"id={newest.get('id') if newest else None}")
    _, ch_detail = get("/changes/" + str(change.get("id")))
    check("变更详情可查看完整 diff 与影响面板",
          len((ch_detail or {}).get("diff", [])) >= 3
          and ((ch_detail or {}).get("impact") or {}).get("studentCount") == 8,
          f"diff={len((ch_detail or {}).get('diff', []))}")

    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump({"reason": success_body["reason"], "revision": (rev_after or {}).get("revision")}, f,
                  ensure_ascii=False)

    # 主验收结束时保留这次成功调课，供「容器重启持久化」校验使用；
    # 持久化校验完成后，由交付脚本调用 POST /api/demo/reset 恢复初始演示现场。
    print("\n=== 6. 现场保留 ===")
    print("  已保留成功调课结果，等待容器重启后执行 --check-persistence 校验持久化。")


if __name__ == "__main__":
    started = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    if "--check-persistence" in sys.argv:
        run_persistence_check()
    else:
        main()
    passed = sum(1 for r in RESULTS if r["passed"])
    failed = len(RESULTS) - passed
    print(f"\n合计 {len(RESULTS)} 项：通过 {passed}，失败 {failed}")
    out = OUT_FILE
    if "--check-persistence" in sys.argv:
        out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "persistence-results.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"time": started, "base": BASE, "passed": passed, "failed": failed, "results": RESULTS},
                  f, ensure_ascii=False, indent=2)
    print("结果已写入 " + out)
    sys.exit(0 if failed == 0 else 1)
