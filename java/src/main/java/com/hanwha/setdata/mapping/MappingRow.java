package com.hanwha.setdata.mapping;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * CSV mapping row from {@code 보종코드_상품코드_매핑.csv}.
 *
 * <p>Python equivalent: dict produced by {@code load_mapping_rows}.
 */
public final class MappingRow {

    public final String csvRowId;
    public final String isrnKindDtcd;
    public final String isrnKindItcd;
    public final String isrnKindSaleNm;
    public final String prodDtcd;
    public final String prodItcd;
    public final String prodSaleNm;
    public final String matchKey;
    public final List<String> matchTokens;
    public final LinkedHashSet<String> matchTokenSet;
    public final String isrnMatchKey;
    public final LinkedHashSet<String> isrnMatchTokenSet;

    public MappingRow(
            String csvRowId,
            String isrnKindDtcd, String isrnKindItcd, String isrnKindSaleNm,
            String prodDtcd, String prodItcd, String prodSaleNm,
            String matchKey, List<String> matchTokens, LinkedHashSet<String> matchTokenSet,
            String isrnMatchKey, LinkedHashSet<String> isrnMatchTokenSet) {
        this.csvRowId = csvRowId;
        this.isrnKindDtcd = isrnKindDtcd;
        this.isrnKindItcd = isrnKindItcd;
        this.isrnKindSaleNm = isrnKindSaleNm;
        this.prodDtcd = prodDtcd;
        this.prodItcd = prodItcd;
        this.prodSaleNm = prodSaleNm;
        this.matchKey = matchKey;
        this.matchTokens = matchTokens;
        this.matchTokenSet = matchTokenSet;
        this.isrnMatchKey = isrnMatchKey;
        this.isrnMatchTokenSet = isrnMatchTokenSet;
    }
}
