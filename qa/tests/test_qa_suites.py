import sys
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import AUTOMATED_SCENARIO_IDS, MANUAL_SCENARIO_IDS, SCENARIOS


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
