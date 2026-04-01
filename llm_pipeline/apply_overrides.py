#!/usr/bin/env python3
"""Consolidated override post-processing.

Applies product_overrides.json rules to LLM extraction results.
Consolidates override logic from all extract_*_v2.py files into a single module.
"""
import copy
import json
from pathlib import Path
from typing import Any, Dict, List, Optional

CONFIG_DIR = Path(__file__).resolve().parent.parent / 'config'
OVERRIDES_PATH = CONFIG_DIR / 'product_overrides.json'


def _load_overrides() -> dict:
    if OVERRIDES_PATH.exists():
        with OVERRIDES_PATH.open('r', encoding='utf-8') as f:
            return json.load(f)
    return {}


def _match_keywords(product_name: str, key: str) -> bool:
    """Check if all '+'-separated keywords in key are present in product_name."""
    keywords = key.split('+')
    return all(kw in product_name for kw in keywords)


def _build_lookup(results: List[dict], data_field: str) -> Dict[str, list]:
    """Build product name → data lookup."""
    return {r.get('상품명', ''): r.get(data_field, []) for r in results}


def _apply_sibling_fallback(
    product_name: str,
    lookup: Dict[str, list],
    suffix_patterns: List[str],
) -> Optional[list]:
    """Try to find data from a sibling product via suffix pattern substitution."""
    for pattern in suffix_patterns:
        parts = pattern.split('|')
        if len(parts) != 2:
            continue
        src, dst = parts
        if src in product_name:
            base_name = product_name.replace(src, dst)
            base_data = lookup.get(base_name, [])
            if base_data:
                return list(base_data)
    return None


# ── Step 1: Product Classification Overrides ──

def apply_classification_overrides(
    results: List[dict],
    filename: str,
) -> List[dict]:
    """Apply product_classification overrides from config."""
    overrides = _load_overrides()
    pc_overrides = overrides.get('product_classification', {})

    for key, cfg in pc_overrides.items():
        if key.startswith('_'):
            continue
        if key not in filename:
            continue

        action = cfg.get('action', 'fixed')

        if action == 'fixed':
            return list(cfg.get('items', []))

        elif action == 'alias':
            # Generate alias products (separate file)
            # This is handled at orchestrator level
            pass

    return results


# ── Step 2a: Payment Cycle Overrides ──

def apply_payment_cycle_overrides(results: List[dict]) -> List[dict]:
    """Apply payment_cycle overrides + sibling fallback."""
    overrides = _load_overrides()
    pc_overrides = overrides.get('payment_cycle', {})
    suffix_patterns = overrides.get('sibling_fallback', {}).get('suffix_patterns', [])

    lookup = _build_lookup(results, '납입주기')

    for r in results:
        product_name = r.get('상품명', '')
        cycles = r.get('납입주기', [])
        applied = False

        for key, cfg in pc_overrides.items():
            if key.startswith('_'):
                continue
            if not _match_keywords(product_name, key):
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
                for name, cdata in lookup.items():
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
                for name, cdata in lookup.items():
                    if (all(m in name for m in match_kws)
                            and any(a in name for a in any_kws)
                            and cdata):
                        r['납입주기'] = list(cdata)
                        applied = True
                        break

            if applied:
                break

        if not applied and not r.get('납입주기'):
            fallback = _apply_sibling_fallback(product_name, lookup, suffix_patterns)
            if fallback:
                r['납입주기'] = fallback

    return results


# ── Step 2b: Insurance Period Overrides ──

