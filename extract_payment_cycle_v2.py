#!/usr/bin/env python3
import argparse
import json
import re
import unicodedata
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

from docx import Document
from docx.oxml.ns import qn
from docx.table import Table as DocxTable
from docx.text.paragraph import Paragraph

from extract_product_classification_v2 import extract_docx

ROOT = Path(__file__).resolve().parent
TARGET_DIR = ROOT / '사업방법서_워드' if (ROOT / '사업방법서_워드').exists() else ROOT.parent / '사업방법서_워드'
OUTPUT_DIR = ROOT / '납입주기'
PRODUCT_META_DIR = ROOT / '상품구분' if (ROOT / '상품구분').exists() else ROOT / '상품분류'
DEFAULT_REPORT_PATH = OUTPUT_DIR / 'mapping_report_paym_cycl.json'
OVERRIDES_PATH = ROOT / 'config' / 'product_overrides.json'

CYCLE_ORDER = ['0', '1', '3', '6', '12']


def _load_overrides() -> dict:
    if OVERRIDES_PATH.exists():
        with OVERRIDES_PATH.open('r', encoding='utf-8') as f:
            return json.load(f)
    return {}


class CycleRule:
    contexts: Tuple[str, ...]
    cycles: Tuple[str, ...]
    priority: int

    def __init__(self, contexts: Sequence[str], cycles: Sequence[str]):
        self.contexts = tuple(contexts)
        self.cycles = tuple(dict.fromkeys(cycles))
        self.priority = len(self.contexts)


def normalize_ws(value: str) -> str:
    return re.sub(r'\s+', ' ', unicodedata.normalize('NFC', value or '')).strip()


def normalize_match_key(value: str) -> str:
    v = unicodedata.normalize('NFKC', value or '')
    return re.sub(r'\s+', '', v)


def load_detail_context_tokens(source_dir: Path) -> List[str]:
    if not source_dir.exists():
        return []

    tokens: List[str] = []
    seen = set()
    for path in sorted(source_dir.glob('*.json')):
        try:
            with path.open('r', encoding='utf-8') as f:
                rows = json.load(f)
        except Exception:
            continue

        if not isinstance(rows, list):
            continue

        for row in rows:
            if not isinstance(row, dict):
                continue
            for key, value in row.items():
                if not re.fullmatch(r'세부종목\d+', str(key)):
                    continue
                if not isinstance(value, str):
                    continue
                text = normalize_ws(value)
                if text:
                    compact = normalize_match_key(text)
                    if compact and compact not in seen:
                        seen.add(compact)
                        tokens.append(text)

    return tokens


def normalize_cycle_name(token: str) -> Optional[str]:
    if token == '일시납':
        return '일시납'
    if token == '수시납':
        return '월납'
    if token == '월납':
        return '월납'
    if token == '3개월납':
        return '3개월납'
    if token == '분기납':
        return '3개월납'
    if token == '6개월납':
        return '6개월납'
    if token == '반기납':
        return '6개월납'
    if token == '연납' or token == '년납':
        return '연납'
    return None


def cycle_value(name: str) -> str:
    if name == '일시납':
        return '0'
    if name == '월납':
        return '1'
    if name == '3개월납':
        return '3'
    if name == '6개월납':
        return '6'
    if name == '연납':
        return '12'
    return ''


def extract_cycle_names(text: str) -> List[str]:
    if not text:
        return []
    raw_matches = re.finditer(r'일시납|수시납|월납|3개월납|분기납|6개월납|반기납|연납|년납|일회납', text)
    names: List[str] = []
    for m in raw_matches:
        token = m.group(0)
        # 보험기간/납입기간 표기에서 자주 보이는 "10년납, 15년납, 20년납, 30년납"은
        # 납입주기가 아니라 기간 정보이므로 제외한다.
        if token == '년납' and m.start() > 0 and text[m.start() - 1].isdigit():
            continue
        name = normalize_cycle_name(token)
        if not name:
            continue
        if not names or names[-1] != name:
            names.append(name)
        if name not in names:
            names.append(name)
    # 중복 제거
    out: List[str] = []
    for name in names:
        if name not in out:
            out.append(name)
    return out


