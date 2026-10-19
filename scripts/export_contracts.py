#!/usr/bin/env python3
"""Generate JSON Schemas from shared Pydantic models."""
import argparse
import json
from pathlib import Path

from capstone_shared.contracts import SearchRulesInput, SearchRulesResult

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
for name, model in [
    ("search-rules.input.schema.json", SearchRulesInput),
    ("search-rules.output.schema.json", SearchRulesResult),
]:
    target = root / "contracts" / name
    content = json.dumps(model.model_json_schema(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not target.exists() or target.read_text(encoding="utf-8") != content:
            raise SystemExit(f"Contract schema is stale: {name}")
    else:
        target.parent.mkdir(exist_ok=True)
        target.write_text(content, encoding="utf-8")
print("Contract schemas are current.")
