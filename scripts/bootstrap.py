#!/usr/bin/env python3
"""Create or extend local configuration without printing or rotating secrets."""
from pathlib import Path
import os
import re
import secrets

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    destination = ROOT / ".env"
    template = (ROOT / ".env.example").read_text(encoding="utf-8")
    existing = destination.read_text(encoding="utf-8") if destination.exists() else ""
    values = dict(re.findall(r"^([A-Z][A-Z0-9_]*)=(.*)$", existing, re.MULTILINE))
    replacements: dict[str, str] = {}
    # Reuse an existing password if a newly added URL references its placeholder.
    for key, placeholder in re.findall(r"^([A-Z][A-Z0-9_]*)=(__GENERATE_[A-Z_]+__)$", template, re.MULTILINE):
        if key in values:
            replacements[placeholder] = values[key]
    for placeholder in set(re.findall(r"__GENERATE_[A-Z_]+__", template)):
        if placeholder not in replacements:
            replacements[placeholder] = secrets.token_urlsafe(36)
    for placeholder, value in replacements.items():
        template = template.replace(placeholder, value)
    if destination.exists():
        missing = [line for line in template.splitlines()
                   if re.match(r"^[A-Z][A-Z0-9_]*=", line) and line.split("=", 1)[0] not in values]
        if missing:
            with destination.open("a", encoding="utf-8") as handle:
                handle.write("\n" + "\n".join(missing) + "\n")
            print("Added missing settings to ignored .env; existing values were preserved.")
        else:
            print(".env already exists; preserved without changes.")
        return
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(template)
    print("Created ignored .env with unique development credentials. Values are not printed.")


if __name__ == "__main__":
    main()
