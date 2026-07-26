<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import {
  METER_GROUPS, formatStat, headline, meterFromPrometheus, parsePrometheus,
  percentileMeters, statsFromActuator, tagValues,
  type ActuatorMeter, type Meter, type MeterGroup, type MeterSpec, type MeterStats,
} from '../lib/metrics'

/**
 * Observability tab — the `atmosphere.*` Micrometer meters, decomposed by tag
 * and rendered under the statistic each meter type actually carries.
 *
 * The metrics plane is described by /api/console/info: `metricsPath` plus
 * `metricsFormat` ("actuator" JSON on Spring Boot, Prometheus text on Quarkus).
 * Both are reported only when the server confirmed the plane genuinely
 * responds, so this component never probes a URL into a 404.
 *
 * Meters a deployment does not publish are omitted rather than shown as zero:
 * an app with no AI traffic has no `atmosphere.ai.*` series at all, and a
 * rendered "0 tokens" would be indistinguishable from missing instrumentation.
 */

const props = defineProps<{
  active: boolean
  /** Actuator health endpoint; absent when the host has no health plane. */
  healthPath?: string
  /** Metrics endpoint confirmed live by the server, e.g. /actuator/metrics. */
  metricsPath?: string
  metricsFormat?: 'actuator' | 'prometheus'
}>()

interface PanelRow {
  key: string
  label: string
  stats: MeterStats
}

interface Panel {
  spec: MeterSpec
  rows: PanelRow[]
  models: string[]
}

interface GroupView {
  id: string
  title: string
  panels: Panel[]
}

const health = ref<string>('unknown')
const groups = ref<GroupView[]>([])
const error = ref<string | null>(null)
const loaded = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

/** Tag-value fan-out is bounded — a high-cardinality tag must not fan out. */
const MAX_SPLIT_VALUES = 8

async function getJson(url: string): Promise<unknown | null> {
  const res = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!res.ok) return null
  return res.json()
}

/**
 * Build one meter from the actuator, decomposing it into a series per value of
 * its split tag. Actuator answers per-tag queries via `?tag=key:value`, so the
 * decomposition costs one request per value; the fan-out is capped.
 */
async function actuatorMeter(base: string, spec: MeterSpec, index: string[]): Promise<Meter | null> {
  if (!index.includes(spec.name)) return null
  const root = await getJson(`${base}/${encodeURIComponent(spec.name)}`)
  if (!root) return null
  const doc = root as ActuatorMeter
  const models = tagValues(doc, 'model')
  const splitValues = spec.splitBy ? tagValues(doc, spec.splitBy).slice(0, MAX_SPLIT_VALUES) : []

  const series: Meter['series'] = []
  if (spec.splitBy && splitValues.length > 0) {
    for (const value of splitValues) {
      const tagged = await getJson(
        `${base}/${encodeURIComponent(spec.name)}`
        + `?tag=${encodeURIComponent(`${spec.splitBy}:${value}`)}`)
      if (!tagged) continue
      series.push({
        tags: { [spec.splitBy]: value },
        stats: statsFromActuator(tagged as ActuatorMeter),
      })
    }
  } else {
    series.push({ tags: {}, stats: statsFromActuator(doc) })
  }

  // Percentiles reach the actuator format only as their own meters — a Timer's
  // measure() output carries COUNT/TOTAL_TIME/MAX and never a percentile
  // snapshot. Fold in whichever companions exist; absent, the column is dropped.
  for (const name of percentileMeters(index, spec.name)) {
    const companion = await getJson(`${base}/${encodeURIComponent(name)}`)
    if (!companion || series.length === 0) continue
    const stats = statsFromActuator(companion as ActuatorMeter)
    const value = stats.value ?? stats.max
    if (typeof value !== 'number') continue
    const key = name.slice(spec.name.length + 1)
    series[0].stats.quantiles = { ...series[0].stats.quantiles, [key]: value }
  }
  return { name: spec.name, kind: spec.kind, series, models }
}

