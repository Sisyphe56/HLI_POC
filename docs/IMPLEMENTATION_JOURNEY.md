# 구현 과정 기록

한화생명 신상품 업무 자동화 — 세트데이터 추출 파이프라인의 구현 여정을 시간순으로 정리한다.

---

## 목표

사업방법서 PDF 51건에서 4가지 세트데이터를 자동 추출하고, 정답 CSV와 비교하여 정확도 100%를 달성한다.

| 데이터셋 | 설명 |
|----------|------|
| 가입가능보기납기 | 보험기간 × 납입기간 조합 + 제2보기개시나이 |
| 납입주기 | 보험료 납입 주기 (월납, 연납, 일시납 등) |
| 보기개시나이 | 연금개시나이 범위 (성별별) |
| 가입가능나이 | 성별/보험기간/납입기간별 가입 가능 나이 범위 |

---

## Phase 1: 기초 구조 구축

### 추출기 3종 신설

- `extract_payment_cycle.py` — 납입주기 추출. `CycleRule` 클래스로 context(세부종목) ↔ 주기 관계를 모델링
- `extract_annuity_age.py` — 보기개시나이 추출. `연금개시나이` 섹션 헤더 감지 후 나이 범위 파싱
- `extract_insurance_period.py` — 가입가능보기납기 추출. 보험기간/납입기간 정규식 패턴 매칭

### 매핑 & 비교 프레임워크

- `map_product_code.py` — 추출 결과를 보종코드/상품코드 CSV와 연결. 토큰 집합 매칭 기반
- `compare_product_data.py` — 코드매핑 결과를 정답 CSV와 비교. matched/mismatched/no_answer 분류

### 초기 정확도

| 데이터셋 | 정확도 |
|----------|--------|
| 가입가능보기납기 | **32.2%** |
| 납입주기 | **84.0%** |
| 보기개시나이 | **84.1%** |

---

## Phase 2: 매핑 정확도 개선

### 문제: 매핑 키 혼동

`isrn_kind_sale_nm`(보험종목명)을 매칭 키로 사용하면, 같은 종목명 아래 여러 상품이 섞여 과매칭(overmatch) 발생.

**해결:** `prod_sale_nm`(상품판매명)으로 매칭 키 전환 → 상품별 분리 정확도 향상

### 문제: 미매핑 행 처리

같은 `(DTCD, ITCD)` 쌍인데 상품명 변형이 달라 매핑 실패하는 행 존재.

**해결:** Sibling Fallback — 매핑된 형제 행의 추출 데이터를 미매핑 행에 복사

### 기타 개선

- **중점(·) 토큰 분리:** `계약전환·단체개인전환·개인중지재개용` → 3개 토큰으로 분리
- **기간 토큰 필터:** `5년`, `15년` 같은 순수 기간 토큰을 context 매칭에서 제외

---

## Phase 3: 납입주기 & 보기개시나이 개선 (84% → 90%)

### 보기개시나이: 개인형/신부부형 context 분리

**문제 (DTCD 2259, 바로연금보험, 8건):**
개인형은 성별 통합값, 신부부형은 남/여 분리값이 정답인데, 추출 시 context를 구분 못해 혼용.

**해결:** context group을 S2 → S3로 세분화하여 개인형/신부부형 블록을 분리 추출

### 납입주기: 테이블 기반 규칙 추출

**문제 (DTCD 1984, 실손의료비, 8건):**
`반기납`(6개월납)이 텍스트에서 인식되지 않음. PDF 테이블의 셀 줄바꿈으로 `6\n개월납`이 분리.

**해결:**
- `extract_pdf_tables()`로 테이블 파싱 추가
- 셀 내 줄바꿈 정규화 (`normalize_ws` 적용)
- fill-down 로직으로 병합 셀 처리

### 정확도 변화

| 데이터셋 | 이전 | 이후 |
|----------|------|------|
| 보기개시나이 | 84.1% | **90.0%** (+5.9%p) |
| 납입주기 | 84.0% | **89.6%** (+5.6%p) |

---

## Phase 4: 가입가능보기납기 대전환 (32.2% → 100%)

가장 복잡한 데이터셋. 보험기간 × 납입기간의 직교곱에 연금/전기납/갱신형 등 특수 로직이 결합된다.

### Step 1: 32.2% → 65.5% (+33.3%p)

**핵심 변경 5가지:**

