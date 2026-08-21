# JMeter Runbook

## Scope

`jmeter/novel-agent-load.jmx` is a safe starting point for local concurrency checks.
The health group is enabled by default. RAG search, writing memory, and generation groups
are disabled so an accidental run does not call Milvus or consume model quota.

## Preconditions

- Start the Spring Boot application on the target host.
- Set `NOVEL_AGENT_ADMIN_API_KEY` when invoking Milvus admin routes or import finalization.
- Set `NOVEL_AGENT_FILE_ALLOWED_ROOTS` to semicolon-separated directories containing import and novel files.
- Verify `GET /api/v1/novel/health` before running a load test.
- Make sure the selected `novelId` has loaded Milvus collections before enabling RAG groups.
- Run JMeter in non-GUI mode for measurements.

## Health Baseline

The following command runs the default health group with 50 threads and 20 loops:

```powershell
jmeter -n -t jmeter/novel-agent-load.jmx `
  -Jhost=localhost -Jport=8080 `
  -JhealthThreads=50 -JhealthLoops=20 -JhealthRampSeconds=30 `
  -l artifacts/jmeter-health.jtl `
  -e -o artifacts/jmeter-health-report
```

This measures the HTTP and Spring Boot baseline only. It does not prove MySQL or Milvus
capacity.

## RAG Retrieval

Enable the `RAG search - enable deliberately` thread group in the JMeter GUI or by editing
its `enabled` attribute in the test plan. Start with low concurrency:

```powershell
jmeter -n -t jmeter/novel-agent-load.jmx `
  -Jhost=localhost -Jport=8080 -JnovelId=0 `
  -Jquery='continue scene' -JtopK=5 -JcurrentChapterNum=1 `
  -JragThreads=5 -JragLoops=3 -JragRampSeconds=30 `
  -l artifacts/jmeter-rag.jtl `
  -e -o artifacts/jmeter-rag-report
```

Increase concurrency in stages such as `1 -> 5 -> 10 -> 20`. Record whether queries are
repeated or unique: repeated queries measure the local embedding cache, while unique queries
measure the embedding provider and Milvus path.

## Writing Memory and Generation

The writing-memory group exercises the combined retrieval path and should be tested after
search latency is understood. The generation group remains disabled by default because it
calls the configured model provider and consumes tokens. Use a mock provider profile for
application-capacity tests, and reserve real-provider runs for small smoke tests such as
`1 -> 2 -> 5` threads.

## Measurements

Capture these values for every run:

- throughput, error percentage, and HTTP status distribution
- P50, P95, and P99 latency
- JVM CPU, heap, GC pauses, and active request threads
- Hikari active, idle, and pending connections
- Embedding and DeepSeek timeout, retry, and rate-limit counts
- Milvus search/insert latency and collection load state
- local task executor activity and rejected-task logs during import/finalize tests

Do not treat a JMeter run where JMeter and the application share the same CPU as a production
capacity claim. Use a separate load-generator machine for representative results.
