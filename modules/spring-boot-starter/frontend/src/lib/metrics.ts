/**
 * Metric parsing for the Observability tab.
 *
 * Two runtimes publish the same `atmosphere.*` Micrometer meters through two
 * different wire formats, and the console must read each one on its own terms:
 *
 *  - **Spring Boot** — actuator JSON (`/actuator/metrics/<name>`), where a meter
 *    is a list of `{statistic, value}` measurements plus its available tags.
 *  - **Quarkus** — Prometheus text (`/q/metrics`), where each line is one fully
 *    tag-decomposed sample.
 *
 * Both are normalised into the same {@link Meter} shape so the view renders one
 * way. The statistic selection is the point of this module: a Micrometer Timer
 * measures COUNT, TOTAL_TIME and MAX — reading `measurements[0]` yields the call
 * *count*, so a latency panel built that way displays a call count labelled as a
 * duration. Here a timer's headline is always its derived mean.
 */

export type MeterKind = 'counter' | 'gauge' | 'timer' | 'summary'

/** Statistic keys a series may carry. Absent keys are genuinely unpublished. */
export interface MeterStats {
  count?: number
  sum?: number
  max?: number
  mean?: number
  value?: number
  /** Quantiles keyed by their published label, e.g. `0.95`. */
  quantiles?: Record<string, number>
}

/** One tag-decomposed sample of a meter. */
export interface MeterSeries {
  tags: Record<string, string>
  stats: MeterStats
}

export interface Meter {
  name: string
  kind: MeterKind
  series: MeterSeries[]
  /** Distinct values seen for context tags (model / provider), for display. */
  models?: string[]
}

/** A statistic chosen for display, with the label it must be shown under. */
export interface HeadlineStat {
  label: string
  value: number
}

/**
 * The statistic that may headline a meter, with the label it must carry.
 *
 * A timer or distribution summary headlines its **mean**, never its count:
 * `measurements[0]` on a Micrometer Timer is COUNT, and labelling that as a
 * latency is the bug this function exists to prevent. Counters headline their
 * count, gauges their instantaneous value. Returns `null` when the meter
 * carries nothing renderable, so the view can show an empty state rather than
 * invent a zero.
 */
export function headline(kind: MeterKind, stats: MeterStats): HeadlineStat | null {
  switch (kind) {
    case 'timer':
    case 'summary': {
      if (typeof stats.mean === 'number') return { label: 'mean', value: stats.mean }
      // A timer that has recorded nothing has a count but no meaningful mean.
      return null
    }
    case 'counter':
      return typeof stats.count === 'number' ? { label: 'total', value: stats.count } : null
    case 'gauge':
      return typeof stats.value === 'number' ? { label: 'current', value: stats.value } : null
  }
}

/** Actuator's `/actuator/metrics/<name>` document. */
export interface ActuatorMeter {
  name?: string
  baseUnit?: string
  measurements?: Array<{ statistic?: string; value?: number }>
  availableTags?: Array<{ tag?: string; values?: string[] }>
}

/**
 * Fold actuator measurements into normalised stats.
 *
 * TOTAL_TIME (timers) and TOTAL (distribution summaries) both become `sum`;
 * the mean is derived from sum/count and is omitted — not zeroed — when the
 * meter has recorded nothing, so an idle timer reads as "no data" instead of
 * "0 ms".
 */
export function statsFromActuator(meter: ActuatorMeter): MeterStats {
  const stats: MeterStats = {}
  for (const m of meter.measurements ?? []) {
    if (typeof m?.value !== 'number' || !Number.isFinite(m.value)) continue
    switch (String(m.statistic).toUpperCase()) {
      case 'COUNT': stats.count = m.value; break
      case 'TOTAL_TIME':
      case 'TOTAL': stats.sum = m.value; break
      case 'MAX': stats.max = m.value; break
      case 'VALUE':
      case 'ACTIVE_TASKS': stats.value = m.value; break
      default: break
    }
  }
  if (typeof stats.sum === 'number' && typeof stats.count === 'number' && stats.count > 0) {
    stats.mean = stats.sum / stats.count
  }
  return stats
}

