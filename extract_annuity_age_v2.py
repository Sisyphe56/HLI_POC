#!/usr/bin/env python3
import argparse
import json
import re
import unicodedata
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Set, Tuple

from docx import Document
from docx.oxml.ns import qn
from docx.text.paragraph import Paragraph
from docx.table import Table as DocxTable


ROOT = Path(__file__).resolve().parent
DEFAULT_DOCX_DIR = (ROOT / '사업방법서_워드' if (ROOT / '사업방법서_워드').exists()
                    else ROOT.parent / '사업방법서_워드')
DEFAULT_JSON_DIR = ROOT / '상품분류'
DEFAULT_OUTPUT_DIR = ROOT / '보기개시나이'
DEFAULT_REPORT_PATH = DEFAULT_OUTPUT_DIR / 'report.json'
OVERRIDES_PATH = ROOT / 'config' / 'product_overrides.json'


def _load_overrides() -> dict:
    if OVERRIDES_PATH.exists():
        with OVERRIDES_PATH.open('r', encoding='utf-8') as f:
            return json.load(f)
    return {}


def normalize_ws(value: str) -> str:
    text = unicodedata.normalize('NFC', value or '')
    text = text.replace('\u200b', '')
    return re.sub(r'\s+', ' ', text).strip()


def normalize_name(value: str) -> str:
    text = normalize_ws(value)
    text = re.sub(r'\.(pdf|docx|json)$', '', text)
    # Truncate after 사업방법서 to match output naming
    m = re.search(r'^(.+사업방법서)', text)
    if m:
        text = m.group(1)
    text = re.sub(r'\s+', '', text)
    return text


def extract_docx_content(docx_path: Path) -> Tuple[List[str], List[List[List[str]]]]:
    """Word 문서에서 문서 순서를 보존하여 lines와 tables를 반환."""
    doc = Document(str(docx_path))
    lines: List[str] = []
    all_tables: List[List[List[str]]] = []
    for child in doc.element.body:
        if child.tag == qn('w:p'):
            normalized = normalize_ws(Paragraph(child, doc).text)
            if normalized:
                lines.append(normalized)
        elif child.tag == qn('w:tbl'):
            table = DocxTable(child, doc)
            cleaned = [[normalize_ws((cell.text or '').replace('\n', '')) for cell in row.cells]
                       for row in table.rows]
            all_tables.append(cleaned)
            # Also add cell text to lines for pattern matching
            for row in table.rows:
                for cell in row.cells:
                    normalized = normalize_ws(cell.text)
                    if normalized:
                        lines.append(normalized)
    return lines, all_tables


def _is_annuity_table(table: List[List[str]]) -> bool:
    """연금개시나이 관련 테이블인지 판별."""
    flat = ' '.join(cell for row in table[:4] for cell in row)
    return '연금개시나이' in flat or ('종신연금형' in flat and '확정기간연금형' in flat)


def extract_annuity_blocks_from_tables(tables: List[List[List[str]]]) -> List[Dict[str, object]]:
    """
    pdfplumber extract_tables() 결과에서 연금개시나이 블록 추출.
    열(column) 헤더로 category key를 생성하므로 종신연금형/확정기간연금형 범위가 혼용되지 않음.
    """
    blocks: List[Dict[str, object]] = []

    for table in tables:
        if not _is_annuity_table(table):
            continue

        # 헤더 행 탐색: 종신연금형/확정기간연금형 키워드가 있는 행 우선, 없으면 연금개시나이 행
        header_row_idx = -1
        col_categories: List[str] = []  # 열 인덱스 → category key
        fallback_header_idx = -1
        fallback_col_categories: List[str] = []
        for ridx, row in enumerate(table):
            has_specific = any(
                tok in cell
                for cell in row
                for tok in ('종신연금형', '확정기간연금형')
            )
            has_generic = any(
                tok in cell
                for cell in row
                for tok in ('연금개시나이', '연금형')
            )
            if has_specific:
                header_row_idx = ridx
                col_categories = [cell for cell in row]
                break
            if has_generic and fallback_header_idx < 0:
                fallback_header_idx = ridx
                fallback_col_categories = [cell for cell in row]
        if header_row_idx < 0 and fallback_header_idx >= 0:
            header_row_idx = fallback_header_idx
            col_categories = fallback_col_categories

        if header_row_idx < 0:
            continue

        # 성별 열 탐색 (남자/여자가 있는 행)
        gender_row_idx = -1
        col_genders: List[str] = []
        for ridx in range(header_row_idx + 1, min(header_row_idx + 3, len(table))):
            row = table[ridx]
            if any('남자' in cell or '여자' in cell for cell in row):
                gender_row_idx = ridx
                col_genders = [cell for cell in row]
                break

        # 데이터 행 수집
        data_start = (gender_row_idx + 1) if gender_row_idx >= 0 else (header_row_idx + 1)

        category_to_values: Dict[str, Dict[str, List[str]]] = {}
        generic_values: Dict[str, List[str]] = {}

        # 열별 이전 값을 저장 (빈 셀 fill-forward 용)
        prev_col_vals: Dict[int, List[str]] = {}
        prev_col_gender: Dict[int, str] = {}

        # row context fill-down (병합셀)
        prev_row_ctx = ''
        for ridx in range(data_start, len(table)):
            row = table[ridx]

            # 첫 열이 비어 있으면 이전 행의 context 상속 (병합셀 fill-down)
            row_ctx_raw = row[0] if row else ''
            if not row_ctx_raw:
                row_ctx_raw = prev_row_ctx
            else:
                prev_row_ctx = row_ctx_raw

            row_ctx_tokens = extract_context_tokens(row_ctx_raw)

            for cidx, cell in enumerate(row):
                # 열 헤더에서 category token 파악
                col_cat = col_categories[cidx] if cidx < len(col_categories) else ''
                col_gender_str = col_genders[cidx] if cidx < len(col_genders) else ''

                # 빈 셀인 경우: 해당 열에 category 또는 gender 정보가 있으면 이전 행의 값을 상속
                if not cell:
                    col_cat_tokens = extract_context_tokens(col_cat)
                    has_gender_header = ('남자' in col_gender_str or '여자' in col_gender_str)
                    if (col_cat_tokens or has_gender_header) and cidx in prev_col_vals:
                        vals = prev_col_vals[cidx]
                        gender = prev_col_gender.get(cidx, '')
                    else:
                        continue
                else:
                    vals = split_age_values(cell)
                    if not vals:
                        continue

                    # 성별 판단 (col_gender 또는 cell 내 남자/여자)
                    gender = ''
                    if '남자' in col_gender_str or '남자' in cell:
                        gender = '남자'
                    elif '여자' in col_gender_str or '여자' in cell:
                        gender = '여자'

                    # 현재 열의 값 저장 (다음 행 fill-forward용)
                    prev_col_vals[cidx] = vals
                    prev_col_gender[cidx] = gender

                # 연금형 category token 추출 (열 헤더 기준)
                col_cat_tokens = extract_context_tokens(col_cat)

                # row context + col context 합산
                merged_groups: Dict[str, Set[str]] = {}
                for g, vs in row_ctx_tokens.items():
                    merged_groups.setdefault(g, set()).update(vs)
                for g, vs in col_cat_tokens.items():
                    merged_groups.setdefault(g, set()).update(vs)

                context_keys = build_context_key(merged_groups)

                if context_keys:
                    assign_context_values(category_to_values, context_keys, gender, vals)
                else:
                    add_values(generic_values, gender, vals)

        if category_to_values or generic_values:
            blocks.append({
                'generic_values': generic_values,
                'category_values': category_to_values,
                '_source': 'table',
            })

    return blocks


