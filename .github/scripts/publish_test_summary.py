#!/usr/bin/env python3
import html
import os
import pathlib
import xml.etree.ElementTree as ET


def collect_suites(result_root: pathlib.Path):
    suites = []
    for xml_path in sorted(result_root.rglob('TEST-*.xml')):
        try:
            root = ET.parse(xml_path).getroot()
        except ET.ParseError as exc:
            suites.append({
                'suite': xml_path.stem,
                'task': xml_path.parent.name,
                'tests': 0,
                'failures': 1,
                'errors': 0,
                'skipped': 0,
                'time': '',
                'failed_cases': [f'Failed to parse {xml_path}: {exc}'],
            })
            continue

        suite_name = root.attrib.get('name', xml_path.stem)
        task_name = xml_path.parent.name
        tests = int(root.attrib.get('tests', '0'))
        failures = int(root.attrib.get('failures', '0'))
        errors = int(root.attrib.get('errors', '0'))
        skipped = int(root.attrib.get('skipped', '0'))
        time = root.attrib.get('time', '')
        failed_cases = []

        for testcase in root.findall('testcase'):
            case_name = testcase.attrib.get('name', 'unknown test')
            classname = testcase.attrib.get('classname', suite_name)
            for tag in ('failure', 'error'):
                node = testcase.find(tag)
                if node is not None:
                    message = (node.attrib.get('message') or (node.text or '')).strip()
                    if len(message) > 300:
                        message = message[:297] + '...'
                    failed_cases.append(f'{classname} :: {case_name} — {message}')
                    break

        suites.append({
            'suite': suite_name,
            'task': task_name,
            'tests': tests,
            'failures': failures,
            'errors': errors,
            'skipped': skipped,
            'time': time,
            'failed_cases': failed_cases,
        })
    return suites


def build_summary(suites):
    total_tests = sum(s['tests'] for s in suites)
    total_failures = sum(s['failures'] for s in suites)
    total_errors = sum(s['errors'] for s in suites)
    total_skipped = sum(s['skipped'] for s in suites)
    executed = len(suites)

    lines = []
    lines.append('## Test results')
    lines.append('')
    if not suites:
        lines.append('No JUnit XML files were found under `build/test-results/`.')
        return '\n'.join(lines) + '\n'

    status = '✅ PASS' if (total_failures == 0 and total_errors == 0) else '❌ FAIL'
    lines.append(
        f'{status} — {total_tests} tests across {executed} suite file(s); '
        f'{total_failures} failures, {total_errors} errors, {total_skipped} skipped.'
    )
    lines.append('')
    lines.append('| Task | Suite | Tests | Failures | Errors | Skipped | Time (s) |')
    lines.append('|---|---|---:|---:|---:|---:|---:|')
    for suite in suites:
        lines.append(
            f"| {suite['task']} | {suite['suite']} | {suite['tests']} | {suite['failures']} | "
            f"{suite['errors']} | {suite['skipped']} | {suite['time'] or ''} |"
        )

    failing = [s for s in suites if s['failed_cases']]
    if failing:
        lines.append('')
        lines.append('### Failed tests')
        lines.append('')
        for suite in failing:
            lines.append(
                f"<details><summary>{html.escape(suite['task'])} / {html.escape(suite['suite'])}</summary>"
            )
            lines.append('')
            for case in suite['failed_cases']:
                lines.append(f'- `{html.escape(case)}`')
            lines.append('')
            lines.append('</details>')

    return '\n'.join(lines) + '\n'


def main():
    summary_target = os.environ.get('GITHUB_STEP_SUMMARY')
    if not summary_target:
        raise SystemExit('GITHUB_STEP_SUMMARY is not set')

    result_root = pathlib.Path('build/test-results')
    suites = collect_suites(result_root)
    summary = build_summary(suites)
    pathlib.Path(summary_target).write_text(summary)


if __name__ == '__main__':
    main()