def apply_insurance_period_overrides(results: List[dict]) -> List[dict]:
    """Apply insurance_period overrides + sibling fallback."""
    overrides = _load_overrides()
    ip_overrides = overrides.get('insurance_period', {})
    suffix_patterns = overrides.get('sibling_fallback', {}).get('suffix_patterns', [])

    lookup = _build_lookup(results, '가입가능보기납기')

    for r in results:
        product_name = r.get('상품명', '')
        periods = r.get('가입가능보기납기', [])
        applied = False

        for key, cfg in ip_overrides.items():
            if key.startswith('_'):
                continue
            if not _match_keywords(product_name, key):
                continue

            action = cfg.get('action', 'fixed')

            if action == 'fixed':
                r['가입가능보기납기'] = list(cfg.get('periods', []))
                applied = True

            elif action == 'sibling_filter':
                match_kws = cfg.get('sibling_match', [])
                exclude_kws = cfg.get('sibling_exclude', [])
                filt = cfg.get('filter', {})
                for name, pdata in lookup.items():
                    if (all(m in name for m in match_kws)
                            and not any(e in name for e in exclude_kws)
                            and pdata):
                        filtered = list(pdata)
                        if filt.get('보험기간구분코드'):
                            filtered = [p for p in filtered
                                        if p.get('보험기간구분코드') == filt['보험기간구분코드']]
                        if filt.get('보험기간값'):
                            vals = filt['보험기간값']
                            if isinstance(vals, list):
                                filtered = [p for p in filtered if p.get('보험기간값') in vals]
                        if filt.get('exclude_jeonginap'):
                            filtered = [p for p in filtered
                                        if p.get('납입기간') != p.get('보험기간')]
                        r['가입가능보기납기'] = filtered
                        applied = True
                        break

            elif action == 'sibling_copy':
                if not periods:
                    match_kws = cfg.get('sibling_match', [])
                    any_kws = cfg.get('sibling_any', [])
                    for name, pdata in lookup.items():
                        if (all(m in name for m in match_kws)
                                and any(a in name for a in any_kws)
                                and pdata):
                            r['가입가능보기납기'] = list(pdata)
                            applied = True
                            break

            elif action == 'filter':
                if periods:
                    filtered = list(periods)
                    for combo in cfg.get('exclude_combinations', []):
                        filtered = [p for p in filtered
                                    if not all(p.get(k) == v for k, v in combo.items())]
                    inc_vals = cfg.get('include_납입기간값')
                    if inc_vals:
                        filtered = [p for p in filtered if p.get('납입기간값') in inc_vals]
                    r['가입가능보기납기'] = filtered
                    applied = True

            elif action == 'add_annuity_start_age':
                if periods:
                    additional_min = cfg.get('additional_min_age', '')
                    new_periods = list(periods)
                    for p in periods:
                        if (p.get('최소제2보기개시나이')
                                and p.get('최소제2보기개시나이') != additional_min
                                and p.get('납입기간구분코드') == 'N'):
                            dup = copy.deepcopy(p)
                            dup['최소제2보기개시나이'] = additional_min
                            new_periods.append(dup)
                    r['가입가능보기납기'] = new_periods
                    applied = True

            if applied:
                break

        if not applied and not r.get('가입가능보기납기'):
            fallback = _apply_sibling_fallback(product_name, lookup, suffix_patterns)
            if fallback:
                r['가입가능보기납기'] = fallback

    return results


# ── Step 2c: Annuity Age Overrides ──

def apply_annuity_age_overrides(results: List[dict]) -> List[dict]:
    """Apply annuity_age_overrides from config."""
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
            if not _match_keywords(product_name, key):
                continue

            action = cfg.get('action', 'fixed')

            if action == 'fixed':
                r['보기개시나이정보'] = list(cfg.get('ages', []))

            elif action == 'gender_split':
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
                    if age_val <= female_max:
                        f_rec = copy.deepcopy(a)
                        f_rec['성별'] = '여자'
                        new_ages.append(f_rec)
                    if age_val >= male_min:
                        m_rec = copy.deepcopy(a)
                        m_rec['성별'] = '남자'
                        new_ages.append(m_rec)
                r['보기개시나이정보'] = new_ages
            break

    return results


# ── Step 2d: Join Age Overrides ──

def apply_join_age_overrides(results: List[dict]) -> List[dict]:
    """Apply join_age overrides from config."""
    overrides = _load_overrides()
    ja_overrides = overrides.get('join_age', {})
    if not ja_overrides:
        return results

    for r in results:
        product_name = r.get('상품명', '')
        ages = r.get('가입가능나이', [])

        for key, cfg in ja_overrides.items():
            if key.startswith('_'):
                continue
            if key not in product_name:
                continue

            action = cfg.get('action')

            if action == 'min_age_floor':
                floor = cfg.get('min_age', '0')
                for a in ages:
                    cur_min = a.get('최소가입나이', '0')
                    if cur_min and int(cur_min) < int(floor):
                        a['최소가입나이'] = floor

            # Variant-based overrides
            variants = cfg.get('variants', [])
            for v in variants:
                match_kws = v.get('match', [])
                if all(m in product_name for m in match_kws):
                    for a in ages:
                        if 'min_age' in v:
                            a['최소가입나이'] = v['min_age']
                        if 'max_age' in v:
                            a['최대가입나이'] = v['max_age']
                    break

    return results


