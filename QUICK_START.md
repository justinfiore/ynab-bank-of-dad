# QUICK START — YNABBankOfDad

This guide is the shortest safe path from clone to first evaluation run.

## 1. Know what this tool assumes

Before you run anything live, understand that the current repo assumes:
- a YNAB budget named `Fiores`
- an account named `Allowance Escrow`
- a category named `Allowance`
- category/account naming patterns used by the current family workflow
- hard-coded kid/account/rate rules in the source

If your budget does not match those assumptions, adapt the code before using it for real transactions.

## 2. Install prerequisites

You need:
- Java JDK 25
- `JAVA_HOME` set to that JDK
- a YNAB personal access token

Example on Ubuntu / Linux Mint:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-25-jdk
```

Typical Linux environment setup:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

Verify:

```bash
java -version
javac -version
echo "$JAVA_HOME"
```

## 3. Clone the repo

```bash
git clone git@github.com:justinfiore/ynab-bank-of-dad.git
cd ynab-bank-of-dad
```

## 4. Set your YNAB token

Linux/macOS:

```bash
export YNAB_ACCESS_TOKEN='your-token-here'
```

PowerShell:

```powershell
$env:YNAB_ACCESS_TOKEN = 'your-token-here'
```

## 5. Run tests first (recommended)

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew test
```

## 6. Do a dry run

Always start here:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew run --args='--dry-run'
```

Or for a specific date:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew run --args='--dry-run --date 2025-08-03'
```

## 7. Review the output before any live run

Before dropping `--dry-run`, verify:
- the correct budget was selected
- expected categories/accounts were found
- generated transactions make sense
- your naming matches the repo's assumptions
- you are comfortable with the current hard-coded rules

## 8. Optional: build a distributable install

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew installDist
```

## 9. Do not do this first

Do **not** start with a live posting run.

Because this tool can create real YNAB transactions, a non-dry-run execution should happen only after:
- reviewing the repo assumptions
- checking your budget/account/category names
- inspecting dry-run output carefully

## 10. Where to learn more

- Overview and current limitations: [README.md](README.md)
- License terms: [LICENSE](LICENSE)
- CI runs and artifacts: https://github.com/justinfiore/ynab-bank-of-dad/actions
