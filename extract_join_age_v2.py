#!/usr/bin/env python3
"""가입가능나이 추출기 (v2 - Word docx).

Word 사업방법서에서 성별/연령 범위를 추출합니다.

Usage:
    python extract_join_age_v2.py                              # 전체 디렉토리
    python extract_join_age_v2.py --docx X.docx --json X.json  # 단일 파일
"""
import argparse
import json
import re
import unicodedata
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from docx import Document
from docx.oxml.ns import qn
from docx.table import Table as DocxTable
from docx.text.paragraph import Paragraph


ROOT = Path(__file__).resolve().parent
DEFAULT_DOCX_DIR = (ROOT / '사업방법서_워드' if (ROOT / '사업방법서_워드').exists()
                    else ROOT.parent / '사업방법서_워드')
DEFAULT_JSON_DIR = ROOT / '상품분류'
DEFAULT_PERIOD_DIR = ROOT / '가입가능보기납기'
DEFAULT_OUTPUT_DIR = ROOT / '가입가능나이'
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
    text = re.sub(r'(.)\1{3}', r'\1', text)
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


def format_period(text: str) -> Tuple[str, str, str]:
    """기간 텍스트를 (코드, 구분코드, 값)으로 변환."""
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

def extract_docx_content(docx_path: Path) -> Tuple[List[str], List[List]]:
    doc = Document(str(docx_path))
    lines: List[str] = []
    all_tables: List[List] = []

    for child in doc.element.body:
        if child.tag == qn('w:p'):
            normalized = normalize_ws(Paragraph(child, doc).text)
            if normalized:
                lines.append(normalized)
        elif child.tag == qn('w:tbl'):
            table = DocxTable(child, doc)
            t = [[cell.text for cell in row.cells] for row in table.rows]
            all_tables.append(t)
            for row in t:
                for cell_text in row:
                    normalized = normalize_ws(cell_text)
                    if normalized:
                        lines.append(normalized)

    return lines, all_tables


# ──────────────────────────────────────────────
# 가입나이 섹션 찾기
# ──────────────────────────────────────────────

def _find_age_section(lines: List[str]) -> Tuple[int, int]:
    """가입나이 관련 섹션의 시작/끝 인덱스."""
    start = -1
    for i, line in enumerate(lines):
        if re.match(r'^\d+\.\s*보험기간', line) and '가입나이' in line:
            start = i
            break
        if re.match(r'^[가-힣]\.\s*.*가입나이', line):
            start = i
            break
    if start < 0:
        # fallback: "가입최저나이" 키워드
        for i, line in enumerate(lines):
            if '가입최저나이' in line or '가입최고나이' in line:
                start = max(0, i - 2)
                break
    if start < 0:
        return 0, len(lines)

    # 다음 큰 섹션 시작까지
    start_num = re.match(r'^(\d+)\.', lines[start])
    end = len(lines)
    for j in range(start + 1, len(lines)):
        m = re.match(r'^(\d+)\.', lines[j])
        if m and start_num and int(m.group(1)) > int(start_num.group(1)):
            end = j
            break
    # 종속특약/부가특약 경계
    for j in range(start + 1, end):
        if re.match(r'^나\.\s*(?:종속특약|부가특약)', lines[j]):
            end = j
            break
    return start, end


# ──────────────────────────────────────────────
# 최소가입나이 추출
# ──────────────────────────────────────────────

def extract_min_age(lines: List[str]) -> Optional[str]:
    """가입최저나이(만 N세) 추출."""
    # 전체 텍스트에서 "가입최저나이" / "최저가입나이" / "최저 가입 나이" 패턴 검색 (섹션 제한 없이)
    for line in lines:
        m = re.search(r'(?:가입\s*최저\s*나이|최저\s*가입\s*나이)\s*[:：]?\s*만?\s*(\d+)\s*세', line)
        if m:
            return m.group(1)

    # fallback: 가입나이 섹션에서 "N ~ M세" 또는 "N ~ (공식)세" 패턴의 N
    sec_start, sec_end = _find_age_section(lines)
    candidates = []
    for i in range(sec_start, sec_end):
        line = lines[i]
        # "N세 ~ M세", "N세(태아포함) ~ M세", "N ~ (연금개시나이-납입기간)세" 등
        m = re.search(r'만?\s*(\d+)\s*세?\s*(?:\([^)]*\))?\s*[~～\-]\s*(?:\d+\s*세|\()', line)
        if m:
            candidates.append(int(m.group(1)))
    if candidates:
        return str(min(candidates))

    return None


# ──────────────────────────────────────────────
# 최대가입나이 전략 감지
# ──────────────────────────────────────────────

def detect_max_age_strategy(lines: List[str], tables: List[List]) -> str:
    """'simple', 'formula', 'table' 중 하나를 반환."""
    # 전체 텍스트에서 formula 패턴 확인
    for line in lines:
        if '연금개시나이' in line and '납입기간' in line and ('가입최고' in line or '-' in line):
            return 'formula'
        if re.search(r'가입최고나이\s*[:：]?\s*\(?\s*연금개시나이', line):
            return 'formula'
        if '연금개시나이' in line and re.search(r'연금개시나이\s*[-\-]\s*1', line):
            return 'formula'

    # table 패턴 확인 (공백 포함 형태도 처리: "최고 가입 나이")
    for line in lines:
        if re.search(r'(?:가입\s*최고\s*나이|최고\s*가입\s*나이).*아래와\s*같', line):
            return 'table'
        if re.search(r'(?:가입\s*최고\s*나이|최고\s*가입\s*나이).*보험기간.*성별', line):
            return 'table'
        if re.search(r'(?:가입\s*최고\s*나이|최고\s*가입\s*나이).*성별.*보험기간', line):
            return 'table'
        if re.search(r'(?:가입\s*최고\s*나이|최고\s*가입\s*나이).*납입기간별', line):
            return 'table'
        if re.search(r'(?:가입\s*최고\s*나이|최고\s*가입\s*나이).*성별.*납입기간', line):
            return 'table'

    # "최고가입나이" 헤더가 있는 테이블 확인
    for table in tables:
        if not table:
            continue
        header = ' '.join(str(c or '') for c in table[0])
        if '최고가입나이' in header or '최고나이' in header:
            return 'table'

    return 'simple'


# ──────────────────────────────────────────────
# Strategy A: 단순형
# ──────────────────────────────────────────────

def extract_simple_max_age(lines: List[str]) -> Optional[str]:
    """가입최고나이: N세 패턴 추출."""
    sec_start, sec_end = _find_age_section(lines)
    for i in range(sec_start, sec_end):
        line = lines[i]
        m = re.search(r'(?:가입\s*최고\s*나이|최고\s*가입\s*나이)\s*[:：]\s*만?\s*(\d+)\s*세', line)
        if m:
            return m.group(1)
    return None


def extract_simple_age_from_table(lines: List[str], tables: List[List]) -> List[dict]:
    """테이블 내 'N세 ~ M세' 패턴으로 단순 나이 범위 추출.

    보장형 계약 등의 테이블에서 성별별 나이 범위를 추출.
    """
    sec_start, sec_end = _find_age_section(lines)
    results = []

    for i in range(sec_start, sec_end):
        line = lines[i]
        # "보험기간 납입기간 남자 여자" 다음 줄 패턴
        # "90세만기 전기납 27~75세 45~75세"
        genders = []
        # 남녀 구분 패턴
        m_male = re.search(r'(\d+)\s*[~\-]\s*(\d+)\s*세.*?(\d+)\s*[~\-]\s*(\d+)\s*세', line)
        if m_male and ('남자' in lines[max(0, i-3):i+1][-1] if i > 0 else False):
            pass

        # 단순 범위: "N~M세" 또는 "N세~M세"
        for m in re.finditer(r'(\d+)\s*세?\s*[~\-]\s*(\d+)\s*세', line):
            min_ag, max_ag = m.group(1), m.group(2)
            results.append({'min_age': min_ag, 'max_age': max_ag, 'gender': ''})

    return results


# ──────────────────────────────────────────────
# Strategy B: 연금 공식형
# ──────────────────────────────────────────────


def parse_deduction_table(lines: List[str], tables: Optional[List[List]] = None) -> Tuple[Dict[str, int], Optional[int]]:
    """납입기간별 가입최고나이 공제 테이블을 파싱.

    텍스트(lines)와 docx 테이블(tables) 두 가지 소스에서 추출 시도.

    Returns:
        (paym_deductions, geochi_deduction):
        - paym_deductions: {PAYM값(str): 공제값(int)} — 적립형 컬럼
        - geochi_deduction: 거치형 공제값 (없으면 None)

    예: '3년납 (연금개시나이 – 5)세' → {'3': 5}
        '(연금개시나이 – 5)세'  (거치형 컬럼) → geochi_deduction=5
    """
    paym_deductions: Dict[str, int] = {}
    geochi_deduction: Optional[int] = None

    # --- 1) 테이블 직접 파싱 (납입기간 | 가입최고나이 [적립형/거치형] 구조) ---
    if tables:
        for table in tables:
            if len(table) < 2:
                continue
            header_text = ' '.join(str(c or '') for c in table[0])
            if '가입최고나이' not in header_text and '최고나이' not in header_text:
                continue
            if '납입기간' not in header_text and len(table) > 1:
                header_text += ' ' + ' '.join(str(c or '') for c in table[1])
            if '납입기간' not in header_text:
                continue

            # 컬럼 구조 판별: [납입기간, 적립형, 거치형] 또는 [납입기간, 가입최고나이]
            header_row = table[0]
            second_row = table[1] if len(table) > 1 else []
            has_geochi_col = any('거치' in str(c) for c in header_row + second_row)
            # 적립형 컬럼 인덱스 = 1 (첫 번째 가입최고나이 컬럼)
            # 거치형 컬럼 인덱스 = 2 (있으면)
            jeok_col = 1
            geo_col = 2 if has_geochi_col and len(header_row) > 2 else None

            # 데이터 행: "N년납" or "전기납" + "(연금개시나이 - D)세"
            data_start = 2 if any('적립' in str(c) or '거치' in str(c) for c in second_row) else 1
            for row in table[data_start:]:
                if len(row) < 2:
                    continue
                paym_text = str(row[0]).strip()
                formula_text = str(row[jeok_col]).strip() if jeok_col < len(row) else ''

                m_ded = re.search(r'연금개시나이\s*[–\-]\s*(\d+)', formula_text)
                if not m_ded:
                    continue
                ded_val = int(m_ded.group(1))

                m_paym = re.search(r'(\d+)\s*년납', paym_text)
                if m_paym:
                    paym_deductions[m_paym.group(1)] = ded_val
                elif '전기납' in paym_text:
                    paym_deductions['전기납'] = ded_val

                # 거치형 컬럼 공제값
                if geo_col is not None and geo_col < len(row):
                    geo_text = str(row[geo_col]).strip()
                    m_geo = re.search(r'연금개시나이\s*[–\-]\s*(\d+)', geo_text)
                    if m_geo and geochi_deduction is None:
                        geochi_deduction = int(m_geo.group(1))

            if paym_deductions:
                return paym_deductions, geochi_deduction

    # --- 2) 텍스트(lines) 기반 파싱 (fallback) ---
    in_section = False
    for line in lines:
        if '가입최고나이' in line and '연금개시나이' in line:
            in_section = True
            continue
        if '가입최고나이' in line and ('납입기간' in line or '아래' in line):
            in_section = True
            continue
        if not in_section:
            continue
        # 섹션 종료: 다음 주제 시작
        if re.match(r'^[가-힣라마바사]\.\s', line) or re.match(r'^\d+\.\s', line):
            break

        # 'N년납 (연금개시나이 – D)세' — 적립형 납입기간별 공제
        m = re.search(r'(\d+)\s*년납.*연금개시나이\s*.\s*(\d+)', line)
        if m:
            paym_val = m.group(1)
            ded_val = int(m.group(2))
            paym_deductions[paym_val] = ded_val
            continue

        # '전기납 (연금개시나이 – D)세' — 전기납 공제
        m_jeon = re.search(r'전기납.*연금개시나이\s*.\s*(\d+)', line)
        if m_jeon:
            paym_deductions['전기납'] = int(m_jeon.group(1))
            continue

        # '(연금개시나이 – D)세' without PAYM — 거치형 공제
        m2 = re.search(r'^\s*\(?연금개시나이\s*.\s*(\d+)\)?\s*세', line)
        if m2:
            geochi_deduction = int(m2.group(1))
            continue

    return paym_deductions, geochi_deduction


