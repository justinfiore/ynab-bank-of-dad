#!/usr/bin/env python3
"""Package the current qaAutomated campaign for always-uploaded CI evidence."""

from __future__ import annotations

import os
from pathlib import Path

from lib.ci_evidence import package_ci_evidence


REPO_ROOT = Path(__file__).resolve().parent.parent
TOKEN_ENV_NAMES = (
    "PARENT_ACCESS_TOKEN",
    "JORSTEN_JR_ACCESS_TOKEN",
    "BORSTEN_ACCESS_TOKEN",
    "THORSTEN_ACCESS_TOKEN",
)


def main() -> None:
    tree, archive, archive_checksum = package_ci_evidence(
        REPO_ROOT, [os.environ.get(name, "") for name in TOKEN_ENV_NAMES]
    )
    print(
        f"CI evidence packaged: {tree.relative_to(REPO_ROOT)}, "
        f"{archive.name}, {archive_checksum.name}"
    )


if __name__ == "__main__":
    main()
