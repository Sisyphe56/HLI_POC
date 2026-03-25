#!/usr/bin/env python3
"""가입가능보기납기 추출기.

보험기간, 납입기간, 제2보기개시나이(연금상품) 정보를 PDF에서 추출합니다.

Usage:
    python extract_insurance_period.py                          # 전체 디렉토리
    python extract_insurance_period.py --pdf X.pdf --json X.json  # 단일 파일
"""
import argparse
import json
import re
import unicodedata
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import pdfplumber


ROOT = Path(__file__).resolve().parent
DEFAULT_PDF_DIR = (Path('/Users/iseonglyeol/workspace_codex/사업방법서')
                   if (Path('/Users/iseonglyeol/workspace_codex/사업방법서')).exists()
                   else ROOT / '사업방법서')
DEFAULT_JSON_DIR = ROOT / '상품분류'
DEFAULT_OUTPUT_DIR = ROOT / '가입가능보기납기'
DEFAULT_REPORT_PATH = DEFAULT_OUTPUT_DIR / 'report.json'
OVERRIDES_PATH = ROOT / 'config' / 'product_overrides.json'


def _load_overrides() -> dict:
    if OVERRIDES_PATH.exists():
        with OVERRIDES_PATH.open('r', encoding='utf-8') as f:
            return json.load(f)
    return {}


# ──────────────────────────────────────────────
# 유틸
# ──────────────────────────────────────────────

def normalize_ws(value: str) -> str:
    text = unicodedata.normalize('NFC', value or '')
    text = text.replace('\u200b', '')
    # PDF 문자 중복 제거 (99990000 → 90 등)
    text = re.sub(r'(.)\1{3}', r'\1', text)
    return re.sub(r'\s+', ' ', text).strip()


def normalize_name(value: str) -> str:
    text = normalize_ws(value)
    text = re.sub(r'\.pdf$', '', text)
    text = re.sub(r'\.json$', '', text)
    text = re.sub(r'\s+', '', text)
    return text


def format_period(text: str) -> Tuple[str, str, str]:
    """기간 텍스트를 (코드, 구분코드, 값) 으로 변환."""
    text = normalize_ws(text)
    if not text:
        return '', '', ''
    if '종신' in text:
        return 'A999', 'A', '999'
    if '일시납' in text:
        return 'N0', 'N', '0'
    m = re.search(r'(\d+)\s*세', text)
    if m:
        return f'X{m.group(1)}', 'X', m.group(1)
    m = re.search(r'(\d+)\s*년', text)
    if m:
        return f'N{m.group(1)}', 'N', m.group(1)
    m = re.search(r'^(\d+)$', text.strip())
    if m:
        return f'N{m.group(1)}', 'N', m.group(1)
    return '', '', ''


def is_annuity_product(row: dict) -> bool:
    text = ' '.join(str(v) for v in row.values() if v)
    return '연금' in text


# ──────────────────────────────────────────────
# PDF 텍스트 추출
# ──────────────────────────────────────────────

def extract_pdf_content(pdf_path: Path) -> Tuple[List[str], List[List]]:
    """PDF에서 텍스트 라인과 모든 페이지 표를 함께 추출."""
    lines: List[str] = []
    all_tables: List[List] = []
    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages[:30]:
            raw = page.extract_text() or ''
            for line in raw.splitlines():
                normalized = normalize_ws(line)
                if normalized:
                    lines.append(normalized)
            all_tables.extend(page.extract_tables() or [])
    return lines, all_tables


def extract_pdf_lines(pdf_path: Path) -> List[str]:
    return extract_pdf_content(pdf_path)[0]


# ──────────────────────────────────────────────
# 테이블 기반 context→납입기간 매핑
# ──────────────────────────────────────────────

def _extract_context_period_map(
    all_tables: List[List],
    period_type: str = '납입기간',
) -> Dict[str, Set[str]]:
    """PDF 테이블에서 context(종/일부지급형/계약유형) → 납입기간 집합을 추출.

    Returns: {context_key: {period_value, ...}, ...}
    예: {'1종·일부지급형I': {'5년납'}, '적립형계약': {'종신'}}
    """
    result: Dict[str, Set[str]] = {}

    for table in all_tables:
        if not table or not table[0]:
            continue
        header = normalize_ws(' '.join(str(c or '') for c in table[0]))
        if period_type not in header:
            continue

        # 이 테이블이 납입기간/보험기간 테이블
        # fill-down: 빈 셀은 위 행의 값을 상속
        prev_cells = [''] * max(len(r) for r in table)
        for ri in range(1, len(table)):
            row = table[ri]
            cells = []
            for ci in range(len(row)):
                val = normalize_ws(str(row[ci] or ''))
                if not val and ci < len(prev_cells):
                    val = prev_cells[ci]
                cells.append(val)
                if ci < len(prev_cells):
                    prev_cells[ci] = val

            # 마지막 유의미한 셀이 납입기간 값 (n년납, 종신납, 일시납 등)
            period_val = ''
            context_parts = []
            for ci, cell in enumerate(cells):
                cell_clean = cell.strip()
                if not cell_clean:
                    continue
                # 납입기간 패턴 감지 ("미만/이상/이하/초과" 조건절은 제외)
                if re.search(r'\d+년납|\d+세납|종신납|일시납', cell_clean) and \
                   not re.search(r'미만|이상|이하|초과', cell_clean):
                    period_val = cell_clean
                elif period_type == '보험기간' and re.search(r'종신|세만기|년만기|\d+세|\d+년', cell_clean):
                    period_val = cell_clean
                else:
                    context_parts.append(cell_clean)

            if not period_val:
                continue

            # context key 생성: 주요 토큰을 정규화하여 결합
            context_key = '·'.join(context_parts) if context_parts else '__default__'
            if context_key not in result:
                result[context_key] = set()
            result[context_key].add(period_val)

    return result


