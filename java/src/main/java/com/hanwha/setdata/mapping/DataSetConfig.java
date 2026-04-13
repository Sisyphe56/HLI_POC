package com.hanwha.setdata.mapping;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Python equivalent: {@code DataSetConfig} dataclass + {@code DATASET_CONFIGS}.
 */
public final class DataSetConfig {

    public final String name;
    public final Path inputDir;
    public final Path outputDir;
    public final String outputPrefix;
    public final BiFunction<Map<String, Object>, MappingRow, LinkedHashMap<String, Object>> buildRow;
    public final String dataFieldName;

    public DataSetConfig(String name, Path inputDir, Path outputDir, String outputPrefix,
                         BiFunction<Map<String, Object>, MappingRow, LinkedHashMap<String, Object>> buildRow,
                         String dataFieldName) {
        this.name = name;
        this.inputDir = inputDir;
        this.outputDir = outputDir;
        this.outputPrefix = outputPrefix;
        this.buildRow = buildRow;
        this.dataFieldName = dataFieldName;
    }

    public static LinkedHashMap<String, DataSetConfig> defaults(Path projectRoot, RowBuilder rb) {
        LinkedHashMap<String, DataSetConfig> m = new LinkedHashMap<>();
        m.put("product_classification", new DataSetConfig(
                "product_classification",
                projectRoot.resolve("상품분류"),
                projectRoot.resolve("코드매핑").resolve("상품분류"),
                "상품분류_",
                rb::productClassification,
                ""
        ));
        m.put("payment_cycle", new DataSetConfig(
                "payment_cycle",
                projectRoot.resolve("납입주기"),
                projectRoot.resolve("코드매핑").resolve("납입주기"),
                "납입주기_",
                rb::paymentCycle,
                "납입주기"
        ));
        m.put("annuity_age", new DataSetConfig(
                "annuity_age",
                projectRoot.resolve("보기개시나이"),
                projectRoot.resolve("코드매핑").resolve("보기개시나이"),
                "보기개시나이_",
                rb::annuityAge,
                "보기개시나이정보"
        ));
        m.put("insurance_period", new DataSetConfig(
                "insurance_period",
                projectRoot.resolve("가입가능보기납기"),
                projectRoot.resolve("코드매핑").resolve("가입가능보기납기"),
                "가입가능보기납기_",
                rb::insurancePeriod,
                "가입가능보기납기"
        ));
        m.put("join_age", new DataSetConfig(
                "join_age",
                projectRoot.resolve("가입가능나이"),
                projectRoot.resolve("코드매핑").resolve("가입가능나이"),
                "가입가능나이_",
                rb::joinAge,
                "가입가능나이"
        ));
        return m;
    }
}