def cycle_records_from_names(names: Sequence[str]) -> List[Dict[str, str]]:
    items: List[Dict[str, str]] = []
    for raw in names:
        if raw == '년납':
            raw = '연납'
        val = cycle_value(raw)
        if not val:
            continue
        items.append(
            {
                '납입주기명': '일시납' if val == '0' else '월납' if val == '1' else ('3월납' if val == '3' else ('6월납' if val == '6' else '년납')),
                '납입주기값': val,
                '납입주기구분코드': 'M',
            }
        )
    return items


def extract_context_tokens(text: str, detail_tokens: Optional[Sequence[str]] = None) -> List[str]:
    txt = normalize_ws(text)
    out: List[str] = []

    # 대표 카테고리
    for token in re.findall(r'\d+종\([^)]*\)', txt):
        out.append(token)

    keyword_patterns = [
        '보장형 계약',
        '적립형 계약',
        '스마트전환형 계약[보증비용부과형]',
        '스마트전환형 계약',
        '해약환급금 미보증',
        '해약환급금 보증',
        '일부지급형',
        '만기환급형',
        '해약환급금 일부지급형',
        '해약환급금 미지급형',
        '해약환급금 보증',
        '스마트전환형',
        '적립형',
        '거치형',
    ]

    for k in keyword_patterns:
        if k in txt:
            out.append(k)

    # 괄호형(유형) 같은 표현
    for token in re.findall(r'\d+종\([^)]*\)', txt):
        if token not in out:
            out.append(token)

    if detail_tokens:
        normalized_text = normalize_match_key(txt)
        for token in detail_tokens:
            # 순수 기간 토큰(n년, n세 단독)은 세부종목이 아닌 보험기간/납입기간
            # 표기이므로 context에서 제외
            if re.fullmatch(r'\d+년|\d+세', token):
                continue
            normalized_token = normalize_match_key(token)
            if normalized_token and normalized_token in normalized_text and token not in out:
                out.append(token)

    # normalize duplicates by removing spaces
    uniq: List[str] = []
    seen: set[str] = set()
    for token in out:
        norm = normalize_match_key(token)
        if norm in seen:
            continue
        seen.add(norm)
        uniq.append(token)
    return uniq


def parse_override_rules(rhs: str, base_context: List[str], detail_tokens: Optional[Sequence[str]] = None) -> List[CycleRule]:
    rules: List[CycleRule] = []
    for inner in extract_parenthesized_sections(rhs):
        if '의 경우' not in inner:
            continue
        cond_text, cyc_text = inner.split('의 경우', 1)
        cond_text = cond_text.replace('단,', '').strip(', ')  # "단, 3종..." 형태 보정
        cond_tokens = extract_context_tokens(cond_text, detail_tokens)
        if not cond_tokens:
            continue
        cyc = extract_cycle_names(cyc_text)
        if not cyc:
            continue
        merged = normalize_context_union(base_context, cond_tokens)
        rules.append(CycleRule(merged, cyc))
    return rules


def extract_parenthesized_sections(text: str) -> List[str]:
    sections: List[str] = []
    stack: List[int] = []
    start = -1
    for i, ch in enumerate(text):
        if ch == '(':
            stack.append(i)
            if len(stack) == 1:
                start = i + 1
            continue
        if ch == ')' and stack:
            stack.pop()
            if not stack and start >= 0:
                sections.append(text[start:i])
                start = -1
    return sections


def normalize_context_union(left: Sequence[str], right: Sequence[str]) -> List[str]:
    merged: List[str] = []
    seen = set()
    for token in list(left) + list(right):
        norm = normalize_match_key(token)
        if norm in seen:
            continue
        seen.add(norm)
        merged.append(token)
    return merged