def row_text(row: dict) -> str:
    chunks = []
    for key in ('상품명칭', '상품명', '세부종목1', '세부종목2', '세부종목3', '세부종목4'):
        value = normalize_ws(str(row.get(key, '')))
        if value:
            chunks.append(value)
    return ' '.join(chunks)


CONTEXT_KEY_SEPARATOR = '||'
_DEFAULT_ANNUITY_CONTEXT_TOKENS: Dict[str, str] = {
    '1종(신계약체결용)': 'S1',
    '2종(계좌이체용)': 'S1',
    '종신연금형': 'S2',
    '확정기간연금형': 'S2',
    '상속연금형': 'S2',
    '거치형': 'S3',
    '적립형': 'S3',
    '개인형': 'S4',
    '신부부형': 'S4',
    '표준형': 'S2',
    '기본형': 'S2',
    '종신플랜': 'S5',
    '환급플랜': 'S5',
}


def _get_annuity_context_tokens() -> Dict[str, str]:
    """product_overrides.json의 annuity_context_tokens가 있으면 사용, 없으면 기본값."""
    overrides = _load_overrides()
    tokens = overrides.get('annuity_context_tokens', {}).get('tokens')
    if tokens and isinstance(tokens, dict):
        return tokens
    return dict(_DEFAULT_ANNUITY_CONTEXT_TOKENS)


ANNUITY_CONTEXT_TOKENS: Dict[str, str] = _get_annuity_context_tokens()
CONTEXT_GROUPS = sorted(set(ANNUITY_CONTEXT_TOKENS.values()))


def normalize_match_token(value: str) -> str:
    return re.sub(r'\s+', '', normalize_ws(value))


def split_context_key(key: str) -> List[str]:
    return [normalize_match_token(part) for part in normalize_ws(key).split(CONTEXT_KEY_SEPARATOR) if part]


def build_context_key(groups: Dict[str, Set[str]]) -> List[str]:
    keys: List[List[str]] = []
    for group in CONTEXT_GROUPS:
        value_set = groups.get(group, set())
        if value_set:
            keys.append(sorted(value_set))
    if not keys:
        return []

    combinations: List[Tuple[str, ...]] = [tuple()]
    for values in keys:
        next_combinations: List[Tuple[str, ...]] = []
        for base in combinations:
            for value in values:
                next_combinations.append(base + (value,))
        combinations = next_combinations

    return [CONTEXT_KEY_SEPARATOR.join(group) for group in combinations]


def extract_context_tokens(line: str) -> Dict[str, Set[str]]:
    found: Dict[str, Set[str]] = {}
    for token, group in ANNUITY_CONTEXT_TOKENS.items():
        if token in line:
            found.setdefault(group, set()).add(token)
    return found


def extract_row_context_tokens(row: dict) -> Set[str]:
    row_fields = [
        row.get('상품명', ''),
        row.get('상품명칭', ''),
        row.get('세부종목1', ''),
        row.get('세부종목2', ''),
        row.get('세부종목3', ''),
        row.get('세부종목4', ''),
    ]
    merged = normalize_ws(' '.join(str(value) for value in row_fields if value))
    tokens: Set[str] = set()

    # 직접 매핑되는 분류키워드 추출
    for token in ANNUITY_CONTEXT_TOKENS.keys():
        if token in merged:
            tokens.add(normalize_match_token(token))

    # 연금개시나이표현에 등장하는 기간 항목(예: 10년) 보정
    for m in re.finditer(r'(\d{1,3})년', merged):
        tokens.add(normalize_match_token(f'{m.group(1)}년'))
    return tokens


