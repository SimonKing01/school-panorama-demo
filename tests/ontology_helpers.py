# -*- coding: utf-8 -*-
"""本体数据底座 / 本体平台验收：公共辅助模块（仅 Python 标准库）。

职责
----
1. 集中管理两套服务地址、演示数据常量、globalId 规则；
2. 轻量 HTTP 客户端（urllib），同时兼容「原业务平铺风格」与「本体 {code,data,message} 包装」；
3. 三态结果记录器（passed / failed / skipped），落盘 tests/ontology-results.json；
4. 轮询、globalId 兼容、属性取值等小工具。

边界（重要）
-----------
* 本模块**不启动/停止任何服务**、**不执行 docker / psql 等容器或直改库命令**、**不写业务库**；
  停启服务、绕过业务直改库等动作由主理人在外部执行，脚本只提供断言入口与可复现步骤。
* 本模块不引入任何第三方依赖，与现有 tests/api_acceptance.py 风格一致。
* 「未执行」的验收项必须由调用方显式标记 skipped 并写明原因，禁止伪装为通过。
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(TESTS_DIR)

# ── 地址（可由环境变量覆盖，默认值与主理人集成约定一致）────────────────────
BIZ_API = os.environ.get("ONTOLOGY_BIZ_API", "http://127.0.0.1:18080/api").rstrip("/")
BIZ_WEB = os.environ.get("ONTOLOGY_BIZ_WEB", "http://127.0.0.1:5180").rstrip("/")
ONTO_API_ROOT = os.environ.get("ONTOLOGY_API", "http://127.0.0.1:18090").rstrip("/")
ONTO_PREFIX = "/api/ontology"
ONTO_WEB = os.environ.get("ONTOLOGY_WEB", "http://127.0.0.1:5181").rstrip("/")
INTERNAL_EVENTS_PATH = "/internal/events"   # 03 契约 §3，投递器内部通道，非 /api/ontology 前缀

RESULTS_FILE = os.path.join(TESTS_DIR, "ontology-results.json")
OFFLINE_STATE_FILE = os.path.join(TESTS_DIR, "ontology-offline-state.json")
CRASH_STATE_FILE = os.path.join(TESTS_DIR, "ontology-crash-state.json")

# ── 演示数据常量（依据 docs/API-CONTRACT.md §演示数据速查 / 03 契约 §2-3）──
SOURCE_ID = "school-panorama"
GID_LESSON = "gid:%s:lessonSession:3" % SOURCE_ID       # LS-003
GID_OLD_TEACHER = "gid:%s:teacher:1" % SOURCE_ID        # 张明远 TCH-001
GID_NEW_TEACHER = "gid:%s:teacher:5" % SOURCE_ID        # 陈思琪 TCH-005
GID_STUDENT = "gid:%s:student:1" % SOURCE_ID            # 陈一诺 STU-0001
QA_REASON = "本体验收专用原因：教师参加教研，改由陈思琪代课并调至第七节"
ADJUST_TARGET = {
    "teacherId": 5, "teacherName": "陈思琪",
    "classroomId": 6, "classroomName": "多功能报告厅",
    "startTime": "15:30", "endTime": "16:15",
}
MAPPED_STATUS = ("SYNCED", "NO_DATA", "NOT_CONNECTED", "LAGGING", "FAILED")


def gid(object_type, source_pk, source_id=SOURCE_ID):
    """按 02 架构 §2.2 稳定全局 ID 规则构造（sourcePk 用来源主键字符串）。"""
    return "gid:%s:%s:%s" % (source_id, object_type, source_pk)


def now_ts():
    return datetime.now().strftime("%Y-%m-%dT%H:%M:%S")


def stamp():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def quote(s):
    return urllib.parse.quote(str(s), safe="")


def save_json(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)


def load_json(path, default=None):
    if not os.path.exists(path):
        return default
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (ValueError, OSError):
        return default


# ── HTTP 客户端 ────────────────────────────────────────────────────────────
class Response(object):
    """一次 HTTP 调用的结果。payload 为已解析 JSON（失败时为 {"error":...}）。"""

    __slots__ = ("status", "payload", "error", "elapsed_ms", "url")

    def __init__(self, status, payload, error=None, elapsed_ms=0, url=""):
        self.status = status
        self.payload = payload
        self.error = error
        self.elapsed_ms = elapsed_ms
        self.url = url

    @property
    def ok(self):
        return self.error is None and 200 <= self.status < 300

    @property
    def code(self):
        return self.payload.get("code") if isinstance(self.payload, dict) else None

    @property
    def data(self):
        if not isinstance(self.payload, dict):
            return None
        return self.payload.get("data")

    @property
    def onto_ok(self):
        """本体 API 成功判定：HTTP 2xx 且 code == 'OK'。"""
        return self.ok and self.code == "OK"

    def text(self):
        return json.dumps(self.payload, ensure_ascii=False) if self.payload is not None else ""


class Client(object):
    def __init__(self, timeout=20):
        self.timeout = timeout

    def request(self, method, url, body=None, timeout=None):
        if not url.isascii():
            url = urllib.parse.quote(url, safe=":/?&=%+,;@!$'()*~")
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json;charset=UTF-8"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                raw = resp.read().decode("utf-8")
                status = resp.status
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8")
            status = e.code
        except Exception as e:  # 网络未就绪 / 连接拒绝 / 超时
            return Response(-1, {"error": str(e)}, str(e), int((time.time() - t0) * 1000), url)
        try:
            payload = json.loads(raw)
        except (ValueError, TypeError):
            payload = {"raw": raw}
        return Response(status, payload, None, int((time.time() - t0) * 1000), url)

    def onto(self, method, path, body=None, timeout=None):
        p = path if path.startswith("/") else "/" + path
        return self.request(method, ONTO_API_ROOT + ONTO_PREFIX + p, body, timeout)

    def biz(self, method, path, body=None, timeout=None):
        p = path if path.startswith("/") else "/" + path
        return self.request(method, BIZ_API + p, body, timeout)

    def internal_event(self, event, timeout=None):
        """POST /internal/events（03 契约 §3，唯一「业务→底座」异步写入通道）。"""
        return self.request("POST", ONTO_API_ROOT + INTERNAL_EVENTS_PATH, event, timeout)


# ── 响应解析小工具 ─────────────────────────────────────────────────────────
def items_of(resp):
    d = resp.data if resp is not None else None
    if isinstance(d, list):
        return d
    if isinstance(d, dict):
        return d.get("items") or []
    return []


def total_of(resp):
    d = resp.data if resp is not None else None
    if isinstance(d, dict):
        if "total" in d:
            return d.get("total") or 0
        return len(d.get("items") or [])
    if isinstance(d, list):
        return len(d)
    return 0


def attr_value(detail_data, name):
    """统一取属性值（主理人裁决 3：两种结构并存，均需支持）。

    * 列表 GET /objects?type= 的 items[].attributes = **对象字典**；
    * 详情 GET /objects/{gid} 的 attributes = **数组**
      [{name, displayName, value, origin, provider}]。
    """
    attrs = (detail_data or {}).get("attributes") if isinstance(detail_data, dict) else None
    if isinstance(attrs, dict):
        return attrs.get(name)
    if isinstance(attrs, list):
        for a in attrs:
            if isinstance(a, dict) and a.get("name") == name:
                return a.get("value")
    return None


def attr_names(detail_data):
    attrs = (detail_data or {}).get("attributes") if isinstance(detail_data, dict) else None
    if isinstance(attrs, dict):
        return sorted(attrs.keys())
    if isinstance(attrs, list):
        return sorted([a.get("name") for a in attrs if isinstance(a, dict) and a.get("name")])
    return []


def dyn_result(detail_data, key):
    """取对象详情 / 列表项里的动态结果项（currentLesson / nextLesson）。"""
    dyn = (detail_data or {}).get("dynamicResults") if isinstance(detail_data, dict) else None
    if not isinstance(dyn, list):
        return None
    return next((x for x in dyn if isinstance(x, dict) and x.get("key") == key), None)


def gid_path(gid_value):
    """globalId 统一 URL 编码（':' → '%3A'）。

    工程师确认：裸 ':' 与 '%3A' 均可命中 @PathVariable；但按主理人要求脚本
    **统一编码**、不混用两种写法，避免偶发 404（前端也统一 encodeURIComponent）。
    """
    return quote(gid_value)


def onto_get_object(client, gid_value, query_time=None):
    """GET /objects/{globalId}（globalId 统一 URL 编码）。"""
    q = ("?queryTime=" + quote(query_time)) if query_time else ""
    return client.onto("GET", "/objects/%s%s" % (gid_path(gid_value), q))


def onto_gid_get(client, gid_value, suffix="", **params):
    """GET /objects/{globalId}{suffix}?...，用于 /relations、/graph 等子资源。"""
    pairs = [(k, v) for k, v in params.items() if v is not None]
    q = ("?" + urllib.parse.urlencode(pairs)) if pairs else ""
    return client.onto("GET", "/objects/%s%s%s" % (gid_path(gid_value), suffix, q))


def onto_changes(client, gid_value, size=100, role=None):
    """GET /changes?globalId=...（03 契约 §2.8，双侧可查；globalId 统一 URL 编码）。"""
    q = "globalId=" + gid_path(gid_value) + "&size=" + str(size)
    if role:
        q += "&role=" + urllib.parse.quote(str(role))
    return client.onto("GET", "/changes?" + q)


def wait_until(predicate, timeout_s=10.0, interval_s=0.3):
    """轮询直到 predicate 返回真值。返回 (ok, last_value, elapsed_seconds)。"""
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout_s:
        try:
            last = predicate()
        except Exception as e:  # 服务暂不可用不应中断轮询
            last = {"_error": str(e)}
        if last:
            return True, last, time.time() - t0
        time.sleep(interval_s)
    return False, last, time.time() - t0


def wait_synced(client, timeout_s=20.0):
    """等待底座追赶完成：/sources/status 状态 SYNCED 且待处理归零。"""
    def f():
        r = client.onto("GET", "/sources/status", timeout=5)
        d = r.data or {}
        if not r.onto_ok:
            return None
        pending = d.get("pendingCount")
        if d.get("status") == "SYNCED" and (pending in (0, None)):
            return d
        return None
    return wait_until(f, timeout_s, 0.5)


def business_reset(client, timeout_s=20.0):
    """恢复初始演示现场并等待底座同步，保证每个 case 可独立、可重复执行。"""
    r = client.biz("POST", "/demo/reset", {})
    ok, _, _ = wait_synced(client, timeout_s)
    return r, ok


def demo_date(client):
    r = client.biz("GET", "/demo/clock")
    d = r.payload or {}
    return (d.get("demoTime") or "")[:10] or datetime.now().strftime("%Y-%m-%d")


# ── 内部事件构造（03 契约 §3；供验收三情况 ①②⑤ 注入）────────────────────
DEFAULT_BEFORE = {
    "teacherId": 1, "teacherName": "张明远", "classroomId": 1, "classroomName": "明理楼 101",
    "lessonDate": "2026-11-14", "startTime": "10:00", "endTime": "10:45", "version": 1,
}
DEFAULT_AFTER = {
    "teacherId": 5, "teacherName": "陈思琪", "classroomId": 6, "classroomName": "多功能报告厅",
    "lessonDate": "2026-11-14", "startTime": "15:30", "endTime": "16:15", "version": 2,
}


def make_event(event_id, revision, change_kind="ADJUST", before=None, after=None,
               business_time=None, occurred_at=None, lesson_date=None,
               aggregate_id="3", diff=None, payload_override=None):
    """构造一条符合 03 契约 §3 的事件。

    注意：revision 必须取业务 system_revision 量级（不要用远大于真实 revision 的值），
    否则会把对象 applied_revision 抬高，导致后续真实业务事件被判 STALE 而永久卡住同步。
    """
    b = dict(DEFAULT_BEFORE if before is None else before)
    a = dict(DEFAULT_AFTER if after is None else after)
    if lesson_date:
        b["lessonDate"] = lesson_date
        a["lessonDate"] = lesson_date
    pl = {
        "sourceTable": "lesson_sessions", "sourcePk": aggregate_id, "sourceCode": "LS-003",
        "before": b, "after": a,
        "diff": diff if diff is not None else [
            {"field": "teacherName", "label": "授课教师", "before": b.get("teacherName"), "after": a.get("teacherName")}
        ],
        "impact": {"teacherIds": [1, 5], "teacherNames": ["张明远", "陈思琪"],
                   "classroomIds": [1, 6], "classIds": [1], "studentIds": []},
        "reason": QA_REASON, "operator": "演示管理员",
    }
    if change_kind == "DELETE":
        pl = {"sourceTable": "lesson_sessions", "sourcePk": aggregate_id, "tombstone": True}
    if payload_override:
        pl = payload_override
    return {
        "eventId": event_id, "batchId": None,
        "aggregateType": "LESSON_SESSION" if change_kind in ("ADJUST", "DELETE") else "SNAPSHOT",
        "aggregateId": aggregate_id, "changeKind": change_kind,
        "revision": revision,
        "businessTime": business_time or now_ts(),
        "occurredAt": occurred_at or now_ts(),
        "payload": pl,
    }


def inject_event(client, event, timeout=8):
    r = client.internal_event(event, timeout=timeout)
    p = r.payload if isinstance(r.payload, dict) else {}
    return r, p


def injection_accepted(resp, payload):
    return resp.status == 200 and payload.get("ack") is True


def describe_injection(resp, payload):
    return ("HTTP %s, ack=%s, persisted=%s, skipped=%s, error=%s"
            % (resp.status, payload.get("ack"), payload.get("persisted"),
               payload.get("skipped"), payload.get("error") or resp.error))


def skip_kind(payload, kind):
    """判断 /internal/events 响应是否为幂等跳过（DUPLICATE / STALE）。

    兼容契约 §3 的 ``skipped`` 字段，也兼容工程师实现把标记放在 status/message
    等字段的写法：只要响应中出现该关键字，且不是「新持久化」即视为命中。
    """
    if not isinstance(payload, dict):
        return False
    if payload.get("ack") is not True or payload.get("persisted") is True:
        return False
    blob = json.dumps(payload, ensure_ascii=False).upper()
    return kind in blob or str(payload.get("skipped") or "").upper() == kind


def event_has_source(event):
    """人工构造事件必须带 payload.sourceTable + payload.sourcePk。

    工程师口径：STALE 判定依赖事件能解析出受影响 globalId；完全不带会被当作
    「无对象变化」处理，仅 bump 平台版本，导致乱序用例**假通过**。DELETE 事件同理。
    """
    pl = (event or {}).get("payload") or {}
    return bool(pl.get("sourceTable")) and pl.get("sourcePk") is not None


# ── 三态结果记录器 ─────────────────────────────────────────────────────────
class CaseRecorder(object):
    """绑定到某个 case 的记录器，自动补齐 case / group 字段。"""

    def __init__(self, parent, case, group):
        self.parent = parent
        self.case = case
        self.group = group

    def check(self, item, ok, detail=""):
        return self.parent._add(item, "passed" if ok else "failed", detail, self.group, self.case)

    def skip(self, item, reason):
        return self.parent._add(item, "skipped", reason, self.group, self.case)

    def fail(self, item, detail):
        return self.parent._add(item, "failed", detail, self.group, self.case)


class Recorder(object):
    """逐项记录 passed / failed / skipped，未执行的 case 由 finalize 自动补 skipped。"""

    def __init__(self, path=None, meta=None, merge=False):
        self.path = path or RESULTS_FILE
        self.meta = meta or {}
        self.items = []
        self._index = {}
        self._silent = False
        if merge:
            old = load_json(self.path, {}) or {}
            self._silent = True
            for item in (old.get("results") or []):
                self._add(item.get("item", "?"), item.get("status", "skipped"),
                          item.get("detail", ""), item.get("group", ""), item.get("case"))
            self._silent = False

    def for_case(self, case, group):
        return CaseRecorder(self, case, group)

    def _add(self, item, status, detail, group="", case=None):
        rec = {
            "item": item,
            "status": status,
            "passed": True if status == "passed" else (False if status == "failed" else None),
            "detail": str(detail)[:900],
            "group": group,
            "case": case,
        }
        if item in self._index:
            self.items[self._index[item]] = rec
        else:
            self._index[item] = len(self.items)
            self.items.append(rec)
        if not self._silent:
            tag = {"passed": "[通过]", "failed": "[失败]", "skipped": "[跳过]"}[status]
            print("  %s %s%s" % (tag, item, (" — " + str(detail)[:200]) if detail else ""))
        return status == "passed"

    def finalize(self, all_cases, run_mode):
        """为本次未执行的 case 显式补一条 skipped，并写明原因与调用方式。"""
        seen = set()
        for x in self.items:
            if x.get("case"):
                seen.add(x["case"])
        # 分阶段合并时：若某 case 本次已真正执行，移除历史结果里它的「未执行」占位，
        # 避免同一 case 同时留下 skipped 与 passed 的自相矛盾证据。
        if seen:
            self.items = [x for x in self.items
                          if not (str(x.get("item", "")).startswith("[未执行] ")
                                  and x.get("case") in seen)]
            self._index = {x["item"]: i for i, x in enumerate(self.items)}
        for c in all_cases:
            if c["name"] in seen:
                continue
            title = "[未执行] " + c["title"]
            if c.get("manual"):
                reason = ("需主理人配合/容器操作，本次(%s)未执行。原因：%s。"
                          "可复现命令：python tests/ontology_acceptance.py --case %s%s"
                          % (run_mode, c.get("hint", "见 05-测试计划.md"), c["name"],
                             (" --phase " + c["phase"]) if c.get("phase") else ""))
            else:
                reason = ("本次(%s)未包含该项。执行方式：python tests/ontology_acceptance.py "
                          "--case %s 或 --all" % (run_mode, c["name"]))
            self._add(title, "skipped", reason, c["group"], c["name"])

    def save(self):
        passed = sum(1 for x in self.items if x["status"] == "passed")
        failed = sum(1 for x in self.items if x["status"] == "failed")
        skipped = sum(1 for x in self.items if x["status"] == "skipped")
        out = {
            "time": stamp(),
            "bases": {"bizApi": BIZ_API, "bizWeb": BIZ_WEB,
                      "ontologyApi": ONTO_API_ROOT, "ontologyWeb": ONTO_WEB},
            "meta": self.meta,
            "summary": {"total": len(self.items), "passed": passed,
                        "failed": failed, "skipped": skipped, "hasSkipped": skipped > 0},
            "results": self.items,
        }
        save_json(self.path, out)
        return out
