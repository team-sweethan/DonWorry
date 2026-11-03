# GitHub → Notion 단방향 동기화 (Notion은 읽기용 미러, 원본은 저장소)
#
# - 작업 현황 DB: Issue · PR 한 건 = 한 행. 키는 GitHub 번호
# - ADR 현황 DB: docs/decisions/NNNN-*.md 한 파일 = 한 행. 키는 파일 이름
# - 상태 페이지 표의 "마지막 동기화" 칸
# - (설정했을 때만) 정본 페이지 맨 아래 "저장소 구현 현황 (자동)" 구역. 본문은 건드리지 않는다
#
# 외부 패키지 없이 표준 라이브러리만 쓴다. 설정은 .github/notion-sync.json, 설명은 docs/ci/07-notion-sync.md
import argparse
import datetime as dt
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONFIG_PATH = ROOT / ".github" / "notion-sync.json"
ADR_DIR = ROOT / "docs" / "decisions"
NOTION_API = "https://api.notion.com/v1"
NOTION_VERSION = "2022-06-28"
GITHUB_API = "https://api.github.com"
KST = dt.timezone(dt.timedelta(hours=9))
ZONE_MARK = "저장소 구현 현황 (자동)"
# 이벤트 실행마다 이 시간 안에 바뀐 Issue · PR도 함께 맞춘다
# 같은 concurrency 그룹에서 대기 중인 실행은 가장 최근 것만 남고 취소되므로, 취소된 실행의 행을 다음 실행이 채운다
CATCH_UP = dt.timedelta(minutes=60)
STATUS_ROW_LABEL = "마지막 동기화"

TYPE_RE = re.compile(r"^\s*\[?(feat|fix|docs|test|refactor|chore)\]?\s*:?\s*", re.I)
CLOSING_RE = re.compile(r"\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s*:?\s+#(\d+)", re.I)
REF_RE = re.compile(r"#(\d+)")
ADR_STATUS_RE = re.compile(r"^\s*-?\s*\*\*상태:?\*\*:?\s*(.+)$", re.M)
MD_LINK_RE = re.compile(r"\[([^\]]*)\]\([^)]*\)")
TRAILING_REFS_RE = re.compile(r"(\s*\(#\d+[^)]*\))+\s*$")  # 제목 끝 "(#210) (#211)"


# ---------------------------------------------------------------- HTTP


def http(method, url, headers, body=None, attempts=5):
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(1, attempts + 1):
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                raw = res.read()
                return json.loads(raw) if raw else {}, res.headers
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            retryable = e.code == 429 or e.code >= 500
            if not retryable or attempt == attempts:
                raise RuntimeError(f"{method} {url} → {e.code}: {detail[:500]}") from None
            wait = float(e.headers.get("Retry-After") or 2**attempt)
            print(f"  재시도 {attempt}/{attempts} ({e.code}), {wait:.0f}초 대기")
            time.sleep(wait)
        except urllib.error.URLError as e:
            if attempt == attempts:
                raise
            print(f"  재시도 {attempt}/{attempts} ({e.reason})")
            time.sleep(2**attempt)
    raise AssertionError("unreachable")


class Notion:
    def __init__(self, token, dry_run):
        self.token = token
        self.dry_run = dry_run
        self.writes = 0

    def call(self, method, path, body=None):
        is_read = method == "GET" or path.endswith("/query")
        if self.dry_run and not is_read:
            print(f"  [dry-run] {method} {path}")
            return {}
        if not self.token:
            raise RuntimeError("NOTION_TOKEN 없이 Notion을 읽을 수 없다")
        time.sleep(0.34)  # Notion 평균 제한 초당 3회
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Notion-Version": NOTION_VERSION,
            "Content-Type": "application/json",
        }
        result, _ = http(method, f"{NOTION_API}{path}", headers, body)
        if not is_read:
            self.writes += 1
        return result

    def query(self, database_id, filter_=None):
        rows, cursor = [], None
        while True:
            body = {"page_size": 100}
            if filter_:
                body["filter"] = filter_
            if cursor:
                body["start_cursor"] = cursor
            res = self.call("POST", f"/databases/{database_id}/query", body)
            rows += res.get("results", [])
            if not res.get("has_more"):
                return rows
            cursor = res["next_cursor"]

    def children(self, block_id):
        items, cursor = [], None
        while True:
            path = f"/blocks/{block_id}/children?page_size=100"
            if cursor:
                path += f"&start_cursor={cursor}"
            res = self.call("GET", path)
            items += res.get("results", [])
            if not res.get("has_more"):
                return items
            cursor = res["next_cursor"]

    def create_row(self, database_id, props):
        return self.call("POST", "/pages", {"parent": {"database_id": database_id}, "properties": props})

    def update_row(self, page_id, props):
        return self.call("PATCH", f"/pages/{page_id}", {"properties": props})

    def archive_row(self, page_id):
        return self.call("PATCH", f"/pages/{page_id}", {"archived": True})