/** Collapse a meter's series into display rows under its split tag. */
function toPanel(spec: MeterSpec, meter: Meter): Panel | null {
  const rows: PanelRow[] = []
  for (const s of meter.series) {
    const hasData = headline(spec.kind, s.stats) !== null || typeof s.stats.count === 'number'
    if (!hasData) continue
    const label = spec.splitBy ? (s.tags[spec.splitBy] ?? 'untagged') : 'all'
    rows.push({ key: `${label}:${JSON.stringify(s.tags)}`, label, stats: s.stats })
  }
  if (rows.length === 0) return null
  rows.sort((a, b) => a.label.localeCompare(b.label))
  return { spec, rows, models: meter.models ?? [] }
}

async function buildPanels(
  group: MeterGroup, resolve: (spec: MeterSpec) => Promise<Meter | null>,
): Promise<Panel[]> {
  const panels: Panel[] = []
  for (const spec of group.meters) {
    const meter = await resolve(spec)
    if (!meter) continue
    const panel = toPanel(spec, meter)
    if (panel) panels.push(panel)
  }
  return panels
}

async function loadActuator(base: string): Promise<GroupView[]> {
  const listing = await getJson(base)
  const index = ((listing as { names?: unknown })?.names as string[] | undefined ?? [])
    .filter(n => typeof n === 'string')
  const views: GroupView[] = []
  for (const group of METER_GROUPS) {
    const panels = await buildPanels(group, spec => actuatorMeter(base, spec, index))
    if (panels.length > 0) views.push({ id: group.id, title: group.title, panels })
  }
  return views
}

async function loadPrometheus(path: string): Promise<GroupView[]> {
  const res = await fetch(path, { headers: { Accept: 'text/plain' } })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const samples = parsePrometheus(await res.text())
  const views: GroupView[] = []
  for (const group of METER_GROUPS) {
    const panels = await buildPanels(group,
      spec => Promise.resolve(meterFromPrometheus(samples, spec.name, spec.kind)))
    if (panels.length > 0) views.push({ id: group.id, title: group.title, panels })
  }
  return views
}

async function load() {
  try {
    if (props.healthPath) {
      const data = await getJson(props.healthPath)
      const status = (data as { status?: unknown })?.status
      health.value = typeof status === 'string' ? status : 'unknown'
    }
    if (props.metricsPath) {
      groups.value = props.metricsFormat === 'prometheus'
        ? await loadPrometheus(props.metricsPath)
        : await loadActuator(props.metricsPath)
    }
    error.value = null
  } catch (e) {
    error.value = String(e)
  } finally {
    loaded.value = true
  }
}

/** Display label for a quantile, from either wire format's key spelling. */
function quantileLabel(key: string): string {
  if (/^p\d+$/i.test(key)) return key.toLowerCase()
  const asNumber = Number(key)
  return Number.isFinite(asNumber) ? `p${Math.round(asNumber * 100)}` : key
}

function quantileEntries(stats: MeterStats): Array<{ label: string; value: number }> {
  return Object.entries(stats.quantiles ?? {})
    .map(([k, v]) => ({ label: quantileLabel(k), value: v }))
    .sort((a, b) => a.label.localeCompare(b.label))
}

/** Timers and summaries carry max/count columns alongside their mean. */
function hasSpread(spec: MeterSpec): boolean {
  return spec.kind === 'timer' || spec.kind === 'summary'
}

