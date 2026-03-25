# 보험상품 데이터 처리 Skills

## 1. 상품 분류 추출 (extract_product_classification.py)

PDF 사업방법서에서 상품명칭과 세부종목을 추출합니다.

### 전체 디렉토리 처리
```bash
python extract_product_classification.py
```
- 입력: `사업방법서/*.pdf`
- 출력: `상품분류/*.json`

### 단일 파일 처리
```bash
python extract_product_classification.py \
  --pdf "사업방법서/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.pdf" \
  --output "상품분류/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.json"
```

### 출력 형식
```json
[
  {
    "상품명칭": "한화생명 H건강플러스보험 무배당",
    "세부종목1": "보장형 계약",
    "세부종목2": "맞춤고지형",
    "세부종목3": "간편가입형(0년)",
    "상품명": "한화생명 H건강플러스보험 무배당 보장형 계약 맞춤고지형 간편가입형(0년)"
  }
]
```

---

## 2. 납입주기 추출 (extract_payment_cycle.py)

상품별 납입주기(월납, 3개월납, 6개월납, 연납, 일시납) 정보를 추출합니다.

### 전체 디렉토리 처리
```bash
python extract_payment_cycle.py
```
- 입력: `사업방법서/*.pdf` + `상품분류/*.json`
- 출력: `납입주기/*.json`
- 리포트: `납입주기/mapping_report_paym_cycl.json`

### 단일 파일 처리
```bash
python extract_payment_cycle.py \
  --pdf "사업방법서/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.pdf" \
  --output "납입주기/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.json"
```

### 출력 형식
```json
[
  {
    "상품명칭": "한화생명 H건강플러스보험 무배당",
    "세부종목1": "보장형 계약",
    "상품명": "한화생명 H건강플러스보험 무배당 보장형 계약",
    "납입주기": [
      { "납입주기명": "월납", "납입주기값": "1", "납입주기구분코드": "M" }
    ]
  }
]
```

---

## 3. 연금개시나이 추출 (extract_annuity_age.py)

연금상품의 연금개시나이 정보를 추출합니다.

### 전체 디렉토리 처리
```bash
python extract_annuity_age.py
```
- 입력: `사업방법서/*.pdf` + `상품분류/*.json`
- 출력: `보기개시나이/*.json`

### 단일 파일 처리
```bash
python extract_annuity_age.py \
  --pdf "사업방법서/한화생명 연금저축 하이드림연금보험_사업방법서_20260101~.pdf" \
  --json "상품분류/한화생명 연금저축 하이드림연금보험_사업방법서_20260101~.json" \
  --output "보기개시나이/한화생명 연금저축 하이드림연금보험_사업방법서_20260101~.json"
```

---

## 4. 가입가능보기납기 추출 (extract_insurance_period.py)

보험상품의 보험기간, 납입기간, 연금개시나이 정보를 추출합니다.

### 전체 디렉토리 처리
```bash
python extract_insurance_period.py
```
- 입력: `사업방법서/*.pdf` + `상품분류/*.json`
- 출력: `가입가능보기납기/*.json`

### 단일 파일 처리
```bash
python extract_insurance_period.py \
  --pdf "사업방법서/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.pdf" \
  --json "상품분류/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.json" \
  --output "가입가능보기납기/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.json"
```

### 기간 형식 규칙
- **종신**: "A999" (구분코드: "A", 값: "999")
- **n세/n세납**: "Xn" (구분코드: "X", 값: "n")
- **n년/n년납**: "Nn" (구분코드: "N", 값: "n")

---

## 5. 가입가능나이 추출 (extract_join_age.py)

성별/보험기간/납입기간별 가입 가능 나이 범위를 추출합니다.

### 전체 디렉토리 처리
```bash
python extract_join_age.py
```
- 입력: `사업방법서/*.pdf` + `상품분류/*.json` + `가입가능보기납기/*.json`
- 출력: `가입가능나이/*.json`
- 리포트: `가입가능나이/report.json`

### 단일 파일 처리
```bash
python extract_join_age.py \
  --pdf "사업방법서/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.pdf" \
  --json "상품분류/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.json" \
  --output "가입가능나이/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.json"
```

### 추출 전략 (3단계 Tier)
1. **테이블 기반** (6가지 파서): 매트릭스, 직접, 인라인, 종별, 최고나이, SPIN×PAYM 테이블
2. **수식 기반**: 연금 상품 `최대가입나이 = 연금개시나이 - 납입기간 - 공제값`
3. **텍스트 기반**: "N~M세" 패턴 매칭 (fallback)

