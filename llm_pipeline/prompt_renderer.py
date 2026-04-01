#!/usr/bin/env python3
"""Prompt template loader and context renderer.

Loads markdown prompt templates from the prompts/ directory and injects
document content + prior step results to produce final LLM prompts.
"""
import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

PROMPTS_DIR = Path(__file__).resolve().parent.parent / 'prompts'


def load_prompt(step_name: str) -> str:
    """Load a prompt template by step name.

    Args:
        step_name: e.g. 'step1_product_classification', 'step2a_payment_cycle'
    """
    path = PROMPTS_DIR / f'{step_name}.md'
    if not path.exists():
        raise FileNotFoundError(f'Prompt template not found: {path}')
    return path.read_text(encoding='utf-8')


def render_prompt(
    step_name: str,
    document_markdown: str,
    *,
    product_list: Optional[List[dict]] = None,
    step2b_result: Optional[List[dict]] = None,
    step2c_result: Optional[List[dict]] = None,
    filename: str = '',
) -> str:
    """Render a prompt template with context variables.

    Replaces {{placeholders}} in the template:
    - {{DOCUMENT}} → full document markdown
    - {{FILENAME}} → document filename
    - {{PRODUCT_LIST}} → JSON of Step 1 result
    - {{STEP2B_RESULT}} → JSON of insurance period result
    - {{STEP2C_RESULT}} → JSON of annuity age result
    """
    template = load_prompt(step_name)

    replacements = {
        'DOCUMENT': document_markdown,
        'FILENAME': filename,
        'PRODUCT_LIST': json.dumps(product_list, ensure_ascii=False, indent=2) if product_list else '[]',
        'STEP2B_RESULT': json.dumps(step2b_result, ensure_ascii=False, indent=2) if step2b_result else '[]',
        'STEP2C_RESULT': json.dumps(step2c_result, ensure_ascii=False, indent=2) if step2c_result else '[]',
    }

    for key, value in replacements.items():
        template = template.replace(f'{{{{{key}}}}}', value)

    return template


def build_messages(
    system_prompt: str,
    user_content: str,
) -> List[Dict[str, str]]:
    """Build a standard chat messages list for any LLM API.

    Returns:
        [{"role": "system", "content": ...}, {"role": "user", "content": ...}]
    """
    return [
        {'role': 'system', 'content': system_prompt},
        {'role': 'user', 'content': user_content},
    ]


# ── System prompt (shared across all steps) ──

SYSTEM_PROMPT = """당신은 한국어 보험 사업방법서에서 구조화된 데이터를 추출하는 전문가입니다.

규칙:
1. 문서에 명시된 정보만 추출하세요. 추측하거나 없는 정보를 만들지 마세요.
2. 출력은 반드시 유효한 JSON 배열이어야 합니다.
3. JSON 외의 텍스트(설명, 마크다운 등)를 포함하지 마세요.
4. 문서의 원문 표현을 최대한 그대로 사용하세요."""