watch(() => props.active, (active) => {
  if (active) {
    load()
    timer = setInterval(load, 5000)
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
}, { immediate: true })

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="obs-view" data-testid="observability-view">
    <div v-if="healthPath" class="obs-health" data-testid="health-status">
      <span class="obs-label">Health</span>
      <span :class="['obs-status', health === 'UP' ? 'up' : 'down']">{{ health }}</span>
    </div>
    <p v-if="error" class="obs-error" data-testid="metrics-error">
      Metrics unavailable: {{ error }}
    </p>
    <template v-else>
      <section v-for="group in groups" :key="group.id" class="obs-group"
               :data-testid="`metric-group-${group.id}`">
        <h3 class="obs-group-title">{{ group.title }}</h3>
        <article v-for="panel in group.panels" :key="panel.spec.name" class="obs-panel"
                 :data-testid="`meter-${panel.spec.name}`">
          <header class="obs-panel-head">
            <span class="obs-panel-label">{{ panel.spec.label }}</span>
            <code class="obs-panel-name">{{ panel.spec.name }}</code>
            <span v-for="model in panel.models" :key="model" class="obs-chip">{{ model }}</span>
          </header>
          <p v-if="panel.spec.note" class="obs-note">{{ panel.spec.note }}</p>
          <table class="obs-table">
            <thead>
              <tr>
                <th>{{ panel.spec.splitBy ?? 'series' }}</th>
                <th>{{ headline(panel.spec.kind, panel.rows[0].stats)?.label ?? 'value' }}</th>
                <th v-if="hasSpread(panel.spec)">max</th>
                <th v-if="hasSpread(panel.spec)">total</th>
                <th v-if="hasSpread(panel.spec)">samples</th>
                <th v-for="q in quantileEntries(panel.rows[0].stats)" :key="q.label">
                  {{ q.label }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in panel.rows" :key="row.key" data-testid="series-row">
                <td class="obs-series" data-testid="series-label">{{ row.label }}</td>
                <td class="obs-value" data-testid="series-headline">
                  {{ headline(panel.spec.kind, row.stats)
                    ? formatStat(headline(panel.spec.kind, row.stats)!.value, panel.spec.unit)
                    : '—' }}
                </td>
                <td v-if="hasSpread(panel.spec)">
                  {{ typeof row.stats.max === 'number'
                    ? formatStat(row.stats.max, panel.spec.unit) : '—' }}
                </td>
                <td v-if="hasSpread(panel.spec)">
                  {{ typeof row.stats.sum === 'number'
                    ? formatStat(row.stats.sum, panel.spec.unit) : '—' }}
                </td>
                <td v-if="hasSpread(panel.spec)">
                  {{ typeof row.stats.count === 'number'
                    ? formatStat(row.stats.count, 'count') : '—' }}
                </td>
                <td v-for="q in quantileEntries(row.stats)" :key="q.label">
                  {{ formatStat(q.value, panel.spec.unit) }}
                </td>
              </tr>
            </tbody>
          </table>
        </article>
      </section>
      <p v-if="loaded && groups.length === 0" class="obs-empty" data-testid="metrics-empty">
        No atmosphere.* meters have recorded anything yet. Send a message, then check back.
      </p>
    </template>
  </div>
</template>

<style scoped>
.obs-view {
  height: 100%;
  overflow: auto;
  padding: 1.5rem;
}

.obs-health {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin-bottom: 1.25rem;
}

.obs-label {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.obs-status {
  font-size: 0.75rem;
  font-weight: 700;
  padding: 0.125rem 0.625rem;
  border-radius: 9999px;
}

.obs-status.up { color: #10b981; background: rgba(16, 185, 129, 0.12); }
.obs-status.down { color: #ef4444; background: rgba(239, 68, 68, 0.12); }

.obs-error, .obs-empty {
  color: var(--text-tertiary);
  font-size: 0.875rem;
}

.obs-group {
  margin-bottom: 1.75rem;
}

.obs-group-title {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-tertiary);
  margin: 0 0 0.75rem;
}

.obs-panel {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.875rem 1rem;
  margin-bottom: 0.75rem;
  max-width: 720px;
}

.obs-panel-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  margin-bottom: 0.25rem;
}

.obs-panel-label {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-primary);
}

.obs-panel-name {
  font-size: 0.6875rem;
  color: var(--text-tertiary);
  font-family: 'SF Mono', 'Fira Code', monospace;
}

.obs-chip {
  font-size: 0.6875rem;
  font-weight: 600;
  color: var(--accent-color);
  background: var(--accent-bg);
  padding: 0.0625rem 0.5rem;
  border-radius: 9999px;
}

.obs-note {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  margin: 0 0 0.5rem;
}

.obs-table {
  border-collapse: collapse;
  width: 100%;
  font-size: 0.8125rem;
}

.obs-table th {
  text-align: left;
  color: var(--text-tertiary);
  font-size: 0.6875rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 0.3125rem 0.75rem 0.3125rem 0;
  border-bottom: 1px solid var(--border-color);
}

.obs-table td {
  padding: 0.3125rem 0.75rem 0.3125rem 0;
  border-bottom: 1px solid var(--border-color);
  color: var(--text-secondary);
}

.obs-table tr:last-child td { border-bottom: none; }

.obs-series { color: var(--text-secondary); font-family: 'SF Mono', 'Fira Code', monospace; }
.obs-value { color: var(--text-primary); font-weight: 600; }
</style>
