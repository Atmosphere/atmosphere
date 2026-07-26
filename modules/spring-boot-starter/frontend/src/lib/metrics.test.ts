import { describe, expect, it } from 'vitest'
import {
  METER_GROUPS, formatSeconds, formatStat, formatUsd, headline, meterFromPrometheus,
  parsePrometheus, percentileMeters, promBaseName, statsFromActuator, tagValues,
} from './metrics'

describe('actuator statistic selection', () => {
  // The shape actuator returns for a Micrometer Timer: COUNT first, which is
  // exactly why reading measurements[0] renders a call count as a latency.
  const timerDoc = {
    name: 'atmosphere.ai.response.duration',
    baseUnit: 'seconds',
    measurements: [
      { statistic: 'COUNT', value: 4 },
      { statistic: 'TOTAL_TIME', value: 2 },
      { statistic: 'MAX', value: 0.9 },
    ],
    availableTags: [{ tag: 'model', values: ['demo'] }],
  }

  it('derives a timer mean instead of headlining its count', () => {
    const stats = statsFromActuator(timerDoc)
    expect(stats).toMatchObject({ count: 4, sum: 2, max: 0.9, mean: 0.5 })

    const head = headline('timer', stats)
    expect(head).toEqual({ label: 'mean', value: 0.5 })
    // The regression this pins: 4 is the call count, never the latency.
    expect(head!.value).not.toBe(stats.count)
  })

  it('omits the mean for a timer that has recorded nothing', () => {
    const stats = statsFromActuator({
      measurements: [
        { statistic: 'COUNT', value: 0 },
        { statistic: 'TOTAL_TIME', value: 0 },
      ],
    })
    expect(stats.mean).toBeUndefined()
    // No mean means no headline — an idle timer must not read as "0 ms".
    expect(headline('timer', stats)).toBeNull()
  })

  it('folds TOTAL into sum for a distribution summary and means it', () => {
    const stats = statsFromActuator({
      measurements: [
        { statistic: 'COUNT', value: 2 },
        { statistic: 'TOTAL', value: 0.004 },
        { statistic: 'MAX', value: 0.003 },
      ],
    })
    expect(stats.sum).toBe(0.004)
    expect(headline('summary', stats)).toEqual({ label: 'mean', value: 0.002 })
  })

  it('headlines a counter by count and a gauge by value', () => {
    expect(headline('counter', statsFromActuator({
      measurements: [{ statistic: 'COUNT', value: 128 }],
    }))).toEqual({ label: 'total', value: 128 })
    expect(headline('gauge', statsFromActuator({
      measurements: [{ statistic: 'VALUE', value: 3 }],
    }))).toEqual({ label: 'current', value: 3 })
  })

  it('drops non-finite measurements rather than rendering NaN', () => {
    const stats = statsFromActuator({
      measurements: [
        { statistic: 'COUNT', value: Number.NaN },
        { statistic: 'VALUE', value: 7 },
      ],
    })
    expect(stats.count).toBeUndefined()
    expect(stats.value).toBe(7)
  })

  it('reads split-tag values off the meter document', () => {
    expect(tagValues({
      availableTags: [
        { tag: 'type', values: ['input', 'output'] },
        { tag: 'model', values: ['demo'] },
      ],
    }, 'type')).toEqual(['input', 'output'])
    expect(tagValues(timerDoc, 'type')).toEqual([])
  })
})

