#!/usr/bin/env python3
"""DOCX to structured markdown converter.

Converts Korean insurance business specification documents (사업방법서) into
structured markdown that can be fed to any LLM for data extraction.

Key features:
- Preserves document order (paragraphs + tables interleaved)
- Renders tables as markdown pipe tables with merged cell handling
- Detects section headers and adds markdown heading markers
- NFC unicode normalization + zero-width space removal
"""
import re
import unicodedata
from pathlib import Path
from typing import List, Optional, Tuple

from docx import Document
from docx.oxml.ns import qn
from docx.table import Table as DocxTable, _Cell
from docx.text.paragraph import Paragraph


def normalize_ws(value: str) -> str:
    """NFC normalize and collapse whitespace."""
    s = unicodedata.normalize('NFC', value or '')
    s = s.replace('\u200b', '').replace('\ufeff', '')  # zero-width chars
    return re.sub(r'\s+', ' ', s).strip()


# ── Section header detection ──

_SECTION_NUMBER_RE = re.compile(
    r'^(?:'
    r'(?:제?\s*\d+\s*조)|'                     # 제1조, 1조
    r'(?:\d+\.\s)|'                             # 1. 2.
    r'(?:[가-힣]\.\s)|'                         # 가. 나. 다.
    r'(?:\(\s*\d+\s*\))|'                       # (1) (2)
    r'(?:[①-⑳])'                               # circled numbers
    r')'
)

_HEADING_KEYWORDS = [
    '보험종목의 명칭', '보험종목의 구성', '세부보험종목',
    '보험기간', '납입기간', '납입주기', '가입나이', '가입가능나이',
    '연금개시나이', '보험료 납입', '종속특약',
]


def _detect_heading_level(text: str) -> int:
    """Return markdown heading level (2-4) or 0 if not a heading."""
    stripped = text.strip()
    if not stripped:
        return 0
    # Top-level numbered sections: "1. ...", "제1조 ..."
    if re.match(r'^(?:제?\s*\d+\s*조|(?:\d+)\.\s)', stripped):
        return 2
    # Sub-sections: "가. ...", "나. ..."
    if re.match(r'^[가-힣]\.\s', stripped):
        return 3
    # Sub-sub: "(1) ...", circled numbers
    if re.match(r'^(?:\(\s*\d+\s*\)|[①-⑳])', stripped):
        return 4
    # Keyword-based detection for lines that are headings but lack numbering
    for kw in _HEADING_KEYWORDS:
        if kw in stripped and len(stripped) < 60:
            return 3
    return 0


# ── Table rendering ──

def _docx_table_to_grid(table: DocxTable) -> List[List[str]]:
    """Convert a docx Table to a 2D grid, resolving merged cells.

    - Vertical merge continuations are filled with the value from the
      cell above (fill-down).
    - Horizontal spans (gridSpan) repeat the cell value across columns.
    """
    raw_rows: List[List[Optional[str]]] = []
    for row in table.rows:
        cells: List[Optional[str]] = []
        for tc in row._tr.tc_lst:
            tc_pr = tc.find(qn('w:tcPr'))
            # Vertical merge continuation → None (to be filled later)
            if tc_pr is not None:
                vmerge = tc_pr.find(qn('w:vMerge'))
                if vmerge is not None and vmerge.get(qn('w:val')) is None:
                    grid_span_el = tc_pr.find(qn('w:gridSpan'))
                    span = int(grid_span_el.get(qn('w:val'))) if grid_span_el is not None else 1
                    cells.extend([None] * span)
                    continue
            # Normal cell or vMerge restart
            text = normalize_ws(_Cell(tc, table).text)
            if tc_pr is not None:
                grid_span_el = tc_pr.find(qn('w:gridSpan'))
                if grid_span_el is not None:
                    span = int(grid_span_el.get(qn('w:val')))
                    cells.append(text)
                    # Repeat value for horizontal span
                    cells.extend([text] * (span - 1))
                    continue
            cells.append(text)
        raw_rows.append(cells)

    # Fill-down for vertical merges (None cells)
    if raw_rows:
        max_cols = max(len(r) for r in raw_rows)
        for r in raw_rows:
            while len(r) < max_cols:
                r.append('')
        for col_idx in range(max_cols):
            for row_idx in range(1, len(raw_rows)):
                if raw_rows[row_idx][col_idx] is None:
                    raw_rows[row_idx][col_idx] = raw_rows[row_idx - 1][col_idx] or ''

    # Replace remaining None with empty string
    return [[cell or '' for cell in row] for row in raw_rows]


