# QUICK START — YNAB Bank of Dad

Use these steps to configure `config.yaml` safely and run a dry run before any live posting.

For the full field-by-field configuration reference, Simple vs. Advanced account modeling, and inline YAML examples, see [CONFIGURATION.md](CONFIGURATION.md).

## 1. Install prerequisites

1. Install Java JDK 25.
2. Set `JAVA_HOME` to that JDK.
3. Export your `YNAB_ACCESS_TOKEN`.

Linux/macOS example:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export YNAB_ACCESS_TOKEN='your-token-here'
```

## 2. Create your local config

1. Copy the example file:

```bash
cp config.yaml.example config.yaml
```

2. Read [CONFIGURATION.md](CONFIGURATION.md) before editing if you need help choosing between Simple and Advanced account modeling.
3. Edit `config.yaml` and replace the example values with your real setup.
4. Update at least these fields:
   - `budgetName`
   - `allowanceEscrowAccountName`
   - `allowanceCategoryName`
   - your Simple-account settings and/or Advanced-account settings
   - any category names, memo text, or rate tables that differ in your budget

`config.yaml` is gitignored and is intended for your personal values.

## 3. Run tests

```bash
./gradlew testAll
```

## 4. Do a safe dry run

Default config path:

```bash
./gradlew run --args='--dry-run --config config.yaml'
```

Specific date:

```bash
./gradlew run --args='--dry-run --date 2025-08-03 --config config.yaml'
```

## 5. Use the helper scripts if you prefer

Windows weekly dry run:

```bat
RunWeeklyAllowance.bat
```

Windows specific-date dry run:

```bat
RunSpecificAllowance.bat 2025-08-03 config.yaml
```

Linux/macOS weekly dry run:

```bash
./run-weekly-allowance.sh
```

Linux/macOS specific-date dry run:

```bash
./run-specific-allowance.sh 2025-08-03 config.yaml
```

## 6. Review the dry-run output

Before any live run, verify:

1. the correct budget was selected
2. the correct account and category names were found
3. the generated transactions match your expectations
4. your configured rates and account mappings are correct

## 7. Optional live run

Only after the dry run looks correct:

```bash
./gradlew run --args='--config config.yaml'
```

Or for a specific date:

```bash
./gradlew run --args='--date 2025-08-03 --config config.yaml'
```
