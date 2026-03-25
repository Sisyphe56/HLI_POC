#!/usr/bin/env python3
"""통합 상품코드 매핑 스크립트.

Usage:
    python map_product_code.py --data-set product_classification
    python map_product_code.py --data-set payment_cycle
    python map_product_code.py --data-set annuity_age   # 추후 구현
"""
import argparse
import csv
import io
import json
import re
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, Set, Tuple

PROJECT_ROOT = Path(__file__).resolve().parent
DEFAULT_MAPPING_CSV = PROJECT_ROOT / 'config' / '보종코드_상품코드_매핑.csv'

# ---------------------------------------------------------------------------
# 공통 유틸
# ---------------------------------------------------------------------------

def read_text_with_fallback(path: Path) -> str:
    encodings = ('utf-8-sig', 'utf-8', 'cp949', 'euc-kr')
    last_error = None
    for enc in encodings:
        try:
            return path.read_text(encoding=enc)
        except UnicodeDecodeError as exc:
            last_error = exc
    raise RuntimeError(f'Cannot decode {path}: {last_error}') from last_error


def normalize_text(value) -> str:
    if value is None:
        return ''
    s = unicodedata.normalize('NFKC', str(value).strip())
    s = s.replace('Ⅰ', 'I').replace('Ⅱ', 'II').replace('Ⅲ', 'III')
    s = s.replace('\u00a0', ' ')
    s = re.sub(r'^[\s·•▪∙◦]+\s*', '', s)
    s = re.sub(r'\s+', ' ', s)
    return s


def normalize_match_key(value: str) -> str:
    return re.sub(r'\s+', '', normalize_text(value))


def split_match_tokens(value: str) -> List[str]:
    compact = normalize_text(value)
    if not compact:
        return []
    raw_tokens = re.split(r'[\s\[\]\(\)·]+', compact)
    out: List[str] = []
    seen: set = set()
    for token in raw_tokens:
        tok = normalize_match_key(token)
        if tok and tok not in seen:
            seen.add(tok)
            out.append(tok)
    return out


def extract_refund_level_token(tokens: List[str]) -> Optional[str]:
    for token in tokens:
        if '일부지급형' not in token:
            continue
        suffix = token.split('일부지급형', 1)[1]
        suffix = suffix.strip(' ()[]')
        match = re.fullmatch(r'I+', suffix)
        if match:
            return f'일부지급형{match.group(0)}'
        suffix = re.sub(r'^.*일부지급형', '', token)
        suffix = suffix.strip(' ()[]')
        match = re.fullmatch(r'I+', suffix)
        if match:
            return f'일부지급형{match.group(0)}'
    return None


def collect_detail_keys(record: dict) -> List[str]:
    return sorted(
        (k for k in record.keys() if re.fullmatch(r'세부종목\d+', str(k))),
        key=lambda k: int(re.fullmatch(r'세부종목(\d+)', str(k)).group(1)),
    )


def collect_components(record: dict) -> List[str]:
    product_name = normalize_text(record.get('상품명칭', ''))
    if not product_name and record.get('상품명'):
        product_name = normalize_text(record.get('상품명', ''))
    detail_keys = collect_detail_keys(record)
    detail_parts = [normalize_text(record.get(k, '')) for k in detail_keys]
    if not product_name:
        return detail_parts
    parts = [product_name]
    parts.extend(p for p in detail_parts if p)
    return parts


# ---------------------------------------------------------------------------
# CSV 로딩
# ---------------------------------------------------------------------------