def _grid_to_markdown(grid: List[List[str]], table_index: int) -> str:
    """Render a 2D grid as a markdown pipe table."""
    if not grid or not grid[0]:
        return ''

    n_cols = max(len(row) for row in grid)
    # Normalize column count
    for row in grid:
        while len(row) < n_cols:
            row.append('')

    # Calculate column widths for alignment
    col_widths = [3] * n_cols
    for row in grid:
        for i, cell in enumerate(row):
            col_widths[i] = max(col_widths[i], len(cell))

    lines = [f'[TABLE {table_index}]']

    for row_idx, row in enumerate(grid):
        cells_str = ' | '.join(cell.ljust(col_widths[i]) for i, cell in enumerate(row))
        lines.append(f'| {cells_str} |')
        # Separator after first row (header)
        if row_idx == 0:
            sep = ' | '.join('-' * col_widths[i] for i in range(n_cols))
            lines.append(f'| {sep} |')

    return '\n'.join(lines)


# ── Main converter ──

def docx_to_markdown(docx_path: Path) -> str:
    """Convert a DOCX file to structured markdown.

    Returns a single markdown string with:
    - Section headings (##, ###, ####)
    - Paragraph text
    - Markdown tables with [TABLE N] markers
    """
    doc = Document(str(docx_path))
    parts: List[str] = []
    table_index = 0

    for child in doc.element.body:
        if child.tag == qn('w:p'):
            para = Paragraph(child, doc)
            text = normalize_ws(para.text)
            if not text:
                continue
            level = _detect_heading_level(text)
            if level > 0:
                parts.append(f'\n{"#" * level} {text}\n')
            else:
                parts.append(text)

        elif child.tag == qn('w:tbl'):
            table_index += 1
            tbl = DocxTable(child, doc)
            grid = _docx_table_to_grid(tbl)
            if grid and any(any(cell for cell in row) for row in grid):
                md_table = _grid_to_markdown(grid, table_index)
                parts.append(f'\n{md_table}\n')

    return '\n'.join(parts)


def docx_to_sections(docx_path: Path) -> dict:
    """Convert DOCX to markdown and split into named sections.

    Returns:
        {
            "full": "complete markdown",
            "sections": {
                "보험종목의 명칭": "section content...",
                "납입주기": "section content...",
                ...
            }
        }
    """
    full_md = docx_to_markdown(docx_path)

    # Split by level-2 and level-3 headings
    section_re = re.compile(r'^(#{2,3})\s+(.+)$', re.MULTILINE)
    matches = list(section_re.finditer(full_md))

    sections = {}
    for i, m in enumerate(matches):
        heading_text = m.group(2).strip()
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(full_md)
        section_content = full_md[start:end].strip()

        # Store by keyword match for easy lookup
        for kw in _HEADING_KEYWORDS:
            if kw in heading_text:
                # Use the keyword as key (not full heading text)
                if kw not in sections:
                    sections[kw] = section_content
                else:
                    sections[kw] += '\n\n' + section_content
                break
        else:
            # Store by full heading text if no keyword match
            sections[heading_text] = section_content

    return {
        'full': full_md,
        'sections': sections,
    }


if __name__ == '__main__':
    import sys
    if len(sys.argv) < 2:
        print('Usage: python docx_to_markdown.py <path.docx> [--sections]')
        sys.exit(1)

    path = Path(sys.argv[1])
    if '--sections' in sys.argv:
        result = docx_to_sections(path)
        print(result['full'][:500])
        print('\n--- Detected sections ---')
        for name in result['sections']:
            preview = result['sections'][name][:100].replace('\n', ' ')
            print(f'  [{name}]: {preview}...')
    else:
        print(docx_to_markdown(path))