describe('prometheus parsing', () => {
  const text = `
# HELP atmosphere_ai_tokens_total Provider tokens
# TYPE atmosphere_ai_tokens_total counter
atmosphere_ai_tokens_total{model="demo",provider="builtin",type="input",} 120.0
atmosphere_ai_tokens_total{model="demo",provider="builtin",type="output",} 45.0
# TYPE atmosphere_ai_response_duration_seconds summary
atmosphere_ai_response_duration_seconds{model="demo",quantile="0.95",} 0.8
atmosphere_ai_response_duration_seconds_count{model="demo",} 4.0
atmosphere_ai_response_duration_seconds_sum{model="demo",} 2.0
atmosphere_ai_response_duration_seconds_max{model="demo",} 0.9
# TYPE atmosphere_ai_active_sessions gauge
atmosphere_ai_active_sessions{provider="builtin",} 2.0
# TYPE atmosphere_ai_errors_total counter
atmosphere_ai_errors_total{error_type="timeout",model="demo",} 1.0
`

  it('parses labels, values and the exporter trailing comma', () => {
    const samples = parsePrometheus(text)
    const input = samples.find(s => s.tags.type === 'input')
    expect(input).toEqual({
      name: 'atmosphere_ai_tokens_total',
      tags: { model: 'demo', provider: 'builtin', type: 'input' },
      value: 120,
    })
    // Comment and blank lines never become samples.
    expect(samples.every(s => !s.name.startsWith('#'))).toBe(true)
  })

  it('keeps braces and escapes inside label values intact', () => {
    const [sample] = parsePrometheus('x_total{note="a}b\\"c",} 1.0')
    expect(sample.tags.note).toBe('a}b"c')
    expect(sample.value).toBe(1)
  })

  it('drops NaN and Inf rather than rendering them as data', () => {
    expect(parsePrometheus('x_total{} NaN\ny_total{} +Inf\nz_total{} 3.0'))
      .toEqual([{ name: 'z_total', tags: {}, value: 3 }])
  })

  it('decomposes a counter by every tag it carries', () => {
    const meter = meterFromPrometheus(parsePrometheus(text), 'atmosphere.ai.tokens', 'counter')
    expect(meter.series).toHaveLength(2)
    const byType = Object.fromEntries(
      meter.series.map(s => [s.tags.type, s.stats.count]))
    expect(byType).toEqual({ input: 120, output: 45 })
    expect(meter.models).toEqual(['demo'])
  })

  it('reads a timer family in seconds, with its quantiles and derived mean', () => {
    const meter = meterFromPrometheus(
      parsePrometheus(text), 'atmosphere.ai.response.duration', 'timer')
    expect(meter.series).toHaveLength(1)
    const { stats } = meter.series[0]
    expect(stats).toMatchObject({ count: 4, sum: 2, max: 0.9, mean: 0.5 })
    expect(stats.quantiles).toEqual({ '0.95': 0.8 })
    // The quantile label must not survive as a series-splitting tag.
    expect(meter.series[0].tags.quantile).toBeUndefined()
  })

  it('reads a gauge without a suffix', () => {
    const meter = meterFromPrometheus(
      parsePrometheus(text), 'atmosphere.ai.active_sessions', 'gauge')
    expect(headline('gauge', meter.series[0].stats)).toEqual({ label: 'current', value: 2 })
  })

  it('does not double the _total suffix on a name that already ends in it', () => {
    expect(promBaseName('atmosphere.ai.errors.total')).toBe('atmosphere_ai_errors_total')
    const meter = meterFromPrometheus(
      parsePrometheus(text), 'atmosphere.ai.errors.total', 'counter')
    expect(meter.series).toHaveLength(1)
    expect(meter.series[0].stats.count).toBe(1)
    expect(meter.series[0].tags.error_type).toBe('timeout')
  })

  it('yields no series for a meter the registry does not publish', () => {
    expect(meterFromPrometheus(parsePrometheus(text), 'atmosphere.ai.cost', 'summary').series)
      .toEqual([])
  })
})

describe('percentile companions', () => {
  it('picks up only percentile meters of the named base', () => {
    const index = [
      'atmosphere.ai.response.duration',
      'atmosphere.ai.response.duration.p95',
      'atmosphere.ai.response.duration.p99',
      'atmosphere.ai.response.duration.percentile',
      'atmosphere.ai.response.duration.errors',
      'atmosphere.ai.prompt.duration.p95',
    ]
    expect(percentileMeters(index, 'atmosphere.ai.response.duration')).toEqual([
      'atmosphere.ai.response.duration.p95',
      'atmosphere.ai.response.duration.p99',
      'atmosphere.ai.response.duration.percentile',
    ])
  })

  it('returns nothing when the registry publishes no percentiles', () => {
    expect(percentileMeters(['atmosphere.ai.response.duration'],
      'atmosphere.ai.response.duration')).toEqual([])
  })
})

describe('formatting', () => {
  it('renders sub-second latency in milliseconds', () => {
    expect(formatSeconds(0.5)).toBe('500 ms')
    expect(formatSeconds(0.0012)).toBe('1.20 ms')
    expect(formatSeconds(2.5)).toBe('2.50 s')
  })

  it('keeps sub-cent spend visible', () => {
    expect(formatUsd(0.000031)).toBe('$0.00003')
    expect(formatUsd(1.5)).toBe('$1.5000')
  })

  it('formats each stat under its meter unit', () => {
    expect(formatStat(0.25, 'seconds')).toBe('250 ms')
    expect(formatStat(1200, 'tokens')).toBe((1200).toLocaleString())
    expect(formatStat(0.5, 'usd')).toBe('$0.5000')
  })
})

describe('meter catalog', () => {
  it('declares a kind for every meter so no statistic is guessed', () => {
    const specs = METER_GROUPS.flatMap(g => g.meters)
    expect(specs.length).toBeGreaterThan(0)
    for (const spec of specs) {
      expect(['counter', 'gauge', 'timer', 'summary']).toContain(spec.kind)
      expect(spec.name.startsWith('atmosphere.')).toBe(true)
    }
    // Meter names are unique — a duplicate would render two identical panels.
    expect(new Set(specs.map(s => s.name)).size).toBe(specs.length)
  })

  it('splits the token counter by type so input and output never merge', () => {
    const tokens = METER_GROUPS.flatMap(g => g.meters)
      .find(s => s.name === 'atmosphere.ai.tokens')
    expect(tokens?.splitBy).toBe('type')
    expect(tokens?.kind).toBe('counter')
  })
})
