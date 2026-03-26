"""Pipeline orchestrator for single-PDF extraction demo."""
import json
import sys
import traceback
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# Ensure project root is importable
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


@dataclass
class StepStatus:
    status: str = "pending"  # pending | running | completed | error
    message: str = ""
    result: Any = None
    substeps: Dict[str, "StepStatus"] = field(default_factory=dict)


@dataclass
class PipelineSession:
    session_id: str
    work_dir: Path
    pdf_path: Path
    filename: str
    steps: Dict[int, StepStatus] = field(default_factory=dict)

    def __post_init__(self):
        for i in range(1, 6):
            self.steps[i] = StepStatus()
        # Step 2 has substeps
        self.steps[2].substeps = {
            "payment_cycle": StepStatus(),
            "annuity_age": StepStatus(),
            "insurance_period": StepStatus(),
            "join_age": StepStatus(),
        }

    @property
    def classification_json_path(self) -> Path:
        return self.work_dir / "상품분류.json"

    @property
    def period_dir(self) -> Path:
        return self.work_dir / "가입가능보기납기"


def create_session(pdf_bytes: bytes, filename: str) -> PipelineSession:
    session_id = uuid.uuid4().hex[:12]
    work_dir = Path("/tmp/hli_poc") / session_id
    work_dir.mkdir(parents=True, exist_ok=True)
    pdf_path = work_dir / filename
    pdf_path.write_bytes(pdf_bytes)
    return PipelineSession(
        session_id=session_id,
        work_dir=work_dir,
        pdf_path=pdf_path,
        filename=filename,
    )


# ── Step 1: Product Classification ──

def run_step1(session: PipelineSession) -> List[dict]:
    from extract_product_classification import extract_pdf

    session.steps[1].status = "running"
    session.steps[1].message = "상품분류 추출 중..."
    try:
        records = extract_pdf(session.pdf_path)
        # Save classification JSON for later steps
        with session.classification_json_path.open("w", encoding="utf-8") as f:
            json.dump(records, f, ensure_ascii=False, indent=2)
        session.steps[1].status = "completed"
        session.steps[1].message = f"상품분류 추출 완료 ({len(records)}건)"
        session.steps[1].result = records
        return records
    except Exception as e:
        session.steps[1].status = "error"
        session.steps[1].message = f"오류: {e}"
        raise


# ── Step 2: Data Extraction (4 substeps) ──

def run_step2_payment_cycle(session: PipelineSession) -> List[dict]:
    from extract_payment_cycle import (
        _apply_cycle_overrides,
        extract_payment_cycle_for_pdf,
        load_detail_context_tokens,
        PRODUCT_META_DIR,
    )

    sub = session.steps[2].substeps["payment_cycle"]
    sub.status = "running"
    sub.message = "납입주기 추출 중..."
    try:
        detail_tokens = load_detail_context_tokens(PRODUCT_META_DIR)
        records = extract_payment_cycle_for_pdf(session.pdf_path, detail_tokens)
        records = _apply_cycle_overrides(records)
        out_path = session.work_dir / "납입주기.json"
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(records, f, ensure_ascii=False, indent=2)
        sub.status = "completed"
        sub.message = f"납입주기 추출 완료 ({len(records)}건)"
        sub.result = records
        return records
    except Exception as e:
        sub.status = "error"
        sub.message = f"오류: {e}"
        raise


def run_step2_annuity_age(session: PipelineSession) -> List[dict]:
    from extract_annuity_age import (
        _load_annuity_blocks,
        extract_escalation_pairs,
        extract_pdf_lines,
        merge_records,
    )

    sub = session.steps[2].substeps["annuity_age"]
    sub.status = "running"
    sub.message = "보기개시나이 추출 중..."
    try:
        lines = extract_pdf_lines(session.pdf_path)
        annuity_blocks = _load_annuity_blocks(session.pdf_path)
        escalations = extract_escalation_pairs(lines)

        with session.classification_json_path.open("r", encoding="utf-8") as f:
            rows = json.load(f)
        if not isinstance(rows, list):
            rows = []

        result_rows = [
            merge_records(
                r if isinstance(r, dict) else {"상품명칭": "", "상품명": ""},
                lines, annuity_blocks, escalations,
            )
            for r in rows
        ]

        out_path = session.work_dir / "보기개시나이.json"
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)

        sub.status = "completed"
        sub.message = f"보기개시나이 추출 완료 ({len(result_rows)}건)"
        sub.result = result_rows
        return result_rows
    except Exception as e:
        sub.status = "error"
        sub.message = f"오류: {e}"
        raise


