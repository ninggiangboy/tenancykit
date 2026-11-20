import { Button } from '@suite/ui'
import { useKitT } from '../i18n/create-remote-i18n'
import type { PageInfo } from './list-spec'
import type { ListState } from './use-list-state'

/** "1–50 of 120" and the two page buttons; an empty first page shows neither. */
export function ListPager({ list, page, count }: { list: ListState; page?: PageInfo | null; count: number }) {
  const t = useKitT()
  const offset = page?.offset ?? list.offset
  const total = page?.total ?? null
  if (count === 0 && offset === 0) return null

  const from = count === 0 ? 0 : offset + 1
  const to = offset + count
  const hasNext = total !== null ? to < total : count >= list.limit
  const hasPrev = offset > 0

  return (
    <div className="flex w-full items-center justify-between gap-2">
      <span>{total !== null ? t('table.showing', { from, to, total }) : t('table.showingUnknown', { from, to })}</span>
      {(hasPrev || hasNext) && (
        <div className="flex items-center gap-1">
          <Button
            variant="outline"
            size="sm"
            icon="chevron-left"
            disabled={!hasPrev}
            onClick={() => list.setOffset(Math.max(0, offset - list.limit))}
          >
            {t('actions.previous')}
          </Button>
          <Button
            variant="outline"
            size="sm"
            iconEnd="chevron-right"
            disabled={!hasNext}
            onClick={() => list.setOffset(offset + list.limit)}
          >
            {t('actions.next')}
          </Button>
        </div>
      )}
    </div>
  )
}