def parse_cycle_line(
    line: str,
    prev_context: List[str],
    detail_tokens: Optional[Sequence[str]] = None,
    in_additional_section: bool = False,
) -> Tuple[List[CycleRule], List[str]]:
    text = normalize_ws(line)
    if not text:
        return [], prev_context

    if '추가납입보험료' in text and '수시납' in text:
        return [], prev_context

    contexts = extract_context_tokens(text, detail_tokens)
    if contexts:
        prev_context = contexts

    cycles = extract_cycle_names(text)
    if in_additional_section or (': ' not in text and ':' not in text and '：' not in text):
        if not in_additional_section and cycles and prev_context:
            return [CycleRule(prev_context, cycles)], prev_context
        return [], prev_context

    rules: List[CycleRule] = []

    sep_match = re.search(r'[:：]', text)
    if not sep_match:
        return rules, prev_context

    lhs = text[: sep_match.start()]
    rhs = text[sep_match.start() + 1 :]

    lhs_ctx = extract_context_tokens(lhs, detail_tokens)
    base_ctx = lhs_ctx or prev_context

    # 기본 주기 (조건부 override 문구에 포함된 주기는 제외)
    base_cycles = extract_cycle_names(rhs)
    for inner in extract_parenthesized_sections(rhs):
        if '의 경우' not in inner:
            continue
        for cyc in extract_cycle_names(inner):
            if cyc in base_cycles:
                base_cycles.remove(cyc)
    if base_cycles:
        rules.append(CycleRule(base_ctx, base_cycles))

    # 조건부 override: 예) 월납(단, 3종(...)의 경우 일시납)
    rules.extend(parse_override_rules(rhs, base_ctx, detail_tokens))

    return rules, prev_context


def parse_rules_from_text(
    text: str,
    detail_tokens: Optional[Sequence[str]] = None,
) -> Tuple[List[CycleRule], List[str]]:
    lines = [normalize_ws(l) for l in text.splitlines()]
    rules: List[CycleRule] = []
    section_default_cycles: List[str] = []
    context_stack: List[str] = []
    in_payment_section = False
    in_additional_section = False

    for line in lines:
        if not line:
            continue

        # 납입주기 관련 절을 추적해서, 보조 설명 문구의 일시납/월납 언급이
        # 잘못 규칙으로 잡히는 것을 방지한다.
        if '납입주기' in line:
            in_payment_section = True

        if '추가납입보험료' in line:
            in_additional_section = True

        if in_additional_section and re.match(r'^\(\d+\)', line) and '추가납입보험료' not in line:
            in_additional_section = False

        if in_payment_section and re.match(r'^\d+\.', line) and '납입주기' not in line:
            in_payment_section = False

        # 제목/항목 라인을 문맥 추적에 활용
        if in_payment_section:
            heading_context = extract_context_tokens(line, detail_tokens)
            if heading_context:
                context_stack = heading_context

        if not in_payment_section and '납입주기' not in line:
            continue

        # 납입주기 관련 라인 파싱
        line_cycles = extract_cycle_names(line)
        if not any(
            k in line
            for k in ['납입주기', '일시납', '수시납', '월납', '3개월납', '6개월납', '연납', '년납']
        ):
            continue

        line_rules, context_stack = parse_cycle_line(
            line,
            context_stack,
            detail_tokens=detail_tokens,
            in_additional_section=in_additional_section,
        )
        rules.extend(line_rules)
        has_context_tokens = bool(extract_context_tokens(line, detail_tokens))

        if (
            in_payment_section
            and not in_additional_section
            and not has_context_tokens
            and line_cycles
            and line_cycles not in section_default_cycles
        ):
            for cycle_name in line_cycles:
                if cycle_name not in section_default_cycles:
                    section_default_cycles.append(cycle_name)

        # 라인에 주기 단어만 존재하고 규칙이 없는 경우는 (납입주기 표 머리말) 문맥을 비움
        if not line_rules and extract_cycle_names(line):
            context_stack = []

    return rules, section_default_cycles


def parse_rules_from_table(table: List[List[Optional[str]]], detail_tokens: Optional[Sequence[str]] = None) -> List[CycleRule]:
    # Strip \n from cells before normalize_ws to handle split values like "6\n개월납"
    rows = [[normalize_ws((str(c or '')).replace('\n', '')) for c in row] for row in table]
    if not rows:
        return []

    rules: List[CycleRule] = []
    prev_cycles: Optional[List[str]] = None
    for row in rows:
        if not row:
            continue

        cycle_cells: List[Tuple[int, List[str]]] = []
        for idx, cell in enumerate(row):
            cycles = extract_cycle_names(cell)
            if cycles:
                cycle_cells.append((idx, cycles))

        row_ctx = extract_context_tokens(' '.join(c for c in row), detail_tokens)

        merged_cycles: List[str] = []
        for _, cs in cycle_cells:
            for c in cs:
                if c not in merged_cycles:
                    merged_cycles.append(c)

        if merged_cycles:
            prev_cycles = merged_cycles
            rules.append(CycleRule(row_ctx, merged_cycles))
        elif row_ctx and prev_cycles:
            # Fill-down: inherit previous row's cycles for merged cells (e.g. 30세형 with None 납입주기)
            rules.append(CycleRule(row_ctx, prev_cycles[:]))

    return rules