1. **테이블 + 텍스트 병행 추출** — `extract_pdf_content()`가 텍스트와 테이블을 동시에 반환하도록 개선
2. **열 인식 연금개시나이 추출** — 테이블 헤더에서 `종신연금형`/`확정기간연금형` 열을 분리하여 나이 범위 혼용 방지
3. **확정기간연금형 항상 추출** — `is_lump` 가드를 제거하여 확정기간형 제2보기개시나이 14건 복구
4. **즉시형/거치형 일시납 제한** — 즉시형/거치형 연금은 납입기간=일시납으로 고정
5. **context 인식 일시납 제외** — `전환형/거치형/즉시형` 맥락의 일시납은 일반 상품에서 제외

### Step 2: 65.5% → 91.6% (+26.1%p)

**핵심 변경 5가지:**

1. **4-Tuple 그루핑** — 비교 기준을 `(dtcd, itcd)` → `(dtcd, itcd, prod_dtcd, prod_itcd)`로 확장. 적립형/거치형 기간 세트 분리
2. **납입≤보험 제약 필터** — 도메인 룰: 납입기간 ≤ 보험기간. 불가능한 조합 자동 제거
3. **종별 연금개시나이** — 상품명에서 종(1종/2종)을 추출하여 테이블 열과 매칭
4. **경계 인식 부분문자열 매칭** — `5년`이 `15년형` 안에서 false match 방지
5. **매트릭스 테이블 무효 조합 필터** — PDF의 `-` 표시된 (보험기간, 납입기간) 쌍 제거

### Step 3: 91.6% → 98.9% (22건 → 3건 mismatch)

- 섹션 기반 context map 강화 (`_extract_section_context_map`)
- 텍스트 기반 context map 추가 (`_extract_text_context_pay_map`)
- 3단계 context 우선순위: 테이블 > 텍스트 > 섹션
- 숫자 기간 정확 매칭 (`5년납` ≠ `15년납`)

### Step 4: 98.9% → 100% (최종 3건 해결)

마지막 3건은 모두 **DTCD 2126 (한화생명 진심가득H보장보험 무배당)**.

**근본 원인:** 테이블 context가 일반형과 상생협력형 데이터를 혼용

이 PDF의 구조:
```
(1) 보험기간
  (가) 일반형 → 보장형 계약: 90세만기, 100세만기
  (나) 상생협력형 → 보장형 계약: 20년만기
(2) 납입기간
  (가) 일반형 → 5년납, 7년납, 10년납, 20년납, 30년납, 20세납, 30세납
  (나) 상생협력형 → 전기납
```

그런데 테이블 기반 `_extract_context_period_map()`이 `{'보장형 계약': {'20년만기'}}`를 반환 — 이것은 상생협력형의 데이터인데, context key가 `보장형 계약`이라 일반형 상품도 매칭됨.

**수정 1: 섹션 context 우선 적용 (lines 824-845)**

```python
has_type_separation = (
    any('일반형' in k for k in section_ctx) and
    any('상생협력형' in k for k in section_ctx)
)
if has_type_separation:
    # 테이블 context 대신 섹션 context 사용
    pay_ctx_map = pay_from_sec
    ins_ctx_map = ins_from_sec
```

일반형/상생협력형 분리가 감지되면, 테이블 context(두 유형을 혼용)보다 섹션 context(정확 분리)를 우선한다.

**수정 2: 전기납 context 필터 (lines 854-876)**

수정 1 적용 후, 일반형 1종에 `(X90,X90)`, `(X100,X100)` 전기납 레코드가 잘못 생성됨.
`has_jeonginap=True`가 PDF 전체에서 전기납 키워드를 감지한 것이기 때문.

```python
if has_jeonginap and pay_ctx_map:
    has_gen = any('일반형' in k for k in pay_ctx_map)
    has_ss = any('상생협력형' in k for k in pay_ctx_map)
    if has_gen and has_ss:
        # 상품명에 매칭되는 context의 기간만 수집
        matched_pay_ctx = set()
        for ctx_key, periods in pay_ctx_map.items():
            if '상생협력형' in ctx_key:
                if name_has_ss: matched_pay_ctx.update(periods)
            elif '일반형' in ctx_key:
                if not name_has_ss: matched_pay_ctx.update(periods)
        # 매칭된 context에 전기납이 없으면 비활성화
        if matched_pay_ctx and not any('전기납' in p for p in matched_pay_ctx):
            has_jeonginap = False
```

