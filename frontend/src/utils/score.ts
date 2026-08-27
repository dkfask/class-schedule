export interface ScoreBreakdown {
  hard: number | null
  medium: number | null
  soft: number | null
  valid: boolean
}

const emptyScore = (): ScoreBreakdown => ({ hard: null, medium: null, soft: null, valid: false })

export function parseScore(raw?: string | null): ScoreBreakdown {
  if (typeof raw !== 'string' || !raw.trim() || raw.trim().toLowerCase() === '等待结果') return emptyScore()

  const parts = raw.split('/')
  if (parts.length !== 2 && parts.length !== 3) return emptyScore()

  const values: Record<'hard' | 'medium' | 'soft', number | null> = { hard: null, medium: null, soft: null }
  for (const part of parts) {
    const match = part.trim().match(/^(-?\d+)(hard|medium|soft)$/i)
    if (!match) return emptyScore()
    const layer = match[2].toLowerCase() as 'hard' | 'medium' | 'soft'
    if (values[layer] !== null) return emptyScore()
    values[layer] = Number(match[1])
  }

  if (parts.length === 2) {
    if (values.hard === null || values.soft === null || values.medium !== null) return emptyScore()
    values.medium = 0
  } else if (values.hard === null || values.medium === null || values.soft === null) {
    return emptyScore()
  }

  return { ...values, valid: true }
}

export function resolveScore(
  raw?: string | null,
  provided: Partial<Pick<ScoreBreakdown, 'hard' | 'medium' | 'soft'>> = {},
): ScoreBreakdown {
  const parsed = parseScore(raw)
  const hard = provided.hard ?? parsed.hard
  const medium = provided.medium ?? parsed.medium
  const soft = provided.soft ?? parsed.soft
  return { hard, medium, soft, valid: parsed.valid || [hard, medium, soft].every(value => value !== null) }
}