def load_mapping_rows(mapping_csv: Path) -> List[Dict]:
    text = read_text_with_fallback(mapping_csv)
    rows: List[Dict] = []
    for idx, raw in enumerate(csv.DictReader(io.StringIO(text))):
        dtcd = normalize_text(raw.get('ISRN_KIND_DTCD', ''))
        itcd = normalize_text(raw.get('ISRN_KIND_ITCD', ''))
        sale_nm = normalize_text(raw.get('ISRN_KIND_SALE_NM', ''))
        prod_dtcd = normalize_text(raw.get('PROD_DTCD', ''))
        prod_itcd = normalize_text(raw.get('PROD_ITCD', ''))
        prod_sale_nm = normalize_text(raw.get('PROD_SALE_NM', ''))
        if not (dtcd and itcd and sale_nm):
            continue
        # prod_sale_nm 기준 매칭 (isrn_kind_sale_nm fallback)
        prod_tokens = split_match_tokens(prod_sale_nm) if prod_sale_nm else split_match_tokens(sale_nm)
        isrn_tokens = split_match_tokens(sale_nm)
        rows.append({
            'csv_row_id': f'row-{idx}',
            'isrn_kind_dtcd': dtcd,
            'isrn_kind_itcd': itcd,
            'isrn_kind_sale_nm': sale_nm,
            'prod_dtcd': prod_dtcd,
            'prod_itcd': prod_itcd,
            'prod_sale_nm': prod_sale_nm,
            'match_key': normalize_match_key(prod_sale_nm or sale_nm),
            'match_tokens': prod_tokens,
            'match_token_set': set(prod_tokens),
            'isrn_match_key': normalize_match_key(sale_nm),
            'isrn_match_token_set': set(isrn_tokens),
        })
    if not rows:
        raise ValueError(f'No valid rows loaded from {mapping_csv}')
    return rows


# ---------------------------------------------------------------------------
# 토큰 기반 매칭
# ---------------------------------------------------------------------------

# 숫자+한글 패턴 (예: "5년", "15년형") — substring 매칭 시 경계 검사 필요
_NUM_SUFFIX_RE = re.compile(r'^\d+')


def _all_tokens_in_key(tokens: set, key: str) -> bool:
    """key 문자열에 모든 token이 포함되는지 확인.

    숫자로 시작하는 토큰(예: '5년')은 단순 ``in`` 대신 경계를 검사하여
    '15년형' 안에 '5년'이 잘못 매칭되는 것을 방지한다.
    """
    for token in tokens:
        if token not in key:
            return False
        # 숫자 접두사 토큰 → 앞에 다른 숫자가 붙어있으면 거짓 매칭
        if _NUM_SUFFIX_RE.match(token):
            idx = 0
            found = False
            while True:
                pos = key.find(token, idx)
                if pos < 0:
                    break
                # 토큰 직전 글자가 숫자이면 건너뜀 (예: '5년' in '15년형')
                if pos > 0 and key[pos - 1].isdigit():
                    idx = pos + 1
                    continue
                found = True
                break
            if not found:
                return False
    return True


def match_codes(mapping_rows: List[Dict], components: List[str]) -> List[Dict]:
    normalized: List[str] = []
    for comp in components:
        normalized.extend(split_match_tokens(comp))
    if not normalized:
        return []

    # 케어백간병플러스보험 예외: '보장형 계약' 토큰 제거
    product_text = ''.join(components).replace(' ', '')
    if '케어백간병플러스보험' in product_text:
        filtered = []
        i = 0
        while i < len(normalized):
            token = normalized[i]
            if token == '보장형' and i + 1 < len(normalized) and normalized[i + 1] == '계약':
                i += 2
                continue
            if token == '계약' and i > 0 and normalized[i - 1] == '보장형':
                i += 1
                continue
            filtered.append(token)
            i += 1
        normalized = filtered

    normalized = [t for t in normalized if t]
    if not normalized:
        return []

    required = set(normalized)

    # 1) prod_sale_nm 기준 토큰 집합 매칭
    candidates = [row for row in mapping_rows if required <= row['match_token_set']]
    # 1-b) prod_sale_nm substring fallback (boundary-aware)
    if not candidates:
        candidates = [
            row for row in mapping_rows
            if _all_tokens_in_key(required, row['match_key'])
        ]
    # 1-c) isrn_kind_sale_nm fallback
    if not candidates:
        candidates = [row for row in mapping_rows if required <= row.get('isrn_match_token_set', set())]
    if not candidates:
        candidates = [
            row for row in mapping_rows
            if _all_tokens_in_key(required, row.get('isrn_match_key', ''))
        ]
    if not candidates:
        return []

    # 납입면제형 필터링
    has_waiver = '납입면제형' in required
    with_waiver = [r for r in candidates if '납입면제형' in r['match_token_set']]
    without_waiver = [r for r in candidates if '납입면제형' not in r['match_token_set']]
    if has_waiver and with_waiver:
        candidates = with_waiver
    elif without_waiver and with_waiver:
        candidates = without_waiver

    # 간편가입 필터링
    has_simple = '간편가입' in required
    with_simple = [r for r in candidates if '간편가입' in r['match_token_set']]
    without_simple = [r for r in candidates if '간편가입' not in r['match_token_set']]
    if has_simple and with_simple:
        candidates = with_simple
    elif without_simple and with_simple:
        candidates = without_simple

    # 일부지급형 레벨 필터링
    refund_token = extract_refund_level_token(list(required))
    if refund_token:
        exact_refund = [r for r in candidates if refund_token in r['match_token_set']]
        if exact_refund:
            candidates = exact_refund

    if not candidates:
        return []

    # 2) 오버매칭 최소화
    min_extra = min(len(r['match_token_set'] - required) for r in candidates)
    candidates = [r for r in candidates if len(r['match_token_set'] - required) == min_extra]

    # 3) 정렬
    candidates.sort(key=lambda r: (
        r['match_key'].count('['),
        -(len(required & r['match_token_set'])),
        r['match_key'],
    ))

    return candidates