def compute_formula_ages(
    min_age: str,
    period_data: List[dict],
    has_gender_split: bool = False,
    spin_minus_one: bool = False,
    deduction_override: Optional[int] = None,
    paym_deductions: Optional[Dict[str, int]] = None,
) -> List[dict]:
    """연금 공식: MAX_AG = 연금개시나이 - 납입기간 (또는 연금개시나이 - 1).

    period_data: 가입가능보기납기 JSON의 레코드들.
    spin_minus_one: True이면 MAX_AG = SPIN - deduction (거치형), PAYM 비포함.
    deduction_override: spin_minus_one 모드에서 사용할 공제값 (기본 1).
    paym_deductions: SPIN×PAYM 모드에서 {PAYM값: 공제값} 매핑. None이면 공제=PAYM.
    """
    results = []
    seen = set()

    if spin_minus_one:
        ded = deduction_override if deduction_override is not None else 1
        # SPIN-ded 모드: 각 고유 SPIN 값에 대해 MAX_AG = SPIN - ded, PAYM/INS 없음
        spin_vals: Set[int] = set()
        for period_rec in period_data:
            for p in period_rec.get('가입가능보기납기', []):
                min_spin = p.get('최소제2보기개시나이', '')
                max_spin = p.get('최대제2보기개시나이', '')
                if not min_spin:
                    continue
                try:
                    spin_lo = int(min_spin)
                    spin_hi = int(max_spin) if max_spin else spin_lo
                except (ValueError, TypeError):
                    continue
                for v in range(spin_lo, spin_hi + 1):
                    spin_vals.add(v)

        genders = ['1', '2'] if has_gender_split else ['']
        for spin_val in sorted(spin_vals):
            max_ag = spin_val - ded
            if max_ag < 0:
                continue
            spin_str = str(spin_val)
            for gender in genders:
                key = (min_age, str(max_ag), gender, spin_str)
                if key in seen:
                    continue
                seen.add(key)
                results.append({
                    '성별': gender,
                    '최소가입나이': min_age,
                    '최대가입나이': str(max_ag),
                    '최소납입기간': '',
                    '최대납입기간': '',
                    '납입기간구분코드': '',
                    '최소제2보기개시나이': spin_str,
                    '최대제2보기개시나이': spin_str,
                    '제2보기개시나이구분코드': 'X',
                    '최소보험기간': '',
                    '최대보험기간': '',
                    '보험기간구분코드': '',
                })
        return results

    # SPIN×PAYM 모드: 각 개별 SPIN 값과 PAYM 조합마다 MAX_AG 계산
    combos: Set[Tuple[int, str, str]] = set()
    for period_rec in period_data:
        periods = period_rec.get('가입가능보기납기', [])
        for p in periods:
            min_spin = p.get('최소제2보기개시나이', '')
            max_spin = p.get('최대제2보기개시나이', '')
            paym_dvsn = p.get('납입기간구분코드', '')
            paym_val = p.get('납입기간값', '')

            if not min_spin or not paym_val:
                continue

            try:
                spin_lo = int(min_spin)
                spin_hi = int(max_spin) if max_spin else spin_lo
            except (ValueError, TypeError):
                continue

            for spin_val in range(spin_lo, spin_hi + 1):
                combos.add((spin_val, paym_val, paym_dvsn))

    for spin_val, paym_val, paym_dvsn in sorted(combos):
        try:
            paym_int = int(paym_val)
        except (ValueError, TypeError):
            continue

        if paym_dvsn == 'N' and paym_int > 0:
            # paym_deductions 매핑이 있으면 해당 공제값 사용
            if paym_deductions and paym_val in paym_deductions:
                max_ag = spin_val - paym_deductions[paym_val]
            else:
                max_ag = spin_val - paym_int
        elif paym_dvsn == 'X':
            # 전기납: paym_val == spin_val → paym_deductions에 '전기납' 키 확인
            if paym_deductions and '전기납' in paym_deductions:
                max_ag = spin_val - paym_deductions['전기납']
            else:
                max_ag = spin_val - 10 if spin_val > 10 else 0
        else:
            continue

        if max_ag < 0:
            continue

        spin_str = str(spin_val)
        genders = ['1', '2'] if has_gender_split else ['']

        for gender in genders:
            key = (min_age, str(max_ag), gender,
                   paym_val, paym_val, paym_dvsn,
                   spin_str, spin_str, 'X')
            if key in seen:
                continue
            seen.add(key)

            results.append({
                '성별': gender,
                '최소가입나이': min_age,
                '최대가입나이': str(max_ag),
                '최소납입기간': paym_val,
                '최대납입기간': paym_val,
                '납입기간구분코드': paym_dvsn,
                '최소제2보기개시나이': spin_str,
                '최대제2보기개시나이': spin_str,
                '제2보기개시나이구분코드': 'X',
                '최소보험기간': '',
                '최대보험기간': '',
                '보험기간구분코드': '',
            })

    return results


# ──────────────────────────────────────────────
# Strategy C: 테이블형
# ──────────────────────────────────────────────

def _parse_age_cell(text: str) -> Optional[Tuple[str, str]]:
    """셀에서 (최소나이, 최대나이) 추출.

    "만15세~80세" → ('15', '80')
    "만15~70세" → ('15', '70')
    "45 ~ 85세" → ('45', '85')
    "80세" → (None, '80')  — 단일 값
    "-" → None
    """
    text = normalize_ws(text)
    if not text or text == '-':
        return None
    # 범위 패턴: N세~M세, N~M세, N ~ M세, N세(태아포함) ~ M세
    m = re.search(r'만?(\d+)\s*세\s*(?:\([^)]*\))?\s*[~\-～]\s*(\d+)\s*세', text)
    if not m:
        m = re.search(r'만?(\d+)\s*세?\s*[~\-～]\s*(\d+)\s*세?', text)
    if m:
        return m.group(1), m.group(2)
    # 단일 값: N세
    m = re.search(r'(\d+)\s*세', text)
    if m:
        return None, m.group(1)
    return None


def parse_age_table(tables: List[List], lines: List[str]) -> List[dict]:
    """PDF 테이블에서 가입나이 테이블 파싱.

    테이블 구조:
    헤더: 구분 | 90세만기 | 100세만기 | 110세만기 ...
    서브헤더:   | 남자 | 여자 | 남자 | 여자 ...
    데이터: 종목 | 납입기간 | 만N세~M세 | 만N세~M세 ...

    Returns: [{context, 보험기간, 납입기간, 성별, 최소가입나이, 최대가입나이, table_group}, ...]
    """
    results = []
    table_group_idx = 0

    for table in tables:
        if not table or len(table) < 2:
            continue

        # 헤더 분석: "구 분" | 보험기간들, 서브헤더: 성별들
        header0 = [normalize_ws(str(c or '')) for c in table[0]]
        header1 = [normalize_ws(str(c or '')) for c in table[1]] if len(table) > 1 else []

        # 이 테이블이 나이 테이블인지 확인
        has_manki = any('세만기' in h or '만기' in h or h == '종신' for h in header0)
        split_header_merged = False
        header_skip_rows = 0  # 추가 건너뛸 헤더 행 수
        # header1에만 '만기'가 있는 경우 (split header: "10년"+"만기" → "10년만기")
        if not has_manki and header1:
            has_manki_in_h1 = any(h == '만기' for h in header1)
            if has_manki_in_h1:
                has_manki = True
                split_header_merged = True
                header_skip_rows = 1
                # header0 + header1 합치기
                merged_header = []
                for ci in range(len(header0)):
                    h0 = header0[ci]
                    h1 = header1[ci] if ci < len(header1) else ''
                    if h1 == '만기' and h0 and h0 != '만기':
                        merged_header.append(h0 + '만기')
                    else:
                        merged_header.append(h0)
                header0 = merged_header
                # 실제 header1은 이미 합쳐졌으므로 데이터 행 결정 시 건너뜀
                # header1에서 성별/납입기간 탐색은 실제 row2부터 해야 함
                # row2를 임시 header1로 사용
                header1 = [normalize_ws(str(c or '')) for c in table[2]] if len(table) > 2 else []
        # row1에 '세만기'가 직접 있는 경우 (row0: 타이틀/종목, row1: 보험기간 헤더)
        # 예: row0=['구분','해약환급금 일부지급형...'], row1=['구분','90세만기','100세만기']
        if not has_manki and header1:
            if any('세만기' in h for h in header1):
                has_manki = True
                header_skip_rows = 1
                header0 = header1
                # 성별 서브헤더는 row2
                if len(table) > 2:
                    header1 = [normalize_ws(str(c or '')) for c in table[2]]
                else:
                    header1 = []
        # row2 이하에 '세만기'가 있는 경우 (타이틀 행 + 서브타이틀 행 + 보험기간 헤더)
        if not has_manki and len(table) > 2:
            for skip_rows in range(2, min(4, len(table))):
                candidate = [normalize_ws(str(c or '')) for c in table[skip_rows]]
                if any('세만기' in h or '만기' in h for h in candidate):
                    has_manki = True
                    header_skip_rows = skip_rows
                    header0 = candidate
                    # 성별 서브헤더는 candidate 다음 행
                    if len(table) > skip_rows + 1:
                        header1 = [normalize_ws(str(c or '')) for c in table[skip_rows + 1]]
                    else:
                        header1 = []
                    break
        has_gender_in_header = any(
            '남자' in h or '여자' in h or '남 자' in h or '여 자' in h
            for h in header1
        )
        # row1에 성별과 납입기간이 동시에 있으면 → row1은 데이터행, 성별은 행 레벨
        has_paym_in_header1 = any(
            re.match(r'^(전기납|일시납|종신납|\d+년납|\d+세납)', h)
            for h in header1 if h
        )
        row_level_gender = has_gender_in_header and has_paym_in_header1
        has_gender = has_gender_in_header and not row_level_gender

        if not has_manki:
            continue

        cur_table_group = table_group_idx
        table_group_idx += 1

        # 보험기간 열 매핑: col_idx -> 보험기간 텍스트
        # 성별 열 매핑: col_idx -> 성별 텍스트
        col_insurance: Dict[int, str] = {}
        col_gender: Dict[int, str] = {}

        # fill-right for header0 (보험기간)
        last_ins = ''
        for ci, cell in enumerate(header0):
            if '세만기' in cell or '만기' in cell or cell == '종신':
                last_ins = cell
            if last_ins and ci > 0:
                col_insurance[ci] = last_ins

        # 성별 매핑 (fill-right within each insurance group)
        if has_gender:
            gender_markers: Dict[int, str] = {}
            for ci, cell in enumerate(header1):
                if '남자' in cell or '남 자' in cell:
                    gender_markers[ci] = '1'
                elif '여자' in cell or '여 자' in cell:
                    gender_markers[ci] = '2'

            # fill-right: 각 열에 대해 해당 열 이하의 가장 가까운 gender marker
            last_gender = ''
            for ci in sorted(col_insurance.keys()):
                if ci in gender_markers:
                    last_gender = gender_markers[ci]
                col_gender[ci] = last_gender

        if not col_insurance:
            continue

        # 빈 성별이면 성별 구분 없음
        if not col_gender:
            for ci in col_insurance:
                col_gender[ci] = ''

        # 보험기간/성별 조합 시퀀스 구축 (순서대로)
        # 예: [(90세만기, 1), (90세만기, 2), (100세만기, 1), (100세만기, 2)]
        ins_periods_ordered = []
        last_ins_val = ''
        for ci in sorted(col_insurance.keys()):
            if col_insurance[ci] != last_ins_val:
                last_ins_val = col_insurance[ci]
                ins_periods_ordered.append(last_ins_val)

        age_slot_seq: List[Tuple[str, str]] = []
        if has_gender:
            genders_seen = []
            for ci in sorted(gender_markers.keys()):
                genders_seen.append(gender_markers[ci])
            genders_per_period = len(genders_seen) // max(len(ins_periods_ordered), 1) or 1
            for ins_p in ins_periods_ordered:
                for g in genders_seen[:genders_per_period]:
                    age_slot_seq.append((ins_p, g))
        else:
            for ins_p in ins_periods_ordered:
                age_slot_seq.append((ins_p, ''))

        # 데이터 행 파싱 - 순차 매핑
        data_start = 2 if has_gender else 1
        if header_skip_rows:
            data_start = header_skip_rows + (2 if has_gender else 1)
        prev_context = ''
        prev_paym = ''
        row_gender = ''  # row_level_gender 시 사용
        first_data_col = min(col_insurance.keys()) - 1  # 데이터는 헤더보다 1칸 앞에 올 수 있음

        for ri in range(data_start, len(table)):
            row = table[ri]
            cells = [normalize_ws(str(c or '')) for c in row]

            # row_level_gender: 첫 번째 열에서 성별 감지
            if row_level_gender:
                cell0 = cells[0] if cells else ''
                if '남자' in cell0 or '남 자' in cell0:
                    row_gender = '1'
                elif '여자' in cell0 or '여 자' in cell0:
                    row_gender = '2'
                # 납입기간 찾기: 모든 셀에서 납입기간 패턴 탐색
                paym_text = prev_paym
                for c in cells:
                    if re.match(r'^(전기납|일시납|종신납|\d+년납|\d+세납)', c):
                        paym_text = c
                        prev_paym = c
                        break
                context = ''
                data_start_col = 1  # 실제 데이터는 age_values 추출에서 처리
            else:
                # 첫 번째/두 번째 열: context(종목명) vs 납입기간 감지
                cell0 = cells[0] if cells else ''
                cell1 = cells[1] if len(cells) > 1 else ''

                # cell0이 납입기간 패턴이면 swap (context 없이 납입기간만 있는 테이블)
                is_paym_pattern = bool(re.match(
                    r'^(전기납|일시납|종신납|\d+년납|\d+세납)', cell0
                ))

                if is_paym_pattern:
                    context = prev_context  # 이전 context 유지
                    paym_text = cell0
                    if cell0:
                        prev_paym = cell0
                    data_start_col = 1  # 데이터는 col1부터 (context 열 없음)
                else:
                    context = cell0 if cell0 else prev_context
                    if cell0:
                        prev_context = cell0
                    paym_text = cell1 if cell1 else prev_paym
                    if cell1:
                        prev_paym = cell1
                    data_start_col = 2  # 데이터는 col2부터

            # 데이터 열에서 나이값 순서대로 추출
            # None/빈 셀(merged padding)은 건너뛰고, "-" 은 슬롯 소비(빈 데이터)
            # "7년납" 등 메타데이터 텍스트는 건너뜀
            age_values = []
            for ci in range(data_start_col, len(cells)):
                raw_val = row[ci] if ci < len(row) else None
                # raw_val이 None이거나 빈 문자열이면 merged cell padding → 건너뜀
                if raw_val is None or str(raw_val).strip() == '':
                    continue
                cell_text = cells[ci]
                parsed = _parse_age_cell(cell_text)
                if parsed:
                    age_values.append(parsed)
                elif cell_text == '-':
                    # 명시적 빈값: 슬롯만 소비
                    age_values.append(None)
                # 그 외 (납입기간 텍스트 등 메타데이터)는 건너뜀

            # 순차 매핑: age_values를 age_slot_seq에 매핑
            slot_idx = 0
            for parsed in age_values:
                if slot_idx >= len(age_slot_seq):
                    break
                ins_period, gender = age_slot_seq[slot_idx]
                if row_level_gender:
                    gender = row_gender
                slot_idx += 1
                if parsed is None:
                    continue
                min_a, max_a = parsed

                results.append({
                    'context': context,
                    '보험기간': ins_period,
                    '납입기간': paym_text,
                    '성별': gender,
                    '최소가입나이': min_a or '',
                    '최대가입나이': max_a,
                    'table_group': cur_table_group,
                })

    # 페이지 분할 후처리: 그룹 시작부의 빈 context에 이전 그룹의 마지막 context 상속
    if results:
        prev_ctx = ''
        for entry in results:
            if entry.get('context', ''):
                prev_ctx = entry['context']
            elif prev_ctx:
                entry['context'] = prev_ctx

    return results


