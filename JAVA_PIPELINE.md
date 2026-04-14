# Java 세트데이터 추출 파이프라인

## 1. 개요

한화생명 사업방법서 워드 문서(.docx)에서 5종 세트데이터를 추출하고, 보종코드 CSV로 상품코드를 매핑한 뒤 정답 데이터와 비교 검증하는 파이프라인이다.

| 항목 | 내용 |
|------|------|
| 입력 | 사업방법서 워드 49개 (`사업방법서_워드/*.docx`) |
| 설정 | `config/product_overrides.json`, `config/보종코드_상품코드_매핑.csv` |
| 출력 | 5종 추출 JSON + 코드매핑 JSON + 정답비교 리포트 |
| 빌드 | Java 17, Maven, Apache POI 5.2.5, Jackson 2.17.2, SQLite JDBC 3.45.3.0 |

### 세트데이터 종류

| 세트데이터 | 출력 디렉토리 | 코드 |
|---|---|---|
| 상품분류 | `상품분류/` | — |
| 납입주기 | `납입주기/` | PAYM_CYCL |
| 가입가능보기납기 | `가입가능보기납기/` | ISRN_TERM / PAYM_TERM |
| 보기개시나이 | `보기개시나이/` | SPIN_STRT_AG |
| 가입가능나이 | `가입가능나이/` | JOIN_AG |

---

## 2. 빌드 및 실행

### 빌드

```bash
cd java
mvn clean compile
```

### 전체 파이프라인 실행

```bash
mvn -q exec:java -Dexec.mainClass="com.hanwha.setdata.Main" -Dexec.args="all"
```

`all`은 아래 순서대로 모든 단계를 실행한다:
1. docx 캐시 → 2. classify → 3. cycle → 4. period → 5. annuity → 6. joinage → 7. map (5회) → 8. compare (4회)

### 개별 서브커맨드 실행

```bash
# 서브커맨드 목록: classify, period, cycle, annuity, joinage, map, compare
mvn -q exec:java -Dexec.mainClass="com.hanwha.setdata.Main" -Dexec.args="classify"
mvn -q exec:java -Dexec.mainClass="com.hanwha.setdata.Main" -Dexec.args="map --data-set payment_cycle"
```

### 단일 파일 처리

```bash
# 상품분류 단일 파일
mvn -q exec:java -Dexec.mainClass="com.hanwha.setdata.Main" \
  -Dexec.args="classify --docx ../사업방법서_워드/파일.docx --output result.json"

# 보험기간 단일 파일 (분류 JSON 필요)
mvn -q exec:java -Dexec.mainClass="com.hanwha.setdata.Main" \
  -Dexec.args="period --docx ../사업방법서_워드/파일.docx --json ../상품분류/파일.json --output result.json"
```

---

## 3. 파이프라인 흐름도

```
사업방법서_워드/*.docx
        │
   ┌────┴─────┐
   │ DocxParser│  1회 파싱
   └────┬─────┘
        ▼
   ┌──────────┐
   │  SQLite   │  cache/docx_cache.db (SHA-256 해시 기반 캐시)
   └────┬─────┘
        │
   ┌────┴────────────────────────────────────────────┐
   │                     추출 단계                      │
   │                                                    │
   │  [1] classify ──→ 상품분류/*.json                  │
   │        │                                           │
   │        ├──→ [2] cycle   ──→ 납입주기/*.json        │
   │        ├──→ [3] period  ──→ 가입가능보기납기/*.json │
   │        ├──→ [4] annuity ──→ 보기개시나이/*.json     │
   │        │            │                              │
   │        └──→ [5] joinage ──→ 가입가능나이/*.json     │
   │                  (← 가입가능보기납기도 소비)         │
   └────────────────────────────────────────────────────┘
        │
   ┌────┴────────────────────────────────────────────┐
   │  [6] map (5개 데이터셋)                           │
   │      × config/보종코드_상품코드_매핑.csv           │
   │      → 코드매핑/*/*.json                          │
   └────┬───────────────────────────────────────────-─┘
        │
   ┌────┴────────────────────────────────────────────┐
   │  [7] compare (4개 데이터셋)                       │
   │      × 정답/*.csv 또는 정답/*.xlsx                │
   │      → 정답비교/*/comparison_report.json          │
   └─────────────────────────────────────────────────┘
```