def github_list(path, token):
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    url, items = f"{GITHUB_API}{path}", []
    while url:
        page, res_headers = http("GET", url, headers)
        items += page
        url = None
        for part in (res_headers.get("Link") or "").split(","):
            if 'rel="next"' in part:
                url = part[part.index("<") + 1 : part.index(">")]
    return items


# ---------------------------------------------------------------- 값 변환


def rich(text):
    text = text or ""
    # Notion rich_text 한 조각은 2000자 제한
    return [{"type": "text", "text": {"content": text[i : i + 2000]}} for i in range(0, len(text), 2000)][:100]


def plain(prop):
    if not prop:
        return ""
    kind = prop.get("type")
    if kind in ("title", "rich_text"):
        return "".join(t.get("plain_text", "") for t in prop[kind])
    if kind == "number":
        return prop["number"]
    if kind == "url":
        return prop["url"] or ""
    if kind in ("select", "status"):
        return (prop[kind] or {}).get("name", "")
    if kind == "multi_select":
        return sorted(o["name"] for o in prop["multi_select"])
    if kind == "date":
        return (prop["date"] or {}).get("start", "")
    return ""


def kst_date(iso):
    if not iso:
        return None
    return dt.datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(KST).date().isoformat()


def same_minute(a, b):
    if not a or not b:
        return False
    pa = dt.datetime.fromisoformat(a.replace("Z", "+00:00"))
    pb = dt.datetime.fromisoformat(b.replace("Z", "+00:00"))
    return pa.replace(second=0, microsecond=0) == pb.replace(second=0, microsecond=0)


def row_matches(row, w):
    """Notion 행이 이번에 쓸 값과 같은지 — 갱신 시각만 보면 같은 분 안의 변경이나 담당 매핑 변경을 놓친다."""
    props = row["properties"]

    def get(name):
        return plain(props.get(name))

    return (
        get("제목") == w["title"]
        and get("종류") == ("PR" if w["is_pr"] else "Issue")
        and get("상태") == w["status"]
        and get("유형") == (w["kind"] or "")
        and get("담당") == (w["owner"] or "")
        and get("연결 Issue") == w["linked"]
        and get("작성자") == w["author"]
        and get("라벨") == sorted(w["labels"])
        and get("링크") == w["url"]
        and get("완료일") == (w["done"] or "")
        and same_minute(get("GitHub 갱신"), w["updated"])
    )


def owner_of(logins, owners):
    for login in logins:
        for owner, members in owners.items():
            if login.lower() in (m.lower() for m in members):
                return owner
    return None


def work_item(item, owners):
    """GitHub Issue / PR 객체(Issues API · 웹훅 payload 공통) → 비교·기록용 dict."""
    is_pr = "pull_request" in item or "head" in item
    merged_at = item.get("merged_at") or (item.get("pull_request") or {}).get("merged_at")
    if is_pr:
        if merged_at:
            status = "Merged"
        elif item["state"] == "closed":
            status = "Closed"
        elif item.get("draft"):
            status = "Draft"
        else:
            status = "In review"
    else:
        status = "Closed" if item["state"] == "closed" else "Open"

    title = item["title"]
    m = TYPE_RE.match(title)
    kind = m.group(1).lower() if m else None
    clean_title = TRAILING_REFS_RE.sub("", title[m.end() :] if m else title)

    linked = []
    if is_pr:
        refs = CLOSING_RE.findall(item.get("body") or "") or REF_RE.findall(title)
        for ref in refs:
            if int(ref) != item["number"] and f"#{ref}" not in linked:
                linked.append(f"#{ref}")

    author = item["user"]["login"]
    assignees = [a["login"] for a in item.get("assignees") or []]
    return {
        "number": item["number"],
        "is_pr": is_pr,
        "title": clean_title.strip() or title,
        "status": status,
        "kind": kind,
        "owner": owner_of(assignees + [author], owners),
        "linked": ", ".join(linked),
        "author": author,
        "labels": [lb["name"].replace(",", " ") for lb in item.get("labels") or []],
        "url": item["html_url"],
        "done": kst_date(merged_at or item.get("closed_at")),
        "updated": item["updated_at"],
    }