def _filter_periods_by_context(
    all_periods: Set[str],
    context_map: Dict[str, Set[str]],
    product_name: str,
) -> Set[str]:
    """상품명 기반으로 context_map에서 매칭되는 납입기간만 필터링.

    상품명의 토큰(종, 일부지급형 등)이 context_key에 매칭되면 해당 기간만 반환.
    매칭되는 context가 없으면 원본 전체 반환 (fallback).
    """
    if not context_map or not product_name:
        return all_periods

    name_norm = normalize_ws(product_name)
    matched_periods: Set[str] = set()

    # 일반형/상생협력형 분리: context에 둘 다 있으면 product name 키워드로 선택
    has_general = any('일반형' in k for k in context_map)
    has_sangsaeng = any('상생협력형' in k for k in context_map)
    name_has_sangsaeng = '상생' in name_norm

    # 상생 상품이면 상생협력형 context만 사용 (다른 context는 일반형 데이터)
    if has_general and has_sangsaeng and name_has_sangsaeng:
        for ctx_key, periods in context_map.items():
            if '상생협력형' in ctx_key:
                matched_periods.update(periods)
        # matched_periods가 있으면 아래 일반 매칭 스킵
    # 일반형 상품이면 상생협력형 context 제외
    if not (has_general and has_sangsaeng and name_has_sangsaeng):
        for ctx_key, periods in context_map.items():
            if ctx_key == '__default__':
                continue
            # 일반형/상생협력형 분리 시 상생협력형 context 스킵
            if has_general and has_sangsaeng and '상생협력형' in ctx_key:
                continue
            # context_key를 OR 대안으로 분리 (콤마 또는 · 구분)
            # "2종(50%환급형), 3종(70%환급형)" → ["2종(50%환급형)", "3종(70%환급형)"]
            alternatives = re.split(r'[,，]\s*', ctx_key)
            any_alt_match = False
            for alt in alternatives:
                # 각 대안 내부의 · 구분 토큰은 AND 조건
                ctx_tokens = [normalize_ws(t) for t in alt.split('·') if t.strip()]
                if not ctx_tokens:
                    continue
                all_match = True
                for token in ctx_tokens:
                    token_compact = re.sub(r'\s+', '', token)
                    name_compact = re.sub(r'\s+', '', name_norm)
                    if token_compact not in name_compact:
                        all_match = False
                        break
                if all_match:
                    any_alt_match = True
                    break
            if any_alt_match:
                matched_periods.update(periods)

    if not matched_periods:
        return all_periods  # fallback

    # matched_periods는 "5년납" 같은 원본 텍스트 → all_periods 형식 ("5년납")으로 필터
    filtered: Set[str] = set()
    for p in all_periods:
        p_text = p  # "5년납", "일시납" etc.
        for mp in matched_periods:
            mp_clean = re.sub(r'\s+', '', mp)
            p_clean = re.sub(r'\s+', '', p)
            # 숫자 기간은 정확히 매칭 (5년납 != 15년납 방지)
            p_num = re.match(r'^(\d+)', p_clean)
            mp_num = re.match(r'^(\d+)', mp_clean)
            if p_num and mp_num:
                # 숫자 부분이 같고 단위도 같아야 매칭
                p_suffix = p_clean[p_num.end():]
                mp_suffix = mp_clean[mp_num.end():]
                if p_num.group(1) == mp_num.group(1) and (
                    p_suffix == mp_suffix or p_suffix in mp_suffix or mp_suffix in p_suffix):
                    filtered.add(p)
                    break
            else:
                # 비숫자 기간 (종신납, 일시납 등): 기존 substring 매칭
                if p_clean in mp_clean or mp_clean in p_clean:
                    filtered.add(p)
                    break

    # context map에서 범위 확장된 기간 추가 (예: "5~10년" → 6년납, 8년납 등)
    # all_periods에는 없지만 context map에서 파생된 유효 기간
    for mp in matched_periods:
        mp_clean = re.sub(r'\s+', '', mp)
        if re.match(r'^\d+년납$', mp_clean):
            filtered.add(mp_clean)

    # 명시적으로 상생협력형/일반형이 분리된 경우 빈 결과도 신뢰
    # (전기납 only 상품은 pay_periods가 비어야 전기납만 생성됨)
    if has_general and has_sangsaeng and matched_periods:
        return filtered  # 빈 set 반환 허용
    return filtered if filtered else all_periods


def _extract_matrix_invalid_pairs(lines: List[str]) -> Set[Tuple[str, str]]:
    """매트릭스 테이블에서 '-' 표시된 (보험기간, 납입기간) 무효 조합 추출.

    PDF 텍스트에서 아래 패턴의 테이블을 파싱:
    구분     20년만기  60세만기  70세만기 ...
    5년납    O        O        O       ...
    20년납   -        O        O       ...
    전기납   -        O        O       ...

    Returns:
        Set of (insurance_period_text, payment_period_text) pairs that are invalid.
        예: {('20년', '20년납'), ('20년', '전기납'), ...}
    """
    invalid_pairs: Set[Tuple[str, str]] = set()

    for i, line in enumerate(lines):
        # 헤더행 탐지: "구분" + 여러 만기 칼럼
        if not re.match(r'^\s*구분\s', line):
            continue
        # 만기 칼럼 추출 (20년만기, 60세만기 등)
        header_periods = re.findall(r'(\d+(?:년|세))\s*만기', line)
        if len(header_periods) < 2:
            continue

        # 데이터행 파싱 (다음 줄부터)
        for j in range(i + 1, min(i + 20, len(lines))):
            data_line = lines[j].strip()
            if not data_line:
                continue
            # 납입기간 행인지 확인
            pay_m = re.match(r'^(\d+(?:년|세)\s*납|전기납|일시납)\s+(.+)', data_line)
            if not pay_m:
                break  # 더 이상 납입기간 행이 아님
            pay_label = re.sub(r'\s+', '', pay_m.group(1))
            rest = pay_m.group(2)

            # 각 칼럼 값 분리: 공백으로 분리 (각 셀은 "-" 또는 "만XX세~YY세" 등)
            cells = rest.strip().split()
            for col_idx, cell in enumerate(cells):
                if col_idx >= len(header_periods):
                    break
                cell_clean = cell.strip()
                if cell_clean == '-':
                    ins_label = header_periods[col_idx]
                    invalid_pairs.add((ins_label, pay_label))

        break  # 첫 번째 매트릭스 테이블만 사용

    return invalid_pairs


# ──────────────────────────────────────────────
# 보험기간/납입기간 추출
# ──────────────────────────────────────────────

def _find_period_section(lines: List[str]) -> Tuple[int, int]:
    """보험기간/납입기간 섹션의 시작/끝 인덱스를 찾는다."""
    start = -1
    for i, line in enumerate(lines):
        # "2. 보험기간, 보험료 납입기간" 등의 섹션 헤더
        if re.match(r'^\d+\.\s*보험기간', line) and '납입기간' in line:
            start = i
            break
        # "가. 보험기간" 헤더
        if re.match(r'^[가-힣]\.\s*보험기간', line):
            start = i
            break
    if start < 0:
        return 0, len(lines)  # fallback: 전체 범위

    # 다음 큰 섹션 시작까지
    start_num = re.match(r'^(\d+)\.', lines[start])
    end = len(lines)
    for j in range(start + 1, len(lines)):
        # 같은 레벨의 다음 번호 섹션 (3., 4. 등)
        m = re.match(r'^(\d+)\.', lines[j])
        if m and start_num and int(m.group(1)) > int(start_num.group(1)):
            end = j
            break
    # 종속특약/부가특약 서브섹션 경계에서 자르기 (주계약만 사용)
    for j in range(start + 1, end):
        if re.match(r'^나\.\s*(?:종속특약|부가특약)', lines[j]):
            end = j
            break
    return start, end