def _extract_docx_with_sections(docx_path: Path):
    """Word 문서에서 라인, 테이블, 테이블별 섹션 타입을 추출."""
    doc = Document(str(docx_path))
    lines: List[str] = []
    tables: List[List[List[Optional[str]]]] = []
    table_sections: List[str] = []

    _current_section = ''
    for child in doc.element.body:
        if child.tag == qn('w:p'):
            text = Paragraph(child, doc).text.strip()
            if text:
                lines.append(text)
                stripped = re.sub(r'\s+', '', text)
                if '최초계약' in stripped:
                    _current_section = '최초계약'
                elif '갱신계약' in stripped:
                    _current_section = '갱신계약'
        elif child.tag == qn('w:tbl'):
            table = DocxTable(child, doc)
            t = [[cell.text for cell in row.cells] for row in table.rows]
            tables.append(t)
            table_sections.append(_current_section)

    return lines, tables, table_sections


def _get_section_filter(docx_path: Path) -> Optional[str]:
    """product_overrides.json의 table_section_filter 설정에 따라 사용할 섹션을 반환."""
    overrides = _load_overrides()
    for rule in overrides.get('table_section_filter', {}).get('rules', []):
        keyword = rule.get('filename_contains', '')
        if keyword and keyword in docx_path.name:
            return rule.get('use_section', '')
    return None


def extract_cycle_rules(
    docx_path: Path,
    detail_tokens: Optional[Sequence[str]] = None,
    *,
    section_filter: Optional[str] = None,
) -> Tuple[List[CycleRule], List[str]]:
    lines, tables, table_sections = _extract_docx_with_sections(docx_path)

    # table_section_filter 설정에 따라 특정 섹션 테이블만 사용
    if section_filter:
        filtered = [t for t, s in zip(tables, table_sections) if s == section_filter]
        if filtered:
            tables = filtered

    full_text = '\n'.join(lines)
    rules, default_cycles = parse_rules_from_text(full_text, detail_tokens)

    for table in tables:
        rules.extend(parse_rules_from_table(table, detail_tokens))

    final_default_cycles: List[str] = []
    for cycle in default_cycles:
        if cycle not in final_default_cycles:
            final_default_cycles.append(cycle)

    # 중복 제거
    uniq = {}
    for r in rules:
        key = (tuple(normalize_match_key(x) for x in r.contexts), r.cycles)
        if key in uniq:
            continue
        uniq[key] = r
    return list(uniq.values()), final_default_cycles


def match_record_context(record: dict, rule: CycleRule) -> bool:
    if not rule.contexts:
        return True

    record_tokens = []
    for key, value in record.items():
        if key.startswith('세부종목') and isinstance(value, str):
            record_tokens.append(value)
    record_norm = normalize_match_key(' '.join(record_tokens))
    if not record_norm:
        return False

    for ctx in rule.contexts:
        if normalize_match_key(ctx) not in record_norm:
            return False
    return True


def pick_cycles(record: dict, rules: List[CycleRule], fallback_cycles: Sequence[str]) -> List[Dict[str, str]]:
    matched: Dict[int, List[str]] = {}

    for rule in rules:
        if not match_record_context(record, rule):
            continue
        matched.setdefault(rule.priority, [])
        existing = matched[rule.priority]
        for c in rule.cycles:
            if c not in existing:
                existing.append(c)

    chosen_cycles: List[str] = []
    if matched:
        max_priority = max(matched.keys())
        if max_priority > 0:
            chosen_cycles = matched[max_priority]
        else:
            for c in fallback_cycles:
                if c not in chosen_cycles:
                    chosen_cycles.append(c)
    else:
        for c in fallback_cycles:
            if c not in chosen_cycles:
                chosen_cycles.append(c)

    ordered: List[str] = []
    for code in CYCLE_ORDER:
        target = '일시납' if code == '0' else ('월납' if code == '1' else ('3개월납' if code == '3' else ('6개월납' if code == '6' else '연납')))
        if target in chosen_cycles:
            if target not in ordered:
                ordered.append(target)

    return cycle_records_from_names(ordered)

