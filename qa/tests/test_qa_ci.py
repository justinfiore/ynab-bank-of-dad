import os
import sys
import tempfile
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import AUTOMATED_SCENARIO_IDS
from lib.junit_writer import write_automated_junit
from lib.qa_config import QaConfigBlocked, expand_env_value, load_budget_identities, load_tokens


class TokenLoadingTest(unittest.TestCase):
    def test_env_only_tokens_are_enough(self):
        env = {
            "PARENT_ACCESS_TOKEN": "parent-token",
            "JORSTEN_JR_ACCESS_TOKEN": "jr-token",
            "BORSTEN_ACCESS_TOKEN": "borsten-token",
            "THORSTEN_ACCESS_TOKEN": "thorsten-token",
        }
        loaded = load_tokens(env, token_file=Path("/tmp/does-not-exist-tokens.txt"))
        self.assertEqual(loaded["PARENT_ACCESS_TOKEN"], "parent-token")

    def test_missing_env_blocks_and_does_not_create_file(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        missing = Path(directory.name) / "tokens.txt"
        with self.assertRaises(QaConfigBlocked):
            load_tokens({}, token_file=missing)
        self.assertFalse(missing.exists())

    def test_file_fills_only_unset_env(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "tokens.txt"
        path.write_text(
            "PARENT_ACCESS_TOKEN=from-file\nJORSTEN_JR_ACCESS_TOKEN=jr-file\n"
            "BORSTEN_ACCESS_TOKEN=borsten-file\nTHORSTEN_ACCESS_TOKEN=thorsten-file\n",
            encoding="utf-8",
        )
        env = {"PARENT_ACCESS_TOKEN": "from-env"}
        loaded = load_tokens(env, token_file=path)
        self.assertEqual(loaded["PARENT_ACCESS_TOKEN"], "from-env")
        self.assertEqual(loaded["JORSTEN_JR_ACCESS_TOKEN"], "jr-file")


class PlanIdExpansionTest(unittest.TestCase):
    def test_placeholder_resolves_in_memory(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "qa-sync.yaml"
        path.write_text(
            """
budgets:
  parent:
    displayName: "Jorsten's Plan"
    fullId: ${QA_PARENT_PLAN_ID}
  children:
    - displayName: "Jorsten Jr's Plan"
      fullId: $QA_JORSTEN_JR_PLAN_ID
    - displayName: "Borsten's Plan"
      fullId: ${QA_BORSTEN_PLAN_ID}
    - displayName: "Thorsten's Plan"
      fullId: ${QA_THORSTEN_PLAN_ID}
""",
            encoding="utf-8",
        )
        env = {
            "QA_PARENT_PLAN_ID": "10000000-0000-0000-0000-000000000001",
            "QA_JORSTEN_JR_PLAN_ID": "10000000-0000-0000-0000-000000000002",
            "QA_BORSTEN_PLAN_ID": "10000000-0000-0000-0000-000000000003",
            "QA_THORSTEN_PLAN_ID": "10000000-0000-0000-0000-000000000004",
        }
        identities = load_budget_identities(path, env)
        self.assertEqual(identities["Jorsten's Plan"].plan_id, env["QA_PARENT_PLAN_ID"])
        self.assertNotIn(env["QA_PARENT_PLAN_ID"], path.read_text(encoding="utf-8"))

    def test_unresolved_placeholder_blocks(self):
        with self.assertRaises(QaConfigBlocked):
            expand_env_value("${QA_PARENT_PLAN_ID}", {})


    def test_workflow_is_gated_to_labeled_maintainer_prs(self):
        text = (QA_ROOT.parent / ".github/workflows/end-to-end-qa.yml").read_text(encoding="utf-8")
        self.assertIn("end-to-end-qa", text)
        self.assertIn("justinfiore", text)
        self.assertIn("jhorgenson", text)
        self.assertIn("head.repo.full_name == github.repository", text)
        self.assertIn("qaAutomated", text)
        self.assertNotIn("qaManual", text)
        self.assertNotIn("tokens.txt", text)


class JunitWriterTest(unittest.TestCase):
    def test_fail_and_blocked_are_junit_failures(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        output = Path(directory.name) / "TEST-qaAutomated.xml"
        receipts = [
            {"scenario_id": scenario_id, "status": "PASS", "reason": "ok"}
            for scenario_id in AUTOMATED_SCENARIO_IDS
        ]
        receipts[0]["status"] = "FAIL"
        receipts[0]["reason"] = "isolation failed"
        failures = write_automated_junit(receipts, output, automated_ids=AUTOMATED_SCENARIO_IDS)
        text = output.read_text(encoding="utf-8")
        self.assertEqual(failures, 1)
        self.assertIn('failures="1"', text)
        self.assertIn(receipts[0]["scenario_id"], text)
        self.assertNotIn("A8-money-movement", text)
        self.assertNotIn("C8-split-component-removed", text)

    def test_missing_or_crash_receipts_are_junit_failures(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        output = Path(directory.name) / "TEST-qaAutomated.xml"
        failures = write_automated_junit([], output, automated_ids=AUTOMATED_SCENARIO_IDS)
        text = output.read_text(encoding="utf-8")
        self.assertEqual(failures, len(AUTOMATED_SCENARIO_IDS))
        self.assertIn("<failure", text)
        self.assertIn(AUTOMATED_SCENARIO_IDS[0], text)
        self.assertNotIn("A8-money-movement", text)
