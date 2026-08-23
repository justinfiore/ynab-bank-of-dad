#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(QA_ROOT))

from lib.evidence_bundle import finalize_campaign


TOKEN_ENV_NAMES = (
    "PARENT_ACCESS_TOKEN",
    "JORSTEN_JR_ACCESS_TOKEN",
    "BORSTEN_ACCESS_TOKEN",
    "THORSTEN_ACCESS_TOKEN",
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate, summarize, checksum, and zip QA evidence")
    parser.add_argument("campaign_root", type=Path)
    args = parser.parse_args()
    zip_path, checksum_path = finalize_campaign(
        args.campaign_root, [os.environ.get(name, "") for name in TOKEN_ENV_NAMES]
    )
    print(f"Evidence finalized: {zip_path.name} and {checksum_path.name}")


if __name__ == "__main__":
    main()