def parse_variant_age_table(tables: List[List]) -> List[dict]:
    """종목별(1종/2종 등) x 성별(남자/여자) x 납입기간 형식 테이블 파싱 (상속H종신 등).

    Table format:
      '' | 1종(기납입플러스형) | '' | 2종(기본형) | ''
      구 분 | '' | '' | '' | ''
      '' | 남자 | 여자 | 남자 | 여자
      10년납 | 80세 | 80세 | 74세 | 78세
      15년납 | 68세 | 72세 | 68세 | 72세

    Returns: [{context, 납입기간, 성별, 최대가입나이}, ...]
    """
    results = []

    for table in tables:
        if not table or len(table) < 3:
            continue

        header0 = [normalize_ws(str(c or '')) for c in table[0]]
        header1 = [normalize_ws(str(c or '')) for c in table[1]] if len(table) > 1 else []

        # 2가지 헤더 포맷 지원:
        # Format A (3행): row0=종목명, row1=구분, row2=남자/여자, data=row3+
        # Format B (2행): row0=구분+종목명, row1=남자/여자, data=row2+
        has_variant_r0 = any(re.search(r'\d+종', h) for h in header0 if h)
        has_gubun_r0 = any('구' in h and '분' in h for h in header0)
        has_gubun_r1 = any('구' in h and '분' in h for h in header1)
        has_gender_r1 = any('남자' in h or '남 자' in h for h in header1)
        has_gender_r2 = False
        header2 = []
        if len(table) > 2:
            header2 = [normalize_ws(str(c or '')) for c in table[2]]
            has_gender_r2 = any('남자' in h or '남 자' in h for h in header2)

        variant_row = header0
        gender_row = []
        data_start = 0

        if has_variant_r0 and has_gubun_r1 and has_gender_r2:
            # Format A: 3행 헤더
            gender_row = header2
            data_start = 3
        elif has_variant_r0 and has_gubun_r0 and has_gender_r1:
            # Format B: 2행 헤더 (구분+종목명이 같은 행)
            gender_row = header1
            data_start = 2
        else:
            continue

        if len(table) <= data_start:
            continue

        # 종목 열 매핑: col_idx -> 종목명
        col_variant: Dict[int, str] = {}
        last_variant = ''
        for ci, cell in enumerate(variant_row):
            if cell and re.search(r'\d+종', cell):
                last_variant = cell
            if last_variant and ci > 0:
                col_variant[ci] = last_variant

        # 성별 열 매핑
        col_gender: Dict[int, str] = {}
        for ci, cell in enumerate(gender_row):
            if '남자' in cell or '남 자' in cell:
                col_gender[ci] = '1'
            elif '여자' in cell or '여 자' in cell:
                col_gender[ci] = '2'

        if not col_variant or not col_gender:
            continue

        # 데이터 행 파싱
        for ri in range(data_start, len(table)):
            row_cells = [normalize_ws(str(c or '')) for c in table[ri]]
            if not any(row_cells):
                continue

            paym_text = row_cells[0] if row_cells else ''
            if not paym_text:
                continue

            for ci in sorted(col_variant.keys()):
                if ci >= len(row_cells) or not row_cells[ci]:
                    continue
                m = re.search(r'(\d+)\s*세?', row_cells[ci])
                if not m:
                    continue
                variant = col_variant.get(ci, '')
                gender = col_gender.get(ci, '')
                results.append({
                    'context': variant,
                    '납입기간': paym_text,
                    '성별': gender,
                    '최대가입나이': m.group(1),
                })

    return results


def parse_direct_age_table(tables: List[List]) -> List[dict]:
    """'구 분 | 남자 | 여자' 형식의 직접 나이 테이블 파싱 (바로연금 등).

    Table format:
      구 분 | 남자 | 여자
      종신연금형(개인형) | 45 ~ 85세 | (empty = same)
      종신연금형(신부부형) | 48 ~ 85세 | 45 ~ 82세

    Returns: [{context, 성별, 최소가입나이, 최대가입나이}, ...]
    """
    results = []

    for table in tables:
        if not table or len(table) < 2:
            continue

        header = [normalize_ws(str(c or '')) for c in table[0]]
        header_text = ' '.join(header)

        # "구 분" + "남자"/"여자" 헤더 (but NOT with 만기 — that's the other parser)
        has_gubun = any('구' in h and '분' in h for h in header)
        has_gender_header = '남자' in header_text or '여자' in header_text
        has_manki = any('만기' in h for h in header)

        if not (has_gubun and has_gender_header and not has_manki):
            continue

        # 성별 열 찾기
        male_cols = [ci for ci, h in enumerate(header) if '남자' in h]
        female_cols = [ci for ci, h in enumerate(header) if '여자' in h]

        if not male_cols and not female_cols:
            continue

        for ri in range(1, len(table)):
            row = table[ri]
            cells = [normalize_ws(str(c or '')) for c in row]

            # 컨텍스트는 첫 비어있지 않은 열
            context = ''
            for ci, cell in enumerate(cells):
                if cell and ci not in male_cols and ci not in female_cols:
                    context = cell
                    break

            if not context:
                continue

            # 남자 나이 추출
            for ci in male_cols:
                if ci < len(cells):
                    parsed = _parse_age_cell(cells[ci])
                    if parsed:
                        min_a, max_a = parsed
                        results.append({
                            'context': context,
                            '성별': '1',
                            '최소가입나이': min_a or '',
                            '최대가입나이': max_a,
                        })

            # 여자 나이 추출
            for ci in female_cols:
                if ci < len(cells):
                    parsed = _parse_age_cell(cells[ci])
                    if parsed:
                        min_a, max_a = parsed
                        results.append({
                            'context': context,
                            '성별': '2',
                            '최소가입나이': min_a or '',
                            '최대가입나이': max_a,
                        })

            # 남자 열에 값이 있고 여자 열이 비어있으면 → 성별 구분 없음
            if male_cols and not female_cols:
                pass  # already handled
            elif male_cols and female_cols:
                # Check if female columns are all empty for this row
                female_empty = all(
                    not normalize_ws(str(cells[ci] if ci < len(cells) else ''))
                    for ci in female_cols
                )
                if female_empty:
                    # 남자 값을 성별 없음으로 변환
                    for ci in male_cols:
                        if ci < len(cells):
                            parsed = _parse_age_cell(cells[ci])
                            if parsed:
                                min_a, max_a = parsed
                                # Remove the male-specific entry, add gender-neutral
                                results = [r for r in results
                                           if not (r['context'] == context and r['성별'] == '1'
                                                   and r['최대가입나이'] == max_a)]
                                results.append({
                                    'context': context,
                                    '성별': '',
                                    '최소가입나이': min_a or '',
                                    '최대가입나이': max_a,
                                })

    return results


def parse_period_gender_age_table(tables: List[List], lines: List[str] = None) -> List[dict]:
    """'보험기간 | 납입기간 | 남자 | 여자' 형식의 테이블 파싱 (경영인H정기 등).

    Table format:
      보험기간 | 납입기간 | 남자 | 여자
      90세만기 | 전기납   | 27~75세 | 45~75세

    lines를 통해 테이블 직전 paragraph에서 종목 context를 추출.

    Returns: [{context, 성별, 최소가입나이, 최대가입나이, 보험기간, 납입기간}, ...]
    """
    results = []

    # lines에서 가입나이 섹션 내 종목 context 라벨을 순서대로 수집
    # (가) 1종(...), (나) 2종(...), (다) 3종(...) 등
    context_labels: List[str] = []
    if lines:
        in_age_section = False
        for line in lines:
            stripped = line.strip()
            # 가입나이 섹션 시작 감지
            if '가입나이' in stripped and ('보험기간' in stripped or '납입기간' in stripped):
                in_age_section = True
                continue
            if in_age_section:
                # 다른 주요 섹션 시작시 종료 (나. 보험료 납입주기 등)
                if re.match(r'^나\.\s', stripped):
                    break
                # (가), (나), (다) 등 종목 라벨 수집
                if re.match(r'^\([가-힣]\)\s', stripped):
                    context_labels.append(stripped)

    # 매칭 테이블 인덱스 (context_labels와 순서 대응)
    matching_table_idx = 0

    for table in tables:
        if not table or len(table) < 2:
            continue

        header = [normalize_ws(str(c or '')) for c in table[0]]
        header_text = ' '.join(header)

        # 헤더 조건: "보험기간" + "납입기간" + ("남자" or "여자")
        has_ins_period = any('보험기간' in h for h in header)
        has_paym_period = any('납입기간' in h for h in header)
        has_gender_header = '남자' in header_text or '여자' in header_text

        if not (has_ins_period and has_paym_period and has_gender_header):
            continue

        # context: 순서대로 context_labels 할당, (가/나/다) 라벨 prefix 제거
        context = ''
        if matching_table_idx < len(context_labels):
            raw_ctx = context_labels[matching_table_idx]
            # "(가) 1종(...)" → "1종(...)"
            context = re.sub(r'^\([가-힣]\)\s*', '', raw_ctx)
        matching_table_idx += 1

        # 열 인덱스 찾기
        ins_col = next((ci for ci, h in enumerate(header) if '보험기간' in h), -1)
        paym_col = next((ci for ci, h in enumerate(header) if '납입기간' in h), -1)
        male_cols = [ci for ci, h in enumerate(header) if '남자' in h]
        female_cols = [ci for ci, h in enumerate(header) if '여자' in h]

        if ins_col < 0 or paym_col < 0 or (not male_cols and not female_cols):
            continue

        for ri in range(1, len(table)):
            row = table[ri]
            cells = [normalize_ws(str(c or '')) for c in row]

            ins_text = cells[ins_col] if ins_col < len(cells) else ''
            paym_text = cells[paym_col] if paym_col < len(cells) else ''

            for ci in male_cols:
                if ci < len(cells):
                    parsed = _parse_age_cell(cells[ci])
                    if parsed:
                        min_a, max_a = parsed
                        results.append({
                            'context': context,
                            '성별': '1',
                            '최소가입나이': min_a or '',
                            '최대가입나이': max_a,
                            '보험기간': ins_text,
                            '납입기간': paym_text,
                        })

            for ci in female_cols:
                if ci < len(cells):
                    parsed = _parse_age_cell(cells[ci])
                    if parsed:
                        min_a, max_a = parsed
                        results.append({
                            'context': context,
                            '성별': '2',
                            '최소가입나이': min_a or '',
                            '최대가입나이': max_a,
                            '보험기간': ins_text,
                            '납입기간': paym_text,
                        })

    return results


def parse_inline_age_table(tables: List[List]) -> List[dict]:
    """납입기간/보험기간 + 만N~M세 패턴의 간단한 테이블 파싱.

    H기업재해보장보험 등:
      구 분 | 3년만기
      전기납 | 만15~70세

    포켓골절보험/실손의료비 등:
      보험기간 | 가입나이 | 납입기간 | 납입주기
      1년만기 | 만19세~65세 | 일시납 | 일시납
    """
    results = []

    for table in tables:
        if not table or len(table) < 2:
            continue

        # 모든 헤더 행 합치기 (일부 테이블은 헤더가 2행에 걸침)
        all_header = []
        for hi in range(min(2, len(table))):
            all_header.extend(normalize_ws(str(c or '')) for c in table[hi])
        header_text = ' '.join(all_header)

        # 특약/태아보장 테이블 제외
        if '태아보장' in header_text or '태아보장기간' in header_text:
            continue

        header = [normalize_ws(str(c or '')) for c in table[0]]

        # Pattern 1: "구 분 | N년만기 | N년만기" (no 성별 sub-headers)
        manki_cols: Dict[int, str] = {}
        for ci, h in enumerate(header):
            if '만기' in h and ci > 0:
                manki_cols[ci] = h

        if manki_cols:
            for ri in range(1, len(table)):
                row = table[ri]
                cells = [normalize_ws(str(c or '')) for c in row]
                paym_text = cells[0] if cells else ''

                for ci, ins_text in manki_cols.items():
                    if ci >= len(cells):
                        continue
                    parsed = _parse_age_cell(cells[ci])
                    if not parsed:
                        continue
                    min_a, max_a = parsed
                    results.append({
                        'context': '',
                        '보험기간': ins_text,
                        '납입기간': paym_text,
                        '성별': '',
                        '최소가입나이': min_a or '',
                        '최대가입나이': max_a,
                    })
            continue

        # Pattern 2: "가입나이 + 보험기간" 키워드가 헤더에 있는 테이블
        # 셀이 offset되어 있을 수 있으므로 데이터 행에서 값을 직접 추출
        has_age_header = '가입나이' in header_text
        has_ins_header = '보험기간' in header_text

        if has_age_header:
            for ri in range(1, len(table)):
                row = table[ri]
                cells = [normalize_ws(str(c or '')) for c in row]

                # 데이터 셀에서 나이, 보험기간, 납입기간을 값 기반으로 탐색
                age_val = None
                ins_text = ''
                paym_text = ''
                for cell in cells:
                    if not cell:
                        continue
                    parsed = _parse_age_cell(cell)
                    if parsed and age_val is None:
                        age_val = parsed
                    elif re.search(r'\d+년만기|\d+년$|종신', cell) and not ins_text:
                        ins_text = cell
                    elif re.match(r'(전기납|일시납|종신납|\d+년납|\d+세납)', cell) and not paym_text:
                        paym_text = cell
                    elif re.match(r'\d+년$', cell) and not ins_text:
                        ins_text = cell

                if age_val:
                    min_a, max_a = age_val
                    results.append({
                        'context': '',
                        '보험기간': ins_text,
                        '납입기간': paym_text,
                        '성별': '',
                        '최소가입나이': min_a or '',
                        '최대가입나이': max_a,
                    })

    return results


