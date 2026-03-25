"""FastAPI web application for demo pipeline."""
import asyncio
import json
import time
from pathlib import Path
from typing import Dict

from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles

from web.pipeline import (
    PipelineSession,
    create_session,
    get_session_status,
    run_step1,
    run_step2,
    run_step2_annuity_age,
    run_step2_insurance_period,
    run_step2_join_age,
    run_step2_payment_cycle,
    run_step3,
    run_step4,
)

app = FastAPI(title="사업방법서 데이터 추출 파이프라인 Demo")

STATIC_DIR = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

# In-memory session store
sessions: Dict[str, PipelineSession] = {}


@app.get("/", response_class=HTMLResponse)
async def index():
    html_path = STATIC_DIR / "index.html"
    return html_path.read_text(encoding="utf-8")


@app.post("/api/upload")
async def upload_pdf(file: UploadFile = File(...)):
    if not file.filename or not file.filename.lower().endswith(".pdf"):
        raise HTTPException(status_code=400, detail="PDF 파일만 업로드 가능합니다.")

    content = await file.read()
    if len(content) == 0:
        raise HTTPException(status_code=400, detail="빈 파일입니다.")

    session = create_session(content, file.filename)
    sessions[session.session_id] = session

    return {"session_id": session.session_id, "filename": session.filename}


@app.get("/api/pipeline/{session_id}/status")
async def pipeline_status(session_id: str):
    session = sessions.get(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="세션을 찾을 수 없습니다.")
    return get_session_status(session)


@app.get("/api/pipeline/{session_id}/run")
async def run_step(session_id: str, step: int = Query(..., ge=1, le=4)):
    session = sessions.get(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="세션을 찾을 수 없습니다.")

    # Check prerequisites
    if step > 1 and session.steps[step - 1].status != "completed":
        raise HTTPException(
            status_code=400,
            detail=f"Step {step - 1}이 완료되지 않았습니다.",
        )

    async def event_stream():
        def send_event(event_type: str, data: dict) -> str:
            return f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

        try:
            if step == 1:
                yield send_event("status", {
                    "step": 1, "substep": None,
                    "status": "running", "message": "상품분류 추출 중...",
                })
                result = await asyncio.to_thread(run_step1, session)
                yield send_event("result", {
                    "step": 1, "substep": None,
                    "status": "completed",
                    "message": f"상품분류 추출 완료 ({len(result)}건)",
                    "data": result,
                    "count": len(result),
                })

            elif step == 2:
                yield send_event("status", {
                    "step": 2, "substep": None,
                    "status": "running", "message": "세트데이터 추출 시작...",
                })

                substep_runners = [
                    ("payment_cycle", "납입주기", run_step2_payment_cycle),
                    ("annuity_age", "보기개시나이", run_step2_annuity_age),
                    ("insurance_period", "가입가능보기납기", run_step2_insurance_period),
                    ("join_age", "가입가능나이", run_step2_join_age),
                ]

                session.steps[2].status = "running"
                all_results = {}

                for sub_name, sub_label, runner in substep_runners:
                    yield send_event("status", {
                        "step": 2, "substep": sub_name,
                        "status": "running", "message": f"{sub_label} 추출 중...",
                    })
                    try:
                        result = await asyncio.to_thread(runner, session)
                        all_results[sub_name] = result
                        yield send_event("result", {
                            "step": 2, "substep": sub_name,
                            "status": "completed",
                            "message": f"{sub_label} 추출 완료 ({len(result)}건)",
                            "data": result,
                            "count": len(result),
                        })
                    except Exception as e:
                        all_results[sub_name] = []
                        yield send_event("error", {
                            "step": 2, "substep": sub_name,
                            "status": "error",
                            "message": f"{sub_label} 추출 오류: {e}",
                        })

                has_error = any(
                    s.status == "error"
                    for s in session.steps[2].substeps.values()
                )
                session.steps[2].status = "error" if has_error else "completed"
                session.steps[2].message = "일부 실패" if has_error else "세트데이터 추출 완료"
                session.steps[2].result = all_results

                yield send_event("result", {
                    "step": 2, "substep": None,
                    "status": session.steps[2].status,
                    "message": session.steps[2].message,
                })

            elif step == 3:
                yield send_event("status", {
                    "step": 3, "substep": None,
                    "status": "running", "message": "코드매핑 중...",
                })
                result = await asyncio.to_thread(run_step3, session)

                summary = {}
                for ds, data in result.items():
                    stats = data.get("stats", {})
                    summary[ds] = {
                        "total": stats.get("total", 0),
                        "matched": stats.get("matched", 0),
                    }

                yield send_event("result", {
                    "step": 3, "substep": None,
                    "status": "completed",
                    "message": "코드매핑 완료",
                    "data": {
                        ds: {
                            "stats": data.get("stats", {}),
                            "mapped_rows": data.get("mapped_rows", []),
                        }
                        for ds, data in result.items()
                    },
                    "summary": summary,
                })

            elif step == 4:
                yield send_event("status", {
                    "step": 4, "substep": None,
                    "status": "running", "message": "정답 비교 중...",
                })
                result = await asyncio.to_thread(run_step4, session)

                yield send_event("result", {
                    "step": 4, "substep": None,
                    "status": "completed",
                    "message": "정답 비교 완료",
                    "data": result,
                })

            yield send_event("done", {"step": step, "message": "완료"})

        except Exception as e:
            yield send_event("error", {
                "step": step, "substep": None,
                "status": "error",
                "message": f"오류 발생: {e}",
            })

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.get("/api/pipeline/{session_id}/result/{step}")
async def get_result(session_id: str, step: int):
    session = sessions.get(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="세션을 찾을 수 없습니다.")

    step_status = session.steps.get(step)
    if not step_status or step_status.status != "completed":
        raise HTTPException(status_code=400, detail=f"Step {step} 결과가 없습니다.")

    return {"step": step, "result": step_status.result}


@app.on_event("startup")
async def startup():
    asyncio.create_task(cleanup_sessions())


async def cleanup_sessions():
    """Remove sessions older than 1 hour."""
    import shutil

    while True:
        await asyncio.sleep(3600)
        to_remove = []
        for sid, session in sessions.items():
            work_dir = session.work_dir
            if work_dir.exists():
                age = time.time() - work_dir.stat().st_mtime
                if age > 3600:
                    shutil.rmtree(work_dir, ignore_errors=True)
                    to_remove.append(sid)
        for sid in to_remove:
            sessions.pop(sid, None)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("web.app:app", host="0.0.0.0", port=8000, reload=True)
