#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import prepare_blocked_campaign


def main() -> None:
    parser = argparse.ArgumentParser(description="Emit all A-D receipts for a Gate 0-blocked campaign")
    parser.add_argument("campaign_root", type=Path)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--discovery", required=True, type=Path)
    args = parser.parse_args()
    prepare_blocked_campaign(args.campaign_root, args.branch, args.commit, args.discovery)


if __name__ == "__main__":
    main()
