# Shared Resources

Shared static assets used by the sample applications.

> **Not a Maven module.** This directory does not contain a `pom.xml` and is not listed in the root reactor. Do not try to build it with `./mvnw -pl samples/shared-resources` — just copy the files you need into your own sample.

## Contents

### css/

Common stylesheets shared across sample UIs.

### grafana/

**`atmosphere-dashboard.json`** — A ready-to-import Grafana dashboard for monitoring Atmosphere applications. Visualizes metrics published by `AtmosphereMetrics` via Micrometer/Prometheus:

- Active connections and broadcasters
- Message throughput (broadcast + delivered)
- Room membership and presence
- Broadcast latency percentiles

**To use:** Import the JSON file into Grafana (`Dashboards → Import → Upload JSON file`). Requires a Prometheus data source scraping your Atmosphere application's `/actuator/prometheus` endpoint.

See [Observability](https://atmosphere.github.io/docs/reference/observability/) for setup details.
