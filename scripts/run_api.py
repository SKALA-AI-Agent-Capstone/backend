#!/usr/bin/env python3
"""Load generated development values and run the local Spring Boot service."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
environment = os.environ.copy()
env_file = root / ".env"
if not env_file.exists():
    raise SystemExit("Run python3 scripts/bootstrap.py first.")
for line in env_file.read_text(encoding="utf-8").splitlines():
    if line and not line.startswith("#"):
        key, value = line.split("=", 1)
        environment.setdefault(key, value)
result = subprocess.run(
    ["mvn", "-f", str(root / "services/api/pom.xml"), "spring-boot:run"],
    cwd=root,
    env=environment,
)
sys.exit(result.returncode)
