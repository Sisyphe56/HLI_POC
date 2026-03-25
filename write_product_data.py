#!/usr/bin/env python3
"""코드매핑 JSON → CSV 변환 스크립트.

코드매핑 완료된 상품별 세트 데이터를 정답 CSV와 동일한 칼럼 구조의 CSV로 작성.

Usage:
    python write_product_data.py --data-set join_age --json <매핑JSON>
    python write_product_data.py --data-set join_age --json <매핑JSON> --output <출력CSV>
"""
import argparse
import csv
import json
from pathlib import Path
from typing import Dict, List

PROJECT_ROOT = Path(__file__).resolve().parent
CONFIG_PATH = PROJECT_ROOT / 'config' / 'dataset_configs.json'

# 공통 키 필드: JSON 키 → CSV 칼럼
COMMON_KEY_MAP = {
    'isrn_kind_dtcd': 'ISRN_KIND_DTCD',
    'isrn_kind_itcd': 'ISRN_KIND_ITCD',
    'isrn_kind_sale_nm': 'ISRN_KIND_SALE_NM',
    'prod_dtcd': 'PROD_DTCD',
    'prod_itcd': 'PROD_ITCD',
    'prod_sale_nm': 'PROD_SALE_NM',
}


def load_config(data_set: str) -> dict:
    with CONFIG_PATH.open('r', encoding='utf-8') as f:
        configs = json.load(f)
    if data_set not in configs:
        raise ValueError(f"Unknown data-set: {data_set}")
    return configs[data_set]


def build_field_map(config: dict) -> Dict[str, str]:
    """tuple_fields에서 JSON→CSV 필드 매핑 구축."""
    mapping = {}
    for tf in config.get('tuple_fields', []):
        mapping[tf['json']] = tf['csv']
    return mapping


def json_to_csv_rows(
    mapped_data: List[dict],
    data_field: str,
    csv_columns: List[str],
    field_map: Dict[str, str],
) -> List[dict]:
    """매핑 JSON 데이터를 CSV 행 리스트로 변환."""
    rows = []
    # CSV 칼럼 → JSON 키 역매핑 (공통 키)
    csv_to_json_common = {v: k for k, v in COMMON_KEY_MAP.items()}

    for product in mapped_data:
        # 공통 키 값 추출
        common_vals = {}
        for csv_col in csv_columns:
            json_key = csv_to_json_common.get(csv_col)
            if json_key:
                common_vals[csv_col] = str(product.get(json_key, ''))

        # 데이터 필드 배열
        data_records = product.get(data_field, [])
        if not data_records:
            # 데이터 없으면 공통 키만으로 1행 출력
            row = {col: '' for col in csv_columns}
            row.update(common_vals)
            rows.append(row)
            continue

        for record in data_records:
            row = {col: '' for col in csv_columns}
            row.update(common_vals)
            # tuple_fields 매핑으로 데이터 칼럼 채우기
            for json_key, csv_col in field_map.items():
                if csv_col in row:
                    row[csv_col] = str(record.get(json_key, ''))
            rows.append(row)

    return rows


def write_csv(rows: List[dict], csv_columns: List[str], output_path: Path,
              encoding: str = 'euc-kr'):
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open('w', encoding=encoding, newline='') as f:
        writer = csv.DictWriter(f, fieldnames=csv_columns)
        writer.writeheader()
        writer.writerows(rows)


def parse_args() -> argparse.Namespace:
    with CONFIG_PATH.open('r', encoding='utf-8') as f:
        configs = json.load(f)
    ds_choices = [k for k in configs.keys() if not k.startswith('_')]

    parser = argparse.ArgumentParser(description='코드매핑 JSON → CSV 변환')
    parser.add_argument('--data-set', required=True, choices=ds_choices,
                        help='세트 데이터 종류')
    parser.add_argument('--json', required=True, type=str,
                        help='코드매핑 JSON 파일 경로')
    parser.add_argument('--output', type=str, default=None,
                        help='출력 CSV 경로 (미지정 시 결과/{korean_name}/ 하위)')
    parser.add_argument('--encoding', type=str, default='euc-kr',
                        help='출력 CSV 인코딩 (기본: euc-kr)')
    return parser.parse_args()


def main():
    args = parse_args()
    config = load_config(args.data_set)

    json_path = Path(args.json)
    with json_path.open('r', encoding='utf-8') as f:
        mapped_data = json.load(f)
    if not isinstance(mapped_data, list):
        raise ValueError(f"JSON 파일이 리스트가 아닙니다: {json_path}")

    data_field = config['data_field']
    csv_columns = config.get('output_csv_columns')
    if not csv_columns:
        raise ValueError(f"output_csv_columns가 dataset_configs.json에 없습니다: {args.data_set}")

    field_map = build_field_map(config)
    rows = json_to_csv_rows(mapped_data, data_field, csv_columns, field_map)

    # 출력 경로 결정
    if args.output:
        output_path = Path(args.output)
    else:
        korean_name = config['korean_name']
        prefix = config.get('file_prefix', '')
        # 입력 파일명에서 prefix 제거 후 .csv 확장자
        stem = json_path.stem
        if stem.startswith(prefix):
            stem = stem  # prefix 유지
        else:
            stem = f"{prefix}{stem}"
        output_path = PROJECT_ROOT / '결과' / korean_name / f"{stem}.csv"

    write_csv(rows, csv_columns, output_path, encoding=args.encoding)

    print(f"{json_path.name} -> {output_path}")
    print(f"  Products: {len(mapped_data)}")
    print(f"  CSV rows: {len(rows)}")
    print(f"  Columns: {len(csv_columns)}")


if __name__ == '__main__':
    main()
