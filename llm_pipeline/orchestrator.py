#!/usr/bin/env python3
"""Pipeline orchestrator for prompt-based extraction.

Manages the full extraction flow:
  DOCX → markdown → LLM calls → parse → normalize → override → JSON output

LLM-agnostic: requires a callable `llm_call(messages) -> str` function.
"""
import json
import logging
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from .docx_to_markdown import docx_to_markdown
from .prompt_renderer import SYSTEM_PROMPT, render_prompt
from .output_parser import extract_json, validate_output
from .apply_overrides import (
    apply_classification_overrides,
    apply_payment_cycle_overrides,
    apply_insurance_period_overrides,
    apply_annuity_age_overrides,
    apply_join_age_overrides,
    normalize_payment_cycles,
    normalize_insurance_periods,
    expand_annuity_ages,
)

log = logging.getLogger(__name__)

# Type alias for the LLM call function
# Signature: (messages: List[dict]) -> str
LLMCallFn = Callable[[List[dict]], str]


def _call_llm_step(
    llm_call: LLMCallFn,
    step_name: str,
    document_md: str,
    step_id: str,
    **render_kwargs,
) -> List[dict]:
    """Run a single extraction step: render prompt → call LLM → parse → validate."""
    user_prompt = render_prompt(step_name, document_md, **render_kwargs)

    messages = [
        {'role': 'system', 'content': SYSTEM_PROMPT},
        {'role': 'user', 'content': user_prompt},
    ]

    log.info(f'[{step_id}] Calling LLM (prompt length: {len(user_prompt):,} chars)')
    response_text = llm_call(messages)
    log.info(f'[{step_id}] Response received ({len(response_text):,} chars)')

    data = extract_json(response_text)
    errors = validate_output(data, step_id)
    if errors:
        log.warning(f'[{step_id}] Validation warnings: {errors[:5]}')

    if not isinstance(data, list):
        data = [data] if isinstance(data, dict) else []

    return data


def run_pipeline(
    docx_path: Path,
    llm_call: LLMCallFn,
    output_dir: Optional[Path] = None,
) -> Dict[str, List[dict]]:
    """Run the full extraction pipeline on a single DOCX file.

    Args:
        docx_path: Path to the DOCX file
        llm_call: Function that takes messages list and returns response text
        output_dir: Optional directory to save JSON outputs

    Returns:
        Dict with keys: 'step1', 'step2a', 'step2b', 'step2c', 'step2d'
    """
    filename = docx_path.stem
    log.info(f'Processing: {filename}')

    # ── Preprocessing ──
    log.info('Converting DOCX to markdown...')
    document_md = docx_to_markdown(docx_path)
    log.info(f'Markdown length: {len(document_md):,} chars')

    results = {}

    # ── Step 1: Product Classification ──
    step1 = _call_llm_step(
        llm_call, 'step1_product_classification', document_md,
        step_id='step1', filename=filename,
    )
    step1 = apply_classification_overrides(step1, filename)
    results['step1'] = step1
    log.info(f'Step 1: {len(step1)} products extracted')

    # ── Steps 2a, 2b, 2c (can run in parallel if async) ──

    # Step 2a: Payment Cycle
    step2a = _call_llm_step(
        llm_call, 'step2a_payment_cycle', document_md,
        step_id='step2a', product_list=step1, filename=filename,
    )
    step2a = normalize_payment_cycles(step2a)
    step2a = apply_payment_cycle_overrides(step2a)
    results['step2a'] = step2a
    log.info(f'Step 2a: {len(step2a)} payment cycle records')

    # Step 2b: Insurance Period
    step2b = _call_llm_step(
        llm_call, 'step2b_insurance_period', document_md,
        step_id='step2b', product_list=step1, filename=filename,
    )
    step2b = normalize_insurance_periods(step2b)
    step2b = apply_insurance_period_overrides(step2b)
    results['step2b'] = step2b
    log.info(f'Step 2b: {sum(len(r.get("가입가능보기납기", [])) for r in step2b)} period combos')

    # Step 2c: Annuity Age
    step2c = _call_llm_step(
        llm_call, 'step2c_annuity_age', document_md,
        step_id='step2c', product_list=step1, filename=filename,
    )
    step2c = expand_annuity_ages(step2c)
    step2c = apply_annuity_age_overrides(step2c)
    results['step2c'] = step2c
    log.info(f'Step 2c: {sum(len(r.get("보기개시나이정보", [])) for r in step2c)} annuity age records')

    # ── Step 2d: Join Age (depends on 2b + 2c) ──
    step2d = _call_llm_step(
        llm_call, 'step2d_join_age', document_md,
        step_id='step2d',
        product_list=step1,
        step2b_result=step2b,
        step2c_result=step2c,
        filename=filename,
    )
    step2d = _post_process_join_age(step2d, step2c)
    step2d = apply_join_age_overrides(step2d)
    results['step2d'] = step2d
    log.info(f'Step 2d: {sum(len(r.get("가입가능나이", [])) for r in step2d)} join age records')

    # ── Save outputs ──
    if output_dir:
        _save_outputs(results, output_dir, filename)

    return results