# ---------------------------------------------------------------------------
# Data-set 별 출력 row 생성
# ---------------------------------------------------------------------------

def _base_output_row(record: dict, csv_match: Optional[Dict]) -> dict:
    """공통 출력 필드 생성."""
    if csv_match:
        row = {
            'isrn_kind_dtcd': csv_match['isrn_kind_dtcd'],
            'isrn_kind_itcd': csv_match['isrn_kind_itcd'],
            'isrn_kind_sale_nm': csv_match['isrn_kind_sale_nm'],
            'prod_dtcd': csv_match['prod_dtcd'],
            'prod_itcd': csv_match['prod_itcd'],
            'prod_sale_nm': csv_match['prod_sale_nm'],
        }
    else:
        row = {
            'isrn_kind_dtcd': '',
            'isrn_kind_itcd': '',
            'isrn_kind_sale_nm': '',
            'prod_dtcd': '',
            'prod_itcd': '',
            'prod_sale_nm': '',
        }
    # 상품명칭, 세부종목, 상품명 추가
    if record.get('상품명칭'):
        row['상품명칭'] = record['상품명칭']
    for key in collect_detail_keys(record):
        row[key] = record.get(key, '')
    if record.get('상품명'):
        row['상품명'] = record['상품명']
    return row


def build_product_classification_row(record: dict, csv_match: Optional[Dict]) -> dict:
    return _base_output_row(record, csv_match)


def build_payment_cycle_row(record: dict, csv_match: Optional[Dict]) -> dict:
    row = _base_output_row(record, csv_match)
    if '납입주기' in record:
        row['납입주기'] = record['납입주기']
    return row


def build_annuity_age_row(record: dict, csv_match: Optional[Dict]) -> dict:
    row = _base_output_row(record, csv_match)
    if '보기개시나이정보' in record:
        row['보기개시나이정보'] = record['보기개시나이정보']
    return row


def build_insurance_period_row(record: dict, csv_match: Optional[Dict]) -> dict:
    row = _base_output_row(record, csv_match)
    if '가입가능보기납기' in record:
        row['가입가능보기납기'] = record['가입가능보기납기']
    return row