def is_annuity_section_header(prev_line: str, line: str, next_line: str) -> bool:
    if '연금개시나이' not in line:
        return False
    # "라. 연금개시나이" 또는 "라. 연금개시나이 : 만 55세~80세"
    # 단, "라. 피보험자 가입나이 및 연금개시나이" 같은 복합 헤더는 제외
    if re.match(r'^[가-힣]\.\s*연금개시나이', line) and '가입나이' not in line:
        return True
    # "마. 연금개시나이"
    if line == '연금개시나이' and ('연금개시나이' in prev_line or '연금개시' in prev_line):
        return True
    # 연금개시나이 다음에 구분/종신연금형 등 테이블
    if line == '연금개시나이' and ('구분' in next_line or '종신연금형' in next_line or '확정기간연금형' in next_line):
        return True
    # "(2) 연금개시나이" 패턴
    if re.match(r'^\(\d+\)\s*연금개시나이$', line):
        return True
    return False


def add_values(target: Dict[str, List[str]], gender: str, values: Sequence[str]) -> None:
    key = normalize_ws(gender)
    if key == '남자':
        append_unique(target.setdefault('남자', []), values)
    elif key == '여자':
        append_unique(target.setdefault('여자', []), values)
    else:
        append_unique(target.setdefault('', []), values)


def assign_context_values(category_values: Dict[str, Dict[str, List[str]]],
                         context_keys: Sequence[str],
                         gender: str,
                         values: Sequence[str]) -> None:
    if not context_keys:
        return
    for key in context_keys:
        bucket = category_values.setdefault(key, {})
        add_values(bucket, gender, values)


def age_range_list(start: int, end: int) -> List[str]:
    if start > end:
        start, end = end, start
    if end - start > 120:
        return [str(start), str(end)]
    return [str(y) for y in range(start, end + 1)]


def append_unique(target: List[str], values: Sequence[str]) -> None:
    for v in values:
        if v and v not in target:
            target.append(v)


def split_age_values(text: str) -> List[str]:
    text = normalize_ws(text)
    # 표기 확장: 전각 물결/긴 대시/기타 변형을 표준 구분자(~)로 정규화
    text = text.replace('\u223c', '~').replace('\uff5e', '~').replace('\u301c', '~')
    text = text.replace('\u2010', '-').replace('\u2012', '-').replace('\u2013', '-')
    text = text.replace('\u2014', '-')
    raw: List[str] = []

    # 범위(45세~80세)
    for m in re.finditer(r'(\d{2,3})\s*세?\s*[~\-]\s*(\d{2,3})\s*세?', text):
        append_unique(raw, age_range_list(int(m.group(1)), int(m.group(2))))

    # 범위(45세 이상 ~ 80세 이하, 45세 이상 80세 이하)
    for m in re.finditer(r'(\d{2,3})\s*세\s*이상\s*(?:이고\s*)?(?:연금개시나이\s*)?[,\s]*(\d{2,3})\s*세\s*이하', text):
        append_unique(raw, age_range_list(int(m.group(1)), int(m.group(2))))

    for m in re.finditer(r'(\d{2,3})\s*세\s*이상\s*[,\s]*(\d{2,3})\s*세\s*이하', text):
        append_unique(raw, age_range_list(int(m.group(1)), int(m.group(2))))

    # 쉼표 열거(60세,70세,80세)
    for m in re.finditer(r'\d{2,3}세(?:\s*,\s*\d{2,3}세)+', text):
        for n in re.finditer(r'(\d{2,3})세', m.group(0)):
            append_unique(raw, [n.group(1)])

    # 슬래시 및 "및" 열거(45세/46세/47세, 45세 및 46세)
    for m in re.finditer(r'(?:\d{2,3}세(?:\s*(?:/|및|또는)\s*)+)+', text):
        for n in re.finditer(r'(\d{2,3})세', m.group(0)):
            append_unique(raw, [n.group(1)])

    # 단일 나이
    if '연금개시나이' in text and ('~' in text or '-' in text):
        if '이상' in text or '이하' in text or ('(' in text and ')' in text and '납입' in text):
            # 이미 위에서 범위를 먼저 수집했거나, 연금개시나이 산식 형태면 단일 추출 생략
            return raw
    for m in re.finditer(r'(?:^|[^\d~])((?:만\s*)?(\d{2,3})세)\b', text):
        append_unique(raw, [m.group(2)])

    return raw


def pick_best_annuity_values(blocks: Sequence[Dict[str, object]]) -> Dict[str, List[str]]:
    best: Dict[str, Set[str]] = {
        '남자': set(),
        '여자': set(),
        '': set(),
    }
    for block in blocks:
        for gender in ('남자', '여자', ''):
            values = block.get('generic_values', {}).get(gender, []) if isinstance(block.get('generic_values', {}), dict) else []
            if values:
                best[gender].update(values)

        # category map에서 남자/여자/공통 값을 모두 반영
        for category_values in block.get('category_values', {}).values():
            for gender in ('남자', '여자'):
                values = category_values.get(gender, [])
                if values:
                    best[gender].update(values)

            if '' in category_values:
                values = category_values.get('')
                if values:
                    best[''].update(values)
    return {
        '남자': sorted(best['남자'], key=lambda x: int(x) if x.isdigit() else float('inf')),
        '여자': sorted(best['여자'], key=lambda x: int(x) if x.isdigit() else float('inf')),
        '': sorted(best[''], key=lambda x: int(x) if x.isdigit() else float('inf')),
    }