def parse_spin_paym_age_table(tables: List[List], lines: Optional[List[str]] = None) -> List[dict]:
    """연금개시나이 x 납입기간 매트릭스 테이블 파싱 (연금보험Enterprise 등).

    Table format:
      연금개시나이 | 거치형 | 적립형(7년납, 10년납, 15년납, 20년납, 전기납)
      45세         | 41    | 34 | 32 | 29 | 24 | 31
      46세         | 42    | 35 | 33 | 30 | 25 | 32
    """
    results = []

    # 텍스트에서 "[남자]" / "[여자]" 라벨 위치 파악
    gender_labels: List[Tuple[int, str]] = []  # (line_idx, gender_code)
    if lines:
        for i, line in enumerate(lines):
            nl = normalize_ws(line)
            if re.search(r'[\[\[]\s*남\s*자?\s*[\]\]]', nl):
                gender_labels.append((i, '1'))
            elif re.search(r'[\[\[]\s*여\s*자?\s*[\]\]]', nl):
                gender_labels.append((i, '2'))

    spin_table_idx = 0

    for table in tables:
        if not table or len(table) < 3:
            continue

        header0 = [normalize_ws(str(c or '')) for c in table[0]]
        header_text = ' '.join(header0)

        # "연금개시나이" 헤더 확인
        if '연금개시나이' not in header_text:
            continue

        # 성별 라벨 결정: N번째 SPIN 테이블 → N번째 gender_label
        table_gender = ''
        if gender_labels and spin_table_idx < len(gender_labels):
            table_gender = gender_labels[spin_table_idx][1]
        spin_table_idx += 1

        # 납입기간 서브헤더 (2행)
        header1 = [normalize_ws(str(c or '')) for c in table[1]] if len(table) > 1 else []

        # 납입기간 열 매핑: 헤더에서 "N년납" 또는 "전기납" 찾기
        paym_cols: Dict[int, str] = {}
        # 거치형/적립형 구분
        type_cols: Dict[int, str] = {}

        for ci, cell in enumerate(header0):
            if '거치형' in cell:
                type_cols[ci] = '거치형'
            elif '적립형' in cell or '적 립 형' in cell:
                type_cols[ci] = '적립형'

        for ci, cell in enumerate(header1):
            if re.match(r'(\d+년납|\d+세납|전기납|일시납)', cell):
                paym_cols[ci] = cell

        if not paym_cols:
            continue

        # 거치형 열: header0에서 '거치형' 위치, 해당 데이터는 다음 열
        geochi_col = None
        for ci, cell in enumerate(header0):
            if '거치형' in cell:
                geochi_col = ci
                break

        # 데이터 행: 연금개시나이(세) → MAX_AG 값들
        for ri in range(2, len(table)):
            row = table[ri]
            cells = [normalize_ws(str(c or '')) for c in row]

            # 첫 번째 열: 연금개시나이 (N세)
            spin_m = re.search(r'(\d+)\s*세', cells[0] if cells else '')
            if not spin_m:
                continue
            spin_val = spin_m.group(1)

            # 거치형 MAX_AG
            if geochi_col is not None:
                # 거치형 데이터는 보통 geochi_col+1에 있음 (셀 offset)
                for offset in range(0, 3):
                    ci = geochi_col + offset
                    if ci < len(cells) and cells[ci] and re.match(r'\d+$', cells[ci]):
                        results.append({
                            'context': '거치형',
                            'spin': spin_val,
                            '납입기간': '',
                            '성별': table_gender,
                            '최대가입나이': cells[ci],
                        })
                        break

            # 적립형 각 납입기간별 MAX_AG
            # 순차 매핑: paym_cols의 값과 데이터 행의 숫자 값 매핑
            data_values = []
            for ci in range(1, len(cells)):
                if cells[ci] and re.match(r'\d+$', cells[ci]):
                    data_values.append(cells[ci])

            # 거치형 값은 첫 번째 → 나머지는 적립형 납입기간 순서
            paym_list = [paym_cols[ci] for ci in sorted(paym_cols.keys())]
            for idx, paym_text in enumerate(paym_list):
                data_idx = idx + 1  # 거치형이 첫 번째
                if data_idx < len(data_values):
                    results.append({
                        'context': '적립형',
                        'spin': spin_val,
                        '납입기간': paym_text,
                        '성별': table_gender,
                        '최대가입나이': data_values[data_idx],
                    })

    return results