def work_props(w):
    def select(name):
        return {"select": {"name": name} if name else None}

    return {
        "제목": {"title": rich(w["title"])},
        "번호": {"number": w["number"]},
        "종류": select("PR" if w["is_pr"] else "Issue"),
        "상태": {"status": {"name": w["status"]}},
        "유형": select(w["kind"]),
        "담당": select(w["owner"]),
        "연결 Issue": {"rich_text": rich(w["linked"])},
        "작성자": {"rich_text": rich(w["author"])},
        "라벨": {"multi_select": [{"name": n} for n in w["labels"]]},
        "링크": {"url": w["url"]},
        "완료일": {"date": {"start": w["done"]} if w["done"] else None},
        "GitHub 갱신": {"date": {"start": w["updated"]}},
    }


def read_adrs(repo, branch):
    adrs = []
    for path in sorted(ADR_DIR.glob("[0-9][0-9][0-9][0-9]-*.md")):
        if path.name.startswith("0000-"):
            continue
        text = path.read_text(encoding="utf-8")
        heading = next((ln[2:].strip() for ln in text.splitlines() if ln.startswith("# ")), path.stem)
        title = re.sub(r"^\d{4}\.\s*", "", heading)
        m = ADR_STATUS_RE.search(text)
        raw = MD_LINK_RE.sub(r"\1", m.group(1)).strip() if m else ""
        raw = raw.replace("**", "")
        if raw.startswith("대체"):
            status = "대체됨"
        elif raw.startswith("채택"):
            status = "채택"
        elif "제안" in raw.split("—")[0]:
            status = "제안"
        else:
            status = "기타"
        adrs.append(
            {
                "file": path.name,
                "number": path.name[:4],
                "title": title,
                "status": status,
                "raw": raw,
                "url": f"https://github.com/{repo}/blob/{branch}/docs/decisions/{path.name}",
            }
        )
    return adrs


def adr_props(a):
    return {
        "제목": {"title": rich(a["title"])},
        "번호": {"rich_text": rich(a["number"])},
        "상태": {"select": {"name": a["status"]}},
        "상태 원문": {"rich_text": rich(a["raw"])},
        "파일": {"rich_text": rich(a["file"])},
        "링크": {"url": a["url"]},
    }


# ---------------------------------------------------------------- 동기화


def sync_work(notion, cfg, items, force=()):
    """값이 Notion 행과 같은 건은 건너뛴다. force에 든 번호는 같아도 다시 쓴다 (이벤트로 들어온 그 건)."""
    db = cfg["work_database_id"]
    owners = cfg.get("owners", {})
    works = list({w["number"]: w for w in (work_item(i, owners) for i in items)}.values())
    if not works:
        return
    if notion.token:
        if len(works) <= 50:  # 번호 조건 묶음 — Notion 복합 필터는 100개까지
            conds = [{"property": "번호", "number": {"equals": w["number"]}} for w in works]
            rows = notion.query(db, conds[0] if len(conds) == 1 else {"or": conds})
        else:
            rows = notion.query(db)
    else:
        rows = []
    index = {}
    for row in rows:
        n = plain(row["properties"].get("번호"))
        if n is not None and n != "":
            index.setdefault(int(n), row)

    created = updated = skipped = 0
    for w in works:
        row = index.get(w["number"])
        if row and w["number"] not in force and row_matches(row, w):
            skipped += 1
            continue
        props = work_props(w)
        if row:
            notion.update_row(row["id"], props)
            updated += 1
        else:
            notion.create_row(db, props)
            created += 1
        print(f"  #{w['number']} {w['status']:<9} {w['title'][:60]}")
    print(f"작업 현황: 생성 {created} · 갱신 {updated} · 변경 없음 {skipped}")