def age_token_to_value(token: str) -> str:
    token = normalize_ws(token)
    if not token:
        return ''
    m = re.match(r'(\d{2,3})(?:세)?', token)
    return m.group(1) if m else ''


def to_entry(value: str, code: str) -> Tuple[str, str, str]:
    if not value:
        return '', '', ''
    return f'{code}{value}', code, value


def to_x_age(value: str) -> Tuple[str, str, str]:
    return to_entry(value, 'X')


def to_n_age(value: str) -> Tuple[str, str, str]:
    return to_entry(value, 'N')


def empty_record(gender: str) -> Dict[str, str]:
    return {
        '제1보기개시나이': '',
        '제1보기개시나이구분코드': '',
        '제1보기개시나이값': '',
        '제2보기개시나이': '',
        '제2보기개시나이구분코드': '',
        '제2보기개시나이값': '',
        '제3보기개시나이': '',
        '제3보기개시나이구분코드': '',
        '제3보기개시나이값': '',
        '성별': gender,
    }


def build_empty_output(row: dict) -> Dict[str, object]:
    return {
        '상품명칭': normalize_ws(str(row.get('상품명칭', ''))),
        '상품명': normalize_ws(str(row.get('상품명', ''))),
        '보기개시나이정보': [empty_record('')],
    }


def extract_annuity_blocks(lines: Sequence[str]) -> List[Dict[str, object]]:
    blocks: List[Dict[str, object]] = []
    for i, line in enumerate(lines):
        prev_line = lines[i - 1] if i > 0 else ''
        next_line = lines[i + 1] if i + 1 < len(lines) else ''
        if not is_annuity_section_header(prev_line, line, next_line):
            continue

        block_lines: List[str] = []

        # 인라인 값 처리: "라. 연금개시나이 : 만 55세~80세"
        inline_m = re.search(r'연금개시나이\s*[:：]\s*(.+)', line)
        if inline_m:
            block_lines.append(inline_m.group(1).strip())

        for j in range(i + 1, len(lines)):
            nxt = lines[j]
            if nxt == '연금개시나이':
                continue
            if (re.match(r'^[가-하]+\.', nxt)
                    or re.match(r'^\([가-하]+\)', nxt)
                    or re.match(r'^\d+\.', nxt)
                    or (re.match(r'^[①-⑳]+\)', nxt) and len(nxt) > 2)):
                break
            block_lines.append(nxt)

        if not block_lines:
            continue

        gender_header = False
        category_to_values: Dict[str, Dict[str, List[str]]] = {}
        generic_values: Dict[str, List[str]] = {}
        generic_seen: List[str] = []
        context_state: Dict[str, Set[str]] = {}

        for bl in block_lines:
            context_update = extract_context_tokens(bl)
            for group, values in context_update.items():
                context_state[group] = values.copy()

            if '남자' in bl and '여자' in bl:
                gender_header = True
                continue

            vals = split_age_values(bl)
            if not vals:
                continue

            is_range = bool(re.search(r'\d{2,3}\s*세?\s*[~\-]\s*\d{2,3}', bl)) or ('이상' in bl and '이하' in bl) or len(vals) > 2
            context_keys = build_context_key(context_state)
            has_context = bool(context_keys)

            if '남자' in bl and '여자' not in bl:
                assign = vals if is_range else [vals[0]]
                if has_context:
                    assign_context_values(category_to_values, context_keys, '남자', assign)
                else:
                    add_values(generic_values, '남자', assign)
                continue
            if '여자' in bl and '남자' not in bl:
                assign = vals if is_range else [vals[0]]
                if has_context:
                    assign_context_values(category_to_values, context_keys, '여자', assign)
                else:
                    add_values(generic_values, '여자', assign)
                continue

            if len(vals) >= 2 and not is_range:
                if gender_header:
                    if has_context:
                        assign_context_values(category_to_values, context_keys, '남자', [vals[0]])
                        assign_context_values(category_to_values, context_keys, '여자', [vals[1]])
                    else:
                        append_unique(generic_values.setdefault('남자', []), [vals[0]])
                        append_unique(generic_values.setdefault('여자', []), [vals[1]])
                    continue
                if has_context:
                    assign_context_values(category_to_values, context_keys, '남자', [vals[0]])
                    assign_context_values(category_to_values, context_keys, '여자', [vals[1]])
                else:
                    append_unique(generic_values.setdefault('남자', []), [vals[0]])
                    append_unique(generic_values.setdefault('여자', []), [vals[1]])
                continue

            if len(vals) >= 1 and is_range:
                # 성별 테이블에서 2개 범위가 한 줄에 있으면 남/여 분리
                if gender_header:
                    ranges = re.findall(r'(\d{2,3})\s*세?\s*[~\-]\s*(\d{2,3})', bl)
                    if len(ranges) >= 2:
                        male_vals = split_age_values(f'{ranges[0][0]}~{ranges[0][1]}')
                        female_vals = split_age_values(f'{ranges[1][0]}~{ranges[1][1]}')
                        if has_context:
                            assign_context_values(category_to_values, context_keys, '남자', male_vals)
                            assign_context_values(category_to_values, context_keys, '여자', female_vals)
                        else:
                            append_unique(generic_values.setdefault('남자', []), male_vals)
                            append_unique(generic_values.setdefault('여자', []), female_vals)
                        continue
                if has_context:
                    assign_context_values(category_to_values, context_keys, '', vals)
                else:
                    append_unique(generic_values.setdefault('', []), vals)
                continue

            if len(vals) == 1 and gender_header:
                first = vals[0]
                if len(generic_seen) < 2:
                    gender = '남자' if len(generic_seen) == 0 else '여자'
                    if has_context:
                        assign_context_values(category_to_values, context_keys, gender, [first])
                    else:
                        append_unique(generic_values.setdefault(gender, []), [first])
                    generic_seen.append(gender)
                continue

            if len(vals) == 1 and not gender_header:
                if has_context:
                    assign_context_values(category_to_values, context_keys, '', [vals[0]])
                else:
                    append_unique(generic_values.setdefault('', []), vals)

            if len(vals) > 1 and not is_range and not generic_values:
                generic_seen.extend(['남자', '여자'])
                append_unique(generic_values.setdefault('남자', []), [vals[0]])
                append_unique(generic_values.setdefault('여자', []), [vals[1]])

        blocks.append({
            'generic_values': generic_values,
            'category_values': category_to_values,
        })
    return blocks