def run_step2_insurance_period(session: PipelineSession) -> List[dict]:
    from extract_insurance_period import process_single

    sub = session.steps[2].substeps["insurance_period"]
    sub.status = "running"
    sub.message = "가입가능보기납기 추출 중..."
    try:
        result_rows = process_single(session.pdf_path, session.classification_json_path)

        # Save to a directory structure for join_age's load_period_data
        period_dir = session.period_dir
        period_dir.mkdir(parents=True, exist_ok=True)
        out_path = period_dir / session.classification_json_path.name
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)

        # Also save flat version
        flat_path = session.work_dir / "가입가능보기납기.json"
        with flat_path.open("w", encoding="utf-8") as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)

        sub.status = "completed"
        sub.message = f"가입가능보기납기 추출 완료 ({len(result_rows)}건)"
        sub.result = result_rows
        return result_rows
    except Exception as e:
        sub.status = "error"
        sub.message = f"오류: {e}"
        raise


def run_step2_join_age(session: PipelineSession) -> List[dict]:
    from extract_join_age import process_single

    sub = session.steps[2].substeps["join_age"]
    sub.status = "running"
    sub.message = "가입가능나이 추출 중..."
    try:
        result_rows = process_single(
            session.pdf_path,
            session.classification_json_path,
            session.period_dir,
        )
        out_path = session.work_dir / "가입가능나이.json"
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(result_rows, f, ensure_ascii=False, indent=2)

        sub.status = "completed"
        sub.message = f"가입가능나이 추출 완료 ({len(result_rows)}건)"
        sub.result = result_rows
        return result_rows
    except Exception as e:
        sub.status = "error"
        sub.message = f"오류: {e}"
        raise


def run_step2(session: PipelineSession) -> Dict[str, List[dict]]:
    session.steps[2].status = "running"
    session.steps[2].message = "세트데이터 추출 중..."
    results = {}

    # Run substeps sequentially (insurance_period before join_age)
    substep_runners = [
        ("payment_cycle", run_step2_payment_cycle),
        ("annuity_age", run_step2_annuity_age),
        ("insurance_period", run_step2_insurance_period),
        ("join_age", run_step2_join_age),
    ]

    for name, runner in substep_runners:
        try:
            results[name] = runner(session)
        except Exception:
            # Continue with other substeps even if one fails
            results[name] = []

    has_error = any(
        s.status == "error" for s in session.steps[2].substeps.values()
    )
    if has_error:
        session.steps[2].status = "error"
        session.steps[2].message = "일부 세트데이터 추출 실패"
    else:
        session.steps[2].status = "completed"
        session.steps[2].message = "세트데이터 추출 완료"
    session.steps[2].result = results
    return results


# ── Step 3: Code Mapping ──

DATASET_FILE_MAP = {
    "product_classification": ("상품분류", "상품분류_"),
    "payment_cycle": ("납입주기", "납입주기_"),
    "annuity_age": ("보기개시나이", "보기개시나이_"),
    "insurance_period": ("가입가능보기납기", "가입가능보기납기_"),
    "join_age": ("가입가능나이", "가입가능나이_"),
}


