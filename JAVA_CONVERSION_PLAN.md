# Python → Java 변환 계획

## 1. 변환 대상 및 범위

### 대상 모듈 (총 ~16,700 LOC)

| 역할 | Python 파일 | LOC | 비고 |
|---|---|---|---|
| 상품분류 추출 | `extract_product_classification_v2.py` | 1,568 | docx 기반, 다른 모듈이 의존 |
| 납입주기 추출 | `extract_payment_cycle_v2.py` | 769 | product_classification 의존 |
| 보기납기 추출 | `extract_insurance_period_v2.py` | 1,389 | 섹션 필터 + 테이블 fallback |
| 가입나이 추출 | `extract_join_age_v2.py` | 2,916 | 가장 복잡 |
| 보기개시나이 추출 | `extract_annuity_age_v2.py` | 1,173 | 연금 상품 전용 |
| 상품코드 매핑 | `map_product_code.py` | 987 | CSV 기반, 외부 의존 없음 |
| 정답 비교 | `compare_product_data.py` | 981 | openpyxl (Excel) |
| 엑셀 출력 | `write_product_data.py` | — | openpyxl |

### 제외 대상 (PDF 기반 v1)
`extract_*.py` (v1, pdfplumber 기반)은 docx 기반 v2로 이미 대체되었으므로 변환 대상에서 제외.

---

## 2. 핵심 외부 의존성 매핑

| Python 라이브러리 | Java 대응 | 비고 |
|---|---|---|
| `python-docx` | **Apache POI (XWPF)** | `XWPFDocument`, `XWPFParagraph`, `XWPFTable` — 문단/테이블 문서 순서 순회 가능 |
| `openpyxl` | **Apache POI (XSSF)** | `XSSFWorkbook`로 xlsx 읽기/쓰기 |
| `re` (정규식) | `java.util.regex` | Python regex 일부 기능(`\p{...}`, lookbehind 제약) 차이 주의 |
| `json` | **Jackson** (`com.fasterxml.jackson`) | `ObjectMapper` + `JsonNode` (동적 JSON용) |
| `pathlib.Path` | `java.nio.file.Path`, `Files` | |
| `argparse` | **picocli** 또는 `java.util.spi` | CLI 파싱 |
| `unicodedata` | `java.text.Normalizer` | NFC/NFKC 정규화 |
| `csv` | **OpenCSV** 또는 수동 파싱 | `보종코드_상품코드_매핑.csv` |

### 빌드 도구
- **Gradle (Kotlin DSL)** 권장 (또는 Maven)
- Java 17 LTS 타겟

---

## 3. 프로젝트 구조

```
java/
├── build.gradle.kts
├── src/main/java/com/hanwha/setdata/
│   ├── Main.java                        # CLI 진입점
│   ├── cli/
│   │   └── PipelineCli.java             # 서브커맨드 (extract/map/compare)
│   ├── config/
│   │   ├── OverridesConfig.java         # product_overrides.json 로드
│   │   ├── DatasetConfig.java           # dataset_configs.json
│   │   └── ConfigLoader.java
│   ├── docx/
│   │   ├── DocxReader.java              # XWPFDocument 래핑
│   │   ├── SectionTracker.java          # 최초계약/갱신계약 컨텍스트
│   │   ├── DocxContent.java             # (lines, tables, tableSections) 레코드
│   │   └── TableView.java               # List<List<String>> 추상화
│   ├── extract/
│   │   ├── Extractor.java               # 공통 인터페이스
│   │   ├── ProductClassificationExtractor.java
│   │   ├── PaymentCycleExtractor.java
│   │   ├── InsurancePeriodExtractor.java
│   │   ├── JoinAgeExtractor.java
│   │   └── AnnuityAgeExtractor.java
│   ├── model/                            # 도메인 객체 (record)
│   │   ├── ProductRecord.java
│   │   ├── PeriodInfo.java
│   │   ├── JoinAgeRange.java
│   │   ├── PaymentCycle.java
│   │   └── AnnuityAgeRange.java
│   ├── override/
│   │   ├── OverrideEngine.java          # action dispatch (fixed/sibling_*/filter)
│   │   ├── SiblingResolver.java
│   │   └── TableSectionFilter.java
│   ├── mapping/
│   │   ├── ProductCodeMapper.java       # map_product_code.py 변환
│   │   └── AliasGenerator.java          # sedata_alias 규칙
│   ├── compare/
│   │   └── AnswerComparator.java        # compare_product_data.py 변환
│   ├── output/
│   │   └── JsonWriter.java              # Jackson 기반 출력
│   └── util/
│       ├── Normalizer.java              # normalize_ws 등 문자열 유틸
│       ├── RegexUtils.java
│       └── KoreanTextUtils.java
├── src/main/resources/
│   └── (config 파일은 외부 path 참조, classpath 번들 X)
└── src/test/java/com/hanwha/setdata/
    ├── docx/DocxReaderTest.java
    ├── extract/*Test.java               # 모듈별 정답 비교 회귀 테스트
    └── override/OverrideEngineTest.java
```

