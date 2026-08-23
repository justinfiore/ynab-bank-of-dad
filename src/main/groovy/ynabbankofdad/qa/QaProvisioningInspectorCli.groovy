package ynabbankofdad.qa

import java.nio.file.Path

class QaProvisioningInspectorCli {
    static void main(String[] args) {
        Map<String, String> options = parseArgs(args)
        QaEvidenceConfig config = QaEvidenceConfig.load(Path.of(options.config))
        def discovery = new QaSnapshotProvisioningDiscovery(Path.of(options.discovery))
        Path artifact = Path.of(options.artifact)
        Map result = new QaProvisioningInspector(discovery).inspect(config.provisioning, artifact)
        println "Wrote read-only QA provisioning evidence to ${artifact} (complete=${result.complete})"
    }

    private static Map<String, String> parseArgs(String[] args) {
        if (args == null || args.length != 6 || args.toList().collate(2).any { pair ->
            pair.size() != 2 || !['--config', '--discovery', '--artifact'].contains(pair[0]) || !pair[1]
        }) {
            throw new IllegalArgumentException(
                'Usage: --config <qa-yaml> --discovery <offline-json> --artifact <output-json>'
            )
        }
        Map<String, String> values = [:]
        args.toList().collate(2).each { pair -> values[(pair[0] as String).substring(2)] = pair[1] as String }
        if (values.keySet() != ['config', 'discovery', 'artifact'] as Set) {
            throw new IllegalArgumentException(
                'Usage: --config <qa-yaml> --discovery <offline-json> --artifact <output-json>'
            )
        }
        values
    }
}