# ── Normalization (code encoding) ──

_CYCLE_MAP = {
    '일시납': ('0', 'O'),
    '월납': ('1', 'M'),
    '3개월납': ('3', 'Q'),
    '6개월납': ('6', 'H'),
    '연납': ('12', 'Y'),
}


def normalize_payment_cycles(results: List[dict]) -> List[dict]:
    """Add 납입주기값 and 납입주기구분코드 from 납입주기명."""
    for r in results:
        for c in r.get('납입주기', []):
            name = c.get('납입주기명', '')
            val, code = _CYCLE_MAP.get(name, ('', ''))
            c['납입주기값'] = val
            c['납입주기구분코드'] = code
    return results


import re

def _encode_period(text: str) -> dict:
    """Convert period text to code format.

    "10년" → {"code": "N10", "구분코드": "N", "값": "10"}
    "90세" → {"code": "X90", "구분코드": "X", "값": "90"}
    "종신" → {"code": "A999", "구분코드": "A", "값": "999"}
    "전기납" or "일시납" → special handling
    """
    text = text.strip()
    if not text:
        return {'code': '', '구분코드': '', '값': ''}
    if '종신' in text:
        return {'code': 'A999', '구분코드': 'A', '값': '999'}
    if text == '일시납':
        return {'code': 'N0', '구분코드': 'N', '값': '0'}

    m = re.match(r'(\d+)\s*세', text)
    if m:
        v = m.group(1)
        return {'code': f'X{v}', '구분코드': 'X', '값': v}
    m = re.match(r'(\d+)\s*년', text)
    if m:
        v = m.group(1)
        return {'code': f'N{v}', '구분코드': 'N', '값': v}

    return {'code': text, '구분코드': '', '값': text}


def normalize_insurance_periods(results: List[dict]) -> List[dict]:
    """Encode 보험기간/납입기간 text to standard codes."""
    for r in results:
        for p in r.get('가입가능보기납기', []):
            # 보험기간
            ins = _encode_period(p.get('보험기간', ''))
            p['보험기간'] = ins['code']
            p['보험기간구분코드'] = ins['구분코드']
            p['보험기간값'] = ins['값']
            # 납입기간
            pay_text = p.get('납입기간', '')
            if pay_text == '전기납':
                # 전기납 = same as 보험기간
                p['납입기간'] = ins['code']
                p['납입기간구분코드'] = ins['구분코드']
                p['납입기간값'] = ins['값']
            else:
                pay = _encode_period(pay_text)
                p['납입기간'] = pay['code']
                p['납입기간구분코드'] = pay['구분코드']
                p['납입기간값'] = pay['값']
            # 제2보기개시나이
            for field in ('최소제2보기개시나이', '최대제2보기개시나이'):
                val = p.get(field, '')
                if val and str(val).isdigit():
                    p[field] = val
                elif not val:
                    p[field] = ''
            if p.get('최소제2보기개시나이') or p.get('최대제2보기개시나이'):
                p.setdefault('제2보기개시나이구분코드', 'X')
            else:
                p.setdefault('제2보기개시나이구분코드', '')
    return results


def expand_annuity_ages(results: List[dict]) -> List[dict]:
    """Expand compact min/max age ranges into individual age rows."""
    for r in results:
        raw_ages = r.get('보기개시나이정보', [])
        expanded = []
        for a in raw_ages:
            age_min = a.get('나이_min')
            age_max = a.get('나이_max')
            if age_min is not None and age_max is not None:
                try:
                    lo, hi = int(age_min), int(age_max)
                except (ValueError, TypeError):
                    expanded.append(a)
                    continue
                for age in range(lo, hi + 1):
                    rec = {
                        '제2보기개시나이': f'X{age}',
                        '제2보기개시나이구분코드': 'X',
                        '제2보기개시나이값': str(age),
                        '성별': a.get('성별', ''),
                    }
                    expanded.append(rec)
            else:
                expanded.append(a)
        r['보기개시나이정보'] = expanded
    return results