**데이터 의존성 요약**:
- classify: 독립 (docx만 소비)
- cycle: classify 출력 소비
- period: classify 출력 소비
- annuity: classify 출력 소비
- joinage: classify + period 출력 소비

---

## 4. 각 추출기 상세

### 4.1 상품분류 (ProductClassificationExtractor)

사업방법서에서 상품명, 세부종목 축을 추출하여 상품 레코드를 생성한다.

| 항목 | 내용 |
|------|------|
| 서브커맨드 | `classify` |
| CLI 인자 | `--target-dir`, `--output-dir`, `--overrides`, `--docx`, `--output` |
| 기본 입력 | `사업방법서_워드/` |
| 기본 출력 | `상품분류/` |
| 의존 데이터 | 없음 (첫 번째 단계) |
| DocxStore 뷰 | PcDocx (null-view 테이블 + fullText) |

**출력 JSON 예시**:
```json
[
  {
    "상품명칭": "한화생명 H건강플러스보험 무배당",
    "상품명": "(무)H건강플러스보험Ⅰ",
    "세부종목1": "질병사망",
    "세부종목2": "1종(해약환급금미지급형)"
  }
]
```

**핵심 클래스**: `PcNames`, `PcText`, `PcAxes`, `PcOverrides`, `PcDocx`

---

### 4.2 납입주기 (PaymentCycleExtractor)

각 상품의 납입주기(월납, 연납, 일시납 등)를 추출한다.

| 항목 | 내용 |
|------|------|
| 서브커맨드 | `cycle` |
| CLI 인자 | `--target-dir`, `--output-dir`, `--product-meta-dir`, `--overrides`, `--docx`, `--output` |
| 기본 입력 | `사업방법서_워드/` |
| 기본 출력 | `납입주기/` |
| 의존 데이터 | `상품분류/*.json` (기본 레코드) |
| DocxStore 뷰 | PcCycleDocx (expanded 테이블 + 단락 lines) |

**출력 JSON 예시**:
```json
[
  {
    "상품명칭": "...",
    "상품명": "...",
    "납입주기": [
      {"납입주기명": "월납", "납입주기값": "1"},
      {"납입주기명": "연납", "납입주기값": "2"}
    ]
  }
]
```

**핵심 클래스**: `CycleRuleParser`, `CycleRule`, `PcCycleDocx`, `PcCycleText`

---

### 4.3 가입가능보기납기 (InsurancePeriodExtractor)

보험기간, 납입기간 조합을 추출한다.

| 항목 | 내용 |
|------|------|
| 서브커맨드 | `period` |
| CLI 인자 | `--target-dir`, `--output-dir`, `--json-dir`, `--overrides`, `--docx`, `--json`, `--output` |
| 기본 입력 | `사업방법서_워드/` |
| 기본 출력 | `가입가능보기납기/` |
| 의존 데이터 | `상품분류/*.json` (페어링) |
| DocxStore 뷰 | DocxContent (expanded 테이블 + 단락/셀 lines) |

**출력 JSON 예시**:
```json
[
  {
    "상품명칭": "...",
    "상품명": "...",
    "가입가능보기납기": [
      {
        "보험기간": "X100", "보험기간구분코드": "X", "보험기간값": "100",
        "납입기간": "N20", "납입기간구분코드": "N", "납입기간값": "20"
      }
    ]
  }
]
```

**핵심 클래스**: `IpRecordBuilder`, `IpPeriods`, `IpText`, `IpAnnuity`, `IpOverrides`

---

### 4.4 보기개시나이 (AnnuityAgeExtractor)

연금 상품의 보험기간 개시 나이를 추출한다.

| 항목 | 내용 |
|------|------|
| 서브커맨드 | `annuity` |
| CLI 인자 | `--target-dir`, `--output-dir`, `--json-dir`, `--overrides`, `--docx`, `--json`, `--output` |
| 기본 입력 | `사업방법서_워드/` |
| 기본 출력 | `보기개시나이/` |
| 의존 데이터 | `상품분류/*.json` (페어링) |
| DocxStore 뷰 | DocxContent (expanded 테이블 + 단락/셀 lines) |

**핵심 클래스**: `AaBlocks`, `AaEscalation`, `AaMerge`, `AaPicker`, `AaText`

---

### 4.5 가입가능나이 (JoinAgeExtractor)

가입 가능한 최소/최대 나이를 추출한다.