def extract_insurance_periods(lines: List[str], product_name: str = '') -> Set[str]:
    """보험기간 추출: n세만기 → Xn, n년만기 → Nn, 종신 → A999."""
    sec_start, sec_end = _find_period_section(lines)
    section = lines[sec_start:sec_end]

    periods: Set[str] = set()
    for line in section:
        # n세만기 패턴
        for m in re.finditer(r'(\d{2,3})\s*세\s*만기', line):
            periods.add(f'{m.group(1)}세')
        # n년만기 패턴
        for m in re.finditer(r'(\d{1,3})\s*년\s*만기', line):
            periods.add(f'{m.group(1)}년')
        # "보험기간 ... n년" 테이블 행 (만기 없이 단독)
        # 예: "보험기간 가입나이 납입기간" 다음 행에 "1년 50세~90세 전기납"
        if re.search(r'보험기간', line):
            for m in re.finditer(r'(?<!\d)(\d{1,3})\s*년(?!\s*[납만세])', line):
                periods.add(f'{m.group(1)}년')
        # 종신만기: "보험기간 : 종신" 또는 보험기간 섹션 내 독립 출현
        # 단, "종신 일시납 나이범위" 같은 데이터 행(표 셀)은 제외
        if re.search(r'종신만기|보험기간\s*[:：]?\s*종신', line):
            periods.add('종신')
        elif re.search(r'(?<![가-힣])종신(?!납|연금|보험|플랜|지급|보장|갱신)', line):
            # "종신 일시납/n년납/n세납/종신납" 패턴은 보험기간+납입기간 데이터 행이므로 제외
            # "구 분 종신" 패턴은 납입기간 테이블 헤더(종신납 컬럼)이므로 제외
            if (not re.search(r'(?<![가-힣])종신\s+(?:일시납|종신납|\d+년납|\d+세납)', line)
                    and not re.match(r'^구\s*분', line)):
                periods.add('종신')

    # 테이블 행에서 "보험기간" 헤더 다음 줄의 단독 "n년" 패턴
    for i in range(sec_start, sec_end):
        if '보험기간' in lines[i] and '가입나이' in lines[i] and '납입기간' in lines[i]:
            # 다음 줄들에서 테이블 데이터 추출
            for j in range(i + 1, min(i + 10, sec_end)):
                row_line = lines[j]
                if re.match(r'^[가-힣]\.', row_line) or re.match(r'^\d+\.', row_line):
                    break
                m = re.match(r'^(\d{1,3})\s*년\b', row_line)
                if m:
                    periods.add(f'{m.group(1)}년')

    # 테이블 헤더에서 "n세" 행 + 근처 행 "만기" 반복 패턴 (병합셀로 분리된 경우)
    # 예: "10년 60세 65세 70세 80세 90세 100세" / "구 분" / "만기 만기 만기 만기 만기 만기 만기"
    for i in range(sec_start, sec_end - 1):
        line = lines[i]
        # 현재 행에 n세 패턴이 다수 있는지 (~ 포함 행은 나이 범위이므로 제외)
        if '~' in line:
            continue
        age_matches = re.findall(r'(\d{2,3})\s*세(?!\s*만기)', line)
        if len(age_matches) < 2:
            continue
        # 1~3행 이내에 "만기"가 2회 이상 반복되는 행이 있는지
        found_manki = False
        for j in range(i + 1, min(i + 4, sec_end)):
            manki_count = len(re.findall(r'만기', lines[j]))
            if manki_count >= 2:
                found_manki = True
                break
        if found_manki:
            for age in age_matches:
                periods.add(f'{age}세')

    return periods


def extract_payment_periods(lines: List[str], product_name: str = '') -> Tuple[Set[str], bool]:
    """납입기간 추출: n년납 → Nn, n세납 → Xn, 전기납 여부."""
    sec_start, sec_end = _find_period_section(lines)
    section = lines[sec_start:sec_end]

    periods: Set[str] = set()
    has_jeonginap = False

    for line in section:
        # n~m년납 범위 패턴 (5~10년납 → 5,6,7,8,9,10년납)
        for m in re.finditer(r'(\d{1,3})\s*[~\-]\s*(\d{1,3})\s*년\s*납', line):
            lo, hi = int(m.group(1)), int(m.group(2))
            if lo <= hi <= 100:
                for y in range(lo, hi + 1):
                    periods.add(f'{y}년납')
        # n년납 패턴 (단독 — 범위 패턴에 이미 매칭된 부분은 중복이지만 set이라 무관)
        for m in re.finditer(r'(\d{1,3})\s*년\s*납', line):
            periods.add(f'{m.group(1)}년납')
        # n세납 패턴
        for m in re.finditer(r'(\d{2,3})\s*세\s*납', line):
            periods.add(f'{m.group(1)}세납')
        # 일시납: 특정 계약유형/조건에 한정된 경우(전환형, 거치형, 즉시형, 경우/단, 계좌이체/승계) 제외
        if '일시납' in line:
            is_table_data_row = bool(re.match(
                r'^(?:\d+세만기|\d+년만기|종신)\s+일시납', line))
            is_qualified = bool(re.search(
                r'(?:전환형|거치형|즉시형).{0,10}일시납'
                r'|일시납.{0,10}경우|단[,，].{0,40}일시납|경우.{0,10}일시납'
                r'|(?:계좌이체|승계).{0,15}일시납',
                line))
            if not is_table_data_row and not is_qualified:
                periods.add('일시납')
        # 전기납
        if '전기납' in line:
            has_jeonginap = True
        # 종신납 (납입기간 정의 컨텍스트에서만)
        if '종신납' in line and ('납입기간' in line or '납입주기' in line):
            periods.add('종신')

    return periods, has_jeonginap