/** Values published for a tag, or `[]` when the meter does not carry it. */
export function tagValues(meter: ActuatorMeter, tag: string): string[] {
  const entry = (meter.availableTags ?? []).find(t => t?.tag === tag)
  return (entry?.values ?? []).filter(v => typeof v === 'string')
}

/** One parsed Prometheus exposition line. */
export interface PromSample {
  name: string
  tags: Record<string, string>
  value: number
}

/**
 * Parse Prometheus text exposition into samples.
 *
 * Tolerates the trailing comma Micrometer's exporter emits inside label sets
 * (`{model="x",}`), escaped quotes and backslashes in label values, `NaN` /
 * `+Inf` (dropped — an unset gauge is not a zero), and an optional trailing
 * timestamp column. Comment and blank lines are skipped.
 */
export function parsePrometheus(text: string): PromSample[] {
  const samples: PromSample[] = []
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const braceAt = line.indexOf('{')
    let name: string
    let tags: Record<string, string> = {}
    let rest: string
    if (braceAt === -1) {
      const space = line.indexOf(' ')
      if (space === -1) continue
      name = line.slice(0, space)
      rest = line.slice(space + 1)
    } else {
      const closeAt = findLabelSetEnd(line, braceAt)
      if (closeAt === -1) continue
      name = line.slice(0, braceAt)
      tags = parseLabels(line.slice(braceAt + 1, closeAt))
      rest = line.slice(closeAt + 1)
    }
    const value = Number.parseFloat(rest.trim().split(/\s+/)[0])
    if (!Number.isFinite(value)) continue
    samples.push({ name, tags, value })
  }
  return samples
}

/** Index of the `}` closing the label set, skipping braces inside quotes. */
function findLabelSetEnd(line: string, open: number): number {
  let inQuote = false
  for (let i = open + 1; i < line.length; i++) {
    const c = line[i]
    if (inQuote) {
      if (c === '\\') i++
      else if (c === '"') inQuote = false
    } else if (c === '"') inQuote = true
    else if (c === '}') return i
  }
  return -1
}

function parseLabels(body: string): Record<string, string> {
  const tags: Record<string, string> = {}
  let i = 0
  while (i < body.length) {
    while (i < body.length && (body[i] === ',' || body[i] === ' ')) i++
    const eq = body.indexOf('=', i)
    if (eq === -1) break
    const key = body.slice(i, eq).trim()
    let j = body.indexOf('"', eq)
    if (j === -1) break
    j++
    let value = ''
    while (j < body.length && body[j] !== '"') {
      if (body[j] === '\\' && j + 1 < body.length) {
        const next = body[++j]
        value += next === 'n' ? '\n' : next
      } else {
        value += body[j]
      }
      j++
    }
    if (key) tags[key] = value
    i = j + 1
  }
  return tags
}

/**
 * The Prometheus base name Micrometer publishes a dotted meter under: dots and
 * hyphens become underscores. Suffixes (`_total`, `_seconds_count`, …) are
 * appended per meter kind by {@link meterFromPrometheus}.
 */
export function promBaseName(dotted: string): string {
  return dotted.replace(/[.-]/g, '_')
}

/**
 * Collect one dotted meter out of parsed Prometheus samples, in the sample
 * family its kind dictates.
 *
 * Resolution runs dotted-name → Prometheus-name, never the reverse: stripping a
 * `_total` suffix to guess a dotted name is ambiguous (`atmosphere_ai_errors_total`
 * is the counter `atmosphere.ai.errors.total`, not `atmosphere.ai.errors`), and
 * guessing wrong would silently show the wrong series.
 */
