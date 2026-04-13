package com.hanwha.setdata.model;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * A single product classification record, corresponding to a Python dict with
 * keys inserted in order: {@code 상품명칭, 세부종목1..N, 상품명}.
 *
 * <p>Wraps {@link LinkedHashMap} to preserve insertion order for JSON output
 * parity with Python {@code json.dumps}.
 */
public final class ProductRecord extends LinkedHashMap<String, String> {

    public ProductRecord() {
    }

    public ProductRecord(java.util.Map<String, String> other) {
        super(other);
    }

    public String getOrEmpty(String key) {
        String v = get(key);
        return v == null ? "" : v;
    }

    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return Objects.hash((Object) entrySet().toArray());
    }
}