def _extract_section_context_map(lines: List[str]) -> Dict[str, Dict[str, Set[str]]]:
    """섹션 내 "(N) context" 패턴을 기반으로 context → {보험기간, 납입기간} 매핑.

    "(1) 1종(순수형)" 다음 줄에 테이블/데이터가 오는 구조를 처리.
    Returns: {context_key: {'ins': {periods}, 'pay': {periods}}}
    """
    sec_start, sec_end = _find_period_section(lines)
    result: Dict[str, Dict[str, Set[str]]] = {}

    current_ctx = ''
    current_sub_ctx = ''  # (가)/(나) 하위 context
    for i in range(sec_start, sec_end):
        line = lines[i]

        # 주요 섹션 헤더 ("다.", "라.", "마." 등)를 만나면 context 리셋
        if re.match(r'^[가-힣]\.\s', line):
            current_ctx = ''
            current_sub_ctx = ''
            continue

        # "(1) 1종(순수형)" 또는 "(2) 2종(50%환급형), 3종, 4종" 패턴
        m = re.match(r'^\([\d]+\)\s*(.+)', line)
        if m:
            ctx_text = normalize_ws(m.group(1).rstrip())
            # "제(1)호" 같은 참조는 제외
            if re.match(r'^제\(?\d', ctx_text):
                continue
            current_ctx = ctx_text
            current_sub_ctx = ''  # 상위 context 변경 시 하위 리셋
            if current_ctx not in result:
                result[current_ctx] = {'ins': set(), 'pay': set()}
            continue

        # (가)/(나)/(다)/(라) 하위 context 패턴 (일반형/상생협력형 등)
        sub_m = re.match(r'^\(([가-힣])\)\s*(.+)', line)
        if sub_m:
            sub_text = normalize_ws(sub_m.group(2).rstrip())
            if sub_text and not re.match(r'^제\(?\d', sub_text):
                current_sub_ctx = sub_text
                effective = current_sub_ctx
                if effective not in result:
                    result[effective] = {'ins': set(), 'pay': set()}
                continue

        if not current_ctx and not current_sub_ctx:
            continue
        # 하위 context가 있으면 해당 key에 데이터 추가, 없으면 상위 key에 추가
        effective_ctx = current_sub_ctx if current_sub_ctx else current_ctx
        if effective_ctx not in result:
            result[effective_ctx] = {'ins': set(), 'pay': set()}

        # 이 줄에서 보험기간/납입기간 데이터 추출
        data = result[effective_ctx]

        # 보험기간: n년만기, n세만기, 종신
        for m2 in re.finditer(r'(\d{1,3})\s*년\s*만기', line):
            data['ins'].add(f'{m2.group(1)}년')
        for m2 in re.finditer(r'(\d{2,3})\s*세\s*만기', line):
            data['ins'].add(f'{m2.group(1)}세')
        if re.search(r'종신만기|보험기간\s*[:：]?\s*종신', line):
            data['ins'].add('종신')

        # 납입기간: n년납, n세납, 전기납, 일시납
        for m2 in re.finditer(r'(\d{1,3})\s*년\s*납', line):
            data['pay'].add(f'{m2.group(1)}년납')
        for m2 in re.finditer(r'(\d{2,3})\s*세\s*납', line):
            data['pay'].add(f'{m2.group(1)}세납')
        if '전기납' in line:
            data['pay'].add('전기납')
        if '일시납' in line:
            data['pay'].add('일시납')

    # 빈 context 제거
    return {k: v for k, v in result.items() if v['ins'] or v['pay']}


def _extract_text_context_pay_map(lines: List[str]) -> Dict[str, Set[str]]:
    """텍스트에서 context → 납입기간 매핑 추출.

    "(1) 1종(xxx) : 5년납, 7년납" 또는 "(가) 적립형 : 5~10년납, 15년납" 패턴 처리.
    """
    sec_start, sec_end = _find_period_section(lines)
    result: Dict[str, Set[str]] = {}

    # "납입기간" 섹션 헤더 이후의 context: period 패턴
    in_pay_section = False
    current_context = ''
    for i in range(sec_start, sec_end):
        line = lines[i]
        # "다. 주계약의 보험료 납입기간" 등의 헤더
        if re.match(r'^[가-힣]\.\s*.*납입기간', line) or re.match(r'^[가-힣]\.\s*보험료\s*납입기간', line):
            in_pay_section = True
            continue
        # 다음 섹션 시작
        if in_pay_section and re.match(r'^[가-힣]\.\s*(?!.*납입)', line):
            break

        if not in_pay_section:
            continue

        # "(1) 1종(xxx) : 5년납, 7년납, ..." 패턴
        m = re.match(r'^\(?[\d가-힣]+\)?\s*(.+?)\s*[:：]\s*(.+)', line)
        if m:
            ctx = normalize_ws(m.group(1))
            vals_text = m.group(2)
            periods: Set[str] = set()
            # 범위: 5~10년납 또는 5~10년 (납 없이)
            for rm in re.finditer(r'(\d+)\s*[~\-]\s*(\d+)\s*년\s*납?', vals_text):
                lo, hi = int(rm.group(1)), int(rm.group(2))
                for y in range(lo, hi + 1):
                    periods.add(f'{y}년납')
            # 단독: n년납 또는 n년 (납 없이, "이상/이내/이하" 뒤따르면 제외)
            for rm in re.finditer(r'(?<!\d)(\d{1,3})\s*년\s*납?', vals_text):
                full = rm.group(0)
                after = vals_text[rm.end():rm.end()+2]
                if after.startswith('이상') or after.startswith('이내') or after.startswith('이하'):
                    continue
                periods.add(f'{rm.group(1)}년납')
            if '일시납' in vals_text:
                periods.add('일시납')
            if '전기납' in vals_text:
                periods.add('전기납')
            if '종신납' in vals_text:
                periods.add('종신')
            if periods:
                result[ctx] = periods
            current_context = ctx
            continue

        # "(가) 적립형 : ..." 서브 항목
        m = re.match(r'^\(?[가-힣]+\)?\s*(.+?)\s*[:：]\s*(.+)', line)
        if m and current_context:
            sub_ctx = normalize_ws(m.group(1))
            ctx = f'{current_context}·{sub_ctx}'
            vals_text = m.group(2)
            periods = set()
            # 범위: 5~10년납 또는 5~10년 (납 없이)
            for rm in re.finditer(r'(\d+)\s*[~\-]\s*(\d+)\s*년\s*납?', vals_text):
                lo, hi = int(rm.group(1)), int(rm.group(2))
                for y in range(lo, hi + 1):
                    periods.add(f'{y}년납')
            # 단독: n년납 또는 n년 (납 없이, "이상/이내/이하" 뒤따르면 제외)
            for rm in re.finditer(r'(?<!\d)(\d{1,3})\s*년\s*납?', vals_text):
                full = rm.group(0)
                after = vals_text[rm.end():rm.end()+2]
                if after.startswith('이상') or after.startswith('이내') or after.startswith('이하'):
                    continue
                periods.add(f'{rm.group(1)}년납')
            if '일시납' in vals_text:
                periods.add('일시납')
            if '전기납' in vals_text:
                periods.add('전기납')
            if '종신납' in vals_text:
                periods.add('종신')
            if periods:
                result[ctx] = periods

    return result


def _is_annuity_section_header(prev_line: str, line: str, next_line: str) -> bool:
    """연금개시나이 섹션 헤더 감지 (extract_annuity_age.py 로직 재사용)."""
    if '연금개시나이' not in line:
        return False
    if re.match(r'^[가-힣]\.\s*연금개시나이', line) and '가입나이' not in line:
        return True
    if line == '연금개시나이' and ('연금개시나이' in prev_line or '연금개시' in prev_line):
        return True
    if line == '연금개시나이' and ('구분' in next_line or '종신연금형' in next_line or '확정기간연금형' in next_line):
        return True
    if re.match(r'^\(\d+\)\s*연금개시나이$', line):
        return True
    return False


