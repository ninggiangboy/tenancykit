import type { FilterOperator } from '@suite/api-client'
import { Button, FacetedFilter, FilterChip, Icon, Kbd, useHotkey } from '@suite/ui'
import { useRef } from 'react'
import { useKitT } from '../i18n/create-remote-i18n'
import type { ListSpec } from './list-spec'
import type { ListState } from './use-list-state'
import { useSearchDraft } from './use-search-draft'

/** One line: the search box, the facet filters, the active chips and Clear. */
export function ListToolbar({ spec, list }: { spec: ListSpec; list: ListState }) {
  const t = useKitT()
  const searchRef = useRef<HTMLInputElement>(null)
  const search = useSearchDraft(list.q, list.setQ)
  useHotkey('/', () => searchRef.current?.focus())
  const facets = spec.facets ?? []
  const searchLabel = spec.searchPlaceholder ?? t('actions.search')

  if (!spec.searchField && facets.length === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-2">
      {spec.searchField && (
        <div className="relative max-md:w-full">
          <Icon
            name="search"
            size={14}
            className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <input
            ref={searchRef}
            value={search.draft}
            onChange={(event) => search.onChange(event.target.value)}
            onFocus={search.onFocus}
            onBlur={search.onBlur}
            onKeyDown={(event) => {
              if (event.key === 'Enter') search.submit()
              else if (event.key === 'Escape') search.clear()
            }}
            placeholder={searchLabel}
            aria-label={searchLabel}
            className="motion-colors h-(--size-control-sm) w-64 max-w-full rounded-md border border-input bg-background pl-7 pr-8 text-small shadow-xs focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-halo max-md:w-full max-md:text-[16px]"
          />
          <Kbd keys={['/']} className="absolute right-1.5 top-1/2 -translate-y-1/2 max-md:hidden" />
        </div>
      )}
      {facets.map((facet) => (
        <FacetedFilter
          key={facet.key}
          label={facet.label}
          options={facet.options}
          selected={list.facetValues(facet.key)}
          onChange={(values) => list.setFacet(facet.key, values)}
        />
      ))}
      {list.q && (
        <FilterChip
          field={spec.searchField ?? 'q'}
          label={searchLabel}
          op="like"
          value={list.q}
          display={list.q}
          onRemove={() => list.setQ('')}
        />
      )}
      {facets.flatMap((facet) =>
        list.facetValues(facet.key).map((value) => (
          <FilterChip
            key={`${facet.key}:${value}`}
            field={facet.field}
            label={facet.label}
            op={chipOp(facet.op)}
            value={value}
            display={facet.options.find((option) => option.value === value)?.label ?? value}
            onRemove={() =>
              list.setFacet(
                facet.key,
                list.facetValues(facet.key).filter((entry) => entry !== value),
              )
            }
          />
        )),
      )}
      {list.filtered && (
        <Button variant="ghost" size="sm" icon="x" onClick={list.clear}>
          {t('actions.clear')}
        </Button>
      )}
    </div>
  )
}

function chipOp(
  op: FilterOperator | undefined,
): 'eq' | 'neq' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'nin' | 'like' | 'is_null' {
  if (!op) return 'eq'
  if (op === 'contains' || op === 'starts_with') return 'like'
  return op
}