### 출력 형식
```json
[
  {
    "상품명칭": "한화생명 H건강플러스보험 무배당",
    "상품명": "한화생명 H건강플러스보험 무배당 보장형 계약",
    "가입가능나이": [
      {
        "성별": "1",
        "최소가입나이": "15", "최대가입나이": "80",
        "최소납입기간": "5", "최대납입기간": "5", "납입기간구분코드": "N",
        "최소보험기간": "90", "최대보험기간": "90", "보험기간구분코드": "X",
        "최소제2보기개시나이": "", "최대제2보기개시나이": "", "제2보기개시나이구분코드": ""
      }
    ]
  }
]
```

---

## 6. 통합 코드 매핑 (map_product_code.py)

추출된 상품 데이터에 보종코드(DTCD)와 보종내코드(ITCD)를 매핑합니다.
`--data-set` 파라미터로 데이터셋별 분기 처리합니다.

### 상품분류 매핑
```bash
python map_product_code.py --data-set product_classification
```
- 입력: `상품분류/*.json` + `보종코드_상품코드_매핑.csv`
- 출력: `코드매핑/상품분류/상품분류_*.json`
- 리포트: `코드매핑/상품분류/mapping_report.json`

### 납입주기 매핑
```bash
python map_product_code.py --data-set payment_cycle
```
- 입력: `납입주기/*.json` + `보종코드_상품코드_매핑.csv`
- 출력: `코드매핑/납입주기/납입주기_*.json`
- 리포트: `코드매핑/납입주기/mapping_report.json`

### 보기개시나이 매핑
```bash
python map_product_code.py --data-set annuity_age
```

### 가입가능보기납기 매핑
```bash
python map_product_code.py --data-set insurance_period
```

### 가입가능나이 매핑
```bash
python map_product_code.py --data-set join_age
```

### 단일 파일 처리
```bash
python map_product_code.py --data-set payment_cycle \
  --json "납입주기/한화생명 H건강플러스보험 무배당_사업방법서_20260201~.json"
```

### 공통 옵션
- `--mapping-csv`: CSV 매핑 파일 경로 (기본: `보종코드_상품코드_매핑.csv`)
- `--input-dir`: 입력 디렉토리 오버라이드
- `--output-dir`: 출력 디렉토리 오버라이드
- `--json`: 단일 JSON 파일 경로
- `--output`: 단일 파일 출력 경로

### 출력 형식 (product_classification)
```json
{
    "isrn_kind_dtcd": "2243",
    "isrn_kind_itcd": "A01",
    "isrn_kind_sale_nm": "한화생명 간편가입 경영인H정기보험 무배당 보장형 계약 1종(5%체증형) 해약환급금 일부지급형",
    "prod_dtcd": "2243",
    "prod_itcd": "1",
    "prod_sale_nm": "한화생명 간편가입 경영인H정기보험 무배당 보장형 계약 1종(5%체증형) 해약환급금 일부지급형",
    "상품명칭": "한화생명 간편가입 경영인H정기보험 무배당",
    "세부종목1": "보장형 계약",
    "세부종목2": "1종(5%체증형)",
    "세부종목3": "해약환급금 일부지급형",
    "상품명": "한화생명 간편가입 경영인H정기보험 무배당 보장형 계약 1종(5%체증형) 해약환급금 일부지급형"
}
```

### 출력 형식 (payment_cycle)
위 형식 + `납입주기` 배열 포함

### 리포트 형식 (CSV row 기준)
```json
{
    "data_set": "product_classification",
    "summary": {
        "total_input_rows": 257,
        "matched": 155,
        "unmatched": 52,
        "ambiguous": 50,
        "csv_total": 261,
        "csv_matched": 248,
        "csv_unmatched": 13
    },
    "csv_rows": [
        { "isrn_kind_dtcd": "...", "isrn_kind_itcd": "...", "isrn_kind_sale_nm": "...", "status": "matched" }
    ]
}
```

---

## 7. 통합 정답 비교 (compare_product_data.py)

매핑된 데이터를 정답 CSV/Excel과 비교하여 정확도를 검증합니다.
`dataset_configs.json`에서 컬럼 매핑과 비교 규칙을 로드하여 범용 비교를 수행합니다.