def sync_adrs(notion, cfg, adrs):
    db = cfg["adr_database_id"]
    if not adrs:
        print("ADR 파일을 찾지 못해 ADR 동기화를 건너뜀 (기존 행 보존)")
        return
    index = {}
    for row in notion.query(db) if notion.token else []:
        props = row["properties"]
        key = plain(props.get("파일")) or plain(props.get("링크")).rsplit("/", 1)[-1]
        if key in index:  # 같은 파일이 두 행이면 하나만 남긴다
            notion.archive_row(row["id"])
            continue
        index[key] = row

    created = updated = archived = 0
    for a in adrs:
        row = index.pop(a["file"], None)
        if row:
            p = row["properties"]
            current = (plain(p.get("제목")), plain(p.get("상태")), plain(p.get("상태 원문")), plain(p.get("파일")), plain(p.get("링크")))
            if current == (a["title"], a["status"], a["raw"], a["file"], a["url"]):
                continue
            notion.update_row(row["id"], adr_props(a))
            updated += 1
        else:
            notion.create_row(db, adr_props(a))
            created += 1
        print(f"  ADR-{a['number']} {a['status']} {a['title'][:60]}")
    for row in index.values():  # 저장소에서 사라진 파일
        notion.archive_row(row["id"])
        archived += 1
    print(f"ADR 현황: 생성 {created} · 갱신 {updated} · 보관 {archived}")


def update_status_row(notion, cfg, value):
    page = cfg.get("status_page_id")
    if not page or not notion.token:
        print(f"{STATUS_ROW_LABEL}: {value}")
        return
    for block in notion.children(page):
        if block["type"] != "table":
            continue
        for row in notion.children(block["id"]):
            cells = row.get("table_row", {}).get("cells", [])
            if cells and "".join(t.get("plain_text", "") for t in cells[0]).strip() == STATUS_ROW_LABEL:
                new_cells = [cells[0], rich(value)] + cells[2:]
                notion.call("PATCH", f"/blocks/{row['id']}", {"table_row": {"cells": new_cells}})
                print(f"{STATUS_ROW_LABEL}: {value}")
                return
    print(f"상태 페이지 표에서 '{STATUS_ROW_LABEL}' 행을 찾지 못함 — 건너뜀")


def zone_children(page_cfg, adrs, merged_prs, owners, stamp):
    def bullet(text, url=None):
        rt = [{"type": "text", "text": {"content": text[:2000], "link": {"url": url} if url else None}}]
        return {"object": "block", "type": "bulleted_list_item", "bulleted_list_item": {"rich_text": rt}}

    def label(text):
        return {"object": "block", "type": "paragraph", "paragraph": {"rich_text": [{"type": "text", "text": {"content": text}, "annotations": {"bold": True}}]}}

    blocks = [{"object": "block", "type": "paragraph", "paragraph": {"rich_text": rich(f"마지막 동기화: {stamp}")}}]
    by_number = {a["number"]: a for a in adrs}
    wanted = [by_number[n] for n in page_cfg.get("adrs", []) if n in by_number]
    if wanted:
        blocks.append(label("관련 ADR"))
        blocks += [bullet(f"ADR-{a['number']} {a['title']} ({a['status']})", a["url"]) for a in wanted]
    owner = page_cfg.get("owner")
    members = {m.lower() for m in owners.get(owner, [])} if owner else set()
    limit = int(page_cfg.get("recent_prs", 5))
    prs = [p for p in merged_prs if not members or p["user"]["login"].lower() in members][:limit]
    if prs:
        blocks.append(label("최근 병합 PR" + (f" ({owner})" if owner else "")))
        blocks += [bullet(f"#{p['number']} {p['title']} · {kst_date(p['merged_at'])}", p["html_url"]) for p in prs]
    paths = page_cfg.get("paths", [])
    if paths:
        blocks.append(label("저장소 문서"))
        blocks += [bullet(p, f"https://github.com/{page_cfg['_repo']}/blob/{page_cfg['_branch']}/{p}") for p in paths]
    return blocks