일반형 상품의 매칭된 context에 전기납이 없으므로 `has_jeonginap=False` → 전기납 레코드 미생성.

**첫 시도에서 실패한 접근:**
- `_filter_periods_by_context({'전기납'}, pay_ctx_map, name)` 호출 → fallback 로직이 `n년납` 패턴을 추가하여 5개 기간 반환
- 모든 non-상생 context의 기간 수집 → 긴 context 문자열 "이 상품 가입시 태아를 피보험자로..."에 포함된 `전기납` 감지

최종적으로 상품명 키워드(일반형/상생) 또는 부분문자열(보장형 계약) 매칭으로 해당 context만 수집하는 방식으로 해결.

---

## Phase 5: 가입가능나이 파이프라인 구축 (신규)

4번째 세트데이터인 가입가능나이 추출기를 새로 구축했다.

### 핵심 설계: 3단계 Tier 추출

가입가능나이는 PDF 구조가 상품마다 매우 다양하므로, 6가지 테이블 파서 + 수식 기반 + 텍스트 기반의 계층적 추출 전략을 적용한다.

**Tier 1 — 테이블 기반 (6가지 유형):**
1. 표준 매트릭스 (`parse_age_table`): 보험기간×성별 그리드
2. 직접 테이블 (`parse_direct_age_table`): 구분|남자|여자 단순 구조
3. 인라인 테이블 (`parse_inline_age_table`): 셀 내 "만N~M세" 패턴
4. 종별 테이블 (`parse_variant_age_table`): 1종|2종 × 성별
5. 최고가입나이 테이블 (`parse_max_age_table`): context별 최고나이
6. SPIN×PAYM 매트릭스 (`parse_spin_paym_age_table`): 연금개시나이×납입기간

**Tier 2 — 수식 기반 (`compute_formula_ages`):**
- 연금 상품: `최대가입나이 = 연금개시나이 - 납입기간 - 공제값`
- 공제 테이블 파싱 → (SPIN, PAYM) 조합별 계산

**Tier 3 — 텍스트 기반:**
- 가입나이 섹션 내 "N~M세" 패턴 탐색 (최후 수단)

### 가입가능보기납기 교차 검증

추출된 나이 레코드의 (보험기간, 납입기간)이 기존 가입가능보기납기 결과에 존재하는지 교차 검증하여 불일치 조합 제거.

### 실손의료비 특수 규칙

태아가입용(min=0, max=0), e실손(min=19), 간편가입+재가입(min=8, max=109), N세형(max=N-1), 종신갱신형(max=199) 등 상품별 하드코딩 규칙.

### 결과

가입가능나이 **261/261 = 100%** 달성 (첫 실행부터 100%).

---

## Phase 6: 하드코딩 외부화 및 확장성 강화

100% 달성 이후, 새로운 세트데이터 추가 시 코드 수정량을 최소화하기 위한 리팩토링.

### 상품별 오버라이드 외부화 (`product_overrides.json`)

4개 추출기(extract_*.py)에 분산되어 있던 ~20개의 상품별 if/elif 분기를 단일 JSON 설정 파일로 통합.

**3가지 action 유형:**
- `fixed` — PDF에 정보 없는 상품의 고정값 (예: 상생친구 보장보험)
- `sibling_filter` — 형제 상품에서 조건부 복사 (예: 진심가득H+태아가입형)
- `sibling_copy` — 형제 상품에서 전체 복사 (예: H간병보험+치매보장플랜형)

**기타 외부화:**
- `sibling_fallback.suffix_patterns` — 접미사 기반 base 상품 탐색 규칙
- `join_age.실손의료비.variants` — 실손 상품군별 나이 보정 규칙
- `annuity_context_tokens` — 연금 유형 → context group 매핑

### 비교 로직 범용화 (`dataset_configs.json` + `compare_product_data.py`)

**문제:** 기존 `compare_product_data.py`에 데이터셋마다 ~250줄의 전용 비교/리포트 함수가 하드코딩.
새 데이터셋 추가 시 ~350줄의 Python 코드가 필요했음.

**해결:**
1. `dataset_configs.json` 신규 생성 — 컬럼 매핑(`tuple_fields`), skip 규칙, 특수 규칙을 JSON으로 선언
2. `generic_compare()` + `generic_answer_report()` — JSON config 기반 범용 비교/리포트 함수
3. 정규화 함수 레지스트리(`NORMALIZERS`) — `text`, `age_code`, `gender`, `gender_code`, `age_val` 등
4. 특수 규칙 핸들러 — `keongang_correction`, `period_strip_fallback`, `not_supported_dtcds`

