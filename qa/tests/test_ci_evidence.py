import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.ci_evidence import (
    _derive_campaign_metadata, package_ci_evidence, write_current_campaign_pointer,
)
from lib.evidence_bundle import EvidenceSafetyError, FULL_UUID_BYTES
import run_qa_suite


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class CiEvidencePackagerTest(unittest.TestCase):
    def _partial_campaign(self, root: Path) -> tuple[str, str, str]:
        campaign_id = "QA-20260830-120000-automated"
        token = "synthetic-parent-token-value"
        uuid = "10000000-0000-0000-0000-000000000001"
        source = root / "qa/artifacts" / campaign_id
        c2 = source / "scenarios/C2-memo-update"
        c2.mkdir(parents=True)
        (source / "environment.json").write_text(json.dumps({
            "campaign_id": campaign_id,
            "branch": "test-branch",
            "commit": "abcdef123456",
            "selected_scenario_ids": ["A1-baseline", "C2-memo-update", "D4-finish"],
        }), encoding="utf-8")
        (source / "campaign-manifest.json").write_text(json.dumps({
            "campaign_id": campaign_id, "status": "BLOCKED",
        }), encoding="utf-8")
        a1 = source / "scenarios/A1-baseline"
        a1.mkdir(parents=True)
        for scenario, path in (("A1-baseline", a1), ("C2-memo-update", c2)):
            (path / "receipt.json").write_text(json.dumps({
                "campaign_id": campaign_id, "scenario_id": scenario,
                "status": "PASS", "safety": {"api_write_attempts": 0},
                "assertions": [],
            }), encoding="utf-8")
        (c2 / "live-run.log").write_text(
            f"Authorization: Bearer synthetic-auth-value\n{token}\nplan={uuid}\n",
            encoding="utf-8",
        )
        (c2 / "dry-run.log").write_text("partial dry run\n", encoding="utf-8")
        observations = source / "api-observations"
        observations.mkdir()
        (observations / "request-telemetry.json").write_text(json.dumps({
            "requests": [{"method": "GET", "resource_class": "transactions",
                          "status_class": "2xx", "count": 1, "retry_count": 0}],
        }), encoding="utf-8")
        (c2 / "sqlite-audit.json").write_text(json.dumps({
            "operation_attempts": [{"plan_id": uuid, "result": "ok"}],
        }), encoding="utf-8")
        cleanup = source / "cleanup"
        cleanup.mkdir()
        (cleanup / "cleanup-manifest.json").write_text(json.dumps({
            "campaign_id": campaign_id, "verification": "PASS",
        }), encoding="utf-8")

        raw = root / "qa/.campaign-state" / campaign_id
        raw.mkdir(parents=True)
        (raw / "campaign.db").write_bytes(b"SQLite format 3\x00" + token.encode())
        (source / "accidental.db").write_bytes(b"SQLite format 3\x00" + uuid.encode())
        (root / "tokens.txt").write_text(token, encoding="utf-8")
        config = root / "qa/config"
        config.mkdir(parents=True)
        (config / "qa-sync.yaml").write_text(f"fullId: {uuid}\n", encoding="utf-8")
        try:
            os.symlink(raw / "campaign.db", source / "raw-state-link")
        except (OSError, NotImplementedError):
            pass
        write_current_campaign_pointer(root, campaign_id)
        return campaign_id, token, uuid

    def test_packages_sanitized_partial_campaign_with_verified_archives(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            campaign_id, token, uuid = self._partial_campaign(root)

            tree, archive, archive_checksum = package_ci_evidence(root, [token])

            c2_log = tree / "scenarios/C2-memo-update/live-run.log"
            self.assertTrue(c2_log.is_file())
            self.assertIn("[REDACTED]", c2_log.read_text(encoding="utf-8"))
            self.assertIn("[REDACTED-UUID]", c2_log.read_text(encoding="utf-8"))
            delivered_bytes = b"".join(path.read_bytes() for path in tree.rglob("*") if path.is_file())
            self.assertNotIn(token.encode(), delivered_bytes)
            self.assertNotIn(b"synthetic-auth-value", delivered_bytes)
            self.assertIsNone(FULL_UUID_BYTES.search(delivered_bytes))
            self.assertFalse((tree / "accidental.db").exists())
            self.assertFalse((tree / "raw-state-link").exists())
            self.assertFalse(any(path.suffix == ".db" for path in tree.rglob("*")))
            self.assertNotIn(".campaign-state", {part for path in tree.rglob("*") for part in path.parts})

            manifest = json.loads((tree / "ci-evidence-manifest.json").read_text())
            self.assertEqual(manifest["schema_version"], 1)
            self.assertEqual(manifest["campaign_id"], campaign_id)
            self.assertEqual(manifest["branch"], "test-branch")
            self.assertEqual(manifest["commit"], "abcdef123456")
            self.assertEqual(manifest["derived_status"], "PARTIAL")
            self.assertEqual(manifest["completeness"]["status"], "PARTIAL")
            self.assertFalse(manifest["completeness"]["complete"])
            self.assertEqual(manifest["completeness"]["actual_receipt_count"], 2)
            self.assertEqual(manifest["completeness"]["expected_receipt_count"], 3)
            self.assertEqual(manifest["receipt_counts"], {"PASS": 2})
            self.assertEqual(manifest["cleanup_verification"], "PASS")
            self.assertEqual(manifest["source_type"], "qa-artifacts-copy")
            self.assertFalse(manifest["raw_state_included"])
            self.assertEqual(manifest["safety_scan"]["status"], "PASS")
            self.assertEqual(manifest["copy_safety"]["exclusion_rules"], {
                "forbidden_file_names": [
                    "ci-evidence-manifest.json", "config.yaml", "qa-sync.yaml",
                    "sha256sums", "tokens.txt",
                ],
                "raw_database_suffixes": [
                    ".db", ".db-wal", ".db-shm", ".sqlite", ".sqlite3",
                ],
                "raw_state_directory_names": [".campaign-state"],
                "symlinks": "skip-all",
            })

            checksum_lines = (tree / "SHA256SUMS").read_text().splitlines()
            checksums = dict(line.split("  ", 1) for line in checksum_lines)
            covered = {
                path.relative_to(tree).as_posix() for path in tree.rglob("*")
                if path.is_file() and path.name != "SHA256SUMS"
            }
            self.assertEqual(set(checksums.values()), covered)
            self.assertNotIn("SHA256SUMS", checksums.values())
            for digest, relative in checksums.items():
                self.assertEqual(digest, sha256(tree / relative))

            expected_zip_digest, zip_name = archive_checksum.read_text().strip().split("  ", 1)
            self.assertEqual(zip_name, archive.name)
            self.assertEqual(expected_zip_digest, sha256(archive))
            with zipfile.ZipFile(archive) as zipped:
                names = set(zipped.namelist())
            self.assertIn(f"{campaign_id}/scenarios/C2-memo-update/live-run.log", names)
            self.assertNotIn(f"{campaign_id}/SHA256SUMS/SHA256SUMS", names)

    def test_refuses_an_unsanitizable_binary_uuid_without_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            campaign_id, token, uuid = self._partial_campaign(root)
            source = root / "qa/artifacts" / campaign_id
            (source / "unsafe.bin").write_bytes(b"\xff\xfe" + uuid.encode())

            with self.assertRaises(EvidenceSafetyError):
                package_ci_evidence(root, [token])

            output = root / "build/qa-ci-evidence"
            self.assertFalse((output / campaign_id).exists())
            self.assertFalse((output / f"{campaign_id}.zip").exists())

    def test_refuses_non_utf8_binary_authorization_marker_without_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            campaign_id, token, _uuid = self._partial_campaign(root)
            source = root / "qa/artifacts" / campaign_id
            (source / "unsafe.bin").write_bytes(
                b"\xff\xfeAuThOrIzAtIoN: Bearer synthetic-binary-value"
            )

            with self.assertRaises(EvidenceSafetyError):
                package_ci_evidence(root, [token])

            output = root / "build/qa-ci-evidence"
            self.assertFalse((output / campaign_id).exists())
            self.assertFalse((output / f"{campaign_id}.zip").exists())
            self.assertFalse((output / f"{campaign_id}.zip.sha256").exists())

    def test_refuses_utf16le_authorization_bearer_value_without_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            campaign_id, token, _uuid = self._partial_campaign(root)
            source = root / "qa/artifacts" / campaign_id
            (source / "unsafe.bin").write_bytes(
                "AuThOrIzAtIoN: Bearer synthetic-wide-value".encode("utf-16-le")
            )

            with self.assertRaises(EvidenceSafetyError):
                package_ci_evidence(root, [token])

            output = root / "build/qa-ci-evidence"
            self.assertFalse((output / campaign_id).exists())
            self.assertFalse((output / f"{campaign_id}.zip").exists())
            self.assertFalse((output / f"{campaign_id}.zip.sha256").exists())

    def test_refuses_supplied_utf16be_token_without_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            campaign_id, token, _uuid = self._partial_campaign(root)
            source = root / "qa/artifacts" / campaign_id
            (source / "unsafe.bin").write_bytes(token.encode("utf-16-be"))

            with self.assertRaises(EvidenceSafetyError):
                package_ci_evidence(root, [token])

            output = root / "build/qa-ci-evidence"
            self.assertFalse((output / campaign_id).exists())
            self.assertFalse((output / f"{campaign_id}.zip").exists())
            self.assertFalse((output / f"{campaign_id}.zip.sha256").exists())


class CurrentCampaignPointerTest(unittest.TestCase):
    def test_pointer_is_deterministic_sanitized_json(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            campaign_id = "QA-20260830-120000-automated"
            pointer = write_current_campaign_pointer(root, campaign_id)
            value = json.loads(pointer.read_text(encoding="utf-8"))
            self.assertEqual(value, {
                "schema_version": 1,
                "campaign_id": campaign_id,
                "source_path": f"qa/artifacts/{campaign_id}",
            })
            text = pointer.read_text(encoding="utf-8")
            self.assertNotIn("token", text.lower())
            self.assertIsNone(FULL_UUID_BYTES.search(pointer.read_bytes()))

    def test_qa_automated_creation_writes_the_current_campaign_pointer(self):
        campaign = SimpleNamespace(
            campaign_id="QA-20260830-120000-automated",
            artifacts=Path("ignored-artifacts"),
            branch="test-branch",
            commit="abcdef123456",
        )
        with (
            patch.object(run_qa_suite, "Campaign", return_value=campaign) as campaign_factory,
            patch.object(run_qa_suite, "write_json"),
            patch.object(run_qa_suite, "write_current_campaign_pointer") as write_pointer,
        ):
            result = run_qa_suite.new_campaign("automated", ("A1-baseline",))

        self.assertIs(result, campaign)
        generated_campaign_id = campaign_factory.call_args.args[0]
        write_pointer.assert_called_once_with(run_qa_suite.REPO_ROOT, generated_campaign_id)


class CiEvidenceMetadataTest(unittest.TestCase):
    def _metadata(self, statuses, cleanup="PASS", **receipt_fields):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        selected_ids = list(statuses)
        (root / "environment.json").write_text(json.dumps({
            "branch": "test", "commit": "abc", "selected_scenario_ids": selected_ids,
        }), encoding="utf-8")
        for scenario_id, status in statuses.items():
            if status is None:
                continue
            receipt = root / "scenarios" / scenario_id / "receipt.json"
            receipt.parent.mkdir(parents=True)
            receipt.write_text(json.dumps({
                "scenario_id": scenario_id, "status": status,
                **receipt_fields.get(scenario_id, {}),
            }), encoding="utf-8")
        cleanup_path = root / "cleanup/cleanup-manifest.json"
        cleanup_path.parent.mkdir(parents=True)
        cleanup_path.write_text(json.dumps({"verification": cleanup}), encoding="utf-8")
        return _derive_campaign_metadata(root, "QA-test-automated")

    def test_selected_not_run_is_partial(self):
        metadata = self._metadata({"A1-baseline": "PASS", "C2-memo-update": "NOT_RUN"})
        self.assertEqual(metadata["derived_status"], "PARTIAL")
        self.assertFalse(metadata["completeness"]["complete"])
        self.assertEqual(metadata["completeness"]["incomplete_scenario_ids"], ["C2-memo-update"])

    def test_cleanup_fail_derives_fail(self):
        metadata = self._metadata({"A1-baseline": "PASS"}, cleanup="FAIL")
        self.assertEqual(metadata["derived_status"], "FAIL")
        self.assertFalse(metadata["completeness"]["complete"])
        self.assertTrue(metadata["completeness"]["selected_matrix_complete"])

    def test_cleanup_not_run_is_partial(self):
        metadata = self._metadata({"A1-baseline": "PASS"}, cleanup="NOT_RUN")
        self.assertEqual(metadata["derived_status"], "PARTIAL")
        self.assertFalse(metadata["completeness"]["complete"])

    def test_fail_receipt_can_be_complete_but_derives_fail(self):
        metadata = self._metadata({"A1-baseline": "PASS", "C2-memo-update": "FAIL"})
        self.assertEqual(metadata["derived_status"], "FAIL")
        self.assertTrue(metadata["completeness"]["complete"])

    def test_selected_blocked_or_execution_error_derives_fail(self):
        cases = (
            ({"A1-baseline": "BLOCKED"}, {}),
            ({"A1-baseline": "PASS"}, {
                "A1-baseline": {"execution_error": True},
            }),
        )
        for statuses, receipt_fields in cases:
            with self.subTest(statuses=statuses, receipt_fields=receipt_fields):
                metadata = self._metadata(statuses, **receipt_fields)
                self.assertEqual(metadata["derived_status"], "FAIL")

    def test_unrecognized_selected_status_cannot_derive_pass(self):
        metadata = self._metadata({"A1-baseline": "UNKNOWN"})
        self.assertEqual(metadata["derived_status"], "PARTIAL")

    def test_all_pass_with_cleanup_pass_is_complete_pass(self):
        metadata = self._metadata({"A1-baseline": "PASS", "C2-memo-update": "PASS"})
        self.assertEqual(metadata["derived_status"], "PASS")
        self.assertTrue(metadata["completeness"]["complete"])

    def test_extra_manual_receipts_do_not_make_selected_automated_matrix_partial(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "environment.json").write_text(json.dumps({
                "branch": "test", "commit": "abc",
                "selected_scenario_ids": ["A1-baseline", "A2-guard-rejection"],
            }), encoding="utf-8")
            statuses = {
                "A1-baseline": "PASS",
                "A2-guard-rejection": "PASS",
                "A8-money-movement": "NOT_RUN",
            }
            for scenario_id, status in statuses.items():
                receipt = root / "scenarios" / scenario_id / "receipt.json"
                receipt.parent.mkdir(parents=True)
                receipt.write_text(json.dumps({
                    "scenario_id": scenario_id, "status": status,
                }), encoding="utf-8")
            cleanup = root / "cleanup/cleanup-manifest.json"
            cleanup.parent.mkdir(parents=True)
            cleanup.write_text(json.dumps({"verification": "PASS"}), encoding="utf-8")

            metadata = _derive_campaign_metadata(root, "QA-test-automated")

            self.assertEqual(metadata["derived_status"], "PASS")
            self.assertTrue(metadata["completeness"]["complete"])
            self.assertEqual(metadata["completeness"]["actual_receipt_count"], 2)
            self.assertEqual(metadata["receipt_counts"], {"NOT_RUN": 1, "PASS": 2})


if __name__ == "__main__":
    unittest.main()