export function meterFromPrometheus(
  samples: PromSample[], name: string, kind: MeterKind,
): Meter {
  const base = promBaseName(name)
  const byTagKey = new Map<string, MeterSeries>()
  const seriesFor = (tags: Record<string, string>): MeterSeries => {
    const clean = { ...tags }
    delete clean.quantile
    const key = JSON.stringify(Object.entries(clean).sort())
    let existing = byTagKey.get(key)
    if (!existing) {
      existing = { tags: clean, stats: {} }
      byTagKey.set(key, existing)
    }
    return existing
  }

  for (const sample of samples) {
    // Counters: Micrometer appends _total unless the name already ends with it.
    if (kind === 'counter') {
      const expected = base.endsWith('_total') ? base : `${base}_total`
      if (sample.name === expected) seriesFor(sample.tags).stats.count = sample.value
      continue
    }
    if (kind === 'gauge') {
      if (sample.name === base) seriesFor(sample.tags).stats.value = sample.value
      continue
    }
    // Timers are exported in seconds; summaries carry no unit suffix.
    const stem = kind === 'timer' ? `${base}_seconds` : base
    if (sample.name === `${stem}_count`) seriesFor(sample.tags).stats.count = sample.value
    else if (sample.name === `${stem}_sum`) seriesFor(sample.tags).stats.sum = sample.value
    else if (sample.name === `${stem}_max`) seriesFor(sample.tags).stats.max = sample.value
    else if (sample.name === stem && typeof sample.tags.quantile === 'string') {
      const series = seriesFor(sample.tags)
      series.stats.quantiles = { ...series.stats.quantiles, [sample.tags.quantile]: sample.value }
    }
  }

  const series: MeterSeries[] = []
  for (const s of byTagKey.values()) {
    if (typeof s.stats.sum === 'number' && typeof s.stats.count === 'number' && s.stats.count > 0) {
      s.stats.mean = s.stats.sum / s.stats.count
    }
    series.push(s)
  }
  return { name, kind, series, models: distinct(series, 'model') }
}

function distinct(series: MeterSeries[], tag: string): string[] {
  const out: string[] = []
  for (const s of series) {
    const v = s.tags[tag]
    if (v && !out.includes(v)) out.push(v)
  }
  return out
}

/**
 * Percentile companion meters for a latency meter, taken from the actuator
 * metrics index.
 *
 * Actuator's metrics endpoint reports a Timer's `measure()` output — COUNT,
 * TOTAL_TIME, MAX — and never its percentile snapshot, so percentiles only
 * reach this format when the registry publishes them as their own meters
 * (`<name>.p95` / `<name>.percentile`). Returning the ones that exist lets the
 * view render them when published and omit the column when they are not,
 * rather than showing an empty promise.
 */
export function percentileMeters(index: string[], base: string): string[] {
  const prefix = `${base}.`
  return index.filter(n =>
    n.startsWith(prefix) && /^(p\d{1,3}|percentile)$/.test(n.slice(prefix.length)))
}

/** Format a duration in seconds the way a latency panel should read. */
export function formatSeconds(seconds: number): string {
  if (!Number.isFinite(seconds)) return '—'
  if (seconds < 1) return `${(seconds * 1000).toFixed(seconds < 0.01 ? 2 : 0)} ms`
  return `${seconds.toFixed(2)} s`
}

/** Format a count without inventing precision it does not have. */
export function formatCount(value: number): string {
  if (!Number.isFinite(value)) return '—'
  return Number.isInteger(value) ? value.toLocaleString() : value.toFixed(2)
}

/** Format a USD amount; sub-cent spend keeps enough digits to be visible. */
export function formatUsd(value: number): string {
  if (!Number.isFinite(value)) return '—'
  return value !== 0 && Math.abs(value) < 0.01 ? `$${value.toFixed(5)}` : `$${value.toFixed(4)}`
}

/** Format one stat under the unit its meter declares. */
export function formatStat(value: number, unit: MeterUnit): string {
  switch (unit) {
    case 'seconds': return formatSeconds(value)
    case 'usd': return formatUsd(value)
    default: return formatCount(value)
  }
}