def _collapse_gender(ages: List[dict]) -> List[dict]:
    """남/여 max_age가 동일한 레코드 쌍을 성별='' 단일 레코드로 합침."""
    def _sig(a: dict) -> tuple:
        """성별·최소가입나이 제외한 비교 키."""
        return (
            a.get('최대가입나이', ''),
            a.get('최소보험기간', ''), a.get('최대보험기간', ''), a.get('보험기간구분코드', ''),
            a.get('최소납입기간', ''), a.get('최대납입기간', ''), a.get('납입기간구분코드', ''),
            a.get('최소제2보기개시나이', ''), a.get('최대제2보기개시나이', ''), a.get('제2보기개시나이구분코드', ''),
        )

    male_by_sig: Dict[tuple, dict] = {}
    female_by_sig: Dict[tuple, dict] = {}
    neutral: List[dict] = []

    for a in ages:
        g = a.get('성별', '')
        sig = _sig(a)
        if g == '1':
            male_by_sig[sig] = a
        elif g == '2':
            female_by_sig[sig] = a
        else:
            neutral.append(a)

    result = list(neutral)
    matched_female_sigs: set = set()

    for sig, m_rec in male_by_sig.items():
        f_rec = female_by_sig.get(sig)
        if f_rec is not None:
            merged = dict(m_rec)
            merged['성별'] = ''
            try:
                m_min = int(m_rec.get('최소가입나이', '0'))
                f_min = int(f_rec.get('최소가입나이', '0'))
                merged['최소가입나이'] = str(min(m_min, f_min))
            except (ValueError, TypeError):
                pass
            result.append(merged)
            matched_female_sigs.add(sig)
        else:
            result.append(m_rec)

    for sig, f_rec in female_by_sig.items():
        if sig not in matched_female_sigs:
            result.append(f_rec)

    return result


def _apply_min_age_floor(ages: List[dict], sale_nm: str) -> List[dict]:
    """product_overrides.json의 min_age_floor 규칙 적용 (CSV sale_nm 기준 매칭)."""
    overrides_path = PROJECT_ROOT / 'config' / 'product_overrides.json'
    if not overrides_path.exists():
        return ages
    with overrides_path.open('r', encoding='utf-8') as f:
        ja_overrides = json.load(f).get('join_age', {})
    nfc_nm = unicodedata.normalize('NFC', sale_nm)
    for ov_key, ov_cfg in ja_overrides.items():
        if ov_key.startswith('_') or not isinstance(ov_cfg, dict):
            continue
        if ov_cfg.get('action') != 'min_age_floor':
            continue
        keywords = ov_key.split('+')
        if not all(kw in nfc_nm for kw in keywords):
            continue
        floor = int(ov_cfg.get('min_age', '0'))
        result = [dict(a) for a in ages]
        for a in result:
            try:
                if int(a.get('최소가입나이', '0')) < floor:
                    a['최소가입나이'] = str(floor)
            except (ValueError, TypeError):
                pass
        return result
    return ages


def build_join_age_row(record: dict, csv_match: Optional[Dict]) -> dict:
    row = _base_output_row(record, csv_match)
    if '가입가능나이' in record:
        ages = record['가입가능나이']
        sale_nm = (csv_match or {}).get('isrn_kind_sale_nm', '')
        ages = _apply_min_age_floor(ages, sale_nm)
        row['가입가능나이'] = ages
    return row


# ---------------------------------------------------------------------------
# DataSetConfig
# ---------------------------------------------------------------------------

@dataclass
class DataSetConfig:
    name: str
    input_dir: Path
    output_dir: Path
    output_prefix: str
    build_row: Callable
    data_field_name: str = ''  # JSON 내 데이터 필드명 (sibling fallback용)


DATASET_CONFIGS = {
    'product_classification': DataSetConfig(
        name='product_classification',
        input_dir=PROJECT_ROOT / '상품분류',
        output_dir=PROJECT_ROOT / '코드매핑' / '상품분류',
        output_prefix='상품분류_',
        build_row=build_product_classification_row,
    ),
    'payment_cycle': DataSetConfig(
        name='payment_cycle',
        input_dir=PROJECT_ROOT / '납입주기',
        output_dir=PROJECT_ROOT / '코드매핑' / '납입주기',
        output_prefix='납입주기_',
        build_row=build_payment_cycle_row,
        data_field_name='납입주기',
    ),
    'annuity_age': DataSetConfig(
        name='annuity_age',
        input_dir=PROJECT_ROOT / '보기개시나이',
        output_dir=PROJECT_ROOT / '코드매핑' / '보기개시나이',
        output_prefix='보기개시나이_',
        build_row=build_annuity_age_row,
        data_field_name='보기개시나이정보',
    ),
    'insurance_period': DataSetConfig(
        name='insurance_period',
        input_dir=PROJECT_ROOT / '가입가능보기납기',
        output_dir=PROJECT_ROOT / '코드매핑' / '가입가능보기납기',
        output_prefix='가입가능보기납기_',
        build_row=build_insurance_period_row,
        data_field_name='가입가능보기납기',
    ),
    'join_age': DataSetConfig(
        name='join_age',
        input_dir=PROJECT_ROOT / '가입가능나이',
        output_dir=PROJECT_ROOT / '코드매핑' / '가입가능나이',
        output_prefix='가입가능나이_',
        build_row=build_join_age_row,
        data_field_name='가입가능나이',
    ),
}


