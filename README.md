# 사업방법서 세트데이터 자동 추출 파이프라인

## 개요

한화생명 신상품 업무 자동화를 위해 공시된 사업방법서 PDF에서 4종 세트데이터를 추출하고 보종코드 CSV에 코드로 매핑합니다.

- **입력**: 51개 사업방법서 PDF + 상품코드 매핑 CSV (261행)
- **출력**: 코드매핑 JSON (데이터셋별) + 정답 비교 리포트

---

## 세트데이터 종류

| 세트데이터 | 스크립트 | 정확도 (answer 있는 건) |
|---|---|---|
| 가입가능보기납기 (ISRN_TERM / PAYM_TERM) | `extract_insurance_period.py` | **100%** (261/261) |
| 납입주기 (PAYM_CYCL) | `extract_payment_cycle.py` | **100%** (250/250) |
| 보기개시나이 (SPIN_STRT_AG) | `extract_annuity_age.py` | **100%** (120/120) |
| 가입가능나이 (JOIN_AG) | `extract_join_age.py` | **99.6%** (260/261) |

---

## 구현 흐름

```
사업방법서 PDF
    ↓
[1단계] 상품분류 추출
    extract_product_classification.py
    → 상품명, 세부종목1~4, 1종/2종 구분
    → 출력: 상품분류/*.json

    ↓
[2단계] 세트데이터 추출
    extract_payment_cycle.py      → 납입주기/*.json
    extract_annuity_age.py        → 보기개시나이/*.json
    extract_insurance_period.py   → 가입가능보기납기/*.json
    extract_join_age.py           → 가입가능나이/*.json

    ↓
[3단계] 코드 매핑
    map_product_code.py --data-set [dataset]
    → 추출 JSON × 보종코드_상품코드_매핑.csv 매칭
    → 출력: 코드매핑/[dataset]/*.json

    ↓
[4단계] 정답 비교
    compare_product_data.py --data-set [dataset]
    → 코드매핑 JSON × 정답/*.csv 비교
    → 출력: 정답비교/[dataset]/answer_based_report.json

    ↓
[5단계] CSV 변환
    write_product_data.py --data-set [dataset] --json [매핑JSON]
    → 코드매핑 JSON → 정답 CSV 동일 포맷 변환
    → 출력: 결과/[dataset]/*.csv
```

---

## 디렉토리 구조

```
HLI_POC/
├── .gitignore
├── README.md
│
├── config/
│   ├── product_overrides.json         # 상품별 추출 오버라이드 설정
│   ├── dataset_configs.json           # 데이터셋별 비교/매핑 컬럼 매핑 설정
│   └── 보종코드_상품코드_매핑.csv      # 261행 상품코드 마스터
│
├── docs/
│   ├── EXTRACTION_LOGIC.md            # 추출 로직 상세
│   ├── IMPLEMENTATION_JOURNEY.md      # 구현 과정 기록
│   └── SKILLS.md                      # 스크립트별 사용법
│
├── extract_product_classification.py  # 상품분류 추출
├── extract_payment_cycle.py           # 납입주기 추출
├── extract_annuity_age.py             # 보기개시나이 추출
├── extract_insurance_period.py        # 가입가능보기납기 추출
├── extract_join_age.py                # 가입가능나이 추출
├── map_product_code.py                # 코드 매핑 (CSV ↔ 추출 JSON)
├── compare_product_data.py            # 정답 비교 리포트 생성 (범용)
├── write_product_data.py              # 코드매핑 JSON → CSV 변환
│
├── 사업방법서/                        # PDF 입력 (51개)
├── 상품분류/                          # 상품분류 추출 결과 JSON
├── 납입주기/                          # 납입주기 추출 결과 JSON
├── 보기개시나이/                      # 보기개시나이 추출 결과 JSON
├── 가입가능보기납기/                  # 가입가능보기납기 추출 결과
├── 가입가능나이/                      # 가입가능나이 추출 결과
│
├── 코드매핑/                          # 코드 매핑 결과
│   ├── 상품분류/
│   ├── 납입주기/
│   ├── 보기개시나이/
│   ├── 가입가능보기납기/
│   └── 가입가능나이/
│
├── 정답/                              # 정답 CSV 파일
│   ├── 판매중_가입가능보기납기_0319.csv
│   ├── 판매중_가입가능납입주기_0319.csv
│   ├── 판매중_보기개시나이_0319.csv
│   └── 판매중_가입가능나이_0319.csv
│
├── 정답비교/                          # 비교 결과 리포트
│   ├── 납입주기/
│   ├── 보기개시나이/
│   ├── 가입가능보기납기/
│   └── 가입가능나이/
│
├── 결과/                              # CSV 변환 결과
│   ├── 가입가능나이/
│   ├── 납입주기/
│   ├── 가입가능보기납기/
│   └── 보기개시나이/
│
└── web/                               # 데모 웹 서비스 (FastAPI + SSE)
    ├── app.py
    ├── pipeline.py
    └── static/
```