| 항목 | 내용 |
|------|------|
| 서브커맨드 | `joinage` |
| CLI 인자 | `--target-dir`, `--output-dir`, `--json-dir`, `--period-dir`, `--overrides`, `--docx`, `--json`, `--output` |
| 기본 입력 | `사업방법서_워드/` |
| 기본 출력 | `가입가능나이/` |
| 의존 데이터 | `상품분류/*.json` + `가입가능보기납기/*.json` |
| DocxStore 뷰 | DocxContent (expanded 테이블 + 단락/셀 lines) |

**핵심 클래스**: `JaAgeTable`, `JaFormula`, `JaMerge`, `JaText`, `JaTextExtract`, `JaPostprocess`, `JaRecordBuilder`, `JaOtherTables`

---

## 5. 코드매핑 (MapProductCode)

추출된 JSON에 보종코드/상품코드를 매핑한다.

| 항목 | 내용 |
|------|------|
| 서브커맨드 | `map` |
| CLI 인자 | `--data-set`, `--mapping-csv`, `--input-dir`, `--output-dir`, `--json`, `--output`, `--project-root` |
| 매핑 CSV | `config/보종코드_상품코드_매핑.csv` (261행) |

**데이터셋 이름**: `product_classification`, `payment_cycle`, `annuity_age`, `insurance_period`, `join_age`

```bash
# 전체 데이터셋 매핑
mvn -q exec:java -Dexec.mainClass="com.hanwha.setdata.Main" -Dexec.args="map --data-set payment_cycle"
```

매핑 결과로 각 레코드에 `isrn_kind_dtcd`, `isrn_kind_itcd`, `prod_dtcd`, `prod_itcd` 등의 코드 필드가 추가된다.

**핵심 클래스**: `DataSetConfig`, `FileProcessor`, `MappingCsv`, `MappingRow`, `RowBuilder`, `SiblingFallback`, `SedataAlias`, `TokenMatcher`

---

## 6. 정답비교 (CompareProductData)

코드매핑된 JSON을 정답 CSV/XLSX와 비교하여 정확도를 검증한다.

| 항목 | 내용 |
|------|------|
| 서브커맨드 | `compare` |
| CLI 인자 | `--data-set`, `--mapped-dir`, `--report-dir`, `--answer-csv`, `--answer-excel`, `--csv`, `--verbose`, `--project-root` |
| 정답 데이터 | `정답/*.csv` 또는 `정답/*.xlsx` |

**데이터셋 이름**: `payment_cycle`, `annuity_age`, `insurance_period`, `join_age`

출력 리포트 3종:
- `comparison_report.json` — 요약 통계
- `comparison_detailed.json` — 상세 결과
- `answer_based_report.json` — 정답 기준 뷰

**핵심 클래스**: `CompareCore`, `CompareLoaders`, `CompareNormalizers`, `CompareReports`, `DatasetConfigLoader`, `CsvReader`

---

## 7. SQLite 캐시 계층

49개 docx 파일을 매번 Apache POI로 파싱하는 대신, 1회 파싱 후 SQLite에 캐싱한다.

### 구조

```
DocxStore (인터페이스)
  └─ SqliteDocxStore (SQLite 구현체)
       ├─ get(Path) → ParsedDocument (캐시 히트/미스 자동 처리)
       ├─ parseAndStore(Path) → 강제 파싱 + 저장
       └─ parseAll(Path dir) → 디렉토리 일괄 파싱

DocxParser → ParsedDocument (POI → 모델 변환)
DocxStoreAdapters → DocxContent / PcDocx.Result / PcCycleDocx.Result (뷰 변환)
```

### SQLite 스키마

```sql
CREATE TABLE documents (
    id INTEGER PRIMARY KEY, filename TEXT UNIQUE,
    file_hash TEXT, file_size INTEGER, parsed_at TEXT
);
CREATE TABLE body_elements (
    doc_id INTEGER, seq INTEGER, kind TEXT,
    text TEXT, section TEXT, table_seq INTEGER,
    PRIMARY KEY(doc_id, seq)
);
CREATE TABLE tables (
    doc_id INTEGER, seq INTEGER, section TEXT,
    row_count INTEGER, col_count INTEGER,
    cells TEXT,   -- JSON 2D 배열
    merges TEXT,  -- JSON 머지 플래그 (N/H/V)
    PRIMARY KEY(doc_id, seq)
);
```