def _extract_ages_from_text(text: str) -> Set[int]:
    """텍스트에서 나이 범위/개별 나이 추출."""
    ages: Set[int] = set()
    # 범위: 55세~80세, 만 55세~80세
    for m in re.finditer(r'(?:만\s*)?(\d{2,3})\s*세?\s*[~\-]\s*(\d{2,3})\s*세?', text):
        low, high = int(m.group(1)), int(m.group(2))
        if 20 <= low <= 120 and 20 <= high <= 120 and low <= high:
            ages.update(range(low, high + 1))
    return ages


def extract_annuity_age_range_by_type(
    all_tables: List[List],
    annuity_type: str,
    context_filter: str = '',
) -> Tuple[Optional[int], Optional[int]]:
    """표에서 특정 연금형 열의 연금개시나이 범위 추출 (열 헤더 매칭 + fill-down).

    예: 종신연금형 열에서 55~80세, 확정기간연금형 열에서 45~110세

    *context_filter*가 주어지면 첫 번째 열에 해당 문자열이 포함된 행만 사용.
    예: context_filter='1종' → 1종 행의 범위만 추출.
    """
    for table in all_tables:
        col_idx = -1
        header_row_idx = -1
        for ri, row in enumerate(table):
            cells = [str(c or '').replace('\n', ' ') for c in row]
            for ci, cell in enumerate(cells):
                if annuity_type in cell:
                    col_idx = ci
                    header_row_idx = ri
                    break
            if col_idx >= 0:
                break

        if col_idx < 0:
            continue

        ages: Set[int] = set()
        last_val = ''
        last_ctx = ''
        for ri in range(header_row_idx + 1, len(table)):
            row = table[ri]
            # 첫 번째 열: context(종) 식별
            ctx_cell = str(row[0] if row else '').replace('\n', ' ').strip()
            if ctx_cell:
                last_ctx = ctx_cell

            if col_idx >= len(row):
                continue
            cell_val = str(row[col_idx] or '').replace('\n', ' ').strip()
            if cell_val and cell_val != '-':
                last_val = cell_val
            elif not cell_val and last_val:
                cell_val = last_val  # fill-down: 병합셀로 비어있으면 위 값 상속

            # context 필터 적용
            if context_filter and context_filter not in last_ctx:
                continue

            ages.update(_extract_ages_from_text(cell_val))

        if ages:
            return min(ages), max(ages)

    return None, None


def extract_annuity_age_range_by_subtype(
    lines: List[str],
    subtype: str,
) -> Tuple[Optional[int], Optional[int]]:
    """연금개시나이 섹션에서 특정 서브타입(개인형/신부부형 등)의 나이 범위만 추출.

    텍스트 패턴: "개인형 45세 ~ 80세 ..." / "신부부형 48세 ~80세 45세 ~ 80세"
    남자/여자 열이 있을 경우 첫 번째 범위(남자)를 반환.
    """
    for i, line in enumerate(lines):
        prev_line = lines[i - 1] if i > 0 else ''
        next_line = lines[i + 1] if i + 1 < len(lines) else ''

        if not _is_annuity_section_header(prev_line, line, next_line):
            continue

        # 섹션 본문에서 서브타입 라인 찾기
        for j in range(i + 1, min(len(lines), i + 30)):
            nxt = lines[j]
            if (re.match(r'^[가-하]+\.', nxt)
                    or re.match(r'^\d+\.', nxt)
                    or (re.match(r'^\(\d+\)', nxt) and '연금개시나이' not in nxt)):
                break
            if subtype in nxt:
                # 첫 번째 범위만 추출 (남자 열)
                m = re.search(r'(?:만\s*)?(\d{2,3})\s*세?\s*[~\-]\s*(\d{2,3})\s*세?', nxt)
                if m:
                    low, high = int(m.group(1)), int(m.group(2))
                    if 20 <= low <= 120 and 20 <= high <= 120 and low <= high:
                        return low, high

    return None, None


def extract_annuity_age_range(lines: List[str]) -> Tuple[Optional[int], Optional[int]]:
    """연금개시나이 범위 추출: 섹션 헤더 기반으로 정확한 범위만 추출."""
    ages: Set[int] = set()

    for i, line in enumerate(lines):
        prev_line = lines[i - 1] if i > 0 else ''
        next_line = lines[i + 1] if i + 1 < len(lines) else ''

        if not _is_annuity_section_header(prev_line, line, next_line):
            continue

        # 인라인 값: "라. 연금개시나이 : 만 55세~80세"
        inline_m = re.search(r'연금개시나이\s*[:：]\s*(.+)', line)
        if inline_m:
            ages.update(_extract_ages_from_text(inline_m.group(1)))

        # 섹션 본문에서 추출 (다음 섹션 헤더까지)
        for j in range(i + 1, min(len(lines), i + 30)):
            nxt = lines[j]
            # 다른 섹션 시작 시 중단
            if (re.match(r'^[가-하]+\.', nxt)
                    or re.match(r'^\d+\.', nxt)
                    or (re.match(r'^\(\d+\)', nxt) and '연금개시나이' not in nxt)):
                break
            if nxt == '연금개시나이':
                continue
            ages.update(_extract_ages_from_text(nxt))

    if ages:
        return min(ages), max(ages)
    return None, None


# ──────────────────────────────────────────────
# 출력 레코드 생성
# ──────────────────────────────────────────────

def build_record(
    ins_period: str,
    pay_period: str,
    min_spin: str = '',
    max_spin: str = '',
    spin_code: str = '',
) -> dict:
    ins = format_period(ins_period)
    pay = format_period(pay_period)
    return {
        '보험기간': ins[0],
        '보험기간구분코드': ins[1],
        '보험기간값': ins[2],
        '납입기간': pay[0],
        '납입기간구분코드': pay[1],
        '납입기간값': pay[2],
        '최소제2보기개시나이': str(min_spin) if min_spin else '',
        '최대제2보기개시나이': str(max_spin) if max_spin else '',
        '제2보기개시나이구분코드': spin_code,
    }