def to_age_value(token: str) -> str:
    token = normalize_ws(token)
    if '~' in token:
        token = token.split('~')[0]
    if ',' in token:
        token = token.split(',')[0]
    m = re.match(r'^(\d{2,3})', token)
    return m.group(1) if m else age_token_to_value(token)


def pick_annuity_values_for_row(row: dict, blocks: Sequence[Dict[str, object]]) -> Dict[str, List[str]]:
    if not blocks:
        return {'남자': [], '여자': [], '': []}

    text = row_text(row)
    if not text:
        # fallback: use first detected generic values
        first = pick_best_annuity_values(blocks)
        return {
            '남자': list(first.get('남자', [])),
            '여자': list(first.get('여자', [])),
            '': list(first.get('', [])),
        }

    row_tokens = extract_row_context_tokens(row)

    best_scores: Optional[Tuple[int, ...]] = None
    best_values: Optional[Dict[str, List[str]]] = None
    # 성별 분리된 값을 가진 최고 매치 (best_values가 공통만 가질 경우 보완용)
    # 남녀 값이 실제로 다른 매치를 우선 추적 (identical gender split은 공통과 동일)
    best_diff_gender_scores: Optional[Tuple[int, ...]] = None
    best_diff_gender_values: Optional[Dict[str, List[str]]] = None
    best_any_gender_scores: Optional[Tuple[int, ...]] = None
    best_any_gender_values: Optional[Dict[str, List[str]]] = None

    for block in blocks:
        category_map = block.get('category_values', {})
        if not isinstance(category_map, dict):
            continue

        for key, values in category_map.items():
            if not isinstance(values, dict):
                continue
            key_tokens = set(split_context_key(str(key)))
            if not key_tokens:
                continue
            overlap = key_tokens & row_tokens
            if not overlap:
                continue

            is_exact = key_tokens.issubset(row_tokens)
            male = values.get('남자', [])
            female = values.get('여자', [])
            common = values.get('', [])
            # table-source 블록에 보너스 점수 부여 (열 헤더 기반이므로 더 정확)
            source_bonus = 1 if block.get('_source') == 'table' else 0
            # 점수: is_exact > overlap 수 > source_bonus > key 길이
            score = (2 if is_exact else 0, len(overlap), source_bonus, len(key_tokens))
            entry = {
                '남자': list(male) if isinstance(male, list) else [male],
                '여자': list(female) if isinstance(female, list) else [female],
                '': list(common) if isinstance(common, list) else [common],
            }
            if best_scores is None or score > best_scores:
                best_values = entry
                best_scores = score
            # 성별 분리 값이 있는 최고 매치를 별도로 추적
            if male or female:
                has_diff = set(male) != set(female)
                if has_diff and (best_diff_gender_scores is None or score > best_diff_gender_scores):
                    best_diff_gender_values = entry
                    best_diff_gender_scores = score
                if best_any_gender_scores is None or score > best_any_gender_scores:
                    best_any_gender_values = entry
                    best_any_gender_scores = score

    if best_values is not None:
        # best_values가 공통만 가지고 있지만 성별 분리 매치가 별도로 있으면 → 성별 값 사용
        bm = best_values.get('남자', [])
        bf = best_values.get('여자', [])
        if not bm and not bf:
            # 남녀 값이 실제로 다른 매치를 우선, 없으면 동일 성별 매치 사용
            gender_fallback = None
            gender_fb_scores = None
            if best_diff_gender_values is not None:
                gender_fallback = best_diff_gender_values
                gender_fb_scores = best_diff_gender_scores
            elif best_any_gender_values is not None:
                gender_fallback = best_any_gender_values
                gender_fb_scores = best_any_gender_scores
            if gender_fallback is not None and gender_fb_scores is not None:
                # 신부부형 상품: 성별 분리 값이 중요하므로 exact match여도 성별 매치 사용
                product_name = normalize_ws(str(row.get('상품명', '') or '') + ' ' + str(row.get('세부종목3', '') or ''))
                is_sinbubu = '신부부형' in product_name
                if is_sinbubu or gender_fb_scores >= best_scores:
                    best_values = gender_fallback
        return {
            '남자': list(best_values.get('남자', [])),
            '여자': list(best_values.get('여자', [])),
            '': list(best_values.get('', [])),
        }

    first = pick_best_annuity_values(blocks)
    if first:
        return {
            '남자': list(first.get('남자', [])),
            '여자': list(first.get('여자', [])),
            '': list(first.get('', [])),
        }

    return {
        '남자': list(first.get('남자', [])),
        '여자': list(first.get('여자', [])),
        '': list(first.get('', [])),
    }


