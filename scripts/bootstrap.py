#!/usr/bin/env python3
"""Generate local-only credentials without printing or overwriting them."""
from pathlib import Path
import os
import re
import secrets

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    destination = ROOT / ".env"
    if destination.exists():
        # Add the new cache credential without changing existing database secrets.
        existing = destination.read_text(encoding="utf-8")
        if not re.search(r"^REDIS_PASSWORD=", existing, re.MULTILINE):
            with destination.open("a", encoding="utf-8") as handle:
                handle.write("\nREDIS_PASSWORD=" + secrets.token_urlsafe(36) + "\n")
            print("Added Redis credential to ignored .env; existing values were preserved.")
        else:
            print(".env already exists; preserved without changes.")
        return
    text = (ROOT / ".env.example").read_text(encoding="utf-8")
    for placeholder in set(re.findall(r"__GENERATE_[A-Z_]+__", text)):
        text = text.replace(placeholder, secrets.token_urlsafe(36))
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("Created ignored .env with unique development credentials. Values are not printed.")


if __name__ == "__main__":
    main()