def sync_zones(notion, cfg, adrs, merged_prs, stamp, repo, branch):
    pages = cfg.get("canonical_pages") or []
    if not pages:
        return
    owners = cfg.get("owners", {})
    for page_cfg in pages:
        page_cfg = {**page_cfg, "_repo": repo, "_branch": branch}
        page_id = page_cfg["page_id"]
        children = zone_children(page_cfg, adrs, merged_prs, owners, stamp)
        header = rich(f"{ZONE_MARK} — 이 구역은 GitHub Actions가 갱신합니다. 직접 수정하지 마세요")
        header[0]["annotations"] = {"bold": True}
        zone = None
        for block in notion.children(page_id) if notion.token else []:
            if block["type"] == "callout":
                text = "".join(t.get("plain_text", "") for t in block["callout"]["rich_text"])
                if text.startswith(ZONE_MARK):
                    zone = block
                    break
        if zone:
            for old in notion.children(zone["id"]):
                notion.call("DELETE", f"/blocks/{old['id']}")
            notion.call("PATCH", f"/blocks/{zone['id']}", {"callout": {"rich_text": header}})
            notion.call("PATCH", f"/blocks/{zone['id']}/children", {"children": children})
            print(f"정본 자동 구역 갱신: {page_cfg.get('name', page_id)}")
        else:
            callout = {
                "object": "block",
                "type": "callout",
                "callout": {"rich_text": header, "icon": {"type": "emoji", "emoji": "🤖"}, "color": "gray_background", "children": children},
            }
            notion.call("PATCH", f"/blocks/{page_id}/children", {"children": [callout]})
            print(f"정본 자동 구역 추가: {page_cfg.get('name', page_id)}")


# ---------------------------------------------------------------- 진입점


def main():
    parser = argparse.ArgumentParser(description="GitHub → Notion 단방향 동기화")
    parser.add_argument("--mode", choices=["auto", "full", "event"], default=os.environ.get("NOTION_SYNC_MODE") or "auto")
    parser.add_argument("--dry-run", action="store_true", default=os.environ.get("DRY_RUN") == "1")
    args = parser.parse_args()

    cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    token = os.environ.get("NOTION_TOKEN", "")
    gh_token = os.environ.get("GITHUB_TOKEN", "")
    repo = os.environ.get("GITHUB_REPOSITORY") or cfg["repository"]
    branch = cfg.get("branch", "dev")
    event = os.environ.get("GITHUB_EVENT_NAME", "local")
    payload_path = os.environ.get("GITHUB_EVENT_PATH")
    payload = json.loads(pathlib.Path(payload_path).read_text(encoding="utf-8")) if payload_path else {}
    sha = (os.environ.get("GITHUB_SHA") or "")[:7]

    if not token and not args.dry_run:
        print("::notice::NOTION_TOKEN secret이 없어 Notion 동기화를 건너뜀 (아직 설정 전)")
        return 0

    mode = args.mode
    if mode == "auto":
        mode = "event" if event in ("issues", "pull_request", "pull_request_target") else "full"

    notion = Notion(token, args.dry_run)
    stamp = f"{dt.datetime.now(KST):%Y-%m-%d %H:%M} KST · {event}" + (f" · {sha}" if sha else "")
    print(f"모드 {mode} · 이벤트 {event} · dry-run {args.dry_run}")

    if mode == "event":
        item = payload.get("issue") or payload.get("pull_request")
        if not item:
            print("payload에 issue/pull_request가 없음 — 건너뜀")
            return 0
        since = (dt.datetime.now(dt.timezone.utc) - CATCH_UP).strftime("%Y-%m-%dT%H:%M:%SZ")
        try:
            recent = github_list(f"/repos/{repo}/issues?state=all&since={since}&per_page=100", gh_token)
        except (RuntimeError, urllib.error.URLError) as e:  # 보정 조회가 실패해도 이벤트로 온 그 건은 쓴다
            print(f"::warning::최근 변경 조회 실패 — 이벤트 건만 동기화: {e}")
            recent = []
        sync_work(notion, cfg, [item] + recent, force={item["number"]})
    else:
        # push(설정 · ADR 변경)에서도 Issue · PR 전체를 대조한다. 값이 다른 행만 쓰므로
        # owners를 바꾼 push는 담당이 달라진 행만 다시 쓰인다 (Actions push payload에는 변경 파일 목록이 없음)
        items = github_list(f"/repos/{repo}/issues?state=all&per_page=100", gh_token)
        sync_work(notion, cfg, items)
        adrs = read_adrs(repo, branch)
        sync_adrs(notion, cfg, adrs)
        if cfg.get("canonical_pages"):
            closed = github_list(f"/repos/{repo}/pulls?state=closed&base={branch}&sort=updated&direction=desc&per_page=100", gh_token)
            merged = sorted((p for p in closed if p.get("merged_at")), key=lambda p: p["merged_at"], reverse=True)
            sync_zones(notion, cfg, adrs, merged, stamp, repo, branch)

    update_status_row(notion, cfg, stamp)
    print(f"Notion 쓰기 {notion.writes}회")
    return 0


if __name__ == "__main__":
    sys.exit(main())