### 납입주기 비교
```bash
python compare_product_data.py --data-set payment_cycle
```
- 입력:
  - `코드매핑/납입주기/납입주기_*.json` (매핑 결과)
  - `정답/판매중_납입주기정보.xlsx` (정답)
  - `보종코드_상품코드_매핑.csv` (CSV 261 row 기준)
- 출력 (`정답비교/납입주기/`):
  - `comparison_report.json` (추출 데이터 기준)
  - `comparison_detailed.json` (전체 비교 상세)
  - `answer_based_report.json` (CSV row 기준)

### 보기개시나이 비교
```bash
python compare_product_data.py --data-set annuity_age
```
- 입력:
  - `코드매핑/보기개시나이/보기개시나이_*.json` (매핑 결과)
  - `정답/판매중_보기개시나이정보.xlsx` (정답)
  - `보종코드_상품코드_매핑.csv` (CSV 261 row 기준)
- 출력 (`정답비교/보기개시나이/`):
  - `comparison_report.json` (추출 데이터 기준)
  - `comparison_detailed.json` (전체 비교 상세)
  - `answer_based_report.json` (CSV row 기준)
- 비교 기준: `(SPIN_STRT_AG_INQY_CODE, TPIN_STRT_AG_INQY_CODE, 성별)` 세트 일치

### 가입가능보기납기 비교
```bash
python compare_product_data.py --data-set insurance_period
```
- 입력:
  - `코드매핑/가입가능보기납기/가입가능보기납기_*.json` (매핑 결과)
  - `정답/판매중_보기납기정보.xlsx` (정답)
  - `보종코드_상품코드_매핑.csv` (CSV 261 row 기준)
- 출력 (`정답비교/가입가능보기납기/`):
  - `comparison_report.json` (추출 데이터 기준)
  - `comparison_detailed.json` (전체 비교 상세)
  - `answer_based_report.json` (CSV row 기준)
- 비교 기준: `(ISRN_TERM_INQY_CODE, PAYM_TERM_INQY_CODE)` 세트 일치

### 가입가능나이 비교
```bash
python compare_product_data.py --data-set join_age
```
- 입력:
  - `코드매핑/가입가능나이/가입가능나이_*.json` (매핑 결과)
  - `정답/판매중_가입가능나이_0319.csv` (정답)
  - `보종코드_상품코드_매핑.csv` (CSV 261 row 기준)
- 출력 (`정답비교/가입가능나이/`):
  - `comparison_report.json` (추출 데이터 기준)
  - `comparison_detailed.json` (전체 비교 상세)
  - `answer_based_report.json` (CSV row 기준)
- 비교 기준: `(성별, 최소가입나이, 최대가입나이, INS, PAYM, SPIN)` 튜플 세트 일치

### 공통 옵션
- `--answer-excel`: 정답 Excel 경로 오버라이드
- `--mapped-dir`: 매핑 데이터 디렉토리 오버라이드
- `--report-dir`: 리포트 출력 디렉토리 오버라이드
- `--verbose`: 불일치 상세 출력

### CSV row 기준 리포트 형식
CSV의 261 row (`isrn_kind_dtcd, isrn_kind_itcd, prod_dtcd, prod_itcd`)를 기준으로 matched / unmatched / mismatched / no_answer 분류:
```json
{
    "summary": {
        "total_csv_rows": 261,
        "matched": 210,
        "unmatched": 30,
        "mismatched": 10,
        "no_answer": 11,
        "match_rate_with_answer": "84.0%",
        "match_rate_total": "80.5%"
    },
    "rows": [
        {
            "isrn_kind_dtcd": "...",
            "isrn_kind_itcd": "...",
            "prod_dtcd": "...",
            "prod_itcd": "...",
            "status": "matched",
            "answer_cycles": [["1", "M"]],
            "mapped_cycles": [["1", "M"]]
        }
    ]
}
```

---

## 전체 파이프라인 실행 예시

```bash
# 1. 상품 분류 추출
python extract_product_classification.py

# 2. 데이터 추출 (병렬 실행 가능)
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

---

## 매칭 로직 (map_product_code.py)

- **토큰 기반 집합 매칭**: 상품명칭 + 세부종목을 공백/괄호 기준으로 토큰 분할 후 CSV의 토큰 집합이 상위집합인지 확인
- **특수 필터링**: 납입면제형, 간편가입, 일부지급형 레벨 구분
- **오버매칭 최소화**: 불필요한 추가 토큰이 최소인 후보만 선택
- **케어백간병플러스보험 예외**: '보장형 계약' 토큰 제거