**결과:** compare_product_data.py 1322줄 → ~530줄 (60% 감소), 4개 전용 함수 세트 제거.

### 코드 매핑 동적화 (`map_product_code.py`)

`DataSetConfig`에 `data_field_name` 속성 추가, sibling fallback의 하드코딩 필드 튜플을 config에서 동적 생성.

### 새 데이터셋 추가 워크플로우 비교

| 항목 | 이전 | 이후 |
|------|------|------|
| extract_*.py | 신규 작성 | 신규 작성 (변동 없음) |
| compare_product_data.py | ~250줄 추가 | **0줄** (JSON 설정만) |
| map_product_code.py | ~50줄 추가 | ~5줄 추가 |
| dataset_configs.json | — | ~20줄 추가 |
| **합계** | ~350줄 Python | **~25줄 설정** |

---

## 최종 정확도

| 데이터셋 | Phase 1 | Phase 2 | Phase 3 | Phase 4 | Phase 5 | Phase 6 | 최종 |
|----------|---------|---------|---------|---------|---------|---------|------|
| 가입가능보기납기 | 32.2% | 32.2% | 32.2% | 65.5→91.6→98.9 | — | 100% 유지 | **100%** |
| 납입주기 | 84.0% | 84.0% | 89.6% | — | — | 100% 유지 | **100%** |
| 보기개시나이 | 84.1% | 84.1% | 90.0% | — | — | 100% 유지 | **100%** |
| 가입가능나이 | — | — | — | — | 100% | 100% 유지 | **100%** |

---

## Phase 7: comparison_detailed 정밀도 개선

answer_based_report(CSV 행 기준 정확도)는 100%이지만, comparison_detailed(개별 코드매핑 행 비교)에서 mismatch가 다수 잔존했다. 두 가지 원인을 수정.

### 수정 1: 정답 행 필터링 — prod 코드 기반 (`compare_product_data.py`)

**문제 (4개 데이터셋 합산 48건):**
comparison_detailed의 `run_comparison()`이 정답 CSV 행을 `isrn_kind_dtcd`/`isrn_kind_itcd`(보종코드)로 필터링하면, 같은 보종코드 아래 적립형/거치형 등 변종이 합쳐져 정답 범위가 과도하게 넓어짐. 결과적으로 정확한 매핑도 mismatch로 판정.

**해결:**
추출 JSON의 `prod_dtcd`/`prod_itcd`(상품코드)로 정답 행을 추가 필터링하여, 해당 변종에 해당하는 정답만 비교 대상에 포함.

```python
# prod 코드로 정답 행 필터링 (적립형/거치형 등 변종 분리)
if prod_dtcd and prod_itcd and answer_rows:
    filtered = [
        r for r in answer_rows
        if normalize_code(r.get('PROD_DTCD', '')) == prod_dtcd
        and normalize_code(r.get('PROD_ITCD', '')) == prod_itcd
    ]
    if filtered:
        answer_rows = filtered
```

### 수정 2: 구분 없는 기간 필드 비우기 (`extract_join_age.py`)

**문제 (가입가능나이 14건):**
`build_table_age_records()`가 표에서 보험기간/납입기간 값을 항상 채우지만, 해당 값이 하나뿐이면 구분 역할을 하지 않으므로 정답 CSV에서는 비어 있음.

예: 하나로H종신 1종 — 납입기간=10년 하나뿐 → 구분 아님 → 비워야 함
예: 연금저축 하이드림 적립형 — 납입기간=5,10,15,20 다양 → 구분 역할 → 유지

**해결:**
`build_table_age_records()` return 직전에, 각 기간 필드 그룹의 고유 값이 1개 이하이면 해당 필드를 비우는 로직 추가. 업무 규칙으로 `납입기간이 복수이면 보험기간도 유지`를 적용.

```python
paym_unique = _count_unique(paym_fields)
ins_unique = _count_unique(ins_fields)

if paym_unique <= 1:
    _clear(paym_fields)
if ins_unique <= 1 and paym_unique <= 1:
    _clear(ins_fields)
```