### 캐시 무효화

SHA-256 파일 해시로 판단. 파일 내용이 변경되면 자동 재파싱.

### 뷰 변환 (DocxStoreAdapters)

| 어댑터 메서드 | 결과 타입 | 사용 추출기 | 테이블 형태 |
|---|---|---|---|
| `toDocxContent()` | DocxContent | period, annuity, joinage | expanded (병합셀 중복) |
| `toPcDocxResult()` | PcDocx.Result | classify | null-view (병합셀=null) |
| `toCycleResult()` | PcCycleDocx.Result | cycle | expanded |

---

## 8. 설정 파일

### config/product_overrides.json

추출기별 상품 특수 처리 규칙을 정의한다.

```json
{
  "product_classification": { ... },
  "payment_cycle": {
    "상품명키워드": {
      "action": "fixed",        // fixed | sibling_filter | sibling_copy
      "cycles": [{"납입주기명": "월납", "납입주기값": "1"}],
      "force": false
    }
  },
  "insurance_period": { ... },
  "annuity_age": { ... },
  "join_age": { ... },
  "table_section_filter": {
    "파일명키워드": {"use_section": "최초계약"}
  },
  "sibling_fallback": {
    "suffix_patterns": ["소스접미사|대상접미사"]
  }
}
```

### config/보종코드_상품코드_매핑.csv

261행의 보종코드↔상품코드 매핑 테이블. `MapProductCode`에서 사용.

---

## 9. 디렉토리 구조

```
web-demo-session/
├── java/                          # Java 프로젝트 루트
│   ├── pom.xml
│   └── src/main/java/com/hanwha/setdata/
│       ├── Main.java              # 통합 CLI 진입점
│       ├── extract/               # 5종 추출기
│       │   ├── ProductClassificationExtractor.java
│       │   ├── PcNames.java, PcText.java, PcAxes.java, PcOverrides.java, PcDocx.java
│       │   ├── period/            # 가입가능보기납기
│       │   │   └── InsurancePeriodExtractor.java, IpRecordBuilder.java, ...
│       │   ├── cycle/             # 납입주기
│       │   │   └── PaymentCycleExtractor.java, CycleRuleParser.java, ...
│       │   ├── annuity/           # 보기개시나이
│       │   │   └── AnnuityAgeExtractor.java, AaBlocks.java, ...
│       │   └── joinage/           # 가입가능나이
│       │       └── JoinAgeExtractor.java, JaAgeTable.java, ...
│       ├── mapping/               # 코드매핑
│       │   └── MapProductCode.java, DataSetConfig.java, ...
│       ├── compare/               # 정답비교
│       │   └── CompareProductData.java, CompareCore.java, ...
│       ├── store/                 # SQLite 캐시 계층
│       │   └── SqliteDocxStore.java, DocxParser.java, DocxStoreAdapters.java, ...
│       ├── config/                # 설정 로더
│       │   └── OverridesConfig.java
│       ├── docx/                  # Docx 리더 (레거시, store에서 재사용)
│       │   └── DocxReader.java, DocxContent.java
│       ├── model/                 # 데이터 모델
│       │   └── ProductRecord.java
│       ├── output/                # 출력 포맷터
│       │   └── PythonStyleJson.java
│       └── util/                  # 유틸리티
│           └── Normalizer.java
│
├── config/                        # 설정 파일
│   ├── product_overrides.json
│   ├── dataset_configs.json
│   └── 보종코드_상품코드_매핑.csv
│
├── cache/                         # SQLite 캐시
│   └── docx_cache.db
│
├── 사업방법서_워드/                # 입력: docx 49개
├── 상품분류/                      # 출력: 상품분류 JSON
├── 납입주기/                      # 출력: 납입주기 JSON
├── 가입가능보기납기/              # 출력: 보험기간/납입기간 JSON
├── 보기개시나이/                  # 출력: 보기개시나이 JSON
├── 가입가능나이/                  # 출력: 가입가능나이 JSON
├── 코드매핑/                      # 출력: 코드매핑 JSON
├── 정답/                          # 입력: 정답 CSV/XLSX
├── 정답비교/                      # 출력: 비교 리포트
│
├── web/                           # 웹 데모 UI (FastAPI + JS)
│   ├── app.py, pipeline.py
│   └── static/ (index.html, app.js, style.css)
│
└── docs/                          # 참고 문서
```