def merge_period_info(
    row: dict,
    pdf_lines: List[str],
    pdf_tables: Optional[List[List]] = None,
) -> Dict[str, object]:
    """상품 정보와 PDF에서 추출한 기간 정보를 병합."""
    is_annuity = is_annuity_product(row)
    product_name = normalize_ws(str(row.get('상품명', '')))

    output = {
        '상품명칭': normalize_ws(str(row.get('상품명칭', ''))),
    }
    for key in sorted(row.keys()):
        if key.startswith('세부종목'):
            output[key] = row[key]
    output['상품명'] = product_name
    output['가입가능보기납기'] = []

    # 추출
    ins_periods_raw = extract_insurance_periods(pdf_lines, product_name)
    pay_periods_raw, has_jeonginap = extract_payment_periods(pdf_lines, product_name)

    # context 기반 필터링: 상품명에 매칭되는 기간만 유지
    # 1) 테이블 기반 context map (H종신 등 명시적 헤더 테이블)
    pay_ctx_map: Dict[str, Set[str]] = {}
    ins_ctx_map: Dict[str, Set[str]] = {}
    if pdf_tables and product_name:
        pay_ctx_map = _extract_context_period_map(pdf_tables, '납입기간')
        ins_ctx_map = _extract_context_period_map(pdf_tables, '보험기간')
    # 2) 텍스트 기반 context map (하이드림연금 등 "다. 납입기간" 섹션)
    if not pay_ctx_map and product_name:
        pay_ctx_map = _extract_text_context_pay_map(pdf_lines)
    # 3) 섹션 내 "(N) context" + 테이블 패턴 (H기업재해보장 등)
    if product_name:
        section_ctx = _extract_section_context_map(pdf_lines)
        if section_ctx:
            # 일반형/상생협력형 분리가 있으면 섹션 context가 테이블보다 더 정확
            has_type_separation = (
                any('일반형' in k for k in section_ctx) and
                any('상생협력형' in k for k in section_ctx)
            )
            # 섹션 context → _filter_periods_by_context 형식으로 변환
            pay_from_sec = {k: v['pay'] for k, v in section_ctx.items() if v['pay']}
            ins_from_sec = {k: v['ins'] for k, v in section_ctx.items() if v['ins']}
            # 일반형/상생협력형 분리 시 섹션 context 우선 (테이블이 둘을 혼용할 수 있음)
            if has_type_separation:
                if pay_from_sec:
                    pay_ctx_map = pay_from_sec
                if ins_from_sec:
                    ins_ctx_map = ins_from_sec
            else:
                if not pay_ctx_map and pay_from_sec:
                    pay_ctx_map = pay_from_sec
                if not ins_ctx_map and ins_from_sec:
                    ins_ctx_map = ins_from_sec
    # 4) 필터 적용
    if pay_ctx_map:
        pay_periods_raw = _filter_periods_by_context(
            pay_periods_raw, pay_ctx_map, product_name)
    if ins_ctx_map:
        ins_periods_raw = _filter_periods_by_context(
            ins_periods_raw, ins_ctx_map, product_name)

    # 전기납 context 필터: 일반형/상생협력형 분리 시, 매칭된 context에 전기납이 없으면 비활성화
    if has_jeonginap and pay_ctx_map:
        has_gen = any('일반형' in k for k in pay_ctx_map)
        has_ss = any('상생협력형' in k for k in pay_ctx_map)
        if has_gen and has_ss:
            name_has_ss = '상생' in normalize_ws(product_name)
            # 상품명에 매칭되는 주요 context에서만 전기납 여부 확인
            name_compact = re.sub(r'\s+', '', normalize_ws(product_name))
            matched_pay_ctx = set()
            for ctx_key, periods in pay_ctx_map.items():
                if '상생협력형' in ctx_key:
                    if name_has_ss:
                        matched_pay_ctx.update(periods)
                elif '일반형' in ctx_key:
                    if not name_has_ss:
                        matched_pay_ctx.update(periods)
                else:
                    # 일반형/상생 아닌 context: 상품명에 실제 매칭되는지 확인
                    ctx_compact = re.sub(r'\s+', '', ctx_key)
                    if ctx_compact in name_compact:
                        matched_pay_ctx.update(periods)
            if matched_pay_ctx and not any('전기납' in p for p in matched_pay_ctx):
                has_jeonginap = False

    # 갱신형 N년만기: 상품명에 명시된 만기만 유지
    manki_m = re.search(r'(\d+)\s*년\s*만기', product_name)
    if manki_m and '갱신' in (output.get('상품명칭', '') + ' ' + product_name):
        manki_val = manki_m.group(1) + '년'
        ins_periods_raw = {p for p in ins_periods_raw if manki_val in p} or ins_periods_raw
        pay_periods_raw = {p for p in pay_periods_raw if manki_val in p} or pay_periods_raw

    # 종신보험 기본값 (종신연금은 제외 — 연금은 별도 처리)
    if not ins_periods_raw:
        name_combined = output['상품명칭'] + ' ' + product_name
        if '종신' in name_combined and '연금' not in name_combined:
            ins_periods_raw = {'종신'}

    # 납입기간이 없으면 빈값
    if not pay_periods_raw and not has_jeonginap:
        pay_periods_raw = {''}

    # 보험기간이 없으면 빈값
    if not ins_periods_raw:
        ins_periods_raw = {''}

    records: List[dict] = []

    if is_annuity:
        # ── 연금 상품 ──

        # 즉시형/거치형: 납입기간=일시납만
        is_immediate = ('즉시형' in product_name or '거치형' in product_name)
        if is_immediate:
            pay_periods_raw = {'일시납'}
            has_jeonginap = False
        else:
            # 비즉시형 연금: 다른 납기가 함께 추출된 경우 일시납 제거
            # (거치형/즉시형 납입주기 설명에서 잘못 섞여 들어온 일시납 방지)
            # 단, 일시납이 유일한 납기인 경우(바로연금 종신형 등)는 유지
            if pay_periods_raw - {'일시납', ''}:
                pay_periods_raw.discard('일시납')

        # 연금개시나이: 종신연금형은 테이블 해당 열, 확정기간연금형은 context(종) 인지 시도
        if '종신연금' in product_name and pdf_tables:
            min_age, max_age = extract_annuity_age_range_by_type(pdf_tables, '종신연금형')
            if min_age is None:
                min_age, max_age = extract_annuity_age_range(pdf_lines)
        elif ('확정기간' in product_name or '환급플랜' in product_name) and pdf_tables:
            # 확정기간연금형: 해당 열의 context(종)별 범위 시도
            # 상품명에서 종 식별 (예: "1종", "2종")
            _jong_m = re.search(r'(\d종)', product_name)
            _jong_ctx = _jong_m.group(1) if _jong_m else ''
            min_age, max_age = extract_annuity_age_range_by_type(
                pdf_tables, '확정기간연금형', context_filter=_jong_ctx)
            if min_age is None:
                min_age, max_age = extract_annuity_age_range_by_type(pdf_tables, '확정기간연금형')
            if min_age is None:
                min_age, max_age = extract_annuity_age_range_by_type(pdf_tables, '종신연금형')
            if min_age is None:
                min_age, max_age = extract_annuity_age_range(pdf_lines)
        else:
            min_age, max_age = extract_annuity_age_range(pdf_lines)

        # 신부부형: 개인형과 다른 연금개시나이 범위가 있으면 추가 row 생성
        extra_min_age = None
        extra_max_age = None
        if '신부부형' in product_name:
            sub_min, sub_max = extract_annuity_age_range_by_subtype(pdf_lines, '신부부형')
            if sub_min is not None and sub_min != min_age:
                extra_min_age, extra_max_age = sub_min, sub_max

        # 연금 상품 보험기간 판단 (상품명 기반)
        annuity_ins_period = ''
        if '종신연금' in product_name or '종신플랜' in product_name:
            annuity_ins_period = '종신'
        else:
            # 확정기간연금형 10년, 확정기간연금형(10년형), 환급플랜(10년형) 등
            m = re.search(r'(?:확정기간|환급플랜).*?(\d+)\s*년', product_name)
            if m:
                fixed_years = int(m.group(1))
                # 보험기간 = 연금개시나이 + 확정기간 → 각 나이별 개별 생성
                if min_age and max_age:
                    for age in range(min_age, max_age + 1):
                        ins_age = age + fixed_years
                        for pay_p in pay_periods_raw:
                            if pay_p:
                                # 확정기간연금형은 일시납 포함 모든 납기에 제2보기개시나이 기록
                                rec = build_record(
                                    f'{ins_age}세', pay_p,
                                    min_spin=str(age),
                                    max_spin=str(age),
                                    spin_code='X',
                                )
                                records.append(rec)
                        if has_jeonginap:
                            rec = build_record(
                                f'{ins_age}세', f'{age}세납',
                                min_spin=age, max_spin=age, spin_code='X',
                            )
                            records.append(rec)

        # 종신연금형 또는 기본 연금 처리 (확정기간 records가 없는 경우 폴백)
        if annuity_ins_period or not records:
            ins_p = annuity_ins_period or '종신'
            for pay_p in pay_periods_raw:
                if pay_p:
                    pay_fmt = format_period(pay_p)
                    is_lump = (pay_fmt[0] == 'N0')
                    rec = build_record(
                        ins_p, pay_p,
                        min_spin='' if is_lump else (min_age or ''),
                        max_spin='' if is_lump else (max_age or ''),
                        spin_code='' if is_lump else ('X' if min_age else ''),
                    )
                    records.append(rec)
                    # 신부부형 추가 범위: N-type 납입기간에 대해 서브타입 나이 범위로 추가 row
                    if extra_min_age and not is_lump:
                        rec2 = build_record(
                            ins_p, pay_p,
                            min_spin=extra_min_age,
                            max_spin=extra_max_age,
                            spin_code='X',
                        )
                        records.append(rec2)

            # 전기납: 각 연금개시나이별 row 생성 (즉시형/거치형은 이미 일시납으로 제한됨)
            if has_jeonginap and min_age and max_age:
                for age in range(min_age, max_age + 1):
                    rec = build_record(
                        ins_p, f'{age}세납',
                        min_spin=age,
                        max_spin=age,
                        spin_code='X',
                    )
                    records.append(rec)
    else:
        # ── 비연금 상품 ──
        for ins_p in ins_periods_raw:
            for pay_p in pay_periods_raw:
                if pay_p:
                    records.append(build_record(ins_p, pay_p))

            # 전기납: 납입기간 = 보험기간
            if has_jeonginap and ins_p:
                rec = build_record(ins_p, ins_p)
                records.append(rec)

        # 납입기간만 있고 보험기간 없는 경우
        if not ins_periods_raw or ins_periods_raw == {''}:
            for pay_p in pay_periods_raw:
                if pay_p:
                    records.append(build_record('', pay_p))

    # 매트릭스 테이블 무효 조합 추출 (보험기간 × 납입기간에서 '-' 표시된 쌍)
    matrix_invalid = _extract_matrix_invalid_pairs(pdf_lines)

    # 납입기간 ≤ 보험기간 제약 적용 (N-type 동끼리 비교, X-type 동끼리 비교)
    filtered_records: List[dict] = []
    for rec in records:
        ins_code = rec.get('보험기간구분코드', '')
        pay_code = rec.get('납입기간구분코드', '')
        ins_val = rec.get('보험기간값', '')
        pay_val = rec.get('납입기간값', '')
        skip = False
        # N-type 보험기간 + X-type 납입기간 조합은 실제 상품에 존재하지 않음
        if ins_code == 'N' and pay_code == 'X':
            skip = True
        # X-type 보험기간 < 70세 + N-type 납입기간 > 20년 조합은 존재하지 않음
        if ins_code == 'X' and pay_code == 'N' and ins_val and pay_val:
            try:
                if int(ins_val) < 70 and int(pay_val) > 20:
                    skip = True
            except ValueError:
                pass
        if ins_code == pay_code and ins_code in ('N', 'X') and ins_val and pay_val:
            try:
                if int(pay_val) > int(ins_val) and ins_val != '999':
                    skip = True  # 납입기간이 보험기간보다 긺 → 무효
            except ValueError:
                pass
        # 매트릭스 테이블 무효 조합 체크
        if not skip and matrix_invalid:
            ins_raw = rec.get('보험기간', '')
            pay_raw = rec.get('납입기간', '')
            # 보험기간 코드 → 원본 텍스트 (N20 → 20년, X60 → 60세)
            if ins_code == 'N' and ins_val:
                ins_text = ins_val + '년'
            elif ins_code == 'X' and ins_val:
                ins_text = ins_val + '세'
            else:
                ins_text = ''
            # 납입기간 코드 → 원본 텍스트 (N20 → 20년납, X60 → 60세납)
            if pay_code == 'N' and pay_val:
                pay_text = pay_val + '년납'
            elif pay_code == 'X' and pay_val:
                pay_text = pay_val + '세납'
            elif pay_code == 'N' and pay_val == '0':
                pay_text = '일시납'
            else:
                pay_text = ''
            # 전기납 체크 (ins == pay인 경우) → 전기납 행만 참조
            if ins_raw == pay_raw and ins_text:
                if (ins_text, '전기납') in matrix_invalid:
                    skip = True
            elif ins_text and pay_text and (ins_text, pay_text) in matrix_invalid:
                skip = True
        if not skip:
            filtered_records.append(rec)
    records = filtered_records

    # 중복 제거
    seen: Set[str] = set()
    unique: List[dict] = []
    for rec in records:
        key = json.dumps(rec, sort_keys=True, ensure_ascii=False)
        if key not in seen:
            seen.add(key)
            unique.append(rec)
    output['가입가능보기납기'] = unique

    if not unique:
        output['가입가능보기납기'].append(build_record('', ''))

    return output