def dedupe_cycles(items: List[Dict[str, str]]) -> List[Dict[str, str]]:
    out: List[Dict[str, str]] = []
    seen = set()
    for item in items:
        key = (item['납입주기명'], item['납입주기값'])
        if key in seen:
            continue
        seen.add(key)
        out.append(item)

    return out

def enrich_records_with_cycles(
    records: List[dict],
    rules: List[CycleRule],
    default_cycles: Optional[Sequence[str]] = None,
) -> List[dict]:
    normalized_rules = sorted(rules, key=lambda r: r.priority, reverse=True)
    fallback_rules = [r for r in normalized_rules if r.priority == 0]
    default_cycles = list(default_cycles or [])

    fallback_cycles: List[str] = []
    for rule in fallback_rules:
        for c in rule.cycles:
            if c not in fallback_cycles:
                fallback_cycles.append(c)

    out: List[dict] = []
    for rec in records:
        cycles = pick_cycles(rec, normalized_rules, fallback_cycles)
        resolved_cycles: List[Dict[str, str]]
        item = dict(rec)
        if cycles:
            resolved_cycles = cycles
        elif default_cycles:
            resolved_cycles = cycle_records_from_names(default_cycles)
        else:
            resolved_cycles = []
        item['납입주기'] = dedupe_cycles(resolved_cycles)
        # 보존: 기존 형식에서 `납입주기명`/`납입주기값` 단일 필드에서
        # `납입주기` 리스트 객체로 확장된 형태로 저장
        out.append(item)

    out.sort(key=lambda x: (x.get('상품명칭', ''), x.get('상품명', '')))
    return out


def _classify_cycle_status(item: dict) -> str:
    cycles = item.get('납입주기')
    if not isinstance(cycles, list):
        return '매핑안됨'
    count = len(cycles)
    if count == 0:
        return '매핑안됨'
    if count == 1:
        return '매핑완료(단일)'
    return '매핑완료(다중)'


def format_record_name(record: dict) -> str:
    return record.get('상품명', '') or record.get('상품명칭', '') or ''


def build_payment_cycle_report(all_records: List[Tuple[str, dict]]) -> Dict[str, object]:
    categories: Dict[str, List[str]] = {
        '매핑완료(단일)': [],
        '매핑완료(다중)': [],
        '매핑안됨': [],
    }
    file_summary: Dict[str, Dict[str, int]] = {}

    for source, record in all_records:
        name = format_record_name(record)
        if not name:
            name = record.get('상품명칭', '')
        status = _classify_cycle_status(record)
        if status in categories:
            if name not in categories[status]:
                categories[status].append(name)

        if source not in file_summary:
            file_summary[source] = {
                '매핑완료(단일)': 0,
                '매핑완료(다중)': 0,
                '매핑안됨': 0,
                '합계': 0,
            }
        file_summary[source][status] += 1
        file_summary[source]['합계'] += 1

    return {
        '총건수': len(all_records),
        '매핑완료(단일)_count': len(categories['매핑완료(단일)']),
        '매핑완료(다중)_count': len(categories['매핑완료(다중)']),
        '매핑안됨_count': len(categories['매핑안됨']),
        '매핑완료(단일)': categories['매핑완료(단일)'],
        '매핑완료(다중)': categories['매핑완료(다중)'],
        '매핑안됨': categories['매핑안됨'],
        '파일별요약': [
            {
                'source': source,
                '매핑완료(단일)': counts['매핑완료(단일)'],
                '매핑완료(다중)': counts['매핑완료(다중)'],
                '매핑안됨': counts['매핑안됨'],
                '합계': counts['합계'],
            }
            for source, counts in sorted(file_summary.items())
        ],
    }


def extract_payment_cycle_for_docx(docx_path: Path, detail_tokens: Optional[Sequence[str]] = None) -> List[dict]:
    product_records = extract_docx(docx_path)
    if not product_records:
        return []

    section = _get_section_filter(docx_path)
    rules, default_cycles = extract_cycle_rules(docx_path, detail_tokens, section_filter=section)
    return enrich_records_with_cycles(product_records, rules, default_cycles=default_cycles)