def extract_escalation_pairs(lines: Sequence[str]) -> List[Dict[str, object]]:
    results: List[Dict[str, object]] = []

    # ── 패턴 1: 체증경과년수 트리거 (duration 방식: start + end = total) ──
    for i, line in enumerate(lines):
        if '체증경과년수' not in line:
            continue

        start = ''
        end = ''
        context = ' '.join(lines[max(0, i - 3):min(len(lines), i + 25)])

        # 우선 현재 라인 및 주변 라인에서 시작/종료 연수 탐색
        for j in range(max(0, i - 6), min(len(lines), i + 6)):
            m_start = re.search(r'(\d{1,3})\s*년\s*경과', lines[j])
            if m_start:
                start = m_start.group(1)
                break

        if not start:
            m_start = re.search(r'(\d{1,3})\s*년\s*경과', context)
            if m_start:
                start = m_start.group(1)

        # "10년을 최대로 함" 형태
        for j in range(i, min(len(lines), i + 20)):
            candidate = lines[j]
            m_end = re.search(r'(\d{1,3})\s*년을\s*최대로', candidate)
            if m_end:
                end = m_end.group(1)
                break
            m_end = re.search(r'최대로.*?(\d{1,3})\s*년', candidate)
            if m_end:
                end = m_end.group(1)
                break

        if not end:
            m_end = re.search(r'(\d{1,3})\s*년을\s*최대로', context)
            if m_end:
                end = m_end.group(1)
            else:
                m_end = re.search(r'최대로.*?(\d{1,3})\s*년', context)
                if m_end:
                    end = m_end.group(1)

        if not start and not end:
            continue

        results.append({
            'start': start,
            'end': end,
            'absolute': False,  # end는 duration → merge 시 start+end
            'context': context,
        })

    # ── 패턴 2: 기준사망보험금 테이블 (absolute 방식: end 그대로 사용) ──
    # "A년 경과시점 계약해당일부터 B년 경과시점" 패턴
    # 종 번호별 구간을 분리하여 각각의 context를 가짐
    seen_abs: Set[Tuple[str, str, str]] = set()  # (start, end, context_hash)
    for i, line in enumerate(lines):
        if '기준사망보험금' not in line:
            continue
        # 기준사망보험금 이후 종별 구간 분리: (가), (나) 등
        block_end = min(len(lines), i + 60)
        # 종 구간 시작점 찾기
        type_sections: List[Tuple[int, str]] = []
        for j in range(i, block_end):
            # 패턴A: (가) 1종, (나) 2종 등
            m_type = re.search(r'\([가-힣]\)\s*(\d종)', lines[j])
            if m_type:
                type_sections.append((j, m_type.group(1)))
                continue
            # 패턴B: "1종(해약환급금...)" — (가) 없이 종 라벨이 직접 나오는 경우
            m_type2 = re.match(r'^\s*(\d종)\s*\(', lines[j])
            if m_type2:
                type_sections.append((j, m_type2.group(1)))
        if not type_sections:
            continue  # 종 구간이 없으면 기준사망보험금 테이블이 아님
        for idx, (sec_start, _) in enumerate(type_sections):
            if idx + 1 < len(type_sections):
                sec_end = type_sections[idx + 1][0]
            else:
                sec_end = block_end
            sec_text = ' '.join(lines[sec_start:sec_end])
            matches = re.finditer(
                r'(\d{1,3})\s*년\s*경과시점\s*계약해당일\s*부터\s*(\d{1,3})\s*년\s*경과시점',
                sec_text,
            )
            for m in matches:
                start_val, end_val = m.group(1), m.group(2)
                dedup_key = (start_val, end_val, sec_text[:100])
                if dedup_key in seen_abs:
                    continue
                seen_abs.add(dedup_key)
                results.append({
                    'start': start_val,
                    'end': end_val,
                    'absolute': True,
                    'context': sec_text,
                })

    return results


def is_life_or_escalation_row(row: dict) -> bool:
    text = row_text(row)
    if not text:
        return False
    return '종신' in text or '체증' in text or '보장형' in text


def is_annuity_row(row: dict) -> bool:
    text = row_text(row)
    if not text:
        return False
    return '연금' in text or '연금저축' in text or '직장인연금' in text


def pick_escalation_for_row(row: dict, escalations: Sequence[Dict[str, object]]) -> Tuple[str, str, bool]:
    """Returns (start, end, absolute)."""
    if not escalations:
        return '', '', False
    candidates = list(escalations)
    text = row_text(row)
    if '스마트전환형' in text:
        smart = [x for x in candidates if '전환일' in x.get('context', '')]
        if smart:
            candidates = smart
    else:
        contract = [x for x in candidates if '계약일' in x.get('context', '')]
        if contract:
            candidates = contract

    # 종 번호 매칭 (1종, 2종 등)
    if len(candidates) > 1:
        m = re.search(r'(\d)종', text)
        if m:
            type_num = m.group(1)
            type_matched = [x for x in candidates if f'{type_num}종' in x.get('context', '')]
            if type_matched:
                candidates = type_matched

    chosen = candidates[0]
    return chosen.get('start', ''), chosen.get('end', ''), bool(chosen.get('absolute', False))