# ──────────────────────────────────────────────
# 리포트 / CLI
# ──────────────────────────────────────────────

def make_report(items: List[Dict[str, object]]) -> Dict[str, object]:
    file_stats = []
    total_rows = 0
    total_results = 0
    for item in items:
        rows = item.get('rows', [])
        count_rows = len(rows)
        count_results = sum(
            len([e for e in r.get('가입가능보기납기', []) if e.get('보험기간') or e.get('납입기간')])
            for r in rows
        )
        total_rows += count_rows
        total_results += count_results
        file_stats.append({
            '파일명': item.get('file'),
            '입력_상품수': count_rows,
            '가입가능보기납기항목수': count_results,
        })
    return {
        '총_파일수': len(items),
        '총_입력_상품수': total_rows,
        '총_가입가능보기납기항목수': total_results,
        '파일별_요약': file_stats,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description='가입가능보기납기 추출기')
    parser.add_argument('--pdf-dir', type=Path, default=DEFAULT_PDF_DIR)
    parser.add_argument('--json-dir', type=Path, default=DEFAULT_JSON_DIR)
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument('--report-path', type=Path, default=DEFAULT_REPORT_PATH)
    parser.add_argument('--pdf', type=str, help='single PDF path')
    parser.add_argument('--json', type=str, help='single JSON path')
    parser.add_argument('--output', type=str, help='single output path')
    parser.add_argument('--dry-run', action='store_true')
    return parser.parse_args()