def _post_process_join_age(
    step2d: List[dict],
    step2c: List[dict],
) -> List[dict]:
    """Post-process join age: handle formula mode computation.

    For formula mode products, compute max_age = annuity_start_age - deduction
    using Step 2c annuity age data.
    """
    # Build annuity age lookup: product_name → list of ages
    annuity_lookup: Dict[str, List[dict]] = {}
    for r in step2c:
        name = r.get('상품명', '')
        annuity_lookup[name] = r.get('보기개시나이정보', [])

    for r in step2d:
        if r.get('추출방식') != 'formula':
            continue

        product_name = r.get('상품명', '')
        deduction_table = r.get('차감표', [])
        base_ages = r.get('가입가능나이', [])
        annuity_ages = annuity_lookup.get(product_name, [])

        if not deduction_table or not annuity_ages:
            continue

        min_age = base_ages[0].get('최소가입나이', '0') if base_ages else '0'

        # Build deduction lookup: 납입기간 → 차감값
        ded_map = {}
        for d in deduction_table:
            period = d.get('납입기간', '')
            ded_val = d.get('차감값', '0')
            ded_map[period] = int(ded_val)

        # Compute max ages for each annuity_age × deduction combination
        computed = []
        for ann in annuity_ages:
            ann_age_val = ann.get('제2보기개시나이값', '')
            gender = ann.get('성별', '')
            if not ann_age_val:
                continue
            ann_age = int(ann_age_val)

            for period_text, deduction in ded_map.items():
                max_age = ann_age - deduction
                if max_age < int(min_age):
                    continue
                computed.append({
                    '최소가입나이': min_age,
                    '최대가입나이': str(max_age),
                    '성별': gender,
                })

        if computed:
            r['가입가능나이'] = computed
            r.pop('차감표', None)
            r.pop('추출방식', None)

    # Clean up 추출방식 for non-formula products
    for r in step2d:
        r.pop('추출방식', None)
        r.pop('차감표', None)

    return step2d


# ── Output file mapping ──

_OUTPUT_MAP = {
    'step1': ('상품분류', '상품분류'),
    'step2a': ('납입주기', '납입주기'),
    'step2b': ('가입가능보기납기', '가입가능보기납기'),
    'step2c': ('보기개시나이', '보기개시나이'),
    'step2d': ('가입가능나이', '가입가능나이'),
}


def _save_outputs(
    results: Dict[str, List[dict]],
    output_dir: Path,
    filename: str,
):
    """Save extraction results as JSON files."""
    for step_key, (dir_name, _) in _OUTPUT_MAP.items():
        data = results.get(step_key, [])
        if not data:
            continue
        step_dir = output_dir / dir_name
        step_dir.mkdir(parents=True, exist_ok=True)
        out_path = step_dir / f'{filename}.json'
        with out_path.open('w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        log.info(f'Saved: {out_path}')