export type MeterUnit = 'seconds' | 'usd' | 'count' | 'tokens'

/** A meter the Observability tab queries, with how it must be decomposed. */
export interface MeterSpec {
  name: string
  kind: MeterKind
  label: string
  unit: MeterUnit
  /** Tag whose values split this meter into rows (input vs output, per tool…). */
  splitBy?: string
  /** Longer-form note rendered under the panel row. */
  note?: string
}

export interface MeterGroup {
  id: string
  title: string
  meters: MeterSpec[]
}

/**
 * The meters the tab queries, each with the kind that decides its statistic.
 *
 * Every name and kind here is taken from the emitting code — `MicrometerAiMetrics`
 * for the `atmosphere.ai.*` series and `AtmosphereMetrics` for the transport
 * series — so a meter that has never been recorded simply yields no series and
 * renders as an empty state. Meters a build does not publish at all (an app with
 * no AI traffic, a registry without the governance module) are absent for the
 * same reason, which is why every panel degrades to "not published" rather than
 * to a zero.
 */
export const METER_GROUPS: MeterGroup[] = [
  {
    id: 'tokens',
    title: 'Tokens & cost',
    meters: [
      {
        name: 'atmosphere.ai.tokens', kind: 'counter', label: 'Provider tokens',
        unit: 'tokens', splitBy: 'type',
        note: 'Authoritative provider usage, split by token type.',
      },
      {
        name: 'atmosphere.ai.cost', kind: 'summary', label: 'Cost per call',
        unit: 'usd',
        note: 'Mean cost per recorded call; total spend is the sum.',
      },
      {
        name: 'atmosphere.ai.input.tokens', kind: 'counter', label: 'Prompt assembly',
        unit: 'tokens', splitBy: 'stage',
        note: 'Approximate tokens contributed by each prompt-assembly stage.',
      },
    ],
  },
  {
    id: 'latency',
    title: 'Latency',
    meters: [
      {
        name: 'atmosphere.ai.prompt.duration', kind: 'timer', label: 'Time to first token',
        unit: 'seconds',
        note: 'Prompt accepted until the first streaming text left the runtime.',
      },
      {
        name: 'atmosphere.ai.response.duration', kind: 'timer', label: 'Full response',
        unit: 'seconds',
      },
      {
        name: 'atmosphere.ai.tool.duration', kind: 'timer', label: 'Tool calls',
        unit: 'seconds', splitBy: 'tool',
      },
    ],
  },
  {
    id: 'throughput',
    title: 'Throughput',
    meters: [
      { name: 'atmosphere.ai.prompts.total', kind: 'counter', label: 'Prompts', unit: 'count' },
      {
        name: 'atmosphere.ai.streaming_texts.total', kind: 'counter',
        label: 'Streaming chunks', unit: 'count',
      },
      {
        name: 'atmosphere.ai.active_sessions', kind: 'gauge',
        label: 'Active sessions', unit: 'count',
      },
    ],
  },
  {
    id: 'errors',
    title: 'Errors',
    meters: [
      {
        name: 'atmosphere.ai.errors.total', kind: 'counter', label: 'AI errors',
        unit: 'count', splitBy: 'error_type',
      },
      {
        name: 'atmosphere.backpressure.drops', kind: 'counter',
        label: 'Backpressure drops', unit: 'count',
      },
    ],
  },
  {
    id: 'transport',
    title: 'Transport',
    meters: [
      {
        name: 'atmosphere.connections.active', kind: 'gauge',
        label: 'Open connections', unit: 'count',
      },
      {
        name: 'atmosphere.connections.total', kind: 'counter',
        label: 'Connections accepted', unit: 'count',
      },
      {
        name: 'atmosphere.messages.delivered', kind: 'counter',
        label: 'Messages delivered', unit: 'count',
      },
      {
        name: 'atmosphere.broadcast.timer', kind: 'timer',
        label: 'Broadcast duration', unit: 'seconds',
      },
    ],
  },
]
