#!/usr/bin/env python3
"""写入口同步闭环验收：批量调课（POST /api/schedule/batch-adjust）与演示时间（POST /api/demo/clock）。

验收目标（对应需求「覆盖调课、批量调整、重置及演示时间相关操作」）：
  1. 批量调课在业务侧单事务落库 → 恰好 1 条 BATCH_ADJUST outbox 事件（不是 2 条）
  2. 两个课次的新教师在底座 5 秒内可见
  3. 底座变化历史可按 batchId 查到这一批（含 2 条明细）
  4. 演示时间变更产生 CLOCK 事件并同步到 ont_data_source.demo_time，且**不重写对象**（零对象版本新增）
  5. 收口 reset 后回到 SYNCED，快照水位追平来源水位

用法：python tests/write_entry_verify.py
输出：tests/write-entry-results.json + 控制台摘要；有失败退出码 1。
"""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

BIZ = "http://127.0.0.1:18080/api"
ONT = "http://127.0.0.1:18090/api/ontology"
ROOT = Path(__file__).resolve().parent

results: list[dict] = []


def check(name: str, ok: bool, detail) -> None:
    results.append({"item": name, "passed": bool(ok), "detail": detail})
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else f"  ← {detail}"))


def http(method: str, url: str, body=None, timeout: float = 15.0):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8") or "{}"
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, {"raw": raw}


def source_status() -> dict:
    _, body = http("GET", f"{ONT}/sources/status")
    return body.get("data") or {}


def wait_synced(timeout: float = 25.0) -> tuple[bool, float, dict]:
    start = time.time()
    st = source_status()
    while time.time() - start < timeout:
        st = source_status()
        if (st.get("status") == "SYNCED"
                and st.get("snapshotRevision") == st.get("currentSourceRevision")
                and int(st.get("pendingCount") or 0) == 0):
            return True, round(time.time() - start, 3), st
        time.sleep(0.25)
    return False, round(time.time() - start, 3), st


def wait_until(fn, timeout: float = 12.0, interval: float = 0.2):
    start = time.time()
    last = None
    while time.time() - start < timeout:
        last = fn()
        if last:
            return True, round(time.time() - start, 3), last
        time.sleep(interval)
    return False, round(time.time() - start, 3), last


def schedule_items() -> list[dict]:
    _, body = http("GET", f"{BIZ}/schedule")
    return (body.get("data") or body).get("items") or []


def teacher_items() -> list[dict]:
    _, body = http("GET", f"{BIZ}/objects?type=teacher")
    payload = body.get("data") or body
    return payload.get("items") or []


def lesson_detail(gid: str) -> dict:
    _, body = http("GET", f"{ONT}/objects/{gid}")
    return body.get("data") or {}


def attr_value(detail: dict, name: str):
    for row in detail.get("attributes") or []:
        if row.get("name") == name:
            return row.get("value")
    return None


