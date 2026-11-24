# Scalability hypotheses

MVP: 100 RPS, P95<=150ms (not measured). Scale stateless application horizontally after measuring DB latency, lock contention and connection pool saturation. Cache only immutable versioned rule snapshots, not unversioned current rule lookups. At 1k/10k RPS explore partitioning, batching, write capacity and hot tenant isolation. All estimates require k6 reports and a documented test environment.