---

## 실행 방법

### 전체 파이프라인

```bash
cd HLI_POC

# 1. 상품분류 추출
python extract_product_classification.py

# 2. 세트데이터 추출
python extract_payment_cycle.py
python extract_annuity_age.py
python extract_insurance_period.py
python extract_join_age.py

# 3. 코드 매핑
for ds in product_classification payment_cycle annuity_age insurance_period join_age; do
  python map_product_code.py --data-set $ds
done

# 4. 정답 비교
for ds in payment_cycle annuity_age insurance_period join_age; do
  python compare_product_data.py --data-set $ds
done
```

### 단일 데이터셋 재실행

```bash
python extract_payment_cycle.py
python map_product_code.py --data-set payment_cycle
python compare_product_data.py --data-set payment_cycle
```

### 단일 상품 파이프라인

```bash
PDF="사업방법서/한화생명 바로연금보험 무배당_사업방법서_20260101~.pdf"
JSON="상품분류/한화생명 바로연금보험 무배당_사업방법서_20260101~.json"

# 1. 상품분류 추출
python extract_product_classification.py --pdf "$PDF" --output "$JSON"

# 2. 세트데이터 추출
python extract_join_age.py --pdf "$PDF" --json "$JSON" --output /tmp/ages.json

# 3. 코드 매핑 (sibling fallback 포함)
python map_product_code.py --data-set join_age --json /tmp/ages.json --output /tmp/mapped.json

# 4. 정답 비교
python compare_product_data.py --data-set join_age --json /tmp/mapped.json --output /tmp/compare.json

# 5. CSV 변환
python write_product_data.py --data-set join_age --json /tmp/mapped.json --output /tmp/result.csv
```

---

## 핵심 로직 설명

### 코드 매핑 (map_product_code.py)

- **매칭 기준**: `prod_sale_nm` (상품판매명) 토큰 집합 기반 subset 매칭
- **sibling fallback**: 같은 `(dtcd, itcd)` 내 matched 형제가 있으면 해당 추출 데이터 공유
- **·(중간점) 분리**: `계약전환·단체개인전환·개인중지재개용` 등을 개별 토큰으로 분리

### 납입주기 추출 (extract_payment_cycle.py)

- PDF 텍스트와 표에서 납입주기 관련 절(section) 탐지
- `CycleRule` 객체로 context(세부종목) ↔ 납입주기 규칙 저장
- `pick_cycles(record, rules, fallback)`: 가장 많은 context가 일치하는 규칙 우선 적용
- `extract_tables()`로 표 기반 파싱: 셀 내 개행 제거, 병합셀 fill-down 처리

### 보기개시나이 추출 (extract_annuity_age.py)

- 연금개시나이 절 헤더 감지 → 이후 라인에서 나이 범위 추출
- `extract_tables()`로 구조화된 표 파싱: `구분 | 종신연금형 | 확정기간연금형` 형태
- `pick_annuity_values_for_row()`: CSV 행의 세부종목 토큰과 category key 매칭으로 올바른 나이 범위 선택

### extraction_not_supported 상품

아래 상품은 사업방법서에서 해당 세트데이터를 추출할 수 없습니다:

| DTCD | 상품명 | 데이터셋 | 사유 |
|---|---|---|---|
| 2242 | 경영인H정기보험 무배당 | 보기개시나이 | N10 코드 - 사업방법서 미기재 |
| 2243 | 간편가입 경영인H정기보험 무배당 | 보기개시나이 | N10 코드 - 사업방법서 미기재 |

---

## 현재 상태

4종 세트데이터 정답 대비 정확도:
- 가입가능보기납기, 납입주기, 보기개시나이: **100%**
- 가입가능나이: **99.6%** (260/261) — 진심가득H 2종 1건 mismatch (정답 데이터 오류)

### 설정 파일 기반 확장

상품별 하드코딩과 데이터셋별 비교 로직을 외부 JSON으로 분리하여 새 데이터셋 추가 시 코드 수정을 최소화:

| 설정 파일 | 역할 |
|---|---|
| `config/product_overrides.json` | 상품별 추출 오버라이드 (고정값, sibling 복사, 나이 보정 등) |
| `config/dataset_configs.json` | 데이터셋별 디렉토리, 컬럼 매핑, 비교 튜플 필드, 특수 규칙 |

**새 데이터셋 추가 시:**
1. `extract_*.py` 신규 작성 (PDF 파싱)
2. `config/dataset_configs.json`에 항목 추가 (~20줄)
3. `map_product_code.py`에 build_row + config 추가 (~5줄)

자세한 추출 로직은 [EXTRACTION_LOGIC.md](docs/EXTRACTION_LOGIC.md), 구현 과정은 [IMPLEMENTATION_JOURNEY.md](docs/IMPLEMENTATION_JOURNEY.md) 참조.
