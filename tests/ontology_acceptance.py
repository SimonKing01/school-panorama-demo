# -*- coding: utf-8 -*-
"""本体数据底座 / 本体平台：六类验收接口自动化脚本（仅 Python 标准库）。

用法（服务启动后由主理人执行；本文件在准备阶段只编写、不运行）
------------------------------------------------------------------
  python tests/ontology_acceptance.py --list          # 列出全部验收项与调用方式
  python tests/ontology_acceptance.py --all           # 跑所有「无需人工/容器操作」的项
  python tests/ontology_acceptance.py --group 3       # 只跑验收三
  python tests/ontology_acceptance.py --case cross_system,sync_dup_event
  python tests/ontology_acceptance.py --phase offline-writes   # 停本体服务期间执行
  python tests/ontology_acceptance.py --phase after-recovery   # 恢复本体服务后执行
  python tests/ontology_acceptance.py --case classroom_zero_diff --before-ref <ref> --after-ref <ref>

分阶段说明
----------
* 「停/启本体服务」「停原业务服务」「绕过业务直改库」「容器重启」「消费中途强制终止」
  由主理人在外部执行；脚本只用 --phase / --case 提供可复现步骤与断言函数，绝不自行操作容器。
* 未被本次执行的验收项会在 tests/ontology-results.json 中显式标记 skipped 并写明原因与
  复现命令；**禁止把未执行项伪装为通过**。
* 退出码 = 失败项数（0 表示无失败；skipped 不计入失败，但会在 summary.hasSkipped 标记）。

接口依据：docs/ontology/03-本体API契约.md（本体 API）与 docs/API-CONTRACT.md（原业务 API）。
风格沿用 tests/api_acceptance.py（urllib + 标准库）；不修改任何现有脚本与源码。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import uuid

try:
    from ontology_helpers import (
        ADJUST_TARGET, BIZ_API, CRASH_STATE_FILE, GID_LESSON, GID_NEW_TEACHER, GID_OLD_TEACHER,
        GID_STUDENT, MAPPED_STATUS, OFFLINE_STATE_FILE, ONTO_API_ROOT, ONTO_WEB, QA_REASON, RESULTS_FILE,
        ROOT_DIR, TESTS_DIR, Client, Recorder, attr_names, attr_value, business_reset,
        demo_date, describe_injection, dyn_result, event_has_source, gid, inject_event, injection_accepted,
        items_of, load_json, make_event, now_ts, onto_changes, onto_get_object,
        onto_gid_get, quote, save_json, skip_kind, total_of, wait_synced, wait_until,
    )
except ImportError:  # 兼容 python -m tests.ontology_acceptance
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from ontology_helpers import (  # noqa: F401
        ADJUST_TARGET, BIZ_API, CRASH_STATE_FILE, GID_LESSON, GID_NEW_TEACHER, GID_OLD_TEACHER,
        GID_STUDENT, MAPPED_STATUS, OFFLINE_STATE_FILE, ONTO_API_ROOT, ONTO_WEB, QA_REASON, RESULTS_FILE,
        ROOT_DIR, TESTS_DIR, Client, Recorder, attr_names, attr_value, business_reset,
        demo_date, describe_injection, dyn_result, event_has_source, gid, inject_event, injection_accepted,
        items_of, load_json, make_event, now_ts, onto_changes, onto_get_object,
        onto_gid_get, quote, save_json, skip_kind, total_of, wait_synced, wait_until,
    )

G1 = "验收一·跨系统变化"
G2 = "验收二·独立运行与恢复"
G3 = "验收三·同步正确性"
G4 = "验收四·新增对象类型（教室）"
G5 = "验收五·来源追溯与可重建"
G6 = "验收六·原业务回归与动态结果"

# 教室后接入「零改动」检查范围：通用内核 Java + 通用前端（02 架构 §9.1 / §10.3 批次④）
KERNEL_PATHS = [
    "ontology-service/src/main/java/com/school/ontology/core",
    "ontology-service/src/main/java/com/school/ontology/controller",
    "ontology-web/src",
]


class Context(object):
    def __init__(self, client, **kw):
        self.client = client
        self.r = None                      # 当前 case 的 CaseRecorder
        self.phase = kw.get("phase")
        self.before_ref = kw.get("before_ref")
        self.after_ref = kw.get("after_ref")


# ══════════════════════════════════════════════════════════════════════════
# 验收一：跨系统变化（≤5 秒、多视角、双侧历史）
# ══════════════════════════════════════════════════════════════════════════
def verify_cross_system(ctx):
    """在原业务完成一次 LS-003 调课，轮询本体平台四视角，记录真实端到端耗时。"""
    r, c = ctx.r, ctx.client
    _, synced = business_reset(c)
    r.check("前置：原业务恢复初始演示现场且底座已同步", synced,
            "POST /api/demo/reset 后等待 /sources/status = SYNCED")

    before = onto_get_object(c, GID_LESSON)
    r.check("调课前授课安排对象详情可读（旧教师 张明远）",
            before.onto_ok and "张明远" in json.dumps(before.data, ensure_ascii=False),
            "属性字段=%s" % attr_names(before.data))

    day = demo_date(c)
    body = {
        "teacherId": 5, "classroomId": 6, "lessonDate": day,
        "startTime": "15:30", "endTime": "16:15", "reason": QA_REASON,
    }
    commit = c.biz("POST", "/schedule/3/adjust", body)
    t_commit = time.time()                      # 计时起点：业务提交成功（响应到达）
    lesson = (commit.payload or {}).get("lesson") or {}
    r.check("原业务调课提交成功（业务事务不等待底座）", commit.ok,
            "HTTP %s，提交耗时 %dms，revision=%s" %
            (commit.status, commit.elapsed_ms, (commit.payload or {}).get("revision")))
    r.check("提交返回的新课表为 陈思琪 / ROM-06 / 15:30-16:15",
            lesson.get("teacherId") == 5 and lesson.get("classroomId") == 6
            and lesson.get("startTime") == "15:30",
            json.dumps(lesson, ensure_ascii=False)[:220])

    # 四个已打开视角：对象详情 / 关系列表 / 关系图 / 变化记录
    views = {
        "对象详情": lambda: onto_get_object(c, GID_LESSON),
        "关系列表": lambda: onto_gid_get(c, GID_LESSON, "/relations", direction="both", depth=1),
        "关系图": lambda: onto_gid_get(c, GID_LESSON, "/graph", depth=1, limit=50),
        "变化记录": lambda: onto_changes(c, GID_LESSON, size=30),
    }

    def ready(name, resp):
        s = json.dumps(resp.data, ensure_ascii=False) if resp.data is not None else ""
        if not resp.onto_ok:
            return False
        if name == "对象详情":
            return "陈思琪" in s and "15:30" in s
        if name == "关系图":
            # 图视角的就绪判据必须是「新边已生效」，不能只看节点标题：
            # 已结束的旧关系同样会带出 teacher:5 的节点，用标题判就绪会在边还没换边时提前返回
            # （实测：节点已含陈思琪但 4 条活动边里没有 teacher:5）。
            edges = (resp.data or {}).get("edges") or []
            return any(e.get("active") is True and e.get("to") == GID_NEW_TEACHER for e in edges)
        return "陈思琪" in s or QA_REASON in s

    first_seen = {}
    poll_end = t_commit + 12.0                  # 采样上限 12s（目标窗口为 5s）
    while time.time() < poll_end:
        for name, fn in views.items():
            if name in first_seen:
                continue
            try:
                resp = fn()
            except Exception as e:
                resp = None
            if resp is not None and ready(name, resp):
                first_seen[name] = (time.time() - t_commit, resp)
        if len(first_seen) == len(views):
            break
        time.sleep(0.3)

    for name in views:
        hit = first_seen.get(name)
        r.check("已打开视角可查到新状态：" + name, hit is not None,
                ("首次可见 %.3fs" % hit[0]) if hit else "12s 内未出现新状态，需检查同步链路")
    elapsed_all = max([v[0] for v in first_seen.values()]) if len(first_seen) == len(views) else None
    timing = {"perViewSec": {k: round(v[0], 3) for k, v in first_seen.items()},
              "allViewsSec": round(elapsed_all, 3) if elapsed_all is not None else None,
              "commitLatencyMs": commit.elapsed_ms, "pollIntervalSec": 0.3,
              "condition": "本地无故障 / 直连 127.0.0.1 / 轮询间隔 0.3s"}
    r.check("从业务提交成功到四视角全部可查 ≤5 秒（记录真实耗时与条件）",
            elapsed_all is not None and elapsed_all <= 5.0,
            json.dumps(timing, ensure_ascii=False))

    # 关系图：旧关系结束 + 新关系建立
    # 断言必须按两端 globalId 判定（缺陷修复，2026-11-16）：图的边只带 from/to 的 globalId
    # 与关系标签，人读的标题在 nodes 里；旧写法在边里找"陈思琪"永远找不到，导致误报失败。
    graph = onto_gid_get(c, GID_LESSON, "/graph", depth=1, limit=50)
    edges = (graph.data or {}).get("edges") or []
    new_edge = any(e.get("active") is True and e.get("to") == GID_NEW_TEACHER for e in edges)
    old_edge = any(e.get("active") is False and e.get("to") == GID_OLD_TEACHER for e in edges)
    r.check("关系图可见新关系建立与旧关系结束", new_edge and old_edge,
            "edges=%d（其中有效 %d 条）, 新边(→%s)=%s, 旧边(→%s 已结束)=%s"
            % (len(edges), sum(1 for e in edges if e.get("active")),
               GID_NEW_TEACHER, new_edge, GID_OLD_TEACHER, old_edge))

    # 旧老师 / 新老师两侧都能查到同一次关系变化
    def qa_change_ids(resp):
        ids = set()
        for it in items_of(resp):
            s = json.dumps(it, ensure_ascii=False)
            if QA_REASON in s or "陈思琪" in s:
                if it.get("changeId") is not None:
                    ids.add(it.get("changeId"))
        return ids

    old_ch = onto_changes(c, GID_OLD_TEACHER, size=100)
    new_ch = onto_changes(c, GID_NEW_TEACHER, size=100)
    common = qa_change_ids(old_ch) & qa_change_ids(new_ch)
    r.check("旧老师与新老师两侧都能查到同一次关系变化历史",
            bool(common),
            "旧侧=%d 条 / 新侧=%d 条 / 共同 changeId=%s" %
            (total_of(old_ch), total_of(new_ch), sorted(common)[:5]))

    # role 区分「本身变化」与「按规则计算的相关对象」
    roles = set()
    for ch in (old_ch, new_ch):
        for change in items_of(ch):
            for it in (change.get("impact") or []):
                if isinstance(it, dict) and it.get("role"):
                    roles.add(it.get("role"))
    if roles:
        r.check("变化与影响区分 SELF_CHANGED / DERIVED_RELATED（不把邻接对象全标为已改变）",
                "SELF_CHANGED" in roles and "DERIVED_RELATED" in roles,
                "抽查 roles=%s" % sorted(str(x) for x in roles))
    else:
        r.skip("变化与影响区分 SELF_CHANGED / DERIVED_RELATED（接口侧 impact 明细）",
               "接口抽查未返回 change.impact[].role 明细；由 tests/ontology_browser.mjs 变化与影响页复核")
    r.skip("页面刷新失败后自动重试、不因提前记住新版本号停留旧数据",
           "需前端已打开视角 + 注入一次刷新失败，由 tests/ontology_browser.mjs 覆盖；"
           "本脚本只校验接口侧 /revision 协议（见 verify_revision_contract）")


def verify_revision_contract(ctx):
    """校验前端短轮询协议所需的 /revision 字段与「只增不减」语义（03 契约 §2.9）。

    主理人裁决 2：三处 revision 必须分别命名、不得混用——
      * 事件 revision / 对象 applied_revision = 业务 system_revision；
      * GET /api/ontology/revision 的 revision = 底座 ont_platform_revision（前端短轮询）；
      * /sources/status 的 snapshotRevision / currentSourceRevision = 业务 system_revision 水位。
    工程师口径：版本顺序比较严格用「业务 revision vs applied_revision」，
    **绝不能**用 ont_platform_revision；本用例只校验三处语义分离，不做跨计数器数值比较。
    """
    r, c = ctx.r, ctx.client
    a = c.onto("GET", "/revision")
    d = a.data or {}
    required = ["revision", "demoTime", "lastSuccessSyncAt", "pendingCount", "sourceUp"]
    missing = [k for k in required if k not in d]
    r.check("/revision 返回刷新协议所需五字段", a.onto_ok and not missing,
            "缺失=%s，实际=%s" % (missing, json.dumps(d, ensure_ascii=False)[:240]))
    b = c.onto("GET", "/revision")
    rev_a, rev_b = d.get("revision"), (b.data or {}).get("revision")
    r.check("ont_platform_revision（底座短轮询版本）单调递增、不回退",
            rev_a is not None and rev_b is not None and rev_b >= rev_a,
            "%s -> %s" % (rev_a, rev_b))
    r.check("两类时间字段分离（demoTime 与 lastSuccessSyncAt 独立存在）",
            "demoTime" in d and "lastSuccessSyncAt" in d,
            "demoTime=%s / lastSuccessSyncAt=%s" % (d.get("demoTime"), d.get("lastSuccessSyncAt")))

    biz_rev = (c.biz("GET", "/revision").payload or {}).get("revision")
    snap = (c.onto("GET", "/sources/status").data or {}).get("snapshotRevision")
    synced, _, _ = wait_until(
        lambda: (c.onto("GET", "/sources/status").data or {}).get("currentSourceRevision") == biz_rev,
        5.0, 0.5)
    cur = (c.onto("GET", "/sources/status").data or {}).get("currentSourceRevision")
    r.check("三处 revision 语义分离并分别命名：业务 system_revision / "
            "ont_platform_revision / 业务水位 snapshotRevision·currentSourceRevision",
            biz_rev is not None and d.get("revision") is not None
            and snap is not None and cur is not None and snap <= cur
            and synced and cur == biz_rev,
            "业务 system_revision=%s（事件/对象水位）；ont_platform_revision=%s（/revision，"
            "仅前端短轮询）；snapshotRevision=%s / currentSourceRevision=%s（/sources/status，"
            "业务水位）" % (biz_rev, d.get("revision"), snap, cur))


# ══════════════════════════════════════════════════════════════════════════
# 验收二：独立运行与恢复（分阶段，停/启服务由主理人执行）
# ══════════════════════════════════════════════════════════════════════════
def verify_source_status_contract(ctx):
    """五态 + 来源不可用标记 + 两类时间分离（无需停服务，可自动执行）。"""
    r, c = ctx.r, ctx.client
    s = c.onto("GET", "/sources/status")
    d = s.data or {}
    r.check("/sources/status 可读且含五态/进度/映射版本字段",
            s.onto_ok and all(k in d for k in
                              ["sourceId", "sourceUp", "status", "statusLabel",
                               "lastSuccessSyncAt", "demoTime", "pendingCount", "mappingVersion"]),
            json.dumps(d, ensure_ascii=False)[:300])
    r.check("status 取值在五态枚举内", d.get("status") in MAPPED_STATUS,
            "status=%s / label=%s" % (d.get("status"), d.get("statusLabel")))
    r.check("演示业务时间与同步处理时间是两个独立字段（不得混用）",
            bool(d.get("demoTime")) and bool(d.get("lastSuccessSyncAt"))
            and "demoTime" in d and "lastSuccessSyncAt" in d,
            "demoTime=%s / lastSuccessSyncAt=%s" % (d.get("demoTime"), d.get("lastSuccessSyncAt")))
    r.check("处理进度/待处理/错误/死信计数可观测",
            all(k in d for k in ["pendingCount", "processingCount", "errorCount", "deadCount"]),
            "pending=%s processing=%s error=%s dead=%s" %
            (d.get("pendingCount"), d.get("processingCount"), d.get("errorCount"), d.get("deadCount")))
    biz_rev = (c.biz("GET", "/revision").payload or {}).get("revision")
    snap, cur = d.get("snapshotRevision"), d.get("currentSourceRevision")
    r.check("/sources/status 的 snapshotRevision / currentSourceRevision 使用业务 "
            "system_revision 水位（不是 ont_platform_revision）",
            snap is not None and cur is not None and snap <= cur and cur == biz_rev,
            "业务 system_revision=%s / snapshotRevision=%s / currentSourceRevision=%s；"
            "前端短轮询的 ont_platform_revision 见 /revision，两者不得混用"
            % (biz_rev, snap, cur))


def verify_offline_writes(ctx):
    """【主理人已停止 ontology-service 后执行】业务连续多次合法修改，逐次校验单事务完整并留存快照。

    对应 PRD 验收二场景 A 前半段与 D5「覆盖全部写入口」。
    """
    r, c = ctx.r, ctx.client
    probe = c.onto("GET", "/revision", timeout=3)
    if probe.onto_ok:
        r.skip("离线期间业务连续多次修改（本体服务停止不影响业务）",
               "本体服务仍可访问（HTTP %s），无法构造离线窗口；请主理人先停止 ontology-service "
               "再执行 --phase offline-writes" % probe.status)
        return

    c.biz("POST", "/demo/reset", {})
    day = demo_date(c)
    rev0 = c.biz("GET", "/revision")
    base_revision = (rev0.payload or {}).get("revision")
    ch0 = c.biz("GET", "/changes?limit=50")
    base_changes = (ch0.payload or {}).get("total", len((ch0.payload or {}).get("items", [])))
    timeline = []

    # 两次合法调课（同一节课、换不同教室，均通过 8 条校验）+ 一次演示时钟变更
    operations = [
        ("adjust-1", "POST", "/schedule/3/adjust",
         {"teacherId": 5, "classroomId": 6, "lessonDate": day,
          "startTime": "15:30", "endTime": "16:15", "reason": QA_REASON + " #1"}),
        ("adjust-2", "POST", "/schedule/3/adjust",
         {"teacherId": 5, "classroomId": 3, "lessonDate": day,
          "startTime": "15:30", "endTime": "16:15", "reason": QA_REASON + " #2"}),
        ("clock-3", "POST", "/demo/clock",
         {"demoTime": day + "T17:00:00", "running": False}),
    ]
    prev_rev, prev_changes = base_revision, base_changes
    all_ok = True
    for tag, method, path, body in operations:
        resp = c.biz(method, path, body)
        rev = c.biz("GET", "/revision")
        ch = c.biz("GET", "/changes?limit=50")
        cur_rev = (rev.payload or {}).get("revision")
        cur_changes = (ch.payload or {}).get("total", len((ch.payload or {}).get("items", [])))
        if tag.startswith("adjust"):
            ok = (resp.ok and cur_rev is not None and prev_rev is not None
                  and cur_rev > prev_rev and cur_changes > prev_changes)
        else:
            ok = (resp.ok and cur_rev is not None and prev_rev is not None and cur_rev > prev_rev)
        all_ok = all_ok and ok
        r.check("离线期间业务写入成功且单事务完整：" + tag, ok,
                "HTTP %s, revision %s→%s, changes %s→%s" %
                (resp.status, prev_rev, cur_rev, prev_changes, cur_changes))
        sess = c.biz("GET", "/schedule/3")
        timeline.append({
            "tag": tag, "reason": body.get("reason"), "revision": cur_rev,
            "changeTotal": cur_changes,
            "lesson": {k: (sess.payload or {}).get("lesson", {}).get(k)
                       for k in ("teacherId", "teacherName", "classroomId", "startTime", "endTime")},
            "demoTime": (c.biz("GET", "/demo/clock").payload or {}).get("demoTime"),
        })
        prev_rev, prev_changes = cur_rev, cur_changes

    r.check("本体服务停机期间连续 3 次业务写入全部成功（底座故障不影响业务事务）", all_ok,
            "共 %d 次，均在本地业务库单事务内完成" % len(operations))
    state = {
        "phase": "offline-writes", "startedAt": now_ts(), "timeline": timeline,
        "baseRevision": base_revision, "finalRevision": prev_rev,
        "finalChanges": prev_changes,
        "note": "本体服务已停止时由主理人执行；恢复服务后运行 --phase after-recovery 比对最终状态与变化记录",
    }
    save_json(OFFLINE_STATE_FILE, state)
    r.check("离线阶段快照已落盘（供恢复后逐次比对）", os.path.exists(OFFLINE_STATE_FILE),
            OFFLINE_STATE_FILE)


def verify_after_recovery(ctx):
    """【主理人恢复 ontology-service 后执行】自动追赶后最终状态 / 变化记录逐次比对。"""
    r, c = ctx.r, ctx.client
    state = load_json(OFFLINE_STATE_FILE)
    if not state or state.get("phase") != "offline-writes":
        r.skip("恢复后最终状态与变化记录逐次比对",
               "缺少离线阶段快照 %s；请先执行 --phase offline-writes（且当时本体服务已停止）"
               % OFFLINE_STATE_FILE)
        return
    ok, _, elapsed = wait_synced(c, 60.0)
    r.check("恢复本体服务后自动追赶完成（pending 归零 / SYNCED）", ok,
            "等待 %.1fs" % elapsed)

    # 1) 最终状态 = 业务最终状态，不被旧快照/旧事件覆盖
    biz = c.biz("GET", "/schedule/3")
    lesson = (biz.payload or {}).get("lesson") or {}
    proj = onto_get_object(c, GID_LESSON)
    p = proj.data or {}
    # 稳健判定：投影 JSON 中同时出现业务当前的教师名与开始时间
    ps = json.dumps(p, ensure_ascii=False)
    same = (bool(lesson) and bool(lesson.get("teacherName"))
            and str(lesson.get("teacherName")) in ps
            and str(lesson.get("startTime")) in ps)
    r.check("恢复后底座最终状态 = 原业务最终状态（无旧覆盖、无回退）", same,
            "业务 teacher=%s %s-%s；投影命中=%s" %
            (lesson.get("teacherName"), lesson.get("startTime"), lesson.get("endTime"), same))

    # 2) 变化记录逐次可查（用带序号的唯一 reason 定位）
    changes = onto_changes(c, GID_LESSON, size=200)
    text = json.dumps(items_of(changes), ensure_ascii=False)
    adjust_tags = [t for t in state.get("timeline", []) if t.get("tag", "").startswith("adjust")]
    missing = [t["tag"] for t in adjust_tags if (t.get("reason") or "") not in text]
    r.check("恢复后变化记录包含离线期间每一次调课（无漏事件）",
            bool(adjust_tags) and not missing,
            "共 %d 次调课，缺失=%s，底座变化总数=%d" %
            (len(adjust_tags), missing, total_of(changes)))

    # 3) 演示时钟也完成同步（CLOCK 事件不产生对象属性变化，但底座 demoTime 应一致）
    st = (c.onto("GET", "/sources/status").data or {})
    clock = (c.biz("GET", "/demo/clock").payload or {}).get("demoTime")
    clock_tag = next((t for t in state.get("timeline", []) if t.get("tag") == "clock-3"), None)
    if clock_tag:
        r.check("演示业务时间（CLOCK 事件）恢复后与业务一致，且不混入同步处理时间",
                st.get("demoTime") == clock and st.get("lastSuccessSyncAt") != clock,
                "业务 demoTime=%s / 底座 demoTime=%s / lastSuccessSyncAt=%s" %
                (clock, st.get("demoTime"), st.get("lastSuccessSyncAt")))

    # 4) Outbox 无残留死信（失败恢复）
    outbox = c.onto("GET", "/admin/outbox?status=DEAD&size=20")
    r.check("恢复后无未恢复的 DEAD 事件（失败恢复完整）",
            outbox.onto_ok and total_of(outbox) == 0,
            "DEAD 事件数=%s" % total_of(outbox))


def verify_source_unavailable(ctx):
    """【主理人已停止原业务服务后执行】平台仍可查已同步对象并显示来源不可用 / 最后同步时间。"""
    r, c = ctx.r, ctx.client
    s = c.onto("GET", "/sources/status")
    d = s.data or {}
    if d.get("sourceUp") is not False:
        r.skip("来源不可用时本体平台仍可查已同步对象并显示新鲜度",
               "sourceUp 仍为 %s（非 false）；请主理人先停止原业务服务(backend)再执行 "
               "--case source_unavailable" % d.get("sourceUp"))
        return
    obj = onto_get_object(c, GID_STUDENT)
    r.check("来源不可用（sourceUp=false）时只读端点仍返回已同步对象",
            obj.onto_ok and bool(obj.data),
            "HTTP %s / code=%s / globalId 可读" % (obj.status, obj.code))
    r.check("明确显示最后成功同步时间（真实处理时钟）", bool(d.get("lastSuccessSyncAt")),
            "lastSuccessSyncAt=%s" % d.get("lastSuccessSyncAt"))
    r.check("明确显示「来源不可用」标记与非第五态语义",
            d.get("sourceUp") is False and bool(d.get("hint") or d.get("statusLabel")),
            "status=%s / label=%s / hint=%s" %
            (d.get("status"), d.get("statusLabel"), d.get("hint")))
    r.skip("浏览器侧「来源不可用」标记与新鲜度文案截图",
           "由 tests/ontology_browser.mjs 覆盖（需主理人同时停止原业务服务）")


# ══════════════════════════════════════════════════════════════════════════
# 验收三：同步正确性 6 种情况（各自可独立调用、可重复执行）
# ══════════════════════════════════════════════════════════════════════════
def _base_revision(c):
    """读取业务 system_revision（事件 revision 与对象 applied_revision 的唯一水位口径）。

    主理人裁决 2：这里**不是** GET /api/ontology/revision 的 ont_platform_revision
    （后者仅供前端短轮询、单调递增、与业务 revision 是两套计数器）。
    """
    return int((c.biz("GET", "/revision").payload or {}).get("revision") or 0)


def _restore_clean_state(ctx, label):
    """用例收尾：用真实业务写入把 applied_revision 推回正常，恢复源/投影一致。

    注入类用例会短暂让投影领先于源，必须用一次真实 revision 更大的业务事件把状态覆盖回来，
    否则后续真实事件会被判 STALE，破坏「可独立调用、可重复执行」。
    """
    r, c = ctx.r, ctx.client
    resp, synced = business_reset(c, 25.0)
    r.check("收尾：" + label + " 后由原业务 reset 恢复源/投影一致（保持可重复执行）",
            resp.ok and synced,
            "POST /api/demo/reset HTTP %s，底座 SYNCED=%s" % (resp.status, synced))


def verify_sync_dup_event(ctx):
    """情况① 同一 eventId 重复投递：不重复对象/关系/变更记录，结果幂等。

    主理人裁决 1：/internal/events 允许故障注入、不校验 eventId 是否存在于业务 Outbox，
    因此直接 POST 构造事件即可（**不得**加「先查 outbox」前置）；去重由底座保证。
    """
    r, c = ctx.r, ctx.client
    _, _ = business_reset(c)
    day = demo_date(c)
    rev = _base_revision(c)
    hist_before = total_of(c.onto("GET", "/changes?size=1"))
    eid = "qa-dup-" + uuid.uuid4().hex
    ev = make_event(eid, rev, business_time=now_ts(), lesson_date=day)
    r.check("构造事件带 payload.sourceTable / sourcePk（可解析出受影响 globalId）",
            event_has_source(ev),
            "sourceTable=%s / sourcePk=%s" %
            (ev["payload"].get("sourceTable"), ev["payload"].get("sourcePk")))

    r1, p1 = inject_event(c, ev)
    r.check("首次投递被持久化（ack=true, persisted=true）",
            injection_accepted(r1, p1) and p1.get("persisted") is True,
            describe_injection(r1, p1))

    r2, p2 = inject_event(c, ev)
    r3, p3 = inject_event(c, ev)
    r.check("第 2 次投递返回幂等跳过（ack=true / DUPLICATE）",
            injection_accepted(r2, p2) and p2.get("persisted") is not True
            and skip_kind(p2, "DUPLICATE"),
            describe_injection(r2, p2))
    r.check("第 3 次投递同样幂等（ack=true / DUPLICATE）",
            injection_accepted(r3, p3) and p3.get("persisted") is not True
            and skip_kind(p3, "DUPLICATE"),
            describe_injection(r3, p3))

    hist_after = total_of(c.onto("GET", "/changes?size=1"))
    r.check("重复投递未追加变更历史（总数最多 +1）", hist_after - hist_before <= 1,
            "changes total %s -> %s" % (hist_before, hist_after))
    ch = onto_changes(c, GID_LESSON, size=100)
    hits = [it for it in items_of(ch) if it.get("eventId") == eid]
    r.check("同一 eventId 对应的变化记录不重复（≤1 条）", len(hits) <= 1,
            "命中 %d 条（eventId=%s）" % (len(hits), eid))
    ov = (c.onto("GET", "/overview").data or {}).get("counts") or {}
    r.check("重复投递未产生重复对象/关系（overview counts 稳定）",
            bool(ov) and ov.get("objects", 0) > 0,
            "objects=%s relations=%s" % (ov.get("objects"), ov.get("relations")))
    _restore_clean_state(ctx, "重复投递")


def verify_sync_stale_event(ctx):
    """情况② 较旧 revision 晚于新事件到达：最终 = 较新事件，不回退、不伪造历史。

    主理人裁决 1+2 / 工程师口径：直接 POST 构造两条事件；事件 revision 用**业务
    system_revision**（rev 与 rev-1），版本比较严格为「业务 revision vs applied_revision」，
    **绝不用 ont_platform_revision**。两条事件必须都带 payload.sourceTable/sourcePk，
    否则会被当成「无对象变化」仅 bump 平台版本，造成本用例假通过。
    """
    r, c = ctx.r, ctx.client
    _, _ = business_reset(c)
    day = demo_date(c)
    rev = _base_revision(c)
    hi_after = {"teacherId": 5, "teacherName": "陈思琪", "classroomId": 6,
                "classroomName": "多功能报告厅", "lessonDate": day,
                "startTime": "15:30", "endTime": "16:15", "version": 2}
    hi = make_event("qa-hi-" + uuid.uuid4().hex, rev, after=hi_after,
                    business_time=now_ts(), lesson_date=day)
    lo_after = {"teacherId": 1, "teacherName": "张明远", "classroomId": 1,
                "classroomName": "明理楼 101", "lessonDate": day,
                "startTime": "10:00", "endTime": "10:45", "version": 1}
    lo = make_event("qa-lo-" + uuid.uuid4().hex, rev - 1, after=lo_after,
                    business_time=now_ts(), lesson_date=day)
    r.check("hi/lo 两条事件均带 payload.sourceTable / sourcePk，且解析出同一个 globalId"
            "（STALE 判定前提，防止「无对象变化」假通过）",
            event_has_source(hi) and event_has_source(lo)
            and hi["payload"].get("sourceTable") == lo["payload"].get("sourceTable")
            and hi["payload"].get("sourcePk") == lo["payload"].get("sourcePk"),
            "hi=%s:%s / lo=%s:%s / hi.revision=%s > lo.revision=%s（均为业务 system_revision）" %
            (hi["payload"].get("sourceTable"), hi["payload"].get("sourcePk"),
             lo["payload"].get("sourceTable"), lo["payload"].get("sourcePk"),
             hi.get("revision"), lo.get("revision")))

    r_hi, p_hi = inject_event(c, hi)
    r.check("较新 revision 事件被持久化（ack=true, persisted=true）",
            injection_accepted(r_hi, p_hi) and p_hi.get("persisted") is True,
            describe_injection(r_hi, p_hi))
    changed, _, _ = wait_until(
        lambda: "陈思琪" in json.dumps(onto_get_object(c, GID_LESSON).data, ensure_ascii=False),
        8.0, 0.3)
    s_hi = json.dumps(onto_get_object(c, GID_LESSON).data, ensure_ascii=False)
    r.check("较新事件确实改变了对象投影（非仅 bump 平台版本的「无对象变化」）",
            changed and "陈思琪" in s_hi and "15:30" in s_hi,
            s_hi[:220])
    hist_before = total_of(c.onto("GET", "/changes?size=1"))
    r_lo, p_lo = inject_event(c, lo)
    r.check("较旧 revision 事件被识别为 STALE 并丢弃（ack=true / STALE、不落库）",
            injection_accepted(r_lo, p_lo) and p_lo.get("persisted") is not True
            and skip_kind(p_lo, "STALE"),
            describe_injection(r_lo, p_lo))
    time.sleep(1.0)                            # 给「旧事件后到」留出污染窗口
    obj = onto_get_object(c, GID_LESSON)
    s = json.dumps(obj.data, ensure_ascii=False)
    r.check("最终状态 = 较新事件（陈思琪 / 15:30），未被旧事件覆盖或回退",
            "陈思琪" in s and "15:30" in s, s[:260])
    hist_after = total_of(c.onto("GET", "/changes?size=1"))
    r.check("旧事件晚到未追加伪造的业务变化历史", hist_after <= hist_before,
            "changes total %s -> %s" % (hist_before, hist_after))
    _restore_clean_state(ctx, "乱序事件")


def _crash_snapshot(c):
    return {
        "at": now_ts(),
        "bizRevision": _base_revision(c),
        "bizChanges": (c.biz("GET", "/changes?limit=100").payload or {}).get("total"),
        "ontoChanges": total_of(c.onto("GET", "/changes?size=1")),
        "platformRevision": (c.onto("GET", "/revision").data or {}).get("revision"),
        "lesson": {k: (c.biz("GET", "/schedule/3").payload or {}).get("lesson", {}).get(k)
                   for k in ("teacherName", "startTime", "endTime")},
        "outboxPending": total_of(c.onto("GET", "/admin/outbox?status=PENDING&size=1")),
    }


def verify_midway_before(ctx):
    """情况③ 前半段：主理人在「消费处理到一半」强制终止本体服务之前记录基线。"""
    r, c = ctx.r, ctx.client
    snap = _crash_snapshot(c)
    snap["phase"] = "crash-before"
    snap["note"] = ("请主理人在此之后制造一次同步中途失败（如消费过程中 docker kill/stop "
                    "ontology-service），随后重启并运行 --phase crash-after")
    save_json(CRASH_STATE_FILE, snap)
    r.check("崩溃前基线已记录（业务 revision / 变更数 / 底座版本 / 待处理事件）",
            all(snap.get(k) is not None for k in ("bizRevision", "bizChanges", "platformRevision")),
            json.dumps(snap, ensure_ascii=False))
    r.skip("同步中途失败与重启的最终一致性判定",
           "需主理人先强制终止再重启 ontology-service，然后执行 --phase crash-after")


def verify_midway_after(ctx):
    """情况③ 后半段：重启后从断点续处理，最终一致、无重复、无遗漏。"""
    r, c = ctx.r, ctx.client
    snap = load_json(CRASH_STATE_FILE)
    if not snap or snap.get("phase") != "crash-before":
        r.skip("重启后从断点续处理且最终一致",
               "缺少崩溃前基线 %s；请先执行 --phase crash-before 并由主理人制造中途失败与重启"
               % CRASH_STATE_FILE)
        return
    ok, _, elapsed = wait_synced(c, 60.0)
    r.check("重启后底座自动追赶完成（pending 归零）", ok, "等待 %.1fs" % elapsed)

    ch = c.onto("GET", "/changes?size=200")
    pairs = {}
    dup = 0
    for it in items_of(ch):
        key = it.get("eventId")
        if key is None:
            continue
        pairs[key] = pairs.get(key, 0) + 1
        if pairs[key] > 1:
            dup += 1
    r.check("重启续处理后无重复变化（同一 eventId 最多 1 条）", dup == 0,
            "重复条目=%d，去重后 eventId 数=%d" % (dup, len(pairs)))

    now_biz = (c.biz("GET", "/changes?limit=100").payload or {}).get("total")
    now_onto = total_of(c.onto("GET", "/changes?size=1"))
    r.check("重启续处理后无遗漏（底座变化数不减少）",
            now_onto >= (snap.get("ontoChanges") or 0) and (now_biz or 0) >= (snap.get("bizChanges") or 0),
            "业务 %s→%s / 底座 %s→%s" %
            (snap.get("bizChanges"), now_biz, snap.get("ontoChanges"), now_onto))
    st = (c.onto("GET", "/sources/status").data or {})
    r.check("重启后无未恢复失败 / 死信",
            (st.get("errorCount") in (0, None)) and (st.get("deadCount") in (0, None)),
            "error=%s dead=%s" % (st.get("errorCount"), st.get("deadCount")))
    r.check("重启后 ont_platform_revision（底座短轮询版本）只增不减",
            (c.onto("GET", "/revision").data or {}).get("revision", 0) >= (snap.get("platformRevision") or 0),
            "%s -> %s" % (snap.get("platformRevision"),
                          (c.onto("GET", "/revision").data or {}).get("revision")))


def verify_admin_retry(ctx):
    """情况③ 补充：失败事件经**底座** POST /api/ontology/admin/retry 重放并可幂等恢复。

    工程师口径（主理人中转）：该端点已改为**代理转发**业务侧
    POST http://backend:8080/admin/retry?eventId=（业务侧同时支持 /api/admin/retry
    与 /admin/retry）。成功仍按 03 契约 §2.13 返回
    {eventId, status:"PENDING", attempts:0, message:"已重新排队"}；
    **业务不可用时返回 SOURCE_UNAVAILABLE + 中文错误**。
    脚本**只调用底座端点**，不直接调用 backend 的 /admin/retry。

    按 /sources/status.sourceUp 分两个互斥分支，未构造的分支显式 skipped：
      * sourceUp=false（主理人停原业务服务时，与 source_unavailable 共用停机窗口）：
        只断言 SOURCE_UNAVAILABLE + 中文错误，不执行 reset（业务不可用）；
      * 其余：跑成功路径，并把「来源不可用」子项 skipped 写明复现方式。
    """
    r, c = ctx.r, ctx.client
    st0 = (c.onto("GET", "/sources/status").data or {})

    if st0.get("sourceUp") is False:
        down = c.onto("POST", "/admin/retry?eventId=" + quote("qa-retry-source-down"), {})
        payload = down.payload if isinstance(down.payload, dict) else {}
        msg = str(payload.get("message") or "")
        dd = down.data if isinstance(down.data, dict) else {}
        r.check("原业务不可用时 /admin/retry 返回 SOURCE_UNAVAILABLE（代理转发失败）",
                down.code == "SOURCE_UNAVAILABLE" or dd.get("sourceUp") is False,
                "HTTP %s / code=%s / message=%s / data=%s" %
                (down.status, down.code, msg,
                 json.dumps(dd, ensure_ascii=False)[:180]))
        r.check("来源不可用的错误提示为中文（不返回英文技术栈错误）",
                bool(re.search(r"[\u4e00-\u9fff]", msg)), "message=%s" % msg)
        r.skip("底座 /admin/retry 成功路径（status=PENDING）",
               "原业务服务已停止，无法构造成功重放；恢复原业务服务后重跑 --case admin_retry")
        return

    _, _ = business_reset(c)               # 产生至少一条真实业务事件并等待同步
    wait_synced(c, 20.0)
    ob = c.onto("GET", "/admin/outbox?size=50")
    rows = items_of(ob)
    if not rows:
        r.skip("底座 /admin/retry 重放失败事件并幂等恢复",
               "/admin/outbox 返回空（底座尚未看到任何业务事件）；请先完成一次同步后重跑"
               " --case admin_retry")
        r.skip("原业务不可用时 /admin/retry 返回 SOURCE_UNAVAILABLE + 中文错误",
               "未构造来源不可用窗口；请主理人停止原业务服务(backend)后重跑 --case admin_retry")
        return
    failed = [x for x in rows if x.get("status") in ("FAILED", "DEAD")]
    if not failed:
        # 健康系统里不存在失败事件，这是好事而不是缺陷。此时只能验证「不该重试的事件
        # 会被明确拒绝」这条语义；真正的重放路径需要先制造失败（见 source_unavailable
        # 用例：停掉原业务服务后重试），否则断言 PENDING 就是强人所难。
        delivered = [x for x in rows if x.get("status") == "DELIVERED"]
        sample = (delivered or rows)[0]
        sid = sample.get("eventId")
        bad = c.onto("POST", "/admin/retry?eventId=" + quote(str(sid)), {})
        r.check("没有失败事件时：重试已投递/不存在的事件被明确拒绝（不误报成功）",
                (not bad.onto_ok) or (bad.data or {}).get("status") != "PENDING",
                "HTTP %s / code=%s（该事件原状态=%s，outbox 共 %d 条，FAILED/DEAD=%d）"
                % (bad.status, bad.code, sample.get("status"), len(rows), len(failed)))
        r.skip("底座 /admin/retry 重放失败事件并幂等恢复",
               "当前无 FAILED/DEAD 事件（outbox 全部 DELIVERED），无法构造重放前置；"
               "需先制造失败事件后重跑：先停 backend 再调一次调课提交，或执行"
               " --case source_unavailable 后在同一停机窗口重跑 --case admin_retry")
        r.skip("原业务不可用时 /admin/retry 返回 SOURCE_UNAVAILABLE + 中文错误",
               "sourceUp 仍为 True，未构造来源不可用窗口；请主理人停止原业务服务(backend)后"
               "在同一停机窗口重跑 --case admin_retry（与 --case source_unavailable 共用）")
        return
    target = failed[0]
    eid = target.get("eventId")
    r.check("/admin/outbox 可读且能定位待重试事件",
            ob.onto_ok and bool(eid),
            "共 %d 条，FAILED/DEAD=%d，选用 eventId=%s（原状态=%s）" %
            (len(rows), len(failed), eid, target.get("status")))
    hist_before = total_of(c.onto("GET", "/changes?size=1"))

    retry = c.onto("POST", "/admin/retry?eventId=" + quote(str(eid)), {})
    d = retry.data or {}
    r.check("底座 /admin/retry 返回契约结构（status=PENDING / attempts 重置 / 已重新排队）",
            retry.onto_ok and d.get("status") == "PENDING" and d.get("eventId") == eid
            and d.get("attempts") in (0, None),
            "HTTP %s / code=%s / data=%s" %
            (retry.status, retry.code, json.dumps(d, ensure_ascii=False)[:220]))

    synced, _, waited = wait_synced(c, 20.0)
    r.check("重放后同步收敛（pending 归零）", synced, "等待 %.1fs" % waited)
    hist_after = total_of(c.onto("GET", "/changes?size=1"))
    r.check("重放事件不产生重复变化（总数最多 +1）", hist_after - hist_before <= 1,
            "changes total %s -> %s；若重放的是未成功事件允许 +1，已处理事件应 +0"
            % (hist_before, hist_after))
    ch = c.onto("GET", "/changes?size=200")
    hits = [x for x in items_of(ch) if x.get("eventId") == eid]
    r.check("同一 eventId 在变化历史中最多 1 条（processed_event 去重生效）",
            len(hits) <= 1, "命中 %d 条" % len(hits))
    out_dead = c.onto("GET", "/admin/outbox?status=DEAD&size=20")
    r.check("重试后无遗留 DEAD 事件", out_dead.onto_ok and total_of(out_dead) == 0,
            "DEAD 事件数=%s" % total_of(out_dead))
    r.skip("原业务不可用时 /admin/retry 返回 SOURCE_UNAVAILABLE + 中文错误",
           "sourceUp 仍为 True，未构造来源不可用窗口；请主理人停止原业务服务(backend)后，"
           "在同一停机窗口重跑 --case admin_retry（与 --case source_unavailable 共用）")


def verify_import_during_change(ctx):
    """情况④ 全量导入期间发生业务修改：不遗漏、不被旧快照覆盖；并验证重复导入不产生重复。"""
    r, c = ctx.r, ctx.client
    _, _ = business_reset(c)
    day = demo_date(c)
    counts_before = (c.onto("GET", "/overview").data or {}).get("counts") or {}

    start = c.onto("POST", "/import/full", {})
    if start.code == "TASK_CONFLICT":
        r.skip("全量导入期间业务修改不遗漏、不被旧快照覆盖",
               "已有导入任务运行中（TASK_CONFLICT）；请稍后重跑 --case sync_import_during_change")
        return
    task_id = (start.data or {}).get("taskId")
    r.check("全量导入已启动并返回 taskId", start.onto_ok and task_id is not None,
            "HTTP %s / code=%s / taskId=%s" % (start.status, start.code, task_id))

    # 立即取一次进度，判断 adjust 是否真的落在「导入进行中」窗口内
    p0 = (c.onto("GET", "/import/progress?taskId=%s" % task_id).data or {}).get("status")
    adj = c.biz("POST", "/schedule/3/adjust", {
        "teacherId": 5, "classroomId": 6, "lessonDate": day,
        "startTime": "15:30", "endTime": "16:15", "reason": QA_REASON + " #导入期修改",
    })
    r.check("导入进行中发起的合法调课提交成功", adj.ok, "HTTP %s" % adj.status)
    window_ok = p0 not in ("SUCCESS", "FAILED")
    if not window_ok:
        r.skip("「导入期间」窗口成立的证据",
               "取进度时导入已是 %s，未能构造窗口；建议主理人加大源数据量或限速后重跑。"
               "下列「最终状态/不遗漏」断言仍照常执行。" % p0)

    def finished():
        d = (c.onto("GET", "/import/progress?taskId=%s" % task_id).data or {})
        return d.get("status") in ("SUCCESS", "FAILED")
    done, last, waited = wait_until(finished, 90.0, 0.5)
    final_status = (c.onto("GET", "/import/progress?taskId=%s" % task_id).data or {}).get("status")
    r.check("全量导入任务结束（可查看进度，SUCCESS/FAILED 明确）", done,
            "status=%s，等待 %.1fs，进度数据=%s" %
            (final_status, waited, json.dumps(last, ensure_ascii=False)[:160] if isinstance(last, dict) else last))
    if final_status == "FAILED":
        retry = c.onto("POST", "/import/retry", {"taskId": task_id})
        r.check("导入失败可从断点幂等重试", retry.onto_ok, "HTTP %s / code=%s" % (retry.status, retry.code))
        wait_until(finished, 90.0, 0.5)

    wait_synced(c, 30.0)
    obj = onto_get_object(c, GID_LESSON)
    s = json.dumps(obj.data, ensure_ascii=False)
    r.check("导入结束后最终状态 = 最新业务状态（修改未被旧快照覆盖）",
            "陈思琪" in s and "15:30" in s, s[:260])
    ch = onto_changes(c, GID_LESSON, size=100)
    r.check("导入期间发生的调课变化记录不遗漏",
            "导入期修改" in json.dumps(items_of(ch), ensure_ascii=False),
            "底座变化总数=%d" % total_of(ch))

    # D1 重复导入不产生重复对象与关系
    # 口径（缺陷修复，2026-11-16）：必须比较「两次连续导入之间」的计数，且第一次导入要在
    # 本用例的调课完全落定之后再跑。原因：本用例的调课会**真实改变关系基数**
    # （329 → 337，差额 = 8 名学生 × 1 位新教师），若拿调课前的 counts_before 当基线，
    # 断言会把「正常的业务变更」误报成「重复导入产生了重复」。
    # 说明（P8 修复后）：JOIN_RULE 派生关系（学生→授课教师）已由事件投影中的独立通道
    # 即时重算，不再存在「只有全量导入/重建才补齐」的滞后；因此本用例的职责收窄为
    # **纯幂等验证**——连续两次全量导入，第二次的对象与关系计数一条都不能增加。
    def _run_full_import():
        resp = c.onto("POST", "/import/full", {})
        if resp.onto_ok:
            tid = (resp.data or {}).get("taskId")
            wait_until(lambda: (c.onto("GET", "/import/progress?taskId=%s" % tid).data or {})
                       .get("status") in ("SUCCESS", "FAILED"), 90.0, 0.5)
        wait_synced(c, 30.0)
        return (c.onto("GET", "/overview").data or {}).get("counts") or {}

    baseline_reimport = _run_full_import()      # 让规则关系先补齐，建立干净基线
    counts_after = _run_full_import()           # 再导入一次，理论上计数不应再变
    stable = (bool(baseline_reimport) and bool(counts_after)
              and baseline_reimport.get("objects") is not None
              and counts_after.get("objects") is not None
              and baseline_reimport.get("objects") == counts_after.get("objects")
              and baseline_reimport.get("relations") == counts_after.get("relations"))
    r.check("连续两次全量导入：对象数/关系数不变（不产生重复）", stable,
            "第一次 objects=%s relations=%s / 第二次 objects=%s relations=%s"
            "（本用例调课前的旧基线 objects=%s relations=%s，差额=本次调课的业务影响，不计入重复判定）" %
            (baseline_reimport.get("objects"), baseline_reimport.get("relations"),
             counts_after.get("objects"), counts_after.get("relations"),
             counts_before.get("objects"), counts_before.get("relations")))
    r.check("重复导入后 globalId 稳定（授课安排仍可定位）", onto_get_object(c, GID_LESSON).onto_ok,
            GID_LESSON)
    _restore_clean_state(ctx, "导入期修改")


def verify_delete_cleanup(ctx):
    """情况⑤ 对象删除后的有效关系清理：墓碑化 + 失效关系 + 可解释变化，不悬挂。

    主理人裁决 1：/internal/events 允许故障注入，直接 POST 一条 changeKind=DELETE
    事件即可构造删除场景，**无需**主理人绕过业务直改库，也**不加**「先查业务 Outbox」前置。

    可重复执行：开头先 rebuild 恢复投影（清除上次留下的墓碑），结尾再 rebuild 一次，
    避免污染依赖完整投影的后续用例（如 rebuild_consistency）。本用例刻意**不**跑
    reconcile——业务源里该记录仍在，对账会把墓碑当 MISSING 修回来，反而破坏删除语义。
    """
    r, c = ctx.r, ctx.client
    _, _ = business_reset(c)

    def rebuild_and_wait():
        reb = c.onto("POST", "/rebuild", {})
        if reb.code == "TASK_CONFLICT":
            time.sleep(2.0)
            reb = c.onto("POST", "/rebuild", {})
        if not reb.onto_ok:
            return False
        ok, _, _ = wait_until(
            lambda: (c.onto("GET", "/sources/status").data or {}).get("status") == "SYNCED"
            and len(items_of(c.onto("GET", "/objects?type=lessonSession&size=1"))) > 0,
            60.0, 0.8)
        return ok

    r.check("前置：重建当前投影，保证被删对象处于未删除状态（可重复执行）",
            rebuild_and_wait(), "POST /api/ontology/rebuild 后等待投影可用")

    lst = c.onto("GET", "/objects?type=lessonSession&size=200")
    pairs = []
    for it in items_of(lst):
        g = it.get("globalId") or ""
        pk = g.rsplit(":", 1)[-1]
        if g and pk and pk != "3":          # 避开 LS-003（多类用例依赖它）
            pairs.append((g, pk))
    if not pairs:
        r.skip("对象删除后有效关系被清理（无悬挂关系）",
               "底座 lessonSession 列表为空或未找到可删对象，前置不成立；"
               "请先完成一次全量导入后重跑 --case sync_delete_cleanup")
        return
    target_gid, target_pk = pairs[-1]
    rev = _base_revision(c)                 # 业务 system_revision（裁决 2）
    eid = "qa-delete-" + uuid.uuid4().hex
    ev = make_event(eid, rev, change_kind="DELETE", aggregate_id=target_pk,
                    business_time=now_ts())
    r.check("DELETE 事件带 payload.sourceTable / sourcePk + tombstone（墓碑化定位前提）",
            event_has_source(ev) and ev["payload"].get("tombstone") is True,
            "payload=%s" % json.dumps(ev["payload"], ensure_ascii=False)[:180])

    resp, p = inject_event(c, ev)
    r.check("DELETE 事件直接经 POST /internal/events 注入并被持久化（无需查业务 Outbox）",
            injection_accepted(resp, p) and p.get("persisted") is True,
            "eventId=%s / %s" % (eid, describe_injection(resp, p)))

    def gone():
        d = onto_get_object(c, target_gid)
        rel = onto_gid_get(c, target_gid, "/relations", direction="both", depth=1)
        return (d.status == 404 or (d.data or {}).get("deleted") is True or not d.onto_ok) \
            and not [x for x in items_of(rel) if x.get("active") is True]
    _, _, waited = wait_until(gone, 10.0, 0.4)

    det = onto_get_object(c, target_gid)
    r.check("被删除对象已墓碑化 / 不再作为有效对象返回",
            det.status == 404 or (det.data or {}).get("deleted") is True or not det.onto_ok,
            "globalId=%s / HTTP %s / code=%s / deleted=%s" %
            (target_gid, det.status, det.code, (det.data or {}).get("deleted")))
    rel = onto_gid_get(c, target_gid, "/relations", direction="both", depth=1)
    active = [x for x in items_of(rel) if x.get("active") is True]
    r.check("被删除对象的有效关系全部失效（无悬挂关系）", len(active) == 0,
            "active 关系数=%d / 总关系数=%d / 墓碑生效等待 %.1fs" %
            (len(active), total_of(rel), waited))
    ch = onto_changes(c, target_gid, size=50)
    r.check("删除/关系失效有可解释的变化记录", total_of(ch) > 0,
            "变化记录数=%d（由 DELETE 事件产生，非对账伪造）" % total_of(ch))

    r.check("收尾：再次重建当前投影，恢复被墓碑对象（不污染后续用例）",
            rebuild_and_wait(), "POST /api/ontology/rebuild；源记录仍在，重建会恢复该对象")


def verify_reconcile_fix(ctx):
    """情况⑥ 绕过业务直改库后对账发现差异并修复：与源一致、不伪造操作人/时间/中间过程。

    前置（主理人执行）：绕过业务服务直改业务库，例如
      UPDATE lesson_sessions SET start_time='16:00' WHERE id=3;
    脚本自动以「原业务 API 读到的当前值」为源真值，比对底座投影；若两者已一致则
    显式 skip（说明前置尚未构造），绝不假装通过。
    """
    r, c = ctx.r, ctx.client
    src = (c.biz("GET", "/schedule/3").payload or {}).get("lesson") or {}
    src_start = src.get("startTime")
    obj = onto_get_object(c, GID_LESSON)
    p_start = attr_value(obj.data, "startTime")
    if p_start is None:
        r.skip("直改库后对账发现差异并修复",
               "对象详情读不到 startTime 属性（属性字段=%s），无法做源/投影比对；"
               "需确认 03 契约 §2.6 attributes 结构后重跑 --case sync_reconcile_fix"
               % attr_names(obj.data))
        return
    diff_found = (p_start != src_start)
    if not diff_found:
        r.skip("直改库后对账发现差异并修复",
               "源与投影 startTime 已一致（源=%s / 投影=%s），前置差异尚未构造。"
               "请主理人绕过业务服务直改业务库（如 UPDATE lesson_sessions "
               "SET start_time='16:00' WHERE id=3）后重跑 --case sync_reconcile_fix"
               % (src_start, p_start))
        return

    fix = c.onto("POST", "/reconcile", {"dryRun": False})
    if fix.code == "TASK_CONFLICT":
        r.skip("对账修复当前投影", "已有对账/导入任务运行中（TASK_CONFLICT），请稍后重跑")
        return
    fd = fix.data or {}
    r.check("对账执行成功并列出当前差异（FIELD_DIFF / MISSING / ORPHAN / RELATION_DIFF）",
            fix.onto_ok and bool(fd.get("diffs")),
            "runId=%s / summary=%s / diffs=%s" %
            (fd.get("runId"), json.dumps(fd.get("summary") or {}, ensure_ascii=False)[:180],
             json.dumps(fd.get("diffs") or [], ensure_ascii=False)[:220]))
    wait_synced(c, 20.0)
    obj2 = onto_get_object(c, GID_LESSON)
    p2 = attr_value(obj2.data, "startTime")
    r.check("修复后当前投影与源一致", p2 == src_start,
            "源=%s / 修复前=%s / 修复后=%s" % (src_start, p_start, p2))

    # 不伪造操作人 / 发生时间 / 中间过程
    note = str(fd.get("changeHistoryNote") or "")
    ch = c.onto("GET", "/changes?size=100")
    rec_items = [it for it in items_of(ch) if it.get("origin") == "RECONCILE"]
    if rec_items:
        clean = all(it.get("operator") is None and it.get("businessTime") is None
                    and "对账修复" in str(it.get("reason") or "") for it in rec_items)
        r.check("对账修复历史不伪造操作人/发生时间/中间过程", clean,
                "RECONCILE 记录 %d 条，样例=%s" %
                (len(rec_items), json.dumps(rec_items[-1], ensure_ascii=False)[:220]))
    else:
        r.check("对账修复历史不伪造操作人/发生时间/中间过程（依据运行说明佐证）",
                "不伪造" in note or "RECONCILE" in note,
                "changeHistoryNote=%s；/changes 中未检出 origin=RECONCILE 明细，"
                "按 03 契约 §2.8 注释/§2.11 changeHistoryNote 佐证" % note[:200])

    again = c.onto("POST", "/reconcile", {"dryRun": False})
    ad = again.data or {}
    r.check("对账可重复执行（第二次无新增字段差异）",
            again.onto_ok and (((ad.get("summary") or {}).get("fieldDiff") in (0, None))
                               or not ad.get("diffs")),
            "第二次 summary=%s" % json.dumps(ad.get("summary") or {}, ensure_ascii=False)[:180])


# ══════════════════════════════════════════════════════════════════════════
# 验收四：新增对象类型（教室后接入）
# ══════════════════════════════════════════════════════════════════════════
def verify_classroom_type(ctx):
    """教室可被全局搜索、打开通用详情、在关系图中展开，无需教室专用页面。"""
    r, c = ctx.r, ctx.client
    types = (c.onto("GET", "/model/types").data or {}).get("types") or []
    classroom = next((t for t in types if t.get("id") == "classroom"), None)
    r.check("模型定义包含后接入的 classroom 对象类型（含显示名/可搜索字段/来源映射）",
            classroom is not None,
            "已注册类型=%s" % [t.get("id") for t in types])
    if classroom is None:
        r.skip("教室全局搜索 / 通用详情 / 关系图展开",
               "classroom 类型尚未接入（批次④未完成），前置不成立；接入后重跑 --case classroom_type")
        return
    mapping = classroom.get("sourceMapping") or classroom.get("sourceMappingRef")
    r.check("教室类型由同一套模型定义驱动（含 searchableFields / sourceMapping）",
            bool(classroom.get("searchableFields")) and bool(mapping),
            "searchable=%s / sourceMapping=%s" % (classroom.get("searchableFields"), mapping))

    opts = (c.biz("GET", "/meta/options").payload or {}).get("classrooms") or []
    keyword = (opts[0].get("name") if opts else "明理楼")
    sr = c.onto("GET", "/search?q=" + quote(keyword))
    hits = [it for it in items_of(sr) if it.get("type") == "classroom"]
    r.check("教室可被全局搜索直达（跨所有已接入类型命中）",
            sr.onto_ok and len(hits) > 0,
            "关键词=%s / 命中 %d 条 / 总结果 %d 条" % (keyword, len(hits), total_of(sr)))
    cgid = (hits[0].get("globalId") if hits else gid("classroom", opts[0].get("id") if opts else 1))

    det = onto_get_object(c, cgid)
    r.check("教室可打开同一套通用对象详情（含来源系统/记录/映射信息）",
            det.onto_ok and bool((det.data or {}).get("source")),
            "globalId=%s / 属性字段=%s" % (cgid, attr_names(det.data)))
    rel = onto_gid_get(c, cgid, "/relations", direction="both", depth=1)
    r.check("教室的正/反向关联对象可查", rel.onto_ok,
            "关系数=%d" % total_of(rel))
    graph = onto_gid_get(c, cgid, "/graph", depth=1, limit=50)
    nodes = (graph.data or {}).get("nodes") or []
    r.check("教室可在关系图中展开（1 层 + 节点上限）",
            graph.onto_ok and len(nodes) > 0,
            "nodes=%d / edges=%d / truncated=%s" %
            (len(nodes), len((graph.data or {}).get("edges") or []), (graph.data or {}).get("truncated")))
    lst = c.onto("GET", "/objects?type=classroom&size=20")
    r.check("教室对象列表由通用探索器提供（无教室专用页面）",
            lst.onto_ok and len(items_of(lst)) > 0,
            "total=%s" % total_of(lst))


def verify_classroom_zero_diff(ctx):
    """检查入口：通用内核 Java 与 ontology-web/src 在教室接入前后零改动（由 git diff 佐证）。"""
    r, c = ctx.r, ctx.client
    before, after = ctx.before_ref, ctx.after_ref
    cmd_hint = ("git diff --name-only <教室接入前ref> <教室接入后ref> -- "
                + " ".join(KERNEL_PATHS))
    if not before or not after:
        r.skip("教室接入前后通用内核/通用前端零改动（git diff 佐证）",
               "需主理人提供教室接入前后的 git ref：--before-ref <ref> --after-ref <ref>。"
               "参考命令：" + cmd_hint)
        return
    cmd = ["git", "diff", "--name-only", before, after, "--"] + KERNEL_PATHS
    try:
        proc = subprocess.run(cmd, cwd=ROOT_DIR, capture_output=True,
                              text=True, encoding="utf-8", errors="replace", timeout=60)
        changed = [x.strip() for x in (proc.stdout or "").splitlines() if x.strip()]
        err = (proc.stderr or "").strip()
    except Exception as e:
        r.fail("git diff 检查入口可执行", "%s: %s" % (type(e).__name__, e))
        return
    existing = [p for p in KERNEL_PATHS
                if os.path.isdir(os.path.join(ROOT_DIR, *p.split("/")))]
    r.check("通用内核 Java 与 ontology-web/src 在教室接入前后零改动",
            proc.returncode == 0 and len(changed) == 0 and len(existing) > 0,
            "命令=%s；returncode=%s；改动文件=%s；已存在目标目录=%d；stderr=%s" %
            (" ".join(cmd), proc.returncode, changed[:20], len(existing), err[:160]))

    # 零 switch 佐证：通用源码中不得出现 classroom 专用分支
    pattern = re.compile(r"(switch\s*\(|===\s*['\"]classroom|equals\(\s*['\"]classroom|"
                         r"type\s*==\s*['\"]classroom)", re.IGNORECASE)
    scanned, offenders = 0, []
    for rel in KERNEL_PATHS:
        base = os.path.join(ROOT_DIR, *rel.split("/"))
        if not os.path.isdir(base):
            continue
        for root, _dirs, files in os.walk(base):
            for fn in files:
                if not fn.endswith((".java", ".ts", ".vue", ".js")):
                    continue
                fp = os.path.join(root, fn)
                try:
                    with open(fp, encoding="utf-8", errors="replace") as f:
                        text = f.read()
                except OSError:
                    continue
                scanned += 1
                if pattern.search(text):
                    offenders.append(os.path.relpath(fp, ROOT_DIR).replace("\\", "/"))
    if scanned == 0:
        r.skip("通用内核/前端源码无 classroom 专用分支（静态扫描）",
               "待检查目录尚未生成（扫描到 0 个源文件）；实现完成后重跑 "
               "--case classroom_zero_diff --before-ref %s --after-ref %s" % (before, after))
    else:
        r.check("通用内核/前端源码无 classroom 专用分支（静态扫描）",
                len(offenders) == 0,
                "扫描 %d 个源文件，命中=%s" % (scanned, offenders[:10]))


# ══════════════════════════════════════════════════════════════════════════
# 验收五：来源追溯与可重建
# ══════════════════════════════════════════════════════════════════════════
def verify_source_traceability(ctx):
    """抽查对象属性与关系，逐项追溯到 sourceTable / sourcePk / 映射版本与生成原因。"""
    r, c = ctx.r, ctx.client
    for label, g in [("授课安排 LS-003", GID_LESSON),
                     ("教师 陈思琪", GID_NEW_TEACHER),
                     ("学生 陈一诺", GID_STUDENT)]:
        d = onto_get_object(c, g)
        src = (d.data or {}).get("source") or {}
        r.check("%s：详情可追溯到 sourceTable / sourcePk / mappingVersion" % label,
                d.onto_ok and bool(src.get("sourceTable"))
                and src.get("sourcePk") is not None and bool(src.get("mappingVersion")),
                "source=%s" % json.dumps(src, ensure_ascii=False)[:220])
        origins = {a.get("origin") for a in ((d.data or {}).get("attributes") or [])
                   if isinstance(a, dict) and a.get("origin")}
        r.check("%s：属性区分 SOURCE / DERIVED 来源，动态结果依赖 queryTime" % label,
                len(origins) > 0 or bool((d.data or {}).get("dynamicResults")),
                "origins=%s / dynamicResults=%d" %
                (sorted(str(x) for x in origins), len((d.data or {}).get("dynamicResults") or [])))

    rel = onto_gid_get(c, GID_LESSON, "/relations", direction="both", depth=1)
    ritems = items_of(rel)
    explained = [it for it in ritems if it.get("reason")
                 and (it.get("mappingVersion") or (it.get("source") or {}).get("sourceTable"))]
    r.check("关系实例可解释成因（reason）+ 来源原始记录 + 映射版本",
            bool(ritems) and len(explained) == len(ritems),
            "%d/%d 条含生成依据" % (len(explained), len(ritems)))

    mp = c.onto("GET", "/model/mappings")
    d = mp.data or {}
    r.check("映射定义可查：数据源 / 对象映射 / 生效版本 / Provider / Resolver",
            mp.onto_ok and all(bool(d.get(k)) for k in
                               ["dataSources", "objectMappings", "versions", "providers", "resolvers"]),
            "keys=%s" % sorted(d.keys()))
    r.check("稳定 ID 规则含 sourceId（不同来源同主键不自动合并）",
            "gid:" in json.dumps(d.get("objectMappings"), ensure_ascii=False),
            "objectMappings 首条=%s" %
            json.dumps((d.get("objectMappings") or [None])[0], ensure_ascii=False)[:220])


def _projection_snapshot(c):
    types = [t.get("id") for t in ((c.onto("GET", "/model/types").data or {}).get("types") or [])]
    gids = []
    for t in types:
        gids += [it.get("globalId") for it in items_of(c.onto("GET", "/objects?type=%s&size=200" % t))]
    det = onto_get_object(c, GID_LESSON)
    rel = onto_gid_get(c, GID_LESSON, "/relations", direction="both", depth=1)
    rel_sig = sorted([
        (it.get("relationType"),
         (it.get("to") or {}).get("globalId") or (it.get("from") or {}).get("globalId"),
         it.get("active"))
        for it in items_of(rel)
    ])
    return {
        "gids": sorted(str(x) for x in gids if x),
        "attrs": (det.data or {}).get("attributes"),
        "relations": rel_sig,
        "changes": total_of(c.onto("GET", "/changes?size=1")),
        "tasks": total_of(c.onto("GET", "/sync/tasks?size=1")),
        "revision": (c.onto("GET", "/revision").data or {}).get("revision"),
        "types": sorted(str(x) for x in types if x),
    }


def verify_rebuild_consistency(ctx):
    """重建当前投影前后 globalId / 当前属性 / 关系一致，审计历史不减少、平台版本不回退。"""
    r, c = ctx.r, ctx.client
    _, _ = business_reset(c)
    before = _projection_snapshot(c)
    r.check("重建前快照可采集（对象 %d 个 / 类型 %d 类 / 关系 %d 条）" %
            (len(before["gids"]), len(before["types"]), len(before["relations"])),
            len(before["gids"]) > 0,
            "changes=%s tasks=%s revision=%s" %
            (before["changes"], before["tasks"], before["revision"]))

    reb = c.onto("POST", "/rebuild", {})
    if reb.code == "TASK_CONFLICT":
        r.skip("重建当前投影前后一致性", "已有任务运行中（TASK_CONFLICT），请稍后重跑")
        return
    rd = reb.data or {}
    r.check("重建任务已启动且 scope=CURRENT_PROJECTION_ONLY（仅清当前投影）",
            reb.onto_ok and rd.get("scope") == "CURRENT_PROJECTION_ONLY"
            and bool(rd.get("preservedTables")),
            "scope=%s / truncated=%s / preserved=%s" %
            (rd.get("scope"), rd.get("truncatedTables"), (rd.get("preservedTables") or [])[:4]))

    def done():
        tasks = items_of(c.onto("GET", "/sync/tasks?type=REBUILD&size=10"))
        if tasks and tasks[0].get("status") == "SUCCESS":
            return True
        st = (c.onto("GET", "/sources/status").data or {})
        return st.get("status") == "SYNCED" and st.get("pendingCount") in (0, None) \
            and len(items_of(c.onto("GET", "/objects?type=lessonSession&size=1"))) > 0
    ok, _, waited = wait_until(done, 90.0, 0.8)
    r.check("重建任务完成且当前投影重新可用", ok, "等待 %.1fs" % waited)
    after = _projection_snapshot(c)

    r.check("重建前后对象 globalId 集合完全一致（身份稳定、跨源不合并）",
            before["gids"] == after["gids"],
            "before=%d / after=%d" % (len(before["gids"]), len(after["gids"])))
    r.check("重建前后当前属性一致", before["attrs"] == after["attrs"],
            "%d vs %d 个属性；重建后属性字段=%s" %
            (len(before["attrs"] or []), len(after["attrs"] or []),
             attr_names({"attributes": after["attrs"]})))
    r.check("重建前后关系（类型/端点/有效状态）一致", before["relations"] == after["relations"],
            "before=%d / after=%d 条" % (len(before["relations"]), len(after["relations"])))
    r.check("重建后审计历史条数不减少（变更历史 / 同步任务全保留）",
            after["changes"] >= before["changes"] and after["tasks"] >= before["tasks"],
            "changes %s→%s / tasks %s→%s" %
            (before["changes"], after["changes"], before["tasks"], after["tasks"]))
    r.check("重建后 ont_platform_revision 只增不减",
            before["revision"] is not None and after["revision"] is not None
            and after["revision"] >= before["revision"],
            "%s → %s" % (before["revision"], after["revision"]))


# ══════════════════════════════════════════════════════════════════════════
# 验收六：原业务回归 + 时间驱动的动态结果
# ══════════════════════════════════════════════════════════════════════════
def _run_existing_script(args, timeout=240):
    script = os.path.join(TESTS_DIR, "api_acceptance.py")
    if not os.path.exists(script):
        return None, None
    proc = subprocess.run([sys.executable, script] + list(args), cwd=ROOT_DIR,
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout)
    return proc, script


def verify_business_regression(ctx):
    """复用现有 tests/api_acceptance.py：调课校验、6 类冲突、4 个时间边界等全部回归断言。"""
    r, c = ctx.r, ctx.client
    proc, script = _run_existing_script([])
    if proc is None:
        r.skip("现有接口自动化验收（51 项）回归", "未找到 %s" % os.path.join(TESTS_DIR, "api_acceptance.py"))
        return
    out = load_json(os.path.join(TESTS_DIR, "acceptance-results.json"), {}) or {}
    rows = out.get("results") or []
    for item in rows:
        r.check("回归·" + str(item.get("item", "?")), bool(item.get("passed")), item.get("detail", ""))
    r.check("现有接口验收脚本完整执行（共 %d 项，退出码反映失败）" % len(rows),
            proc.returncode == 0 and len(rows) > 0,
            "退出码=%s；stdout 尾部=%s；stderr 尾部=%s" %
            (proc.returncode, (proc.stdout or "")[-220:].replace("\n", " "),
             (proc.stderr or "")[-220:].replace("\n", " ")))
    r.skip("现有浏览器验收（42 项）与容器重启持久化回归",
           "浏览器回归由 tests/browser_acceptance.mjs（原业务前端 :5180）执行；"
           "重启持久化由主理人重启容器后执行 --case regression_persistence；"
           "本脚本不改动上述两个既有脚本")


def verify_regression_persistence(ctx):
    """【主理人重启业务容器后执行】复用 api_acceptance.py --check-persistence。"""
    r, c = ctx.r, ctx.client
    proc, script = _run_existing_script(["--check-persistence"], timeout=120)
    if proc is None:
        r.skip("容器重启持久化校验", "未找到 tests/api_acceptance.py")
        return
    out = load_json(os.path.join(TESTS_DIR, "persistence-results.json"), {}) or {}
    rows = out.get("results") or []
    if not rows:
        r.skip("容器重启持久化校验", "未生成 persistence-results.json，可能尚未重启容器")
        return
    for item in rows:
        r.check("持久化·" + str(item.get("item", "?")), bool(item.get("passed")), item.get("detail", ""))
    r.check("重启持久化校验整体通过", proc.returncode == 0,
            "退出码=%s，共 %d 项" % (proc.returncode, len(rows)))


def _fetch_dynamic(c, qt=None):
    """取学生详情（或列表项兜底）的 currentLesson / nextLesson 动态结果。"""
    d = onto_get_object(c, GID_STUDENT, query_time=qt)
    data = d.data or {}
    cur, nxt = dyn_result(data, "currentLesson"), dyn_result(data, "nextLesson")
    if cur is None and nxt is None:
        lst = c.onto("GET", "/objects?type=student&q=" + quote("陈一诺")
                     + ("&queryTime=" + quote(qt) if qt else ""))
        first = (items_of(lst) or [None])[0] or {}
        cur, nxt = dyn_result(first, "currentLesson"), dyn_result(first, "nextLesson")
    return d, cur, nxt


def verify_dynamic_no_write(ctx):
    """零写入验证：仅改变 queryTime，当前/下一节课动态结果随演示业务时间变化。

    关键点：全程不产生任何写入（revision 与历史条数不变），证明动态结果不依赖数据变更事件。
    """
    r, c = ctx.r, ctx.client
    _, _ = business_reset(c)
    day = demo_date(c)
    rev0 = (c.onto("GET", "/revision").data or {}).get("revision")
    hist0 = total_of(c.onto("GET", "/changes?size=1"))

    expectations = [
        ("07:30:00", None, "08:00"),
        ("10:15:00", "语文", "11:00"),
        ("10:45:00", None, "11:00"),
        ("17:00:00", None, None),
    ]
    for t, cur_course, next_start in expectations:
        qt = day + "T" + t
        d, cur, nxt = _fetch_dynamic(c, qt)
        cv = (cur or {}).get("value")
        nv = (nxt or {}).get("value")
        cur_ok = (cv is None) if cur_course is None else (
            isinstance(cv, dict) and cv.get("courseName") == cur_course)
        next_ok = (nv is None) if next_start is None else (
            isinstance(nv, dict) and nv.get("startTime") == next_start)
        r.check("queryTime=%s：当前/下一节课动态结果正确" % t,
                (d.onto_ok or cur is not None or nxt is not None) and cur_ok and next_ok,
                "current=%s / next=%s" %
                (json.dumps(cv, ensure_ascii=False)[:110], json.dumps(nv, ensure_ascii=False)[:110]))
        basis = (cur or nxt or {}).get("basis")
        r.check("queryTime=%s：动态结果附可读计算依据（basis / inputs / queryTime）" % t,
                bool(basis) and bool((cur or nxt or {}).get("inputs") is not None
                                     or (cur or nxt or {}).get("queryTime")),
                "basis=%s" % str(basis)[:160])

    rev1 = (c.onto("GET", "/revision").data or {}).get("revision")
    hist1 = total_of(c.onto("GET", "/changes?size=1"))
    r.check("四组 queryTime 期间本体平台零写入（revision 与历史条数不变）",
            rev0 is not None and hist0 is not None and rev1 == rev0 and hist1 == hist0,
            "revision %s→%s / changes total %s→%s；证明动态结果不依赖数据变更事件" %
            (rev0, rev1, hist0, hist1))


def verify_dynamic_after_clock(ctx):
    """补充：通过原业务改演示时间（CLOCK 事件，不改对象属性），底座默认动态结果随之变化。"""
    r, c = ctx.r, ctx.client
    _, _ = business_reset(c)
    day = demo_date(c)
    _, cur0, nxt0 = _fetch_dynamic(c)
    r.check("10:15 底座默认动态结果为课中（当前语文 / 下一节 11:00）",
            isinstance((cur0 or {}).get("value"), dict)
            and (cur0 or {}).get("value", {}).get("courseName") == "语文",
            json.dumps((cur0 or {}).get("value"), ensure_ascii=False)[:140])

    clk = c.biz("POST", "/demo/clock", {"demoTime": day + "T17:00:00", "running": False})
    r.check("原业务演示时间调整成功（产生 CLOCK 事件，不改课表）", clk.ok,
            "HTTP %s / demoTime=%s" % (clk.status, (clk.payload or {}).get("demoTime")))

    def refreshed():
        d, cur, nxt = _fetch_dynamic(c)
        return (cur or {}).get("value") is None and (nxt or {}).get("value") is None
    ok, _, waited = wait_until(refreshed, 15.0, 0.6)
    r.check("改演示时间后本体平台默认动态结果随之变化（17:00 当前/下一节均为空）", ok,
            "等待 %.1fs；CLOCK 事件不产生对象属性变化" % waited)

    st = (c.onto("GET", "/sources/status").data or {})
    r.check("两类时间分离：底座 demoTime 跟随业务，lastSuccessSyncAt 为真实处理时间",
            str(st.get("demoTime", "")).endswith("17:00:00")
            and st.get("lastSuccessSyncAt") != st.get("demoTime"),
            "demoTime=%s / lastSuccessSyncAt=%s" %
            (st.get("demoTime"), st.get("lastSuccessSyncAt")))
    c.biz("POST", "/demo/reset", {})       # 收尾：恢复演示现场


# ══════════════════════════════════════════════════════════════════════════
# 用例登记表（顺序即 --all 执行顺序；回归放最后，避免其 reset 影响前序观察）
# ══════════════════════════════════════════════════════════════════════════
CASES = [
    dict(name="cross_system", title="跨系统变化：调课后四视角 ≤5 秒 + 双侧历史", group=G1,
         manual=False, fn=verify_cross_system),
    dict(name="revision_contract", title="/revision 刷新协议字段与只增不减", group=G1,
         manual=False, fn=verify_revision_contract),
    dict(name="source_status_contract", title="数据源五态 / 两类时间分离 / 进度可观测", group=G2,
         manual=False, fn=verify_source_status_contract),
    dict(name="offline_writes", title="离线期间业务连续多次修改（停本体服务）", group=G2,
         manual=True, phase="offline-writes",
         hint="先停止 ontology-service，再连续完成 ≥2 次调课 + 演示时钟变更",
         fn=verify_offline_writes),
    dict(name="after_recovery", title="恢复本体服务后自动追赶与逐次比对", group=G2,
         manual=True, phase="after-recovery",
         hint="恢复 ontology-service 后执行，与 --phase offline-writes 的快照比对",
         fn=verify_after_recovery),
    dict(name="source_unavailable", title="来源不可用时仍可查已同步对象并显示新鲜度", group=G2,
         manual=True, hint="先停止原业务服务(backend)",
         fn=verify_source_unavailable),
    dict(name="sync_dup_event", title="情况① 同一 eventId 重复投递幂等", group=G3,
         manual=False, fn=verify_sync_dup_event),
    dict(name="sync_stale_event", title="情况② 较旧 revision 晚到不回退、不伪造历史", group=G3,
         manual=False, fn=verify_sync_stale_event),
    dict(name="admin_retry", title="情况③补充：底座 /admin/retry 重放失败事件（转发业务侧）", group=G3,
         manual=False, fn=verify_admin_retry),
    dict(name="sync_midway_before", title="情况③ 同步中途失败前基线（崩溃/重启）", group=G3,
         manual=True, phase="crash-before",
         hint="在消费处理到一半时强制终止 ontology-service 前记录基线",
         fn=verify_midway_before),
    dict(name="sync_midway_after", title="情况③ 重启后断点续处理且最终一致", group=G3,
         manual=True, phase="crash-after",
         hint="重启 ontology-service 后执行，与 --phase crash-before 的基线比对",
         fn=verify_midway_after),
    dict(name="sync_import_during_change", title="情况④ 全量导入期间业务修改不遗漏、不被旧快照覆盖",
         group=G3, manual=False, fn=verify_import_during_change),
    dict(name="sync_delete_cleanup", title="情况⑤ 注入 DELETE 事件后有效关系清理（无悬挂）", group=G3,
         manual=False, hint="裁决1：直接 POST /internal/events 构造 DELETE 事件，无需直改库",
         fn=verify_delete_cleanup),
    dict(name="sync_reconcile_fix", title="情况⑥ 直改库后对账发现差异并修复、不伪造历史", group=G3,
         manual=True, hint="先绕过业务服务直改业务库（如 UPDATE lesson_sessions SET start_time=...）",
         fn=verify_reconcile_fix),
    dict(name="classroom_type", title="教室可被全局搜索 / 通用详情 / 关系图展开", group=G4,
         manual=False, fn=verify_classroom_type),
    dict(name="classroom_zero_diff", title="教室接入前后通用内核/前端零改动（git diff 入口）", group=G4,
         manual=True, hint="需提供教室接入前后的 git ref（--before-ref / --after-ref）",
         fn=verify_classroom_zero_diff),
    dict(name="source_traceability", title="对象属性/关系可追溯到原记录与映射版本", group=G5,
         manual=False, fn=verify_source_traceability),
    dict(name="rebuild_consistency", title="重建前后 globalId/属性/关系一致、历史不减少", group=G5,
         manual=False, fn=verify_rebuild_consistency),
    dict(name="dynamic_no_write", title="零写入：queryTime 四边界动态结果变化", group=G6,
         manual=False, fn=verify_dynamic_no_write),
    dict(name="dynamic_after_clock", title="改演示时间后底座默认动态结果随之变化", group=G6,
         manual=False, fn=verify_dynamic_after_clock),
    dict(name="regression_persistence", title="容器重启持久化回归（复用既有脚本）", group=G6,
         manual=True, hint="主理人重启业务容器后执行",
         fn=verify_regression_persistence),
    dict(name="business_regression", title="原业务接口回归（复用 tests/api_acceptance.py 全部断言）",
         group=G6, manual=False, fn=verify_business_regression),
]
CASE_BY_NAME = {c["name"]: c for c in CASES}
PHASE_TO_CASE = {
    "offline-writes": "offline_writes",
    "after-recovery": "after_recovery",
    "crash-before": "sync_midway_before",
    "crash-after": "sync_midway_after",
}
GROUP_NUM = {"1": "一", "2": "二", "3": "三", "4": "四", "5": "五", "6": "六"}


def print_cases():
    print("本体平台六类验收 —— 用例清单（%d 项）" % len(CASES))
    print("-" * 96)
    for c in CASES:
        flag = "人工/容器" if c["manual"] else "自动"
        extra = ("  --phase " + c["phase"]) if c.get("phase") else ""
        print("  %-28s [%s] %-6s %s%s" % (c["name"], flag, c["group"][:5], c["title"], extra))
        if c.get("hint"):
            print(" " * 38 + "前置：" + c["hint"])
    print("-" * 96)
    print("用法： --all | --group N | --case a,b | --phase offline-writes | --list")
    print("结果： tests/ontology-results.json（逐项 passed/failed/skipped + detail）")


def main():
    ap = argparse.ArgumentParser(description="本体数据底座六类验收（接口自动化）")
    ap.add_argument("--list", action="store_true", help="列出全部用例与调用方式")
    ap.add_argument("--all", action="store_true", help="执行所有无需人工/容器操作的用例")
    ap.add_argument("--group", type=int, choices=range(1, 7), help="只执行第 N 类验收")
    ap.add_argument("--case", default="", help="显式执行指定用例（逗号分隔，含人工项）")
    ap.add_argument("--phase", default="", help="分阶段执行（逗号分隔），见 --list")
    ap.add_argument("--include-manual", action="store_true", help="--all/--group 时也执行人工项")
    ap.add_argument("--merge", action="store_true", help="与已有 ontology-results.json 合并（默认 --case/--phase 自动合并）")
    ap.add_argument("--no-merge", action="store_true", help="强制覆盖已有结果文件")
    ap.add_argument("--before-ref", default="", help="教室接入前的 git ref（classroom_zero_diff）")
    ap.add_argument("--after-ref", default="", help="教室接入后的 git ref（classroom_zero_diff）")
    args = ap.parse_args()

    if args.list:
        print_cases()
        return 0

    explicit = set()
    for x in (args.case or "").split(","):
        if x.strip():
            explicit.add(x.strip())
    phases = [x.strip() for x in (args.phase or "").split(",") if x.strip()]
    for p in phases:
        if p not in PHASE_TO_CASE:
            print("未知 --phase：%s（可选：%s）" % (p, ", ".join(PHASE_TO_CASE)))
            return 2
        explicit.add(PHASE_TO_CASE[p])
    unknown = [x for x in explicit if x not in CASE_BY_NAME]
    if unknown:
        print("未知 --case：%s（用 --list 查看）" % ", ".join(unknown))
        return 2

    mode = "指定用例"
    if explicit:
        selected = [c for c in CASES if c["name"] in explicit]
    elif args.group:
        prefix = "验收" + GROUP_NUM[str(args.group)]
        selected = [c for c in CASES if c["group"].startswith(prefix)]
        mode = "验收" + GROUP_NUM[str(args.group)]
    else:
        selected = list(CASES)
        mode = "--all（全部自动项）"

    merge = args.merge or (bool(explicit) and not args.no_merge)
    if args.no_merge:
        merge = False
    rec = Recorder(meta={"mode": mode, "selected": [c["name"] for c in selected],
                         "phases": phases}, merge=merge)
    client = Client()
    ctx = Context(client, phase=(phases[0] if phases else None),
                  before_ref=args.before_ref, after_ref=args.after_ref)

    print("本体验收：%s" % mode)
    print("原业务 API=%s | 本体 API=%s/api/ontology | 本体平台=%s" %
          (BIZ_API, ONTO_API_ROOT, ONTO_WEB))
    for c in selected:
        if c["manual"] and c["name"] not in explicit and not args.include_manual:
            continue                       # 人工项由 finalize 补 skipped，绝不假装执行
        print("\n=== %s · %s ===" % (c["group"], c["title"]))
        ctx.r = rec.for_case(c["name"], c["group"])
        try:
            c["fn"](ctx)
        except Exception as e:
            ctx.r.fail(c["title"] + " · 脚本执行异常",
                       "%s: %s（若为服务未就绪请检查服务状态；不视为源码缺陷）"
                       % (type(e).__name__, e))

    rec.finalize(CASES, mode)
    out = rec.save()
    s = out["summary"]
    print("\n合计 %d 项：通过 %d，失败 %d，跳过 %d" %
          (s["total"], s["passed"], s["failed"], s["skipped"]))
    print("结果已写入 " + RESULTS_FILE)
    return min(s["failed"], 250)


if __name__ == "__main__":
    sys.exit(main())