# ---------------------------------------------------------------------------
# 파일 처리
# ---------------------------------------------------------------------------

def load_json(json_path: Path) -> List[dict]:
    with json_path.open('r', encoding='utf-8') as f:
        data = json.load(f)
    if not isinstance(data, list):
        return []
    return data


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def process_file(
    json_path: Path,
    mapping_rows: List[Dict],
    config: DataSetConfig,
) -> Tuple[List[dict], Dict[str, int], Set[str]]:
    records = load_json(json_path)
    mapped_rows: List[dict] = []
    stats = {'total': 0, 'matched': 0, 'unmatched': 0, 'ambiguous': 0}
    matched_csv_ids: Set[str] = set()

    for record in records:
        stats['total'] += 1
        components = collect_components(record)
        matches = match_codes(mapping_rows, components)

        if not matches:
            mapped_rows.append(config.build_row(record, None))
            stats['unmatched'] += 1
        elif len(matches) == 1:
            mapped_rows.append(config.build_row(record, matches[0]))
            stats['matched'] += 1
            matched_csv_ids.add(matches[0]['csv_row_id'])
        else:
            for m in matches:
                mapped_rows.append(config.build_row(record, m))
                matched_csv_ids.add(m['csv_row_id'])
            stats['ambiguous'] += 1

    return mapped_rows, stats, matched_csv_ids


# ---------------------------------------------------------------------------
# CSV row 기준 리포트
# ---------------------------------------------------------------------------

def generate_csv_based_report(
    data_set: str,
    mapping_rows: List[Dict],
    matched_csv_ids: Set[str],
    file_stats: List[Dict],
) -> dict:
    total_input = sum(s['total'] for s in file_stats)
    total_matched = sum(s['matched'] for s in file_stats)
    total_unmatched = sum(s['unmatched'] for s in file_stats)
    total_ambiguous = sum(s['ambiguous'] for s in file_stats)

    csv_rows_report = []
    for row in mapping_rows:
        status = 'matched' if row['csv_row_id'] in matched_csv_ids else 'unmatched'
        csv_rows_report.append({
            'isrn_kind_dtcd': row['isrn_kind_dtcd'],
            'isrn_kind_itcd': row['isrn_kind_itcd'],
            'isrn_kind_sale_nm': row['isrn_kind_sale_nm'],
            'status': status,
        })

    csv_matched = sum(1 for r in csv_rows_report if r['status'] == 'matched')
    csv_unmatched = len(csv_rows_report) - csv_matched

    return {
        'data_set': data_set,
        'summary': {
            'total_input_rows': total_input,
            'matched': total_matched,
            'unmatched': total_unmatched,
            'ambiguous': total_ambiguous,
            'match_rate': f'{total_matched / total_input * 100:.1f}%' if total_input else '0%',
            'csv_total': len(mapping_rows),
            'csv_matched': csv_matched,
            'csv_unmatched': csv_unmatched,
            'csv_match_rate': f'{csv_matched / len(mapping_rows) * 100:.1f}%' if mapping_rows else '0%',
        },
        'file_details': file_stats,
        'csv_rows': csv_rows_report,
    }


# ---------------------------------------------------------------------------
# Sibling fallback: unmatched CSV rows의 (dtcd,itcd) 매칭된 sibling이 있으면
# 같은 추출 데이터에 매핑
# ---------------------------------------------------------------------------