def run_step3(session: PipelineSession) -> Dict[str, Any]:
    from map_product_code import (
        DATASET_CONFIGS,
        load_mapping_rows,
        process_file,
        write_json,
        DEFAULT_MAPPING_CSV,
        _apply_sibling_fallback_inline,
    )

    session.steps[3].status = "running"
    session.steps[3].message = "코드매핑 중..."

    mapping_rows = load_mapping_rows(DEFAULT_MAPPING_CSV)
    results = {}
    code_map_dir = session.work_dir / "코드매핑"
    code_map_dir.mkdir(parents=True, exist_ok=True)

    # For product_classification, use classification JSON
    # For others, use Step 2 output JSONs
    source_map = {
        "product_classification": session.classification_json_path,
        "payment_cycle": session.work_dir / "납입주기.json",
        "annuity_age": session.work_dir / "보기개시나이.json",
        "insurance_period": session.work_dir / "가입가능보기납기.json",
        "join_age": session.work_dir / "가입가능나이.json",
    }

    for ds_name, config in DATASET_CONFIGS.items():
        source_json = source_map.get(ds_name)
        if not source_json or not source_json.exists():
            continue

        try:
            mapped_rows, stats, matched_ids = process_file(
                source_json, mapping_rows, config,
            )
            sibling_count = _apply_sibling_fallback_inline(
                mapped_rows, matched_ids, mapping_rows,
            )
            if sibling_count:
                stats["sibling_fallback"] = sibling_count
            _, prefix = DATASET_FILE_MAP[ds_name]
            out_path = code_map_dir / f"{prefix}{source_json.name}"
            write_json(out_path, mapped_rows)
            results[ds_name] = {
                "mapped_rows": mapped_rows,
                "stats": stats,
            }
        except Exception as e:
            results[ds_name] = {"error": str(e), "mapped_rows": [], "stats": {}}

    session.steps[3].status = "completed"
    session.steps[3].message = f"코드매핑 완료 ({len(results)}개 데이터셋)"
    session.steps[3].result = results
    return results


# ── Step 4: Validation ──

def run_step4(session: PipelineSession) -> Dict[str, Any]:
    from compare_product_data import (
        get_dataset_config,
        load_csv_rows,
        load_answer_csv,
        load_answer_excel,
        normalize_code,
        normalize_text,
    )

    session.steps[4].status = "running"
    session.steps[4].message = "정답 비교 중..."

    results = {}
    code_map_dir = session.work_dir / "코드매핑"

    for ds_name in ["payment_cycle", "annuity_age", "insurance_period", "join_age"]:
        try:
            config = get_dataset_config(ds_name)
            _, prefix = DATASET_FILE_MAP[ds_name]

            # Load mapped rows from session's code mapping output
            mapped_file = code_map_dir / f"{prefix}상품분류.json"
            if not mapped_file.exists():
                # Try other naming patterns
                candidates = list(code_map_dir.glob(f"{prefix}*.json"))
                if candidates:
                    mapped_file = candidates[0]
                else:
                    results[ds_name] = {
                        "error": "매핑 파일 없음",
                        "summary": {},
                        "details": [],
                    }
                    continue

            with mapped_file.open("r", encoding="utf-8") as f:
                mapped_rows = json.load(f)

            if not mapped_rows:
                results[ds_name] = {
                    "summary": {"total": 0, "matched": 0, "unmatched": 0},
                    "details": [],
                }
                continue

            # Load answer data
            if config.answer_csv and config.answer_csv.exists():
                answer_data = load_answer_csv(
                    config.answer_csv, config.answer_key_cols, config.answer_value_cols,
                )
            elif config.answer_excel and config.answer_excel.exists():
                answer_data = load_answer_excel(
                    config.answer_excel, config.answer_key_cols, config.answer_value_cols,
                )
            else:
                results[ds_name] = {
                    "error": "정답 파일 없음",
                    "summary": {},
                    "details": [],
                }
                continue

            # Compare mapped rows against answers
            comparison_results = []
            for mapped_row in mapped_rows:
                dtcd = normalize_code(mapped_row.get("isrn_kind_dtcd", ""))
                itcd = normalize_code(mapped_row.get("isrn_kind_itcd", ""))
                sale_nm = normalize_text(str(mapped_row.get("isrn_kind_sale_nm", "")))
                prod_dtcd = normalize_code(mapped_row.get("prod_dtcd", ""))
                prod_itcd = normalize_code(mapped_row.get("prod_itcd", ""))

                result_base = {
                    "product": mapped_row.get("상품명", ""),
                    "isrn_kind_dtcd": dtcd,
                    "isrn_kind_itcd": itcd,
                    "prod_dtcd": prod_dtcd,
                    "prod_itcd": prod_itcd,
                }

                if not (dtcd and itcd and sale_nm):
                    result_base["matched"] = False
                    result_base["reason"] = "코드 없음"
                    comparison_results.append(result_base)
                    continue

                key = tuple(
                    normalize_code(v)
                    if c in ("ISRN_KIND_DTCD", "ISRN_KIND_ITCD")
                    else normalize_text(str(v))
                    for c, v in zip(
                        config.answer_key_cols, [dtcd, itcd, sale_nm]
                    )
                )
                answer_rows = answer_data.get(key, [])

                if prod_dtcd and prod_itcd and answer_rows:
                    filtered = [
                        r for r in answer_rows
                        if normalize_code(r.get("PROD_DTCD", "")) == prod_dtcd
                        and normalize_code(r.get("PROD_ITCD", "")) == prod_itcd
                    ]
                    if filtered:
                        answer_rows = filtered

                if not answer_rows:
                    result_base["matched"] = False
                    result_base["reason"] = "정답에 없음"
                    comparison_results.append(result_base)
                    continue

                comparison = config.compare_fn(mapped_row, answer_rows)
                result_base["matched"] = comparison.get("matched", False)
                result_base["reason"] = comparison.get("reason", "")
                comparison_results.append(result_base)

            total = len(comparison_results)
            matched = sum(1 for r in comparison_results if r["matched"])
            results[ds_name] = {
                "summary": {
                    "total": total,
                    "matched": matched,
                    "unmatched": total - matched,
                    "match_rate": f"{matched / total * 100:.1f}%" if total else "0%",
                },
                "details": comparison_results,
            }
        except Exception as e:
            results[ds_name] = {
                "error": str(e),
                "traceback": traceback.format_exc(),
                "summary": {},
                "details": [],
            }

    session.steps[4].status = "completed"
    session.steps[4].message = "정답 비교 완료"
    session.steps[4].result = results
    return results


