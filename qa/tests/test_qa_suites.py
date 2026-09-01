import sys
import json
import tempfile
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import AUTOMATED_SCENARIO_IDS, MANUAL_SCENARIO_IDS, SCENARIOS
from run_qa_suite import SMOKE_SCENARIO_IDS, load_smoke_selection


class QaSuitePartitionTest(unittest.TestCase):
    def test_every_official_scenario_is_automated_or_manual_not_both(self):
        official = {item[0] for item in SCENARIOS}
        automated = set(AUTOMATED_SCENARIO_IDS)
        manual = set(MANUAL_SCENARIO_IDS)
        self.assertEqual(official, automated | manual)
        self.assertFalse(automated & manual)

    def test_manual_suite_is_only_ui_gated_scenarios(self):
        self.assertEqual(
            MANUAL_SCENARIO_IDS,
            ("A8-money-movement", "B5-live-movement", "C8-split-component-removed"),
        )

    def test_gradle_default_verification_does_not_invoke_live_qa_suites(self):
        build = (QA_ROOT.parent / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("qaAutomated", build)
        self.assertIn("qaManual", build)
        self.assertNotIn("dependsOn(qaAutomated)", build)
        self.assertNotIn("dependsOn(qaManual)", build)
        self.assertNotIn("dependsOn(tasks.named('qaAutomated'))", build)
        self.assertNotIn("dependsOn(tasks.named('qaManual'))", build)

    def test_smoke_is_explicit_machine_readable_and_does_not_shrink_full_suite(self):
        selection = QA_ROOT / "fixtures/automated-smoke-scenarios.json"
        raw = json.loads(selection.read_text(encoding="utf-8"))
        self.assertEqual(load_smoke_selection(selection), SMOKE_SCENARIO_IDS)
        self.assertEqual(tuple(raw["scenario_ids"]), SMOKE_SCENARIO_IDS)
        self.assertTrue(set(SMOKE_SCENARIO_IDS) < set(AUTOMATED_SCENARIO_IDS))
        self.assertIn("B1-live-create", SMOKE_SCENARIO_IDS)
        self.assertIn("B2-live-replay", SMOKE_SCENARIO_IDS)

        build = (QA_ROOT.parent / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("tasks.register('qaAutomated'", build)
        self.assertIn("tasks.register('qaAutomatedSmoke'", build)
        self.assertIn("automated-smoke-scenarios.json", build)

    def test_smoke_selection_rejects_an_unreviewed_subset(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        selection = Path(directory.name) / "selection.json"
        selection.write_text(json.dumps({
            "schema_version": 1,
            "suite": "automated-smoke",
            "scenario_ids": ["A3-config-smoke"],
        }), encoding="utf-8")
        with self.assertRaises(SystemExit):
            load_smoke_selection(selection)

    def test_labeled_workflow_invokes_full_automated_suite(self):
        workflow = (QA_ROOT.parent / ".github/workflows/end-to-end-qa.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("run: ./gradlew --no-daemon qaAutomated -PqaConfirmLive=YES", workflow)
        self.assertNotIn("run: ./gradlew --no-daemon qaAutomatedSmoke", workflow)