def apply_sibling_fallback(
    mapping_rows: List[Dict],
    all_matched_csv_ids: Set[str],
    output_dir: Path,
    config: 'DataSetConfig',
) -> Set[str]:
    """Unmatched CSV rows 중 같은 (dtcd,itcd) sibling이 매칭된 경우 fallback 적용.

    Returns: sibling fallback으로 추가 매칭된 csv_row_id 집합.
    """
    # (dtcd, itcd) -> [row indices]
    dtcd_itcd_index: Dict[Tuple[str, str], List[int]] = defaultdict(list)
    for i, row in enumerate(mapping_rows):
        key = (row['isrn_kind_dtcd'], row['isrn_kind_itcd'])
        dtcd_itcd_index[key].append(i)

    # Identify unmatched rows that have matched siblings
    sibling_targets: List[Dict] = []  # unmatched CSV rows to be added
    for i, row in enumerate(mapping_rows):
        if row['csv_row_id'] in all_matched_csv_ids:
            continue
        key = (row['isrn_kind_dtcd'], row['isrn_kind_itcd'])
        matched_siblings = [
            mapping_rows[j] for j in dtcd_itcd_index[key]
            if mapping_rows[j]['csv_row_id'] in all_matched_csv_ids
        ]
        if matched_siblings:
            sibling_targets.append(row)

    if not sibling_targets:
        return set()

    # Read existing output files and find rows matching sibling's (dtcd, itcd)
    # to copy the extracted data (상품명칭, 세부종목, 상품명 etc.)
    output_files = sorted(output_dir.glob(f'{config.output_prefix}*.json'))

    # Build index: (dtcd, itcd) -> output rows from existing files
    dtcd_itcd_output: Dict[Tuple[str, str], List[dict]] = defaultdict(list)
    output_file_map: Dict[Tuple[str, str], Path] = {}  # which file to append to
    for fpath in output_files:
        rows = load_json(fpath)
        for row in rows:
            key = (row.get('isrn_kind_dtcd', ''), row.get('isrn_kind_itcd', ''))
            if key[0]:
                dtcd_itcd_output[key].append(row)
                if key not in output_file_map:
                    output_file_map[key] = fpath

    # Create new output rows for sibling targets
    added_ids: Set[str] = set()
    file_appends: Dict[Path, List[dict]] = defaultdict(list)

    for target_row in sibling_targets:
        key = (target_row['isrn_kind_dtcd'], target_row['isrn_kind_itcd'])
        existing = dtcd_itcd_output.get(key, [])
        if not existing:
            continue

        # Find best-matching template by comparing target's prod_sale_nm
        # with each existing output row's 상품명 (token overlap score)
        target_tokens = set(split_match_tokens(target_row.get('prod_sale_nm', '')))
        best_template = existing[0]
        best_score = -1
        for cand in existing:
            cand_name = cand.get('상품명', '') or cand.get('상품명칭', '')
            cand_tokens = set(split_match_tokens(cand_name))
            if not target_tokens or not cand_tokens:
                continue
            overlap = len(target_tokens & cand_tokens)
            extra = len(cand_tokens - target_tokens)
            score = overlap * 10 - extra
            if score > best_score:
                best_score = score
                best_template = cand
        template = best_template
        new_row = {}
        # Copy CSV code fields from the sibling target
        new_row['isrn_kind_dtcd'] = target_row['isrn_kind_dtcd']
        new_row['isrn_kind_itcd'] = target_row['isrn_kind_itcd']
        new_row['isrn_kind_sale_nm'] = target_row['isrn_kind_sale_nm']
        new_row['prod_dtcd'] = target_row['prod_dtcd']
        new_row['prod_itcd'] = target_row['prod_itcd']
        new_row['prod_sale_nm'] = target_row['prod_sale_nm']
        # Copy extracted data fields from the template
        for k in ('상품명칭', '상품명'):
            if k in template:
                new_row[k] = template[k]
        for k in sorted(template.keys()):
            if k.startswith('세부종목'):
                new_row[k] = template[k]
        # Copy data-set specific fields (동적으로 config에서 로드)
        _data_fields = [c.data_field_name for c in DATASET_CONFIGS.values() if c.data_field_name]
        for k in _data_fields:
            if k in template:
                new_row[k] = template[k]

        fpath = output_file_map.get(key)
        if fpath:
            file_appends[fpath].append(new_row)
        added_ids.add(target_row['csv_row_id'])

    # Append to output files
    for fpath, new_rows in file_appends.items():
        existing = load_json(fpath)
        existing.extend(new_rows)
        write_json(fpath, existing)

    if added_ids:
        print(f'Sibling fallback: {len(added_ids)} CSV rows added')

    return added_ids


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description='통합 상품코드 매핑')
    parser.add_argument(
        '--data-set',
        required=True,
        choices=list(DATASET_CONFIGS.keys()),
        help='데이터셋 종류',
    )
    parser.add_argument('--mapping-csv', type=Path, default=DEFAULT_MAPPING_CSV)
    parser.add_argument('--input-dir', type=Path, default=None)
    parser.add_argument('--output-dir', type=Path, default=None)
    parser.add_argument('--json', type=str, help='단일 JSON 파일 경로')
    parser.add_argument('--output', type=str, help='단일 파일 출력 경로')
    return parser.parse_args()


