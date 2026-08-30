import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
REPORTER_ACTION = (
    "dorny/test-reporter@df6247429542221bc30d46a036ee47af1102c451 # v2"
)
SECRET_KEYS = (
    "PARENT_ACCESS_TOKEN",
    "JORSTEN_JR_ACCESS_TOKEN",
    "BORSTEN_ACCESS_TOKEN",
    "THORSTEN_ACCESS_TOKEN",
    "QA_PARENT_PLAN_ID",
    "QA_JORSTEN_JR_PLAN_ID",
    "QA_BORSTEN_PLAN_ID",
    "QA_THORSTEN_PLAN_ID",
    "QA_CONFIRM_LIVE_MUTATIONS",
)


def workflow_text(name: str) -> str:
    return (REPO_ROOT / ".github/workflows" / name).read_text(encoding="utf-8")


class TestResultsPublicationWorkflowContractTest(unittest.TestCase):
    def assert_common_publication(self, text: str, check_name: str) -> None:
        self.assertIn("permissions:\n  contents: read\n  checks: write", text)
        self.assertEqual(text.count(REPORTER_ACTION), 1)
        self.assertIn(f"name: {check_name}", text)
        self.assertIn("path: build/test-results/**/*.xml", text)
        self.assertIn("reporter: java-junit", text)
        self.assertIn("fail-on-error: false", text)
        self.assertIn("- name: Upload JUnit XML test results\n        if: always()", text)
        self.assertIn(
            "- name: Publish test result summary to job summary\n"
            "        if: always()\n"
            "        run: python3 .github/scripts/publish_test_summary.py",
            text,
        )

    def test_ci_publishes_junit_results_as_a_dedicated_check(self):
        text = workflow_text("ci.yml")
        self.assert_common_publication(text, "CI JUnit Test Results")
        self.assertIn(
            "if: always() && (github.event_name != 'pull_request' || "
            "github.event.pull_request.head.repo.full_name == github.repository)",
            text,
        )
        for key in SECRET_KEYS:
            self.assertNotIn(key, text)

    def test_smoke_qa_publishes_junit_results_as_a_distinct_check(self):
        text = workflow_text("end-to-end-qa.yml")
        self.assert_common_publication(text, "Disposable-plan Smoke QA JUnit Results")
        self.assertIn("if: always()", text)
        self.assertIn("qaAutomatedSmoke", text)
        self.assertNotIn("run: ./gradlew --no-daemon qaAutomated -PqaConfirmLive=YES", text)
        job_prefix, smoke_step = text.split("- name: Run bounded automated disposable-plan smoke QA", 1)
        self.assertTrue(job_prefix.strip())
        for key in SECRET_KEYS:
            self.assertNotIn(key, job_prefix)
            self.assertIn(key, smoke_step.split("- name: Upload JUnit XML test results", 1)[0])
        reporter_block = smoke_step.split("- name: Publish JUnit results as a GitHub Check", 1)[1]
        for key in SECRET_KEYS:
            self.assertNotIn(key, reporter_block)


if __name__ == "__main__":
    unittest.main()
