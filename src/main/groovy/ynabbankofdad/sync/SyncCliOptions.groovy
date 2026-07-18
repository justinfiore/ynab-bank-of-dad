package ynabbankofdad.sync

class SyncCliOptions {
    boolean help = false
    boolean dryRun = false
    String configPath = ParentChildBudgetSyncer.DEFAULT_CONFIG_PATH
    String syncStateDbPath
    int maxCycles = ParentChildBudgetSyncer.DEFAULT_MAX_CYCLES

    static SyncCliOptions parse(String[] args) {
        SyncCliOptions options = new SyncCliOptions()
        int i = 0
        while (i < args.length) {
            String arg = args[i]
            switch (arg) {
                case '--help':
                case '-h':
                    options.help = true
                    i++
                    break
                case '--dry-run':
                    options.dryRun = true
                    i++
                    break
                case '--config':
                case '-c':
                    options.configPath = requireValue(args, i, arg)
                    i += 2
                    break
                case '--sync-state-db-path':
                    options.syncStateDbPath = requireValue(args, i, arg)
                    i += 2
                    break
                case '--max-cycles':
                    String maxCyclesValue = requireValue(args, i, arg)
                    try {
                        options.maxCycles = Integer.parseInt(maxCyclesValue)
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("--max-cycles must be a positive integer: ${maxCyclesValue}", ex)
                    }
                    if (options.maxCycles <= 0) {
                        throw new IllegalArgumentException('--max-cycles must be positive')
                    }
                    i += 2
                    break
                default:
                    throw new IllegalArgumentException("Unknown argument: ${arg}")
            }
        }
        options
    }

    static void printUsage() {
        println '''Usage: ParentChildBudgetSyncer [options]
  -c, --config PATH             Path to config YAML file (default: config.yaml)
      --sync-state-db-path PATH Override SQLite sync state path
      --dry-run                 Read and plan only; do not post or persist state
      --max-cycles N            Run N polling cycles, then exit (default: continuous)
  -h, --help                    Show this help'''
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for ${flag}")
        }
        String value = args[index + 1]
        if (!value?.trim()) {
            throw new IllegalArgumentException("Missing value for ${flag}")
        }
        value
    }
}