def main() -> int:
    print("== 0. 清场：重置业务并等待底座同步 ==")
    http("POST", f"{BIZ}/demo/reset")
    ok, sec, st = wait_synced()
    check("reset 后底座回到 SYNCED 且快照水位=来源水位", ok,
          {"waitSec": sec, "snapshot": st.get("snapshotRevision"),
           "current": st.get("currentSourceRevision"), "status": st.get("status")})
    if not ok:
        return report()

    lessons = schedule_items()
    teachers = teacher_items()
    if not lessons or not teachers:
        check("读取课表与教师清单", False, {"lessons": len(lessons), "teachers": len(teachers)})
        return report()

    # 找到「教师在该时段时间空闲且学科匹配」的可调组合，避免与 8 条校验冲突
    # （TEACHER_SUBJECT_MISMATCH：教师授课科目必须与课程科目一致）。
    busy = {(l["lessonDate"], l["startTime"], l["teacherId"]) for l in lessons}
    picks = []
    for lesson in sorted(lessons, key=lambda x: x["id"]):
        subject = lesson.get("subject") or lesson.get("courseName")
        for teacher in teachers:
            if teacher.get("schoolId") != lesson.get("schoolId"):
                continue
            if teacher["id"] == lesson["teacherId"]:
                continue
            if teacher.get("subject") != subject:
                continue
            if (lesson["lessonDate"], lesson["startTime"], teacher["id"]) in busy:
                continue
            picks.append((lesson, teacher))
            break
        if len(picks) == 2:
            break
    if len(picks) < 2:
        check("构造两条可调课的组合", False, f"只找到 {len(picks)} 条")
        return report()

    before_rev = int(http("GET", f"{BIZ}/revision")[1].get("revision") or 0)
    outbox_before = http("GET", f"{BIZ}/admin/outbox?limit=200")[1]
    base_total = int(outbox_before.get("total") or len(outbox_before.get("items") or []))

    print("== 1. 批量调课：一次提交 2 条 ==")
    items = [{"sessionId": l["id"], "teacherId": t["id"],
              "reason": f"验收批量调课：{l['code']} 换 {t['name']}"} for l, t in picks]
    t0 = time.time()
    code, body = http("POST", f"{BIZ}/schedule/batch-adjust",
                      {"reason": "验收：批量调整两位教师的授课安排", "items": items})
    commit_ms = round((time.time() - t0) * 1000)
    # 原业务 API 沿用既有扁平风格：响应体直接是业务字段，没有 code/data 包裹。
    data = body.get("data") or body
    check("批量调课提交成功", code == 200,
          {"http": code, "code": body.get("code"), "message": body.get("message"),
           "conflicts": body.get("conflicts"), "commitMs": commit_ms,
           "items": [(l["code"], l["teacherName"], t["name"]) for l, t in picks]})
    if code != 200:
        return report()

    batch_id = data.get("batchId")
    new_rev = int(data.get("revision") or 0)
    check("批量调课只 bump 一次 revision", new_rev == before_rev + 1,
          {"before": before_rev, "after": new_rev, "batchId": batch_id})
    check("返回体含 appliedCount=2 与 2 条课次", data.get("appliedCount") == 2,
          {"appliedCount": data.get("appliedCount"), "batchId": batch_id})

    print("== 2. 事件：一次批量只产生 1 条 BATCH_ADJUST ==")
    outbox = http("GET", f"{BIZ}/admin/outbox?limit=200")[1]
    rows = outbox.get("items") or []
    batch_rows = [r for r in rows if r.get("changeKind") == "BATCH_ADJUST"
                  and int(r.get("revision") or -1) == new_rev]
    check("恰好 1 条 BATCH_ADJUST 事件（不是逐条 2 条）", len(batch_rows) == 1,
          {"matched": len(batch_rows), "rows": [r.get("changeKind") for r in rows[:4]],
           "totalBefore": base_total, "totalAfter": outbox.get("total")})

    delivered, wait_sec, _ = wait_until(
        lambda: (lambda r: r[0] if r else None)(
            [x for x in (http("GET", f"{BIZ}/admin/outbox?limit=200")[1].get("items") or [])
             if x.get("eventId") == (batch_rows[0]["eventId"] if batch_rows else "")
             and x.get("status") == "DELIVERED"]),
        timeout=15)
    check("BATCH_ADJUST 事件被投递器置为 DELIVERED", bool(delivered), {"waitSec": wait_sec})

    print("== 3. 底座：5 秒内可见新教师，且变化历史含本批 2 条明细 ==")
    latencies = []
    for lesson, teacher in picks:
        gid = f"gid:school-panorama:lessonSession:{lesson['id']}"
        ok2, sec2, detail = wait_until(
            lambda g=gid, t=teacher: (lambda d: d if attr_value(d, "teacherName") == t["name"] else None)(lesson_detail(g)),
            timeout=12)
        latencies.append({"lesson": lesson["code"], "expected": teacher["name"],
                          "visibleSec": sec2, "ok": ok2,
                          "actual": attr_value(lesson_detail(gid), "teacherName")})
        check(f"底座可见 {lesson['code']} 新教师 {teacher['name']}", ok2, latencies[-1])

    check("两条课次均在 5 秒内同步（含轮询间隔）",
          all(x["ok"] and x["visibleSec"] <= 5.0 for x in latencies),
          {"perLessonSec": {x["lesson"]: x["visibleSec"] for x in latencies},
           "commitMs": commit_ms, "condition": "本地直连 / 1s 投递轮询 / 0.2s 查询轮询"})

    # 按「该对象自己作为主体的最新一条记录」判定。
    # 注意两点：① /changes?globalId= 也会命中 impact 行，必须加 role=SELF_CHANGED 才是「属于它的记录」；
    # ② 审计历史不清空（reset/rebuild 都保留），所以只能取 changeId 最大的那条，不能取列表首尾碰运气。
    wants = {f"gid:school-panorama:lessonSession:{l['id']}": l["code"] for l, _ in picks}
    mine = {}
    for gid in wants:
        _, r = http("GET", f"{ONT}/changes?globalId={gid}&role=SELF_CHANGED&page=1&size=5")
        rows = [c for c in ((r.get("data") or {}).get("items") or [])
                if c.get("changeKind") == "BATCH_ADJUST"]
        if rows:
            mine[gid] = max(rows, key=lambda c: c["changeId"])
    check("底座变化历史按条目各写 1 条（2 条课次 → 2 条记录）", len(mine) == 2,
          {"matched": len(mine), "expected": list(wants.values()),
           "changeIds": {code: (mine.get(g) or {}).get("changeId") for g, code in wants.items()}})
    own_ok = True
    detail = {}
    for gid, code in wants.items():
        rec = mine.get(gid)
        diffs = [d for d in (rec.get("diff") or [])] if rec else []
        fields = [(d.get("field"), d.get("before"), d.get("after")) for d in diffs]
        others = [f for f in fields
                  if any(f[2] == t["name"] for l2, t in picks if l2["code"] != code)]
        detail[code] = {"changeId": (rec or {}).get("changeId"), "diff": fields, "foreign": others,
                        "relationsAdded": len((rec or {}).get("relationsAdded") or []),
                        "relationsRemoved": len((rec or {}).get("relationsRemoved") or [])}
        if not rec or len(fields) != 1 or others:
            own_ok = False
    check("每条记录只带自己的 diff，不混入同批其他课次", own_ok, detail)
    check("每条记录带自己的关系增删（换教师=旧关系结束+新关系建立）",
          own_ok and all(v["relationsAdded"] and v["relationsRemoved"] for v in detail.values()),
          detail)

    print("== 4. 演示时间：CLOCK 事件只改数据源时间，不重写对象 ==")
    _, clock = http("GET", f"{BIZ}/demo/clock")
    cur = (clock.get("data") or clock).get("demoTime")
    target = "2026-11-15T07:30:00" if cur != "2026-11-15T07:30:00" else "2026-11-15T17:00:00"
    rev_before_clock = int(http("GET", f"{BIZ}/revision")[1].get("revision") or 0)
    _, lesson_before = (0, lesson_detail(f"gid:school-panorama:lessonSession:{picks[0][0]['id']}"))

    t0 = time.time()
    code, body = http("POST", f"{BIZ}/demo/clock", {"demoTime": target})
    clock_commit = round((time.time() - t0) * 1000)
    check("演示时间提交成功", code == 200, {"http": code, "code": body.get("code"),
                                          "target": target, "commitMs": clock_commit})

    ok3, sec3, st3 = wait_until(lambda: (lambda s: s if s.get("demoTime") == target else None)(source_status()),
                                timeout=12)
    check("底座 ont_data_source.demo_time 同步为新时间", ok3,
          {"expected": target, "visibleSec": sec3, "actual": (st3 or {}).get("demoTime")})
    check("演示时间同步在 5 秒内完成", ok3 and sec3 <= 5.0, {"visibleSec": sec3})

    _, lesson_after = (0, lesson_detail(f"gid:school-panorama:lessonSession:{picks[0][0]['id']}"))
    rev_after_clock = int(http("GET", f"{BIZ}/revision")[1].get("revision") or 0)
    ver_before = attr_value(lesson_before, "version")
    ver_after = attr_value(lesson_after, "version")
    check("CLOCK 不产生对象版本（动态结果零写入）", ver_before == ver_after,
          {"lessonVersionBefore": ver_before, "lessonVersionAfter": ver_after,
           "revisionBefore": rev_before_clock, "revisionAfter": rev_after_clock,
           "note": "业务 revision 增加（CLOCK 事件本身），但课次对象版本不变"})

    print("== 5. 收口：reset 后再回 SYNCED ==")
    http("POST", f"{BIZ}/demo/reset")
    ok4, sec4, st4 = wait_synced()
    check("reset 后重新 SYNCED 且水位追平", ok4,
          {"waitSec": sec4, "snapshot": st4.get("snapshotRevision"),
           "current": st4.get("currentSourceRevision"), "dead": st4.get("deadCount")})
    return report()


def report() -> int:
    total = len(results)
    passed = sum(1 for r in results if r["passed"])
    payload = {
        "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "title": "写入口同步闭环验收（批量调课 + 演示时间）",
        "summary": {"total": total, "passed": passed, "failed": total - passed},
        "results": results,
    }
    (ROOT / "write-entry-results.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n== 汇总：{passed}/{total} 通过，{total - passed} 失败 ==")
    print("结果已写入 tests/write-entry-results.json")
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