# ── Step 5: CSV Export ──

EXPORT_DATASETS = ["payment_cycle", "annuity_age", "insurance_period", "join_age"]


def run_step5(session: PipelineSession) -> Dict[str, Any]:
    from write_product_data import (
        load_config,
        build_field_map,
        json_to_csv_rows,
        write_csv,
    )

    session.steps[5].status = "running"
    session.steps[5].message = "CSV 생성 중..."

    code_map_dir = session.work_dir / "코드매핑"
    csv_dir = session.work_dir / "결과"
    csv_dir.mkdir(parents=True, exist_ok=True)

    results = {}

    for ds_name in EXPORT_DATASETS:
        try:
            config = load_config(ds_name)
            _, prefix = DATASET_FILE_MAP[ds_name]

            # Find mapped JSON
            mapped_file = None
            candidates = list(code_map_dir.glob(f"{prefix}*.json"))
            if candidates:
                mapped_file = candidates[0]

            if not mapped_file or not mapped_file.exists():
                results[ds_name] = {"error": "매핑 파일 없음"}
                continue

            with mapped_file.open("r", encoding="utf-8") as f:
                mapped_data = json.load(f)

            data_field = config["data_field"]
            csv_columns = config.get("output_csv_columns", [])
            if not csv_columns:
                results[ds_name] = {"error": "output_csv_columns 미설정"}
                continue

            field_map = build_field_map(config)
            rows = json_to_csv_rows(mapped_data, data_field, csv_columns, field_map)

            korean_name = config["korean_name"]
            csv_filename = f"{korean_name}_{session.filename.replace('.pdf', '')}.csv"
            csv_path = csv_dir / csv_filename
            write_csv(rows, csv_columns, csv_path, encoding="utf-8-sig")

            results[ds_name] = {
                "csv_path": str(csv_path),
                "csv_filename": csv_filename,
                "row_count": len(rows),
                "product_count": len(mapped_data),
            }
        except Exception as e:
            results[ds_name] = {"error": str(e)}

    session.steps[5].status = "completed"
    session.steps[5].message = f"CSV 생성 완료 ({len([r for r in results.values() if 'csv_path' in r])}개)"
    session.steps[5].result = results
    return results


def get_session_status(session: PipelineSession) -> dict:
    status = {"session_id": session.session_id, "filename": session.filename, "steps": {}}
    for step_num, step in session.steps.items():
        step_info = {
            "status": step.status,
            "message": step.message,
        }
        if step.substeps:
            step_info["substeps"] = {
                name: {"status": sub.status, "message": sub.message}
                for name, sub in step.substeps.items()
            }
        status["steps"][step_num] = step_info
    return status