def gather_pairs(pdf_dir: Path, json_dir: Path) -> List[Tuple[Path, Path]]:
    json_map = {normalize_name(p.name): p for p in json_dir.glob('*.json')}
    pairs = []
    for pdf in pdf_dir.glob('*.pdf'):
        key = normalize_name(pdf.name)
        json_path = json_map.get(key)
        if json_path:
            pairs.append((pdf, json_path))
    return pairs


def _apply_period_overrides(results: List[dict]) -> List[dict]:
    """Post-process: product_overrides.json 기반 override + sibling fallback."""
    overrides = _load_overrides()
    ip_overrides = overrides.get('insurance_period', {})
    sibling_cfg = overrides.get('sibling_fallback', {})
    suffix_patterns = sibling_cfg.get('suffix_patterns', [])

    # Build lookup: 상품명 → 가입가능보기납기
    name_to_periods: Dict[str, list] = {}
    for r in results:
        name_to_periods[r.get('상품명', '')] = r.get('가입가능보기납기', [])

    for r in results:
        product_name = r.get('상품명', '')
        periods = r.get('가입가능보기납기', [])
        applied = False

        # 1) JSON override 적용
        for key, cfg in ip_overrides.items():
            if key.startswith('_'):
                continue
            # key 매칭: "A+B" → 상품명에 A, B 모두 포함
            keywords = key.split('+')
            if not all(kw in product_name for kw in keywords):
                continue

            action = cfg.get('action', 'fixed')

            if action == 'fixed':
                r['가입가능보기납기'] = list(cfg.get('periods', []))
                applied = True

            elif action == 'sibling_filter':
                match_kws = cfg.get('sibling_match', [])
                exclude_kws = cfg.get('sibling_exclude', [])
                filt = cfg.get('filter', {})
                for name, pdata in name_to_periods.items():
                    if (all(m in name for m in match_kws)
                            and not any(e in name for e in exclude_kws)
                            and pdata):
                        filtered = list(pdata)
                        if filt.get('보험기간구분코드'):
                            filtered = [p for p in filtered if p.get('보험기간구분코드') == filt['보험기간구분코드']]
                        if filt.get('보험기간값'):
                            vals = filt['보험기간값']
                            if isinstance(vals, list):
                                filtered = [p for p in filtered if p.get('보험기간값') in vals]
                        if filt.get('exclude_jeonginap'):
                            filtered = [p for p in filtered if p.get('납입기간') != p.get('보험기간')]
                        r['가입가능보기납기'] = filtered
                        applied = True
                        break

            elif action == 'sibling_copy':
                if not periods:
                    match_kws = cfg.get('sibling_match', [])
                    any_kws = cfg.get('sibling_any', [])
                    for name, pdata in name_to_periods.items():
                        if (all(m in name for m in match_kws)
                                and any(a in name for a in any_kws)
                                and pdata):
                            r['가입가능보기납기'] = list(pdata)
                            applied = True
                            break

            if applied:
                break

        # 2) sibling fallback (suffix_patterns 기반)
        if not applied and not r.get('가입가능보기납기'):
            for pattern in suffix_patterns:
                parts = pattern.split('|')
                if len(parts) != 2:
                    continue
                src, dst = parts
                if src in product_name:
                    base_name = product_name.replace(src, dst)
                    base_data = name_to_periods.get(base_name, [])
                    if base_data:
                        r['가입가능보기납기'] = list(base_data)
                        break

    return results


def process_single(pdf_path: Path, json_path: Path) -> List[dict]:
    pdf_lines, pdf_tables = extract_pdf_content(pdf_path)
    with json_path.open('r', encoding='utf-8') as f:
        rows = json.load(f)
    if not isinstance(rows, list):
        rows = []
    results = [merge_period_info(r if isinstance(r, dict) else {}, pdf_lines, pdf_tables) for r in rows]
    return _apply_period_overrides(results)


def run(pdf_dir: Path, json_dir: Path, output_dir: Path, report_path: Optional[Path]) -> Tuple[int, int]:
    pairs = gather_pairs(pdf_dir, json_dir)
    outputs = []
    output_dir.mkdir(parents=True, exist_ok=True)

    for pdf_path, json_path in sorted(pairs, key=lambda x: x[1].name):
        result_rows = process_single(pdf_path, json_path)
        outputs.append({'file': json_path.name, 'rows': result_rows})
        out_path = output_dir / json_path.name
        with out_path.open('w', encoding='utf-8') as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)
        print(f'Processed: {json_path.name} -> {out_path.name} ({len(result_rows)} items)')

    report = make_report(outputs)
    rp = report_path or (output_dir / 'report.json')
    rp.parent.mkdir(parents=True, exist_ok=True)
    with rp.open('w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    return len(pairs), sum(len(item.get('rows', [])) for item in outputs)


if __name__ == '__main__':
    args = parse_args()
    if args.dry_run:
        print('pairs:', len(gather_pairs(args.pdf_dir, args.json_dir)))
    elif args.pdf and args.json:
        pdf_path = Path(args.pdf)
        json_path = Path(args.json)
        output_path = Path(args.output) if args.output else args.output_dir / json_path.name
        result_rows = process_single(pdf_path, json_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open('w', encoding='utf-8') as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)
        print(f'{pdf_path.name} + {json_path.name} -> {output_path.name} ({len(result_rows)} items)')
    else:
        processed_files, processed_rows = run(args.pdf_dir, args.json_dir, args.output_dir, args.report_path)
        print(f'processed_files={processed_files}')
        print(f'processed_rows={processed_rows}')