def merge_records(row: dict, lines: Sequence[str], annuity_blocks: Sequence[Dict[str, object]],
                  escalations: Sequence[Dict[str, object]]) -> Dict[str, object]:
    output = {
        '상품명칭': normalize_ws(str(row.get('상품명칭', ''))),
    }
    # 세부종목 필드 복사
    for key in sorted(row.keys()):
        if key.startswith('세부종목'):
            output[key] = row[key]
    output['상품명'] = normalize_ws(str(row.get('상품명', '')))
    output['보기개시나이정보'] = []

    if is_annuity_row(row):
        ages = pick_annuity_values_for_row(row, annuity_blocks)
        if not ages.get('남자') and not ages.get('여자') and not ages.get('', []):
            # 공통 항목
            rec = empty_record('')
            output['보기개시나이정보'].append(rec)
            return output

        male_vals = list(ages.get('남자', []) or [])
        female_vals = list(ages.get('여자', []) or [])
        common_vals = list(ages.get('', []) or [])
        # 남녀 동일 range이면 공통으로 통합
        if male_vals and female_vals and set(male_vals) == set(female_vals):
            # 공통에 합치고 남/여 제거
            for v in male_vals:
                if v not in common_vals:
                    common_vals.append(v)
            male_vals = []
            female_vals = []
            ages[''] = common_vals
            ages['남자'] = []
            ages['여자'] = []
        # 남녀 별도 값 + 공통 값이 동시에 있는 경우: 상품 유형에 따라 결정
        elif (male_vals or female_vals) and common_vals:
            product_name = normalize_ws(str(row.get('상품명', '')))
            if '신부부형' in product_name:
                # 신부부형: 성별 구분 유지, 공통 제거
                common_vals = []
                ages[''] = []
            else:
                # 개인형/상속연금형 등: 공통 유지, 성별 제거
                male_vals = []
                female_vals = []
                ages['남자'] = []
                ages['여자'] = []

        for gender in ('남자', '여자'):
            for value in ages.get(gender, []) or []:
                rec = empty_record(gender)
                value = to_age_value(value)
                if not value:
                    continue
                rec['제2보기개시나이'] = f'X{value}'
                rec['제2보기개시나이구분코드'] = 'X'
                rec['제2보기개시나이값'] = value
                output['보기개시나이정보'].append(rec)

        # 성별 헤더가 없고 값만 존재하는 경우
        for value in ages.get('', []) or []:
            rec = empty_record('')
            value = to_age_value(value)
            if not value:
                continue
            rec['제2보기개시나이'] = f'X{value}'
            rec['제2보기개시나이구분코드'] = 'X'
            rec['제2보기개시나이값'] = value
            output['보기개시나이정보'].append(rec)

        if not output['보기개시나이정보']:
            output['보기개시나이정보'].append(empty_record(''))
        return output

    if is_life_or_escalation_row(row):
        start, end, absolute = pick_escalation_for_row(row, escalations)
        rec = empty_record('')
        if start:
            code = 'N'
            rec['제2보기개시나이'] = f'{code}{start}'
            rec['제2보기개시나이구분코드'] = code
            rec['제2보기개시나이값'] = start
        if end:
            code = 'N'
            if absolute:
                # 절대값: "3년경과시점부터 8년경과시점까지" → end 그대로
                end_val = end
            else:
                # duration: "5년경과 + 10년 최대" → start + end
                try:
                    end_val = str(int(start) + int(end))
                except (ValueError, TypeError):
                    end_val = end
            rec['제3보기개시나이'] = f'{code}{end_val}'
            rec['제3보기개시나이구분코드'] = code
            rec['제3보기개시나이값'] = end_val
        output['보기개시나이정보'].append(rec)
        return output

    # 기본값: 1보기개시나이가 공란인 상태 유지
    output['보기개시나이정보'].append(empty_record(''))
    return output


def make_report(items: List[Dict[str, object]]) -> Dict[str, object]:
    def has_value_entry(entry: Dict[str, str]) -> bool:
        for key in ('제1보기개시나이', '제2보기개시나이', '제3보기개시나이'):
            if entry.get(key):
                return True
        return False

    file_stats: List[Dict[str, object]] = []
    total_rows = 0
    total_results = 0
    for item in items:
        file_name = item.get('file')
        rows = item.get('rows', [])
        count_rows = len(rows)
        count_results = 0
        for row in rows:
            entries = row.get('보기개시나이정보', [])
            if not entries:
                continue
            count_results += sum(1 for item in entries if isinstance(item, dict) and has_value_entry(item))
        total_rows += count_rows
        total_results += count_results
        file_stats.append({
            '파일명': file_name,
            '입력_상품수': count_rows,
            '보기개시나이항목수': count_results,
        })

    return {
        '총_파일수': len(items),
        '총_입력_상품수': total_rows,
        '총_보기개시나이항목수': total_results,
        '파일별_요약': file_stats,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description='보기개시나이 추출기 (v2 - Word docx)')
    parser.add_argument('--docx-dir', type=Path, default=DEFAULT_DOCX_DIR)
    parser.add_argument('--json-dir', type=Path, default=DEFAULT_JSON_DIR)
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument('--report-path', type=Path, default=DEFAULT_REPORT_PATH)
    parser.add_argument('--docx', type=str, help='single docx path to process')
    parser.add_argument('--json', type=str, help='single JSON path (product classification) to process')
    parser.add_argument('--output', type=str, help='single output JSON path if --docx and --json are used')
    parser.add_argument('--dry-run', action='store_true')
    return parser.parse_args()