def main():
    args = parse_args()
    config = DATASET_CONFIGS[args.data_set]

    # 디렉토리 오버라이드
    input_dir = args.input_dir or config.input_dir
    output_dir = args.output_dir or config.output_dir

    mapping_rows = load_mapping_rows(args.mapping_csv)
    print(f'Loaded {len(mapping_rows)} CSV mapping rows')

    # --- 단일 파일 모드 ---
    if args.json:
        json_path = Path(args.json)
        output_path = (
            Path(args.output) if args.output
            else output_dir / f'{config.output_prefix}{json_path.name}'
        )
        mapped_rows, stats, _ = process_file(json_path, mapping_rows, config)
        write_json(output_path, mapped_rows)

        print(f'{json_path.name} -> {output_path.name}')
        print(f'  Total: {stats["total"]}')
        print(f'  Matched: {stats["matched"]}')
        print(f'  Unmatched: {stats["unmatched"]}')
        print(f'  Ambiguous: {stats["ambiguous"]}')
        return

    # --- 디렉토리 모드 ---
    if not input_dir.exists():
        raise FileNotFoundError(f'입력 폴더를 찾지 못했습니다: {input_dir}')

    input_files = sorted(input_dir.glob('*.json'))
    if not input_files:
        raise FileNotFoundError(f'매핑 대상 JSON이 없습니다: {input_dir}')

    all_matched_csv_ids: Set[str] = set()
    file_stats: List[Dict] = []

    for json_path in input_files:
        output_name = f'{config.output_prefix}{json_path.name}'
        output_path = output_dir / output_name

        mapped_rows, stats, matched_ids = process_file(json_path, mapping_rows, config)
        write_json(output_path, mapped_rows)
        all_matched_csv_ids.update(matched_ids)

        file_stats.append({'file': json_path.name, 'output': output_name, **stats})
        print(
            f'{json_path.name}: '
            f'Matched={stats["matched"]}, Unmatched={stats["unmatched"]}, '
            f'Ambiguous={stats["ambiguous"]}'
        )

    # Sibling fallback
    sibling_ids = apply_sibling_fallback(
        mapping_rows, all_matched_csv_ids, output_dir, config,
    )
    all_matched_csv_ids.update(sibling_ids)

    # 리포트 생성
    report = generate_csv_based_report(
        args.data_set, mapping_rows, all_matched_csv_ids, file_stats,
    )
    report_path = output_dir / 'mapping_report.json'
    write_json(report_path, report)

    s = report['summary']
    print(f'\n{"="*50}')
    print(f'[{args.data_set}] MAPPING SUMMARY')
    print(f'{"="*50}')
    print(f'Input rows:  {s["total_input_rows"]}')
    print(f'  Matched:   {s["matched"]}')
    print(f'  Unmatched: {s["unmatched"]}')
    print(f'  Ambiguous: {s["ambiguous"]}')
    print(f'  Rate:      {s["match_rate"]}')
    print(f'CSV rows:    {s["csv_total"]}')
    print(f'  Matched:   {s["csv_matched"]}')
    print(f'  Unmatched: {s["csv_unmatched"]}')
    print(f'  Rate:      {s["csv_match_rate"]}')
    print(f'Report: {report_path}')


if __name__ == '__main__':
    main()
