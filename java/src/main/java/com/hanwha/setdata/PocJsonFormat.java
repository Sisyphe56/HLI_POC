package com.hanwha.setdata;

import com.hanwha.setdata.output.PythonStyleJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verify JSON output matches Python {@code json.dumps(ensure_ascii=False, indent=2)}. */
public final class PocJsonFormat {
    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> o1 = new LinkedHashMap<>();
        o1.put("상품명칭", "한화생명 H건강플러스보험 무배당");
        o1.put("세부종목1", "1종");
        o1.put("세부종목2", "1형(기본형)");
        o1.put("상품명", "한화생명 H건강플러스보험 무배당 1종 1형(기본형)");
        list.add(o1);
        Map<String, Object> o2 = new LinkedHashMap<>();
        o2.put("상품명칭", "한화생명 H건강플러스보험 무배당");
        o2.put("세부종목1", "2종");
        o2.put("상품명", "한화생명 H건강플러스보험 무배당 2종");
        list.add(o2);
        System.out.println(PythonStyleJson.writeString(list));
    }
}