def _apply_cycle_overrides(records: List[dict]) -> List[dict]:
    """Post-process: product_overrides.json 기반 override + sibling fallback."""
    overrides = _load_overrides()
    pc_overrides = overrides.get('payment_cycle', {})
    sibling_cfg = overrides.get('sibling_fallback', {})
    suffix_patterns = sibling_cfg.get('suffix_patterns', [])

    # Build lookup: 상품명 → 납입주기
    name_to_cycles: Dict[str, List[dict]] = {}
    for r in records:
        name_to_cycles[r.get('상품명', '')] = r.get('납입주기', [])

    for r in records:
        product_name = r.get('상품명', '')
        cycles = r.get('납입주기', [])
        applied = False

        # 1) JSON override 적용
        for key, cfg in pc_overrides.items():
            if key.startswith('_'):
                continue
            keywords = key.split('+')
            if not all(kw in product_name for kw in keywords):
                continue

            action = cfg.get('action', 'fixed')
            is_force = cfg.get('force', False)

            if not is_force and cycles:
                continue

            if action == 'fixed':
                r['납입주기'] = list(cfg.get('cycles', []))
                applied = True

            elif action == 'sibling_filter':
                match_kws = cfg.get('sibling_match', [])
                exclude_kws = cfg.get('sibling_exclude', [])
                filter_val = cfg.get('filter_cycle_value')
                for name, cdata in name_to_cycles.items():
                    if (all(m in name for m in match_kws)
                            and not any(e in name for e in exclude_kws)
                            and cdata):
                        if filter_val:
                            filtered = [c for c in cdata if c.get('납입주기값') == filter_val]
                            r['납입주기'] = filtered if filtered else list(cdata)
                        else:
                            r['납입주기'] = list(cdata)
                        applied = True
                        break

            elif action == 'sibling_copy':
                match_kws = cfg.get('sibling_match', [])
                any_kws = cfg.get('sibling_any', [])
                for name, cdata in name_to_cycles.items():
                    if (all(m in name for m in match_kws)
                            and any(a in name for a in any_kws)
                            and cdata):
                        r['납입주기'] = list(cdata)
                        applied = True
                        break

            if applied:
                break

        # 2) sibling fallback (suffix_patterns 기반)
        if not applied and not r.get('납입주기'):
            for pattern in suffix_patterns:
                parts = pattern.split('|')
                if len(parts) != 2:
                    continue
                src, dst = parts
                if src in product_name:
                    base_name = product_name.replace(src, dst)
                    base_data = name_to_cycles.get(base_name, [])
                    if base_data:
                        r['납입주기'] = list(base_data)
                        break

    return records


def _make_output_stem(filename: str) -> str:
    """Truncate filename after '사업방법서' for output naming."""
    name = unicodedata.normalize('NFC', filename)
    m = re.search(r'^(.+사업방법서)', re.sub(r'\.\w+$', '', name))
    return m.group(1) if m else re.sub(r'\.\w+$', '', name)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description='Extract 납입주기 from 사업방법서 Word docs')
    parser.add_argument('--docx', type=str, help='single docx path to process')
    parser.add_argument('--output', type=str, help='single output JSON path if --docx is used')
    parser.add_argument('--report-path', type=Path, default=DEFAULT_REPORT_PATH, help='path to mapping report output')
    return parser


def _main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    detail_tokens = load_detail_context_tokens(PRODUCT_META_DIR)
    if args.docx:
        docs = [Path(args.docx)]
    else:
        docs = sorted(TARGET_DIR.glob('*.docx'))

    all_records: List[Tuple[str, dict]] = []
    for docx_file in docs:
        records = extract_payment_cycle_for_docx(docx_file, detail_tokens)
        records = _apply_cycle_overrides(records)
        out_stem = _make_output_stem(docx_file.name)
        out_name = f"{out_stem}.json"
        out_path = Path(args.output) if args.output and args.docx else OUTPUT_DIR / out_name
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(records, ensure_ascii=False, indent=2),
            encoding='utf-8',
        )
        all_records.extend((docx_file.name, rec) for rec in records)
        print(f'{docx_file.name} -> {out_path.name} ({len(records)} items)')

    report = build_payment_cycle_report(all_records)
    args.report_path.parent.mkdir(parents=True, exist_ok=True)
    with args.report_path.open('w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f'[완료] 납입주기 매핑 리포트: {args.report_path}')


if __name__ == '__main__':
    _main()