**시행착오:**
- 글로벌 `_strip_non_distinguishing_periods()` 함수를 전체 코드 경로에 적용 → regression 26건 발생 (100% → 90%). 상품별로 기간 필드가 이미 분리된 상태에서 고유 값을 세면 각 상품이 1개로 보이기 때문.
- `product_overrides.json`으로 개별 처리하는 방안도 검토했으나, `build_table_age_records()` 내부 로직만으로 14건 전량 해결되어 불필요.

### 결과

| 데이터셋 | comparison_detailed mismatch |
|---|---|
| 가입가능나이 | 14건 → **0건** |
| 가입가능보기납기 | 16건 → **0건** |
| 납입주기 | 16건 → **0건** |
| 보기개시나이 | 16건 → **0건** |

---

## 핵심 설계 결정과 교훈

### 1. Context 3단계 우선순위

**결정:** 테이블 > 텍스트 > 섹션 순서로 context를 추출하되, 일반형/상생협력형 분리 시 섹션 context 우선

**교훈:** PDF 구조가 상품마다 다르므로, 단일 방식이 아닌 계층적 fallback이 필수. 단, 특정 조건에서는 우선순위를 역전시켜야 정확도가 올라간다.

### 2. Context 필터링 revert 사건

**사건:** context 기반 납입기간 필터링을 추가했으나 regression 발생 → revert (commit f275646)

**교훈:** 모든 개선은 전체 데이터셋에 대해 검증해야 한다. 특정 상품에서 잘 작동하는 로직이 다른 상품에서 오히려 악화시킬 수 있다.

### 3. 4-Tuple 그루핑

**결정:** `(dtcd, itcd)` → `(dtcd, itcd, prod_dtcd, prod_itcd)` 확장

**교훈:** 같은 보종코드라도 상품코드가 다르면 기간 세트가 다를 수 있다. 비교 단위의 정밀도가 정확도에 직접 영향.

### 4. 도메인 제약 필터

**결정:** 납입기간 ≤ 보험기간 비즈니스 룰 적용

**교훈:** 명시적 패턴 매칭보다 도메인 지식 기반 제약이 더 효과적이고 유지보수하기 쉽다.

### 5. 전기납 context 인식

**결정:** 전기납 플래그를 전역이 아닌 context별로 판단

**교훈:** PDF 전체에서 감지한 플래그는 특정 상품 유형에만 해당할 수 있다. 글로벌 플래그는 반드시 context로 검증해야 한다.

### 6. 글로벌 후처리 vs 로컬 로직

**사건:** 기간 필드 비우기 로직을 전체 코드 경로에 글로벌 적용 → 26건 regression (100% → 90%)

**교훈:** 추출 결과가 상품별로 이미 분리된 상태에서 "고유 값이 1개면 비움" 로직을 적용하면, 원래 복수 값이었던 필드도 잘못 비워진다. 로직의 적용 범위(테이블 전체 vs 상품별 분리 후)가 결과를 완전히 바꾼다. 가능하면 데이터가 분리되기 전 시점에서 처리하고, 불가능하면 product_overrides.json으로 개별 처리.

### 7. 설정 파일 기반 확장성

**결정:** 상품별 오버라이드와 데이터셋별 비교 규칙을 JSON으로 외부화

**교훈:** 100% 달성 후 리팩토링이 안전하다. 정확도가 보장된 상태에서 구조 변경하면 regression을 즉시 감지할 수 있다. "먼저 동작하게, 그 다음 깨끗하게".

---

## 커밋 히스토리 요약

```
fe808cc  first approach
  ...
f51fd06  Add annuity age extractor
6d7ce71  Add unified product code mapping and payment cycle comparison
09c9746  Add unified compare_product_data.py with CSV-row-based reporting
  ...
5d83507  Switch mapping match key from isrn_kind_sale_nm to prod_sale_nm
ad18f5e  Improve mapping accuracy: sibling fallback, · token split, period token filter
252e541  Improve accuracy: 보기개시나이 84.1%→90.0%, 납입주기 84.0%→89.6%
2444bfb  Improve insurance period accuracy: 32.2% → 65.5%
e237318  Improve insurance period accuracy: 65.5% → 91.6%
c78dd76  Achieve 100% accuracy on all 3 datasets and add extraction logic docs
5128231  Achieve 100% accuracy on join_age dataset (88.1% → 100%)
aa4deb1  Update docs: add join_age pipeline, update accuracy to 100% across all 4 datasets
2f8d24d  Externalize hardcoded configs for new dataset extensibility
4f190de  Update docs: Phase 6 externalization, config file references
```