def parse_max_age_table(tables: List[List]) -> List[dict]:
    """'구분 | 최고가입나이' 형식 테이블 파싱 (H종신 등).

    Table format:
      구분 | [납입기간] | [성별] | 최고가입나이
      해약환급금 일부지급형I | 5년납 | 남자 | 64세
                             |       | 여자 | 64세
    """
    results = []
    table_group_idx = 0

    for table in tables:
        if not table or len(table) < 2:
            continue

        # 헤더 확인: "최고가입나이" 포함 여부
        header = [normalize_ws(str(c or '')) for c in table[0]]
        age_col = -1
        for ci, cell in enumerate(header):
            if '최고가입나이' in cell or '최고나이' in cell:
                age_col = ci
                break
        if age_col < 0:
            continue

        cur_table_group = table_group_idx
        table_group_idx += 1
        prev_context = ''
        prev_paym = ''

        for ri in range(1, len(table)):
            row = table[ri]
            cells = [normalize_ws(str(c or '')) for c in row]

            # fill-down for context and payment period
            context = ''
            paym = ''
            gender = ''
            max_age_text = ''

            for ci, cell in enumerate(cells):
                if ci == age_col:
                    max_age_text = cell
                elif ci == 0:
                    context = cell if cell else prev_context
                    if cell:
                        prev_context = cell
                elif '납' in cell or '년납' in cell or '일시납' in cell or '전기납' in cell:
                    paym = cell if cell else prev_paym
                    if cell:
                        prev_paym = cell
                elif cell in ('남자', '여자'):
                    gender = '1' if cell == '남자' else '2'
                elif not cell and ci == 1:
                    paym = prev_paym

            if not max_age_text:
                continue

            m = re.search(r'(\d+)\s*세?', max_age_text)
            if not m:
                continue

            # 납입기간이 없으면 prev_paym 사용
            if not paym:
                paym = prev_paym

            # context가 납입기간 패턴이면 납입기간으로도 사용
            if not paym and re.match(r'(\d+년납|\d+세납|전기납|일시납|종신납)', context):
                paym = context

            results.append({
                'context': context,
                '납입기간': paym,
                '성별': gender,
                '최대가입나이': m.group(1),
                'table_group': cur_table_group,
            })

    # 페이지 분할 후처리: 빈 context/납입기간에 이전 값 상속
    if results:
        prev_ctx = ''
        prev_paym_carry = ''
        for entry in results:
            if entry.get('context', ''):
                prev_ctx = entry['context']
            elif prev_ctx:
                entry['context'] = prev_ctx
            if entry.get('납입기간', ''):
                prev_paym_carry = entry['납입기간']
            elif prev_paym_carry:
                entry['납입기간'] = prev_paym_carry

    # 소규모 continuation 그룹을 이전 그룹에 병합
    if results:
        from collections import defaultdict, Counter
        grp_counts = Counter(e.get('table_group', 0) for e in results)
        if len(grp_counts) > 1:
            median_cnt = sorted(grp_counts.values())[len(grp_counts) // 2]
            prev_grp = None
            for entry in results:
                g = entry.get('table_group', 0)
                if grp_counts[g] <= max(2, median_cnt * 0.3) and prev_grp is not None:
                    entry['table_group'] = prev_grp
                else:
                    prev_grp = g

    return results


def _normalize_roman(text: str) -> str:
    """로마 숫자 정규화: Ⅰ→I, Ⅱ→II, Ⅲ→III."""
    return text.replace('Ⅰ', 'I').replace('Ⅱ', 'II').replace('Ⅲ', 'III')


def _token_in_name(token: str, name_compact: str) -> bool:
    """토큰이 name_compact에 포함되는지 확인 (로마 숫자/숫자 경계 고려)."""
    if token not in name_compact:
        return False
    idx = name_compact.index(token)
    end_idx = idx + len(token)
    # 로마 숫자 경계: "I"가 "II"에 매칭되지 않도록
    if token and token[-1] in 'IVX':
        if end_idx < len(name_compact) and name_compact[end_idx] in 'IVX':
            return False
    # 숫자 경계: "0년"이 "10년"에 매칭되지 않도록
    if token and token[0].isdigit() and idx > 0 and name_compact[idx - 1].isdigit():
        return False
    return True


def match_context_to_product(context: str, product_name: str) -> bool:
    """테이블의 context(종목명)가 상품명에 매칭되는지 확인."""
    if not context:
        return True
    # 납입기간 패턴 (예: "10년납", "전기납", "일시납")은 종목이 아니므로 매칭으로 처리
    if re.match(r'^(\d+년납|\d+세납|전기납|일시납|종신납)$', context.strip()):
        return True
    # 괄호/공백 제거하여 비교
    strip_re = re.compile(r'[\s\(\)\[\]（）]')
    ctx_compact = _normalize_roman(strip_re.sub('', context))
    name_compact = _normalize_roman(strip_re.sub('', product_name))
    # 로마 숫자 경계 처리: "I"가 "II"에 매칭되지 않도록 뒤에 로마 숫자가 오면 불일치
    if ctx_compact in name_compact:
        idx = name_compact.index(ctx_compact)
        end_idx = idx + len(ctx_compact)
        # ctx가 로마 숫자로 끝나면, name에서 뒤에 추가 로마 숫자가 오지 않는지 확인
        if ctx_compact and ctx_compact[-1] in 'IVX':
            if end_idx < len(name_compact) and name_compact[end_idx] in 'IVX':
                pass  # 경계 불일치 → 매칭 실패 (계속 다음 확인으로)
            else:
                return True
        else:
            return True
    # 토큰 기반 매칭: 괄호/공백으로 분리된 토큰들이 모두 product_name에 포함되는지
    ctx_tokens = [t for t in re.split(r'[\s\(\)\[\]（）,，]+', _normalize_roman(context)) if t]
    if ctx_tokens and all(_token_in_name(t, name_compact) for t in ctx_tokens):
        return True
    # "N년형" → "N년" 등 trailing "형" 제거 후 재시도
    if ctx_tokens:
        relaxed = [re.sub(r'(\d+년)형$', r'\1', t) for t in ctx_tokens]
        if relaxed != ctx_tokens and all(_token_in_name(t, name_compact) for t in relaxed):
            return True
    return False


def build_table_age_records(
    min_age: str,
    table_entries: List[dict],
    product_name: str,
    target_table_group: Optional[int] = None,
    collapse_gender: bool = False,
) -> List[dict]:
    """테이블 파싱 결과를 상품 context에 맞게 필터링하여 가입가능나이 레코드 생성."""
    records = []
    seen = set()

    # 먼저 context 매칭으로 필터링 시도
    filtered_entries = []
    for entry in table_entries:
        if target_table_group is not None and entry.get('table_group', 0) != target_table_group:
            continue
        if match_context_to_product(entry.get('context', ''), product_name):
            filtered_entries.append(entry)
    # context 매칭 결과가 없으면, 모든 항목 사용 (context가 상품명과 무관한 분류인 경우)
    if not filtered_entries:
        for entry in table_entries:
            if target_table_group is not None and entry.get('table_group', 0) != target_table_group:
                continue
            filtered_entries.append(entry)

    for entry in filtered_entries:
        ins_text = entry.get('보험기간', '')
        paym_text = entry.get('납입기간', '')
        gender = '' if collapse_gender else entry.get('성별', '')
        max_age = entry.get('최대가입나이', '')
        # 테이블에서 추출된 최소나이가 있으면 사용, 없으면 글로벌 min_age
        entry_min_age = entry.get('최소가입나이', '') or min_age

        # 보험기간 코드 변환
        ins_code, ins_dvsn, ins_val = format_period(ins_text)

        # 납입기간 코드 변환
        paym_code, paym_dvsn, paym_val = format_period(paym_text)

        # 전기납의 경우 납입기간 = 보험기간
        if '전기납' in paym_text and ins_text:
            paym_code, paym_dvsn, paym_val = ins_code, ins_dvsn, ins_val

        key = (entry_min_age, max_age, gender,
               ins_val, ins_val, ins_dvsn,
               paym_val, paym_val, paym_dvsn)
        if key in seen:
            continue
        seen.add(key)

        records.append({
            '성별': gender,
            '최소가입나이': entry_min_age,
            '최대가입나이': max_age,
            '최소납입기간': paym_val,
            '최대납입기간': paym_val,
            '납입기간구분코드': paym_dvsn,
            '최소제2보기개시나이': '',
            '최대제2보기개시나이': '',
            '제2보기개시나이구분코드': '',
            '최소보험기간': ins_val,
            '최대보험기간': ins_val,
            '보험기간구분코드': ins_dvsn,
        })

    # ── 구분 없는 기간 필드 제거 ──
    # 기간 필드가 하나의 고유 비어있지 않은 값만 가지면 구분 역할이 아니므로 비운다
    # 업무 규칙: 납입기간이 복수이면 보험기간도 유지
    ins_fields = ('최소보험기간', '최대보험기간', '보험기간구분코드')
    paym_fields = ('최소납입기간', '최대납입기간', '납입기간구분코드')
    spin_fields = ('최소제2보기개시나이', '최대제2보기개시나이', '제2보기개시나이구분코드')

    def _count_unique(fields):
        unique = set()
        for r in records:
            val = tuple(r[f] for f in fields)
            if any(v for v in val):
                unique.add(val)
        return len(unique)

    def _clear(fields):
        for r in records:
            for f in fields:
                r[f] = ''

    paym_unique = _count_unique(paym_fields)
    ins_unique = _count_unique(ins_fields)

    if paym_unique <= 1:
        _clear(paym_fields)
    if ins_unique <= 1 and paym_unique <= 1:
        _clear(ins_fields)
    if _count_unique(spin_fields) <= 1:
        _clear(spin_fields)

    return records


# ──────────────────────────────────────────────
# 성별 구분 감지
# ──────────────────────────────────────────────

def detect_gender_split(lines: List[str], tables: List[List]) -> bool:
    """PDF에 성별 구분이 있는지 감지."""
    sec_start, sec_end = _find_age_section(lines)
    passed_spin_section = False
    for i in range(sec_start, sec_end):
        line = lines[i]
        # 연금개시나이 섹션 시작 이후의 성별 구분은 SPIN에 대한 것이므로 무시
        if re.match(r'^[가-힣라마]\.\s*연금개시나이', line) or (line.strip() == '연금개시나이' and i > sec_start):
            passed_spin_section = True
        if '남자' in line and '여자' in line and not passed_spin_section:
            return True

    for table in tables:
        if not table:
            continue
        header_text = ' '.join(str(c or '') for c in table[0])
        # 연금개시나이 전용 테이블은 가입나이 테이블이 아님 — 성별 무시
        if '연금개시나이' in header_text and '가입' not in header_text:
            continue
        for row in table[:3]:
            row_text = ' '.join(str(c or '') for c in row)
            if '남자' in row_text and '여자' in row_text:
                return True

    return False


# ──────────────────────────────────────────────
# 텍스트에서 직접 성별별 나이 범위 추출
# ──────────────────────────────────────────────

def extract_text_gender_ages(lines: List[str]) -> List[dict]:
    """텍스트에서 "보험기간 납입기간 남자 여자" 패턴의 나이 범위 추출.

    예: "90세만기 전기납 27~75세 45~75세"
    """
    sec_start, sec_end = _find_age_section(lines)
    results = []

    # 먼저 "보험기간 납입기간 남자 여자" 헤더 행을 찾기
    prev_context = ''
    for i in range(sec_start, sec_end):
        line = lines[i]
        # context 추적: "(가) 1종(...)", "(1) 순수보장형" 등의 서브섹션 헤더
        ctx_m = re.match(r'^\s*[\(\（]([가-힣\d]+)\s*[\)\）]\s*(.+)', line)
        if ctx_m:
            prev_context = ctx_m.group(2).strip()
        if ('보험기간' in line and '납입기간' in line and
                ('남자' in line or '여자' in line)):
            cur_context = prev_context
            # 이후 데이터 행 파싱
            for j in range(i + 1, min(i + 20, sec_end)):
                data_line = lines[j]
                # 다음 섹션/서브섹션 헤더이면 중지
                # (가), (나), (1), 가., 나. 등
                if re.match(r'^[\(\（][가-힣\d]+[\)\）]\s', data_line):
                    break
                if re.match(r'^[가-힣\d][\.\)]\s', data_line):
                    break

                # "90세만기 전기납 27~75세 45~75세" 패턴
                # "90세만기 일시납 24~75세 36~75세" 패턴
                age_ranges = list(re.finditer(r'(\d+)\s*[~\-]\s*(\d+)\s*세', data_line))
                if len(age_ranges) >= 2:
                    # 보험기간/납입기간 추출
                    ins_m = re.search(r'(\d+)\s*세만기|종신', data_line)
                    paym_m = re.search(r'전기납|일시납|(\d+)\s*년납|(\d+)\s*세납|종신납', data_line)

                    ins_text = ins_m.group(0) if ins_m else ''
                    paym_text = paym_m.group(0) if paym_m else ''

                    # 남자 (첫 번째 범위)
                    results.append({
                        'context': cur_context,
                        '보험기간': ins_text,
                        '납입기간': paym_text,
                        '성별': '1',
                        '최소가입나이': age_ranges[0].group(1),
                        '최대가입나이': age_ranges[0].group(2),
                    })
                    # 여자 (두 번째 범위)
                    results.append({
                        'context': cur_context,
                        '보험기간': ins_text,
                        '납입기간': paym_text,
                        '성별': '2',
                        '최소가입나이': age_ranges[1].group(1),
                        '최대가입나이': age_ranges[1].group(2),
                    })
                elif len(age_ranges) == 1:
                    ins_m = re.search(r'(\d+)\s*세만기|종신', data_line)
                    paym_m = re.search(r'전기납|일시납|(\d+)\s*년납|(\d+)\s*세납|종신납', data_line)
                    ins_text = ins_m.group(0) if ins_m else ''
                    paym_text = paym_m.group(0) if paym_m else ''
                    results.append({
                        'context': cur_context,
                        '보험기간': ins_text,
                        '납입기간': paym_text,
                        '성별': '',
                        '최소가입나이': age_ranges[0].group(1),
                        '최대가입나이': age_ranges[0].group(2),
                    })

    return results


def parse_double_gubun_age_table(
    tables: List[List],
    lines: List[str],
    product_name: str,
) -> List[dict]:
    """이중 '구분' 컬럼 + 가입나이 컬럼 테이블 파싱 (Wealth단체저축보험 등).

    Table format (docx Table):
      row0: ['구    분', '구    분', '가입나이']  ← 헤더: col0/col1이 모두 '구분', col2가 '가입나이'
      row1: ['3년만기',  '3년만기',  '만 15세 ~ 75세']  ← col0==col1 → 전기납 행
      row5: ['60세만기', '5년납',    '만 15세 ~ 53세']  ← col0≠col1 → 특정 납입기간 행

    Text lines에서 보험기간 → [납입기간] 매핑을 보조 정보로 추출:
      예) "3년만기: 전기납", "10년만기: 5년납, 7년납, 전기납"

    알고리즘:
    - col0==col1인 행(전기납 행): 해당 보험기간에 허용된 전체 납입기간 목록을 텍스트에서 읽어
      * 납입기간이 1종류('전기납')이면 전기납 단일 레코드 생성
      * 납입기간이 여러 종류면 min_paym~max_paym 범위 레코드 1개 생성
    - col0≠col1인 행: col1을 납입기간으로 사용, 단일 레코드 생성

    Returns: 표준 가입가능나이 레코드 목록
    """
    results = []

    # ── 1) 해당 테이블 찾기 ──
    target_table: Optional[List] = None
    for table in tables:
        if not table or len(table) < 2:
            continue
        header = [normalize_ws(str(c or '')) for c in table[0]]
        if len(header) < 3:
            continue
        # 헤더의 첫 두 셀에 '구분'이 포함되고, 세 번째 셀에 '가입나이'가 포함돼야 함
        gubun_count = sum(1 for h in header[:2] if '구분' in h.replace(' ', ''))
        has_join_age = any('가입나이' in h for h in header[2:4])
        if gubun_count >= 2 and has_join_age:
            target_table = table
            break

    if target_table is None:
        return results

    # ── 2) 텍스트 lines에서 보험기간 → 납입기간 목록 매핑 파싱 ──
    # 패턴: "3년만기 : 전기납", "10년만기 : 5년납, 7년납, 전기납" 등
    # 또는 "- 3년만기: 전기납" 형태
    paym_map: Dict[str, List[str]] = {}
    for line in lines:
        # 보험기간 레이블과 납입기간 목록이 같은 줄에 있는 패턴
        m = re.match(
            r'.*?(\d+[년세]만기|\d+년)\s*[:：]\s*(.+)',
            line.replace('\u200b', '')
        )
        if m:
            ins_raw = normalize_ws(m.group(1))
            payms_raw = m.group(2)
            # 납입기간 파싱: "5년납, 7년납, 전기납"
            payms: List[str] = []
            for part in re.split(r'[,，、]', payms_raw):
                part = normalize_ws(part)
                if part and ('년납' in part or '전기납' in part or '일시납' in part):
                    payms.append(part)
            if payms:
                paym_map[ins_raw] = payms

    # ── 3) 테이블 데이터 행 파싱 ──
    prev_ins_raw = ''
    for ri in range(1, len(target_table)):
        row = target_table[ri]
        cells = [normalize_ws(str(c or '')) for c in row]
        if len(cells) < 3:
            continue

        col0 = cells[0]
        col1 = cells[1]
        col2 = cells[2]  # 가입나이 셀

        if not col0 and not col1 and not col2:
            continue

        # fill-down: col0이 비어 있으면 이전 행 값 유지
        if col0:
            prev_ins_raw = col0
        ins_raw = prev_ins_raw

        # 가입나이 파싱
        age_parsed = _parse_age_cell(col2)
        if not age_parsed:
            continue
        min_age_val, max_age_val = age_parsed
        if not max_age_val:
            continue
        min_age_val = min_age_val or ''

        # 보험기간 코드화
        ins_code, ins_dvsn, ins_val = format_period(ins_raw)
        if not ins_val:
            continue

        if col0 == col1:
            # ── 전기납 행: 텍스트 매핑으로 납입기간 목록 확장 ──
            mapped_payms = paym_map.get(ins_raw, [])

            if len(mapped_payms) <= 1:
                # 납입기간이 전기납뿐이거나 매핑 없음 → 단순 전기납 레코드
                # 전기납: paym = ins
                results.append({
                    '성별': '',
                    '최소가입나이': min_age_val,
                    '최대가입나이': max_age_val,
                    '최소보험기간': ins_val,
                    '최대보험기간': ins_val,
                    '보험기간구분코드': ins_dvsn,
                    '최소납입기간': ins_val,
                    '최대납입기간': ins_val,
                    '납입기간구분코드': ins_dvsn,
                    '최소제2보기개시나이': '',
                    '최대제2보기개시나이': '',
                    '제2보기개시나이구분코드': '',
                })
            else:
                # 납입기간이 여러 종류 → 최소~최대 납입기간 범위 레코드
                # 숫자 납입기간 수집 + '전기납'이 있으면 ins_val을 상한으로 사용
                has_jeon = any('전기납' in p for p in mapped_payms)
                num_payms: List[int] = []
                for p in mapped_payms:
                    pm = re.search(r'(\d+)\s*년납', p)
                    if pm:
                        num_payms.append(int(pm.group(1)))
                # 전기납이 있고 ins_dvsn == 'N'이면 ins_val을 최대 납입기간으로 포함
                if has_jeon and ins_dvsn == 'N' and ins_val.isdigit():
                    num_payms.append(int(ins_val))
                if num_payms:
                    min_paym = str(min(num_payms))
                    max_paym = str(max(num_payms))
                    results.append({
                        '성별': '',
                        '최소가입나이': min_age_val,
                        '최대가입나이': max_age_val,
                        '최소보험기간': ins_val,
                        '최대보험기간': ins_val,
                        '보험기간구분코드': ins_dvsn,
                        '최소납입기간': min_paym,
                        '최대납입기간': max_paym,
                        '납입기간구분코드': 'N',
                        '최소제2보기개시나이': '',
                        '최대제2보기개시나이': '',
                        '제2보기개시나이구분코드': '',
                    })
                else:
                    # 숫자 납입기간 없음 (전기납만) → 전기납 단일 레코드
                    results.append({
                        '성별': '',
                        '최소가입나이': min_age_val,
                        '최대가입나이': max_age_val,
                        '최소보험기간': ins_val,
                        '최대보험기간': ins_val,
                        '보험기간구분코드': ins_dvsn,
                        '최소납입기간': ins_val,
                        '최대납입기간': ins_val,
                        '납입기간구분코드': ins_dvsn,
                        '최소제2보기개시나이': '',
                        '최대제2보기개시나이': '',
                        '제2보기개시나이구분코드': '',
                    })
        else:
            # ── 특정 납입기간 행 ──
            paym_text = col1
            paym_code, paym_dvsn, paym_val = format_period(paym_text)
            if '전기납' in paym_text:
                # 전기납은 paym = ins
                paym_dvsn = ins_dvsn
                paym_val = ins_val
            if not paym_val:
                continue
            results.append({
                '성별': '',
                '최소가입나이': min_age_val,
                '최대가입나이': max_age_val,
                '최소보험기간': ins_val,
                '최대보험기간': ins_val,
                '보험기간구분코드': ins_dvsn,
                '최소납입기간': paym_val,
                '최대납입기간': paym_val,
                '납입기간구분코드': paym_dvsn,
                '최소제2보기개시나이': '',
                '최대제2보기개시나이': '',
                '제2보기개시나이구분코드': '',
            })

    return results


# ──────────────────────────────────────────────
# 메인 병합 함수
# ──────────────────────────────────────────────

def merge_join_age_info(
    row: dict,
    pdf_lines: List[str],
    pdf_tables: List[List],
    period_data: List[dict],
) -> dict:
    """상품 레코드 + PDF 데이터 + 기존 보기납기 데이터 → 가입가능나이 레코드 생성."""
    product_name = normalize_ws(str(row.get('상품명', '')))
    is_annuity = is_annuity_product(row)

    output = {
        '상품명칭': normalize_ws(str(row.get('상품명칭', ''))),
    }
    for key in sorted(row.keys()):
        if key.startswith('세부종목'):
            output[key] = row[key]
    output['상품명'] = product_name
    output['가입가능나이'] = []

    # 최소가입나이 추출
    min_age = extract_min_age(pdf_lines)

    # 상품별 조건부 최소가입나이 오버라이드 ("단, N종의 경우 가입최저나이는 M세")
    for line in pdf_lines:
        m = re.search(r'단,?\s*(.+?)의\s*경우\s*가입최저나이[는은]\s*(\d+)\s*세', line)
        if m:
            condition = m.group(1)
            override_age = m.group(2)
            # condition의 핵심 키워드가 상품명에 포함되면 오버라이드
            if match_context_to_product(condition, product_name):
                min_age = override_age

    # 전략 감지
    strategy = detect_max_age_strategy(pdf_lines, pdf_tables)
    has_gender = detect_gender_split(pdf_lines, pdf_tables)

    age_records: List[dict] = []

    # ── 0-pre) 상생협력형 텍스트 전용 추출 ──
    # docx에 "(나) 상생협력형" 섹션이 있고, 상품명에 "상생"이 포함된 경우
    # 테이블 파서가 일반형 데이터를 가져오는 것을 방지하기 위해 최우선 처리
    if '상생' in product_name:
        in_sangsaeng = False
        sangsaeng_min = None
        sangsaeng_max = None
        for li, line in enumerate(pdf_lines):
            if re.search(r'상생협력형\s*$', line) or re.match(r'^\s*\d\)\s*상생협력형', line):
                in_sangsaeng = True
                continue
            if in_sangsaeng:
                # 다른 섹션 시작 시 중단
                if re.match(r'^\s*\d\)\s', line) and '상생' not in line:
                    in_sangsaeng = False
                    continue
                if re.match(r'^\s*[\(\（]', line) and '상생' not in line:
                    in_sangsaeng = False
                    continue
                m_min = re.search(r'최저가입나이\s*[:：]?\s*(\d+)\s*세', line)
                if m_min:
                    sangsaeng_min = m_min.group(1)
                m_max = re.search(r'최고가입나이\s*[:：]?\s*(\d+)\s*세', line)
                if m_max:
                    sangsaeng_max = m_max.group(1)
                    break
        if sangsaeng_max is not None:
            age_records = [{
                '성별': '',
                '최소가입나이': sangsaeng_min or '0',
                '최대가입나이': sangsaeng_max,
                '최소납입기간': '', '최대납입기간': '', '납입기간구분코드': '',
                '최소제2보기개시나이': '', '최대제2보기개시나이': '', '제2보기개시나이구분코드': '',
                '최소보험기간': '', '최대보험기간': '', '보험기간구분코드': '',
            }]

    # ── 1) 테이블 기반 추출 시도 (가장 정확) ──

    # 0) 이중 구분 컬럼 테이블 (Wealth단체저축보험 등) - 최우선 감지
    dg_entries = parse_double_gubun_age_table(pdf_tables, pdf_lines, product_name)
    if dg_entries:
        age_records = dg_entries

    # 1a) 보험기간x성별 매트릭스 테이블 (H간병, H건강플러스 등) - "만N세~M세" 범위 포함
    table_entries = parse_age_table(pdf_tables, pdf_lines)
    if table_entries and not age_records:
        # 다중 테이블 그룹 감지: context가 비어있으면 종번호로 테이블 그룹 필터링
        tbl_groups = sorted(set(e.get('table_group', 0) for e in table_entries))
        target_tg = None
        if len(tbl_groups) > 1:
            # 모든 entry의 context가 비어있는 경우에만 종번호로 필터링
            all_empty_ctx = all(not e.get('context', '') for e in table_entries)
            if all_empty_ctx:
                # 텍스트에서 섹션/서브섹션 구조 파싱하여 테이블 그룹 매칭
                sec_start_l, sec_end_l = _find_age_section(pdf_lines)
                sec_headers: List[str] = []
                # 하위 서브섹션 수 (각 섹션당 테이블 그룹 수 추정)
                subsec_counts: List[int] = []
                cur_subsec_count = 0
                for li in range(sec_start_l, sec_end_l):
                    # "나." 또는 "나. 보험료 납입주기" 같은 다른 주제 시작시 스캔 중지
                    if re.match(r'^\s*나\.\s', pdf_lines[li]):
                        break
                    sh_m = re.match(r'^\s*[\(\（](\d+)\s*[\)\）]\s*(.+)', pdf_lines[li])
                    if sh_m:
                        if sec_headers:
                            subsec_counts.append(max(cur_subsec_count, 1))
                        sec_headers.append(sh_m.group(2).strip())
                        cur_subsec_count = 0
                    # 하위 제목 감지: "1) ...", "2) ..." (테이블 그룹에 대응하는 형 레벨)
                    elif re.match(r'^\s*\d+\)\s+', pdf_lines[li]):
                        cur_subsec_count += 1
                if sec_headers:
                    subsec_counts.append(max(cur_subsec_count, 1))

                # 1:1 매핑 (섹션수 == 그룹수)
                if len(sec_headers) == len(tbl_groups):
                    for si, sh in enumerate(sec_headers):
                        if match_context_to_product(sh, product_name):
                            target_tg = tbl_groups[si]
                            break

                # 1:N 매핑 (하위 서브섹션이 테이블 그룹에 매핑)
                if target_tg is None and sec_headers and sum(subsec_counts) == len(tbl_groups):
                    grp_offset = 0
                    for si, sh in enumerate(sec_headers):
                        n_subs = subsec_counts[si]
                        if match_context_to_product(sh, product_name):
                            # 이 섹션 내에서 형 번호로 서브그룹 선택
                            m_hyung = re.search(r'(\d+)형', product_name)
                            if m_hyung and n_subs > 1:
                                hyung_idx = int(m_hyung.group(1)) - 1
                                if 0 <= hyung_idx < n_subs:
                                    target_tg = tbl_groups[grp_offset + hyung_idx]
                            else:
                                target_tg = tbl_groups[grp_offset]
                            break
                        grp_offset += n_subs

                # fallback: 종번호만으로 시도
                if target_tg is None:
                    m_jong = re.search(r'(\d+)종', product_name)
                    if m_jong:
                        jong_idx = int(m_jong.group(1)) - 1
                        if 0 <= jong_idx < len(tbl_groups):
                            target_tg = tbl_groups[jong_idx]

                # 선택된 그룹이 페이지 분할로 불완전한 경우 → 동일 구조의 완전한 그룹 탐색
                if target_tg is not None:
                    grp_counts = {g: sum(1 for e in table_entries if e.get('table_group', 0) == g) for g in tbl_groups}
                    selected_cnt = grp_counts.get(target_tg, 0)
                    median_cnt = sorted(grp_counts.values())[len(grp_counts) // 2]
                    if selected_cnt < median_cnt * 0.6:  # 선택된 그룹이 중간값의 60% 미만
                        m_hyung = re.search(r'(\d+)형', product_name)
                        hyung_num = int(m_hyung.group(1)) if m_hyung else 0
                        # 같은 entry 수를 가진 그룹들 중 형 번호에 맞는 것 선택
                        full_groups = [g for g in tbl_groups if grp_counts[g] >= median_cnt]
                        if full_groups and hyung_num > 0:
                            # 형 번호 순서로 매칭 (짝수 그룹: 1형→짝수인덱스, 2형→홀수인덱스 또는 순서)
                            # 종번호로 필터: 앞쪽 그룹은 1종, 뒷쪽은 2종
                            m_jong2 = re.search(r'(\d+)종', product_name)
                            if m_jong2:
                                jong_num = int(m_jong2.group(1))
                                # 종별 그룹 구간 추정: 전체를 균등 분할
                                sec_count = len(sec_headers) if sec_headers else jong_num
                                per_sec = len(full_groups) // max(sec_count, 1)
                                if per_sec > 0:
                                    start = (jong_num - 1) * per_sec
                                    end = start + per_sec
                                    sec_groups = full_groups[start:end]
                                    if sec_groups:
                                        idx = min(hyung_num - 1, len(sec_groups) - 1)
                                        target_tg = sec_groups[idx]
        age_records = build_table_age_records(min_age or '', table_entries, product_name, target_tg)

    # 1b) "구분 | 최고가입나이" 테이블 (H종신 등)
    # formula 전략 + 연금 상품은 max_age_table이 공제값을 잘못 파싱할 수 있으므로 skip
    if not age_records and not (strategy == 'formula' and is_annuity):
        max_age_entries = parse_max_age_table(pdf_tables)
        if max_age_entries and min_age is not None:
            # 가입가능보기납기에서 유효한 PAYM 값 수집 (모든 경로에서 사용)
            matching_periods = [pd for pd in period_data
                               if normalize_ws(str(pd.get('상품명', ''))) == product_name]
            valid_payms: Set[Tuple[str, str]] = set()
            for pd_rec in matching_periods:
                for p in pd_rec.get('가입가능보기납기', []):
                    pv = p.get('납입기간값', '')
                    pd_ = p.get('납입기간구분코드', '')
                    if pv:
                        valid_payms.add((pv, pd_))

            # 다중 테이블 그룹 감지: 종번호로 테이블 그룹 필터링
            table_groups = sorted(set(e.get('table_group', 0) for e in max_age_entries))
            target_group = None
            if len(table_groups) > 1:
                # 상품명에서 종번호 추출 (예: "1종" → 0, "2종" → 1)
                m_jong = re.search(r'(\d+)종', product_name)
                if m_jong:
                    jong_idx = int(m_jong.group(1)) - 1
                    if 0 <= jong_idx < len(table_groups):
                        target_group = table_groups[jong_idx]

            seen = set()
            for entry in max_age_entries:
                if target_group is not None and entry.get('table_group', 0) != target_group:
                    continue
                if not match_context_to_product(entry['context'], product_name):
                    continue
                paym_text = entry['납입기간']
                paym_code, paym_dvsn, paym_val = format_period(paym_text)
                gender = entry['성별']
                max_age = entry['최대가입나이']

                # PAYM 필터
                if valid_payms and paym_val and (paym_val, paym_dvsn) not in valid_payms:
                    continue

                key = (min_age, max_age, gender, paym_val, paym_dvsn)
                if key in seen:
                    continue
                seen.add(key)

                age_records.append({
                    '성별': gender,
                    '최소가입나이': min_age,
                    '최대가입나이': max_age,
                    '최소납입기간': paym_val,
                    '최대납입기간': paym_val,
                    '납입기간구분코드': paym_dvsn,
                    '최소제2보기개시나이': '',
                    '최대제2보기개시나이': '',
                    '제2보기개시나이구분코드': '',
                    '최소보험기간': '',
                    '최대보험기간': '',
                    '보험기간구분코드': '',
                })

            # context 매칭 실패시 PAYM 필터링으로 재시도
            if not age_records:
                for entry in max_age_entries:
                    if target_group is not None and entry.get('table_group', 0) != target_group:
                        continue
                    paym_text = entry['납입기간']
                    paym_code, paym_dvsn, paym_val = format_period(paym_text)
                    gender = entry['성별']
                    max_age = entry['최대가입나이']

                    # PAYM 필터: 유효한 PAYM이 있으면 매칭되는 것만
                    if valid_payms and paym_val and (paym_val, paym_dvsn) not in valid_payms:
                        continue

                    key = (min_age, max_age, gender, paym_val, paym_dvsn)
                    if key in seen:
                        continue
                    seen.add(key)

                    age_records.append({
                        '성별': gender,
                        '최소가입나이': min_age,
                        '최대가입나이': max_age,
                        '최소납입기간': paym_val,
                        '최대납입기간': paym_val,
                        '납입기간구분코드': paym_dvsn,
                        '최소제2보기개시나이': '',
                        '최대제2보기개시나이': '',
                        '제2보기개시나이구분코드': '',
                        '최소보험기간': '',
                        '최대보험기간': '',
                        '보험기간구분코드': '',
                    })

    # 1b-2) 종목별x성별 테이블 (상속H종신 등)
    if not age_records:
        variant_entries = parse_variant_age_table(pdf_tables)
        if variant_entries and min_age is not None:
            # 가입가능보기납기에서 유효한 PAYM 값 수집
            matching_periods_v = [pd for pd in period_data
                                 if normalize_ws(str(pd.get('상품명', ''))) == product_name]
            valid_payms_v: Set[Tuple[str, str]] = set()
            for pd_rec in matching_periods_v:
                for p in pd_rec.get('가입가능보기납기', []):
                    pv = p.get('납입기간값', '')
                    pd_ = p.get('납입기간구분코드', '')
                    if pv:
                        valid_payms_v.add((pv, pd_))

            seen_v = set()
            for entry in variant_entries:
                if not match_context_to_product(entry['context'], product_name):
                    continue
                paym_text = entry['납입기간']
                paym_code, paym_dvsn, paym_val = format_period(paym_text)
                gender = entry['성별']
                max_age = entry['최대가입나이']

                # PAYM 필터
                if valid_payms_v and paym_val and (paym_val, paym_dvsn) not in valid_payms_v:
                    continue

                key = (min_age, max_age, gender, paym_val, paym_dvsn)
                if key in seen_v:
                    continue
                seen_v.add(key)

                age_records.append({
                    '성별': gender,
                    '최소가입나이': min_age,
                    '최대가입나이': max_age,
                    '최소납입기간': paym_val,
                    '최대납입기간': paym_val,
                    '납입기간구분코드': paym_dvsn,
                    '최소제2보기개시나이': '',
                    '최대제2보기개시나이': '',
                    '제2보기개시나이구분코드': '',
                    '최소보험기간': '',
                    '최대보험기간': '',
                    '보험기간구분코드': '',
                })

    # 1c-0) "보험기간 | 납입기간 | 남자 | 여자" 테이블 (경영인H정기 등)
    if not age_records:
        pg_entries = parse_period_gender_age_table(pdf_tables, pdf_lines)
        if pg_entries:
            seen_pg = set()
            for entry in pg_entries:
                # context가 있으면 상품명과 매칭
                ctx = entry.get('context', '')
                if ctx and not match_context_to_product(ctx, product_name):
                    continue
                gender = entry['성별']
                min_a = entry['최소가입나이']
                max_a = entry['최대가입나이']
                ins_text = entry.get('보험기간', '')
                paym_text = entry.get('납입기간', '')
                ins_code, ins_dvsn, ins_val = format_period(ins_text)
                paym_code, paym_dvsn, paym_val = format_period(paym_text)
                # 전기납의 경우 paym = ins (보험기간과 동일)
                if '전기납' in paym_text:
                    paym_dvsn = 'X'
                    paym_val = ins_val
                key = (min_a, max_a, gender, ins_val, ins_dvsn, paym_val, paym_dvsn)
                if key in seen_pg:
                    continue
                seen_pg.add(key)
                age_records.append({
                    '성별': gender,
                    '최소가입나이': min_a,
                    '최대가입나이': max_a,
                    '최소보험기간': ins_val,
                    '최대보험기간': ins_val,
                    '보험기간구분코드': ins_dvsn,
                    '최소납입기간': paym_val,
                    '최대납입기간': paym_val,
                    '납입기간구분코드': paym_dvsn,
                    '최소제2보기개시나이': '',
                    '최대제2보기개시나이': '',
                    '제2보기개시나이구분코드': '',
                })

    # 1c) "구 분 | 남자 | 여자" 직접 나이 테이블 (바로연금 등)
    if not age_records:
        direct_entries = parse_direct_age_table(pdf_tables)
        if direct_entries:
            age_records = build_table_age_records(min_age or '', direct_entries, product_name)

    # 1d) 인라인 나이 테이블 (H기업재해, 포켓골절 등)
    # formula 전략 + 연금 상품은 인라인 테이블이 즉시형(55~80) 등을 잘못 전체 적용하므로 skip
    is_silson = '실손의료비' in product_name
    if not age_records and not (strategy == 'formula' and is_annuity):
        inline_entries = parse_inline_age_table(pdf_tables)
        if inline_entries:
            age_records = build_table_age_records(min_age or '', inline_entries, product_name)

    # ── 2) SPIN x PAYM 매트릭스 테이블 (연금보험Enterprise 등) ──
    if not age_records:
        spin_entries = parse_spin_paym_age_table(pdf_tables, pdf_lines)
        if spin_entries and min_age is not None:
            seen_spin = set()
            for entry in spin_entries:
                if not match_context_to_product(entry.get('context', ''), product_name):
                    continue
                spin_val = entry['spin']
                paym_text = entry.get('납입기간', '')
                paym_code, paym_dvsn, paym_val = format_period(paym_text)
                max_age = entry['최대가입나이']
                gender = entry.get('성별', '')

                # 전기납의 경우 paym_dvsn = X, paym_val = spin_val
                if '전기납' in paym_text:
                    paym_dvsn = 'X'
                    paym_val = spin_val

                genders = ['1', '2'] if has_gender and not gender else [gender]
                for g in genders:
                    key = (min_age, max_age, g, paym_val, paym_dvsn, spin_val)
                    if key in seen_spin:
                        continue
                    seen_spin.add(key)
                    age_records.append({
                        '성별': g,
                        '최소가입나이': min_age,
                        '최대가입나이': max_age,
                        '최소납입기간': paym_val,
                        '최대납입기간': paym_val,
                        '납입기간구분코드': paym_dvsn,
                        '최소제2보기개시나이': spin_val,
                        '최대제2보기개시나이': spin_val,
                        '제2보기개시나이구분코드': 'X',
                        '최소보험기간': '',
                        '최대보험기간': '',
                        '보험기간구분코드': '',
                    })

    # ── 3) 연금 공식 (SPIN - PAYM 또는 SPIN - deduction) ──
    if not age_records and strategy == 'formula' and is_annuity:
        # PDF에서 납입기간별 공제 테이블 파싱
        pdf_paym_deds, pdf_geochi_ded = parse_deduction_table(pdf_lines, pdf_tables)

        matching_periods = [pd for pd in period_data
                           if normalize_ws(str(pd.get('상품명', ''))) == product_name]
        if not matching_periods:
            matching_periods = [pd for pd in period_data
                               if normalize_ws(str(pd.get('상품명칭', ''))) ==
                               normalize_ws(str(row.get('상품명칭', '')))]

        if matching_periods and min_age is not None:
            # 거치형 → SPIN-deduction 모드 (PAYM 없음)
            # 적립형 → SPIN×PAYM 모드
            # 즉시형 → 단순 모드 (formula 아님)
            use_spin_minus_one = ('거치' in product_name and '즉시' not in product_name)
            age_records = compute_formula_ages(
                min_age, matching_periods, has_gender,
                spin_minus_one=use_spin_minus_one,
                deduction_override=pdf_geochi_ded if use_spin_minus_one else None,
                paym_deductions=pdf_paym_deds if not use_spin_minus_one else None,
            )
            # SPIN 데이터가 없어서 결과가 0인 경우, 같은 상품명칭의 형제 상품에서 SPIN 차용
            if not age_records and use_spin_minus_one:
                prod_base = normalize_ws(str(row.get('상품명칭', '')))
                # 연금유형 추출 (종신연금형, 확정기간연금형 등)
                annuity_type_m = re.search(r'(종신연금형|확정기간연금형\s*\d+년)', product_name)
                annuity_type = annuity_type_m.group(1) if annuity_type_m else ''
                sibling_periods = []
                for pd_item in period_data:
                    if normalize_ws(str(pd_item.get('상품명칭', ''))) != prod_base:
                        continue
                    if not any(p.get('최소제2보기개시나이', '') and p.get('최소제2보기개시나이', '') != '-'
                               for p in pd_item.get('가입가능보기납기', [])):
                        continue
                    # 연금유형이 있으면, 같은 연금유형의 형제만 사용
                    if annuity_type:
                        sib_name = normalize_ws(str(pd_item.get('상품명', '')))
                        if annuity_type not in sib_name:
                            continue
                    sibling_periods.append(pd_item)
                if sibling_periods:
                    age_records = compute_formula_ages(
                        min_age, sibling_periods, has_gender,
                        spin_minus_one=True,
                        deduction_override=pdf_geochi_ded,
                    )

    # ── 3a-post) 종별 납입기간 제한 필터 (예: "3종(연금강화형) 적립형 : 3년납, 5년납") ──
    if age_records and strategy == 'formula' and is_annuity:
        jong_m = re.search(r'(\d+)종', product_name)
        if jong_m:
            jong_label = jong_m.group(1) + '종'
            is_jeok = '적립' in product_name
            is_geo = '거치' in product_name
            type_label = '적립형' if is_jeok else ('거치형' if is_geo else '')
            allowed_payms: Optional[Set[str]] = None
            for line in pdf_lines:
                if jong_label in line:
                    # 같은 줄 또는 바로 다음 줄에서 "적립형 : N년납, M년납" 패턴 검색
                    idx = pdf_lines.index(line)
                    search_range = pdf_lines[idx:idx + 3]
                    for sl in search_range:
                        if type_label and type_label in sl and '년납' in sl:
                            payms = re.findall(r'(\d+)\s*년납', sl)
                            if payms:
                                allowed_payms = set(payms)
                                break
                    if allowed_payms is not None:
                        break
            if allowed_payms:
                age_records = [r for r in age_records
                               if r.get('납입기간구분코드') != 'N'
                               or r.get('최소납입기간', '') in allowed_payms]

    # ── 3b) 가입나이 섹션 텍스트에서 직접 "CONTEXT MIN ~ MAX세" 추출 ──
    if not age_records:
        sec_start, sec_end = _find_age_section(pdf_lines)
        for i in range(sec_start, sec_end):
            line = pdf_lines[i]
            m = re.search(r'만?\s*(\d+)\s*세?\s*[~～\-]\s*(\d+)\s*세', line)
            if m:
                # 이 라인의 context(앞부분)가 상품명에 매칭되는지 확인
                line_prefix = line[:m.start()].strip()
                if match_context_to_product(line_prefix, product_name):
                    age_records.append({
                        '성별': '',
                        '최소가입나이': m.group(1),
                        '최대가입나이': m.group(2),
                        '최소납입기간': '',
                        '최대납입기간': '',
                        '납입기간구분코드': '',
                        '최소제2보기개시나이': '',
                        '최대제2보기개시나이': '',
                        '제2보기개시나이구분코드': '',
                        '최소보험기간': '',
                        '최대보험기간': '',
                        '보험기간구분코드': '',
                    })
                    break  # 첫 번째 매칭만 사용

    # ── 4) 텍스트 기반 추출 ──
    if not age_records:
        text_ages = extract_text_gender_ages(pdf_lines)
        if text_ages:
            # context 필터링: context가 있는 엔트리는 match_context_to_product로 필터
            has_any_context = any(e.get('context', '') for e in text_ages)
            if has_any_context:
                filtered_text = [e for e in text_ages
                                 if match_context_to_product(e.get('context', ''), product_name)]
                if filtered_text:
                    text_ages = filtered_text

            for entry in text_ages:
                ins_text = entry.get('보험기간', '')
                paym_text = entry.get('납입기간', '')
                ins_code, ins_dvsn, ins_val = format_period(ins_text)
                paym_code, paym_dvsn, paym_val = format_period(paym_text)

                if '전기납' in paym_text and ins_text:
                    paym_code, paym_dvsn, paym_val = ins_code, ins_dvsn, ins_val

                age_records.append({
                    '성별': entry['성별'],
                    '최소가입나이': entry['최소가입나이'],
                    '최대가입나이': entry['최대가입나이'],
                    '최소납입기간': paym_val,
                    '최대납입기간': paym_val,
                    '납입기간구분코드': paym_dvsn,
                    '최소제2보기개시나이': '',
                    '최대제2보기개시나이': '',
                    '제2보기개시나이구분코드': '',
                    '최소보험기간': ins_val,
                    '최대보험기간': ins_val,
                    '보험기간구분코드': ins_dvsn,
                })

    # ── 4) 최종 fallback: 단순 최대가입나이 ──
    if not age_records:
        max_age = extract_simple_max_age(pdf_lines)
        if min_age is not None and max_age is not None:
            age_records.append({
                '성별': '',
                '최소가입나이': min_age,
                '최대가입나이': max_age,
                '최소납입기간': '',
                '최대납입기간': '',
                '납입기간구분코드': '',
                '최소제2보기개시나이': '',
                '최대제2보기개시나이': '',
                '제2보기개시나이구분코드': '',
                '최소보험기간': '',
                '최대보험기간': '',
                '보험기간구분코드': '',
            })

    # ── PAYM/INS 필터: 가입가능보기납기 데이터로 유효성 검증 ──
    if age_records and len(age_records) > 1:
        period_matches = [pd for pd in period_data
                         if normalize_ws(str(pd.get('상품명', ''))) == product_name]
        if period_matches:
            valid_payms_final: Set[Tuple[str, str]] = set()
            valid_ins_final: Set[Tuple[str, str]] = set()
            for pd_rec in period_matches:
                for p in pd_rec.get('가입가능보기납기', []):
                    pv = p.get('납입기간값', '')
                    pd_ = p.get('납입기간구분코드', '')
                    iv = p.get('보험기간값', '')
                    id_ = p.get('보험기간구분코드', '')
                    if pv:
                        valid_payms_final.add((pv, pd_))
                    if iv:
                        valid_ins_final.add((iv, id_))
            if valid_payms_final or valid_ins_final:
                filtered = []
                for rec in age_records:
                    paym_v = rec.get('최소납입기간', '')
                    paym_d = rec.get('납입기간구분코드', '')
                    ins_v = rec.get('최소보험기간', '')
                    ins_d = rec.get('보험기간구분코드', '')
                    # PAYM 필터
                    if valid_payms_final and paym_v and (paym_v, paym_d) not in valid_payms_final:
                        continue
                    # INS 필터
                    if valid_ins_final and ins_v and (ins_v, ins_d) not in valid_ins_final:
                        continue
                    filtered.append(rec)
                if filtered:
                    age_records = filtered

    # ── 실손의료비 후처리 (product_overrides.json 기반) ──
    overrides = _load_overrides()
    ja_overrides = overrides.get('join_age', {})
    # 상품명에 매칭되는 override 그룹 찾기
    matched_ja_cfg = None
    for ov_key, ov_cfg in ja_overrides.items():
        if ov_key.startswith('_'):
            continue
        if ov_key in product_name:
            matched_ja_cfg = ov_cfg
            break

    if matched_ja_cfg and 'variants' in matched_ja_cfg:
        silson_min = '0'
        silson_max = ''
        # variants에서 상품명에 매칭되는 variant 찾기 (먼저 매칭되는 것 우선)
        for variant in matched_ja_cfg['variants']:
            match_kws = variant.get('match', [])
            if all(kw in product_name or kw.replace(' ', '') in product_name.replace(' ', '') for kw in match_kws):
                if 'min_age' in variant:
                    silson_min = variant['min_age']
                if 'max_age' in variant:
                    silson_max = variant['max_age']
                break

        if not silson_max:
            # 상품명에서 "N세형" 패턴으로 MAX 결정 (범용 로직)
            m_age_type = re.search(r'(\d+)세형', product_name)
            if m_age_type:
                silson_max = str(int(m_age_type.group(1)) - 1)

        if silson_max:
            age_records = [{
                '성별': '', '최소가입나이': silson_min, '최대가입나이': silson_max,
                '최소납입기간': '', '최대납입기간': '', '납입기간구분코드': '',
                '최소제2보기개시나이': '', '최대제2보기개시나이': '', '제2보기개시나이구분코드': '',
                '최소보험기간': '', '최대보험기간': '', '보험기간구분코드': '',
            }]
        elif age_records:
            # fallback: 유효한 항목 중 첫 번째 사용, silson_min 적용
            valid = [r for r in age_records
                     if r.get('최대가입나이') and r['최대가입나이'].isdigit()
                     and int(r['최대가입나이']) > 0
                     and (r.get('최소가입나이', '').isdigit() or r.get('최소가입나이') == '')
                     and int(r['최대가입나이']) > int(r.get('최소가입나이') or '0')]
            if valid:
                first = valid[0]
                first_min = silson_min if silson_min != '0' else (first.get('최소가입나이') or '0')
                age_records = [{
                    '성별': '',
                    '최소가입나이': first_min,
                    '최대가입나이': first['최대가입나이'],
                    '최소납입기간': '', '최대납입기간': '', '납입기간구분코드': '',
                    '최소제2보기개시나이': '', '최대제2보기개시나이': '', '제2보기개시나이구분코드': '',
                    '최소보험기간': '', '최대보험기간': '', '보험기간구분코드': '',
                }]



    age_records = _apply_join_age_postprocess(age_records, product_name)
    output['가입가능나이'] = age_records
    return output


# ──────────────────────────────────────────────
# 후처리 (product_overrides.json 기반 + 상품별 로직)
# ──────────────────────────────────────────────


def _merge_paym_ranges(ages: List[dict]) -> List[dict]:
    """동일 (INS, INS_CODE, PAYM_CODE) 그룹에서 PAYM 범위 병합."""
    from collections import defaultdict
    groups: Dict[tuple, List[dict]] = defaultdict(list)
    for a in ages:
        key = (
            a.get('최소보험기간', ''), a.get('보험기간구분코드', ''),
            a.get('납입기간구분코드', ''),
        )
        groups[key].append(a)
    result: List[dict] = []
    for key, group in groups.items():
        if len(group) == 1:
            result.append(group[0])
        else:
            paym_codes = {r.get('납입기간구분코드', '') for r in group}
            if len(paym_codes) > 1:
                result.extend(group)
                continue
            merged = dict(group[0])
            all_min = [int(r.get('최소납입기간') or '0') for r in group if r.get('최소납입기간', '').isdigit()]
            all_max = [int(r.get('최대납입기간') or '0') for r in group if r.get('최대납입기간', '').isdigit()]
            if all_min:
                merged['최소납입기간'] = str(min(all_min))
            if all_max:
                merged['최대납입기간'] = str(max(all_max))
            result.append(merged)
    return result


def _postprocess_jinsim(ages: List[dict], product_name: str) -> List[dict]:
    """진심가득H: 성별 제거 + dedup + 그룹별 max_age 유지 + 3종 PAYM 보정."""
    from collections import defaultdict
    # 1) 성별 제거 + 중복 제거
    seen: set = set()
    deduped: List[dict] = []
    for a in ages:
        rec = dict(a)
        rec['성별'] = ''
        sig = tuple(rec.get(k, '') for k in (
            '최소가입나이', '최대가입나이', '성별',
            '최소보험기간', '최대보험기간', '보험기간구분코드',
            '최소납입기간', '최대납입기간', '납입기간구분코드',
            '최소제2보기개시나이', '최대제2보기개시나이', '제2보기개시나이구분코드',
        ))
        if sig not in seen:
            seen.add(sig)
            deduped.append(rec)
    # 2) 동일 (INS, PAYM code) 그룹에서 max max_age만 유지
    groups: Dict[tuple, List[dict]] = defaultdict(list)
    for rec in deduped:
        key = (
            rec.get('최소보험기간', ''), rec.get('보험기간구분코드', ''),
            rec.get('최소납입기간', ''), rec.get('납입기간구분코드', ''),
        )
        groups[key].append(rec)
    result: List[dict] = []
    for key, group in groups.items():
        if len(group) == 1:
            result.append(group[0])
        else:
            best = max(group, key=lambda r: int(r.get('최대가입나이', '0')))
            min_age = min(int(r.get('최소가입나이', '0')) for r in group)
            best = dict(best)
            best['최소가입나이'] = str(min_age)
            result.append(best)
    # 3종 보정: 누락된 PAYM=5,7 추가 (max_age=35)
    if '3종' in product_name:
        existing_payms = {(r.get('최소보험기간', ''), r.get('최소납입기간', ''), r.get('납입기간구분코드', '')) for r in result}
        for ins in ['90', '100']:
            for paym in ['5', '7']:
                if (ins, paym, 'N') not in existing_payms:
                    result.append({
                        '성별': '', '최소가입나이': '0', '최대가입나이': '35',
                        '최소보험기간': ins, '최대보험기간': ins, '보험기간구분코드': 'X',
                        '최소납입기간': paym, '최대납입기간': paym, '납입기간구분코드': 'N',
                        '최소제2보기개시나이': '', '최대제2보기개시나이': '', '제2보기개시나이구분코드': '',
                    })
    return result


def _postprocess_tuntuni(ages: List[dict]) -> List[dict]:
    """튼튼이 갱신형: INS/PAYM 있는 레코드 중 최광범위 나이 선택 → 단순형."""
    best = None
    for a in ages:
        has_detail = a.get('최소보험기간', '') or a.get('최소납입기간', '')
        if has_detail:
            try:
                max_a = int(a.get('최대가입나이', '0'))
                if best is None or max_a > int(best.get('최대가입나이', '0')):
                    best = a
            except (ValueError, TypeError):
                pass
    if best:
        return [{
            '성별': '', '최소가입나이': best.get('최소가입나이', '0'),
            '최대가입나이': best.get('최대가입나이', '0'),
            '최소납입기간': '', '최대납입기간': '', '납입기간구분코드': '',
            '최소제2보기개시나이': '', '최대제2보기개시나이': '', '제2보기개시나이구분코드': '',
            '최소보험기간': '', '최대보험기간': '', '보험기간구분코드': '',
        }]
    return ages


def _apply_join_age_postprocess(ages: List[dict], product_name: str) -> List[dict]:
    """product_overrides.json 기반 + 상품별 가입가능나이 후처리."""
    if not ages:
        return ages

    overrides = _load_overrides()
    ja_overrides = overrides.get('join_age', {})
    nfc_name = unicodedata.normalize('NFC', product_name)

    # 1) JSON override 매칭 (action: fixed, min_age_floor)
    for ov_key, ov_cfg in ja_overrides.items():
        if ov_key.startswith('_'):
            continue
        if not isinstance(ov_cfg, dict):
            continue
        action = ov_cfg.get('action', '')
        if not action:
            continue
        # + 구분 AND 매칭
        keywords = ov_key.split('+')
        if not all(kw in nfc_name for kw in keywords):
            continue

        if action == 'fixed':
            # 직접 ages 리스트 반환
            if 'ages' in ov_cfg:
                return list(ov_cfg['ages'])
            # ages_template: 매트릭스 생성 (스마트상해 2형용)
            tmpl = ov_cfg.get('ages_template')
            if tmpl:
                # 기존 추출에서 INS 정보 가져오기
                ins_val, ins_dvsn = '', ''
                if ov_cfg.get('ins_from_extract'):
                    for a in ages:
                        if a.get('최소보험기간', ''):
                            ins_val = a['최소보험기간']
                            ins_dvsn = a.get('보험기간구분코드', '')
                            break
                result = []
                for g in tmpl['genders']:
                    for p in tmpl['payms']:
                        result.append({
                            '성별': g,
                            '최소가입나이': tmpl.get('min_age', '0'),
                            '최대가입나이': tmpl['max_age'],
                            '최소보험기간': ins_val, '최대보험기간': ins_val,
                            '보험기간구분코드': ins_dvsn,
                            '최소납입기간': p, '최대납입기간': p,
                            '납입기간구분코드': tmpl.get('paym_code', 'N'),
                            '최소제2보기개시나이': '', '최대제2보기개시나이': '',
                            '제2보기개시나이구분코드': '',
                        })
                return result

        # min_age_floor는 CSV sale_nm 기준 매칭이 필요하므로
        # map_product_code.py에서 처리 (extract 단계에서는 skip)

    # 2) 상품별 코드 로직
    if '진심가득' in nfc_name:
        return _postprocess_jinsim(ages, nfc_name)

    if '튼튼이' in nfc_name and '갱신' in nfc_name:
        return _postprocess_tuntuni(ages)

    if '기업재해보장' in nfc_name:
        return _merge_paym_ranges(ages)

    # config 기반 gender collapse: 동일 range의 성별=1/2 쌍을 성별='' 로 통합
    collapse_keywords = overrides.get('gender_collapse_targets', {}).get('keywords', [])
    if any(kw in nfc_name for kw in collapse_keywords):
        ages = _collapse_identical_gender(ages)

    return ages


def _collapse_identical_gender(ages: List[dict]) -> List[dict]:
    """남/여가 min_age, max_age, 기간 모두 동일하면 성별='' 단일 레코드로 합산."""
    def _sig(a: dict) -> tuple:
        return (
            a.get('최소가입나이', ''), a.get('최대가입나이', ''),
            a.get('최소보험기간', ''), a.get('최대보험기간', ''), a.get('보험기간구분코드', ''),
            a.get('최소납입기간', ''), a.get('최대납입기간', ''), a.get('납입기간구분코드', ''),
            a.get('최소제2보기개시나이', ''), a.get('최대제2보기개시나이', ''), a.get('제2보기개시나이구분코드', ''),
        )

    male_by_sig: Dict[tuple, dict] = {}
    female_by_sig: Dict[tuple, dict] = {}
    result: List[dict] = []

    for a in ages:
        g = a.get('성별', '')
        if g == '1':
            male_by_sig[_sig(a)] = a
        elif g == '2':
            female_by_sig[_sig(a)] = a
        else:
            result.append(a)

    if not male_by_sig and not female_by_sig:
        return ages

    matched_sigs: set = set()
    for sig, m_rec in male_by_sig.items():
        f_rec = female_by_sig.get(sig)
        if f_rec is not None and m_rec.get('최소가입나이') == f_rec.get('최소가입나이'):
            # 동일 range → 성별='' 로 합산
            merged = dict(m_rec)
            merged['성별'] = ''
            result.append(merged)
            matched_sigs.add(sig)
        else:
            result.append(m_rec)

    for sig, f_rec in female_by_sig.items():
        if sig not in matched_sigs:
            result.append(f_rec)

    return result


# ──────────────────────────────────────────────
# 리포트
# ──────────────────────────────────────────────

def make_report(outputs: List[dict]) -> dict:
    total_files = len(outputs)
    total_rows = sum(len(item.get('rows', [])) for item in outputs)
    total_with_ages = sum(
        1 for item in outputs
        for row in item.get('rows', [])
        if row.get('가입가능나이')
    )
    return {
        'total_files': total_files,
        'total_rows': total_rows,
        'rows_with_age_data': total_with_ages,
        'rows_without_age_data': total_rows - total_with_ages,
    }


# ──────────────────────────────────────────────
# CLI
# ──────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description='가입가능나이 추출기')
    parser.add_argument('--docx-dir', type=Path, default=DEFAULT_DOCX_DIR)
    parser.add_argument('--json-dir', type=Path, default=DEFAULT_JSON_DIR)
    parser.add_argument('--period-dir', type=Path, default=DEFAULT_PERIOD_DIR)
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument('--report-path', type=Path, default=DEFAULT_REPORT_PATH)
    parser.add_argument('--docx', type=str, help='single docx path')
    parser.add_argument('--json', type=str, help='single JSON path')
    parser.add_argument('--output', type=str, help='single output path')
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


def load_period_data(period_dir: Path, json_name: str) -> List[dict]:
    """가입가능보기납기 JSON에서 해당 파일의 데이터를 로드."""
    period_path = period_dir / json_name
    if not period_path.exists():
        return []
    with period_path.open('r', encoding='utf-8') as f:
        data = json.load(f)
    return data if isinstance(data, list) else []


def process_single(
    docx_path: Path,
    json_path: Path,
    period_dir: Path,
) -> List[dict]:
    docx_lines, docx_tables = extract_docx_content(docx_path)
    with json_path.open('r', encoding='utf-8') as f:
        rows = json.load(f)
    if not isinstance(rows, list):
        rows = []

    # 가입가능보기납기 데이터 로드
    period_data = load_period_data(period_dir, json_path.name)

    return [
        merge_join_age_info(
            r if isinstance(r, dict) else {},
            docx_lines,
            docx_tables,
            period_data,
        )
        for r in rows
    ]


def run(
    docx_dir: Path,
    json_dir: Path,
    period_dir: Path,
    output_dir: Path,
    report_path: Optional[Path],
) -> Tuple[int, int]:
    pairs = gather_pairs(docx_dir, json_dir)
    outputs = []
    output_dir.mkdir(parents=True, exist_ok=True)

    for docx_path, json_path in sorted(pairs, key=lambda x: x[1].name):
        result_rows = process_single(docx_path, json_path, period_dir)
        outputs.append({'file': json_path.name, 'rows': result_rows})
        out_path = output_dir / json_path.name
        with out_path.open('w', encoding='utf-8') as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)
        age_count = sum(1 for r in result_rows if r.get('가입가능나이'))
        print(f'Processed: {json_path.name} ({len(result_rows)} items, {age_count} with ages)')

    report = make_report(outputs)
    rp = report_path or (output_dir / 'report.json')
    rp.parent.mkdir(parents=True, exist_ok=True)
    with rp.open('w', encoding='utf-8') as f:
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
        result_rows = process_single(docx_path, json_path, args.period_dir)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open('w', encoding='utf-8') as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)
        print(f'{docx_path.name} + {json_path.name} -> {output_path.name} ({len(result_rows)} items)')
    else:
        processed_files, processed_rows = run(
            args.docx_dir, args.json_dir, args.period_dir,
            args.output_dir, args.report_path,
        )
        print(f'processed_files={processed_files}')
        print(f'processed_rows={processed_rows}')