---

## 4. 단계별 변환 계획

### Phase 0 — 준비 및 기반 검증
1. Gradle 프로젝트 초기화 (Java 17, POI, Jackson, JUnit 5 의존성)
2. **docx 읽기 PoC**: Apache POI로 사업방법서 1개 파일을 문서 순서(문단↔테이블 혼합)로 순회 가능한지 확인
   - 핵심: `document.getBodyElements()` 사용 (`getParagraphs() + getTables()`은 순서 유실)
3. JSON 출력이 Python 결과와 바이트 단위로 일치 가능한지 확인 (키 순서, indent=2, ensure_ascii=False 대응 → Jackson `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS=false` + `ObjectWriter.withDefaultPrettyPrinter`, UTF-8 raw)

### Phase 1 — 공통 인프라
1. `util.Normalizer` — Python `normalize_ws`, `re.sub(r'\s+', '')` 등 문자열 정규화 전량 이식
2. `docx.DocxReader` + `SectionTracker` — `extract_docx_content()` 3-tuple 대응 (`lines`, `tables`, `tableSections`)
3. `config.OverridesConfig` — `product_overrides.json` 역직렬화
   - 동적 스키마(action 타입별 필드 상이)는 `JsonNode`로 읽은 뒤 action 별 POJO 래핑
4. `util.RegexUtils` — Python에서 쓰는 정규식 패턴 Java 호환 확인 및 공용 Pattern 캐싱

### Phase 2 — 모듈별 포팅 (난이도 오름차순)

| 순서 | 모듈 | 난이도 | 핵심 난점 |
|---|---|---|---|
| 1 | ProductClassificationExtractor | 중 | 상품명 파싱 규칙 다수, `product_classification` override |
| 2 | PaymentCycleExtractor | 중 | section_filter 적용, sibling_copy/sibling_filter |
| 3 | InsurancePeriodExtractor | 중상 | 테이블 fallback, sibling_filter, fixed override |
| 4 | AnnuityAgeExtractor | 상 | `annuity_context_tokens` 그룹핑, gender_split |
| 5 | JoinAgeExtractor | 최상 | 2,900 LOC, 가장 복잡한 파싱 분기 |

각 모듈 포팅 절차:
1. Python 구현을 **위에서 아래로 함수 단위** 번역 (리팩터링은 금물 — 동작 동일성 우선)
2. 해당 모듈의 process_single을 CLI로 노출
3. **회귀 테스트**: Python 출력 JSON vs Java 출력 JSON을 전체 49개 파일에 대해 diff
4. diff 0이 될 때까지 수정

### Phase 3 — 매핑 및 비교
1. `ProductCodeMapper` — `map_product_code.py` 이식 + `sedata_alias` 규칙 적용
2. `AnswerComparator` — POI XSSF로 정답 엑셀 로드, 정답 기반 리포트 생성

### Phase 4 — 파이프라인 통합 및 CLI
1. `PipelineCli`로 서브커맨드 구성:
   - `extract product-classification|payment-cycle|insurance-period|join-age|annuity-age`
   - `map-products`
   - `compare --data-set <name>`
2. Python의 디렉토리 규약(`사업방법서_워드/`, `가입가능나이/`, `가입가능보기납기/` 등) 유지

### Phase 5 — 전체 회귀 검증
- 49개 docx × 5개 데이터셋 = 전량 파이프라인 실행
- 기존 Python 산출물과 JSON diff 0 확인
- `compare_product_data` match rate가 Python과 동일한지 확인 (현재 insurance_period 100%, join_age 99.6%)

---

