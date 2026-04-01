#!/usr/bin/env python3
"""Parse and validate LLM JSON responses.

Handles common LLM output quirks:
- Markdown code fences around JSON
- Trailing commas
- Explanatory text before/after JSON
"""
import json
import re
from typing import Any, Dict, List, Optional, Set


def extract_json(text: str) -> Any:
    """Extract JSON from LLM response text.

    Handles:
    - ```json ... ``` code fences
    - Raw JSON arrays/objects
    - Text before/after the JSON
    """
    # Try markdown code fence first
    fence_match = re.search(r'```(?:json)?\s*\n?([\s\S]*?)\n?```', text)
    if fence_match:
        return _parse_json(fence_match.group(1).strip())

    # Try to find JSON array or object directly
    # Find the outermost [ ... ] or { ... }
    for start_char, end_char in [('[', ']'), ('{', '}')]:
        start_idx = text.find(start_char)
        if start_idx == -1:
            continue
        # Find matching end bracket
        depth = 0
        for i in range(start_idx, len(text)):
            if text[i] == start_char:
                depth += 1
            elif text[i] == end_char:
                depth -= 1
                if depth == 0:
                    return _parse_json(text[start_idx:i + 1])

    raise ValueError(f'No valid JSON found in response (length={len(text)})')


def _parse_json(text: str) -> Any:
    """Parse JSON with tolerance for trailing commas."""
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # Remove trailing commas before ] or }
        cleaned = re.sub(r',\s*([}\]])', r'\1', text)
        return json.loads(cleaned)


# ── Schema validation ──

_STEP_SCHEMAS: Dict[str, Dict[str, str]] = {
    'step1': {
        'required': {'상품명칭', '상품명'},
        'optional': {f'세부종목{i}' for i in range(1, 11)},
    },
    'step2a': {
        'required': {'상품명', '납입주기'},
        'nested': {'납입주기': {'required': {'납입주기명'}}},
    },
    'step2b': {
        'required': {'상품명', '가입가능보기납기'},
        'nested': {'가입가능보기납기': {
            'required': {'보험기간', '납입기간'},
            'optional': {'제2보기개시나이_min', '제2보기개시나이_max'},
        }},
    },
    'step2c': {
        'required': {'상품명', '보기개시나이정보'},
        'nested': {'보기개시나이정보': {
            'required': {'나이_min', '나이_max'},
            'optional': {'성별', '연금유형'},
        }},
    },
    'step2d': {
        'required': {'상품명', '추출방식'},
        'optional': {'가입가능나이', '차감표'},
    },
}


def validate_output(data: Any, step: str) -> List[str]:
    """Validate LLM output against expected schema.

    Args:
        data: Parsed JSON (should be a list of dicts)
        step: Step identifier ('step1', 'step2a', etc.)

    Returns:
        List of validation error messages (empty if valid)
    """
    errors = []

    if not isinstance(data, list):
        return [f'Expected JSON array, got {type(data).__name__}']

    schema = _STEP_SCHEMAS.get(step)
    if not schema:
        return []  # No schema defined, skip validation

    required: Set[str] = schema.get('required', set())
    nested: Dict = schema.get('nested', {})

    for i, item in enumerate(data):
        if not isinstance(item, dict):
            errors.append(f'Item [{i}]: expected object, got {type(item).__name__}')
            continue

        for field in required:
            if field not in item:
                errors.append(f'Item [{i}]: missing required field "{field}"')

        # Validate nested arrays
        for field_name, nested_schema in nested.items():
            if field_name not in item:
                continue
            nested_data = item[field_name]
            if not isinstance(nested_data, list):
                errors.append(f'Item [{i}].{field_name}: expected array')
                continue
            nested_required = nested_schema.get('required', set())
            for j, nested_item in enumerate(nested_data):
                if not isinstance(nested_item, dict):
                    errors.append(f'Item [{i}].{field_name}[{j}]: expected object')
                    continue
                for nf in nested_required:
                    if nf not in nested_item:
                        errors.append(f'Item [{i}].{field_name}[{j}]: missing "{nf}"')

    return errors
