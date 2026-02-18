# Shared Resources

Shared static assets used by the sample applications.

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

See [Observability](https://github.com/Atmosphere/atmosphere/wiki/Getting-Started-with-Spring-Boot#observability) in the wiki for setup details.