def gather_pairs(docx_dir: Path, json_dir: Path) -> List[Tuple[Path, Path]]:
    json_map = {normalize_name(p.name): p for p in json_dir.glob('*.json')}
    pairs = []
    for docx_file in docx_dir.glob('*.docx'):
        key = normalize_name(docx_file.name)
        json_path = json_map.get(key)
        if json_path:
            pairs.append((docx_file, json_path))
    return pairs


def _load_annuity_blocks(docx_path: Path) -> List[Dict[str, object]]:
    """테이블 기반 블록(우선) + 텍스트 기반 블록(보완)을 합산하여 반환."""
    lines, tables = extract_docx_content(docx_path)
    table_blocks = extract_annuity_blocks_from_tables(tables)
    text_blocks = extract_annuity_blocks(lines)
    useful_table_blocks = []
    for tb in table_blocks:
        cat_vals = tb.get('category_values', {})
        if len(cat_vals) >= 2:
            useful_table_blocks.append(tb)
    return useful_table_blocks + text_blocks


def _apply_annuity_overrides(results: List[dict]) -> List[dict]:
    """product_overrides.json의 annuity_age override 적용."""
    overrides = _load_overrides()
    aa_overrides = overrides.get('annuity_age_overrides', {})
    if not aa_overrides:
        return results

    for r in results:
        product_name = r.get('상품명', '')
        ages = r.get('보기개시나이정보', [])

        for key, cfg in aa_overrides.items():
            if key.startswith('_'):
                continue
            keywords = key.split('+')
            if not all(kw in product_name for kw in keywords):
                continue

            action = cfg.get('action', 'fixed')

            if action == 'fixed':
                r['보기개시나이정보'] = list(cfg.get('ages', []))

            elif action == 'gender_split':
                # 성별 없는 entries를 남/여로 분리
                male_min = int(cfg.get('male_min_age', '0'))
                female_max = int(cfg.get('female_max_age', '9999'))
                new_ages = []
                for a in ages:
                    if a.get('성별'):
                        new_ages.append(a)
                        continue
                    val = a.get('제2보기개시나이값', '')
                    if not val:
                        continue
                    age_val = int(val)
                    # 여자 entry (only if <= female_max)
                    if age_val <= female_max:
                        import copy
                        f_rec = copy.deepcopy(a)
                        f_rec['성별'] = '여자'
                        new_ages.append(f_rec)
                    # 남자 entry (only if >= male_min)
                    if age_val >= male_min:
                        import copy
                        m_rec = copy.deepcopy(a)
                        m_rec['성별'] = '남자'
                        new_ages.append(m_rec)
                r['보기개시나이정보'] = new_ages
            break

    return results


def run(docx_dir: Path, json_dir: Path, output_dir: Path, report_path: Optional[Path]) -> Tuple[int, int]:
    pairs = gather_pairs(docx_dir, json_dir)
    outputs = []
    output_dir.mkdir(parents=True, exist_ok=True)
    for docx_path, json_path in sorted(pairs, key=lambda x: x[1].name):
        lines, _ = extract_docx_content(docx_path)
        annuity_blocks = _load_annuity_blocks(docx_path)
        escalations = extract_escalation_pairs(lines)

        with json_path.open('r', encoding='utf-8') as f:
            rows = json.load(f)
        if not isinstance(rows, list):
            rows = []

        result_rows = []
        for row in rows:
            if not isinstance(row, dict):
                row = {'상품명칭': '', '상품명': ''}
            result_rows.append(merge_records(row, lines, annuity_blocks, escalations))

        result_rows = _apply_annuity_overrides(result_rows)
        outputs.append({'file': json_path.name, 'rows': result_rows})
        out_path = output_dir / json_path.name
        with out_path.open('w', encoding='utf-8') as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)

    report = make_report(outputs)
    if report_path is None:
        report_path = output_dir / 'report.json'
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with report_path.open('w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    return len(pairs), sum(len(item.get('rows', [])) for item in outputs)


if __name__ == '__main__':
    args = parse_args()
    if args.dry_run:
        print('pairs:', len(gather_pairs(args.docx_dir, args.json_dir)))
    elif args.docx and args.json:
        docx_path = Path(args.docx)
        json_path = Path(args.json)
        output_path = Path(args.output) if args.output else args.output_dir / json_path.name

        lines, _ = extract_docx_content(docx_path)
        annuity_blocks = _load_annuity_blocks(docx_path)
        escalations = extract_escalation_pairs(lines)

        with json_path.open('r', encoding='utf-8') as f:
            rows = json.load(f)
        if not isinstance(rows, list):
            rows = []

        result_rows = []
        for row in rows:
            if not isinstance(row, dict):
                row = {'상품명칭': '', '상품명': ''}
            result_rows.append(merge_records(row, lines, annuity_blocks, escalations))

        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open('w', encoding='utf-8') as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)

        print(f'{docx_path.name} + {json_path.name} -> {output_path.name} ({len(result_rows)} items)')
    else:
        processed_files, processed_rows = run(args.docx_dir, args.json_dir, args.output_dir, args.report_path)
        print(f'processed_files={processed_files}')
        print(f'processed_rows={processed_rows}')
