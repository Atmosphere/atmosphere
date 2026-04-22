import { ref, onMounted, onUnmounted, watch, type Ref } from 'vue'

export interface Policy {
  name: string
  source: string
  version: string
  className: string
}

export interface Decision {
  timestamp: string
  policy_name: string
  policy_source: string
  policy_version: string
  decision: string
  reason: string
  evaluation_ms: number
  context_snapshot: Record<string, unknown>
}

export interface OwaspEvidence {
  class: string
  test: string
  consumer_grep: string
  description: string
}

export interface OwaspRow {
  id: string
  title: string
  description: string
  coverage: 'COVERED' | 'PARTIAL' | 'DESIGN' | 'NOT_ADDRESSED'
  notes: string
  evidence: OwaspEvidence[]
}

export interface OwaspMatrix {
  framework: string
  rows: OwaspRow[]
  coverage_counts: Record<string, number>
  total_rows: number
}

/**
 * Poll a read-only governance endpoint on a fixed interval and expose the
 * result + last-error + loading flag. Callers mount the composable inside
 * `setup()` — the poll is torn down automatically when the component
 * unmounts so the console stops hitting the admin surface when the tab
 * closes.
 */
export function usePollingResource<T>(
  url: string,
  initial: T,
  intervalMs: number,
  enabled: Ref<boolean>
) {
  const data = ref<T>(initial) as Ref<T>
  const error = ref<string | null>(null)
  const loading = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  async function load() {
    loading.value = true
    try {
      const res = await fetch(url, { headers: { Accept: 'application/json' } })
      if (!res.ok) {
        throw new Error(`HTTP ${res.status} ${res.statusText}`)
      }
      data.value = (await res.json()) as T
      error.value = null
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  }

  function start() {
    if (timer) return
    load()
    timer = setInterval(load, intervalMs)
  }

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  onMounted(() => {
    if (enabled.value) start()
  })
  onUnmounted(stop)
  watch(enabled, (active) => {
    if (active) start()
    else stop()
  })

  return { data, error, loading, refresh: load }
}