## 5. 동작 동일성을 위한 주의사항

### 5.1 JSON 직렬화
- Python `json.dump(..., ensure_ascii=False, indent=2)` → Jackson `ObjectMapper.writerWithDefaultPrettyPrinter()` + UTF-8 출력
- **dict 삽입 순서 보존**: Python 3.7+ dict 순서 → Java `LinkedHashMap` 사용
- 숫자 포맷: Python `int`/`str` 구분 유지 (나이 "0" vs 0)

### 5.2 정규식 차이
- Python `(?P<name>...)` → Java `(?<name>...)` (동일)
- Python `\A`, `\Z` 플래그 동작 차이 확인
- 한글 문자 클래스는 Java `\p{IsHangul}` 사용 가능

### 5.3 한글 문자열 정규화
- Python `unicodedata.normalize('NFC', ...)` → Java `Normalizer.normalize(s, Form.NFC)`
- 파일명 비교 시 NFC 강제 (macOS HFS+는 NFD 사용)

### 5.4 Apache POI 주의점
- 임시 파일(`~$...docx`)은 `.startsWith("~$")` 필터링
- `XWPFDocument`는 Python `Document`보다 테이블 병합 셀 접근 방식이 다름 → `getCells()` 호출 전 병합 확인
- 문서 순서 순회: `document.getBodyElements()` 반드시 사용

### 5.5 Override 엔진
- `action` 종류: `fixed`, `sibling_filter`, `sibling_copy`, `filter`, `add_annuity_start_age`, `alias`, `gender_split`, `min_age_floor`
- 각 action을 `sealed interface OverrideAction` + 서브 record로 모델링
- action dispatch는 `switch` expression (Java 17)

---

## 6. 테스트 전략

### 6.1 Golden test
- `src/test/resources/golden/` 에 Python이 생성한 정답 JSON 배치
- Java 산출물과 바이트 단위 비교 (diff 0 목표)

### 6.2 단위 테스트
- `Normalizer` 문자열 정규화 케이스
- `OverrideEngine` 각 action 타입별 적용
- `SectionTracker` 최초계약/갱신계약 전환 감지

### 6.3 통합 테스트
- 대표 docx 5개 (실손 1, 종신 1, 연금 1, 건강 1, 단체 1) 전체 파이프라인 실행 후 JSON diff

---

## 7. 리스크 및 대응

| 리스크 | 영향 | 대응 |
|---|---|---|
| Apache POI 가 python-docx와 문서 순회 순서가 다를 수 있음 | 섹션 추적 전체가 깨짐 | Phase 0 PoC에서 먼저 검증 |
| 정규식 미묘한 차이로 파싱 결과 차이 | 추출값 불일치 | 모듈별 golden test로 조기 감지 |
| JoinAge 2,900 LOC 파서 복잡도 | 포팅 기간 장기화 | 함수 단위 체크리스트 관리, 리팩터링 금지 |
| JSON 키 순서 불일치 | golden diff 노이즈 | `LinkedHashMap` + Jackson 순서 보존 설정 |
| 한글 파일명 NFC/NFD | 파일 매칭 실패 | 입력/출력 경로 전부 NFC 정규화 |
| override 동적 스키마 | 역직렬화 실패 | `JsonNode`로 로드 후 action별 POJO 변환 |

---

## 8. 마일스톤

| 단계 | 완료 기준 |
|---|---|
| M1: Phase 0 | docx 1개 파일을 Java로 읽어 문단/테이블 문서 순서대로 덤프 가능 |
| M2: Phase 1 | OverridesConfig 로드 + DocxReader가 Python `extract_docx_content`와 동일한 3-tuple 생산 |
| M3: 각 Extractor 완료 | 해당 데이터셋의 Java vs Python JSON diff == 0 (49개 파일) |
| M4: Phase 3 | 매핑/비교까지 통합, compare_product_data 일치율 동일 |
| M5: Phase 5 | 전체 파이프라인 회귀 통과 |

---

## 9. 비변환 자원 (공유)

- `config/product_overrides.json`
- `config/dataset_configs.json`
- `config/보종코드_상품코드_매핑.csv`
- `사업방법서_워드/*.docx`
- `정답데이터/*.xlsx`

→ Python/Java 양쪽에서 동일 파일 참조. 변환 중 병렬 운영 가능.
